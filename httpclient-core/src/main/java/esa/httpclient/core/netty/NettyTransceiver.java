/*
 * Copyright 2020 OPPO ESA Stack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package esa.httpclient.core.netty;

import esa.commons.Checks;
import esa.commons.annotation.Internal;
import esa.commons.concurrent.ThreadFactories;
import esa.commons.http.HttpHeaderNames;
import esa.commons.netty.http.Http1HeadersImpl;
import esa.httpclient.core.Context;
import esa.httpclient.core.HttpClientBuilder;
import esa.httpclient.core.HttpRequest;
import esa.httpclient.core.HttpResponse;
import esa.httpclient.core.Listener;
import esa.httpclient.core.RequestType;
import esa.httpclient.core.Scheme;
import esa.httpclient.core.config.SslOptions;
import esa.httpclient.core.exception.ConnectionInactiveException;
import esa.httpclient.core.exception.WriteBufFullException;
import esa.httpclient.core.exec.HttpTransceiver;
import esa.httpclient.core.spi.SslEngineFactory;
import esa.httpclient.core.util.Futures;
import esa.httpclient.core.util.LoggerUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.SystemPropertyUtil;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

import static esa.httpclient.core.netty.Utils.getValue;

@Internal
public class NettyTransceiver implements HttpTransceiver {

    static final String CHUNK_WRITER = "$chunkWriter";

    private static final String HASHEDWHEELTIMER_TICKDURATION_KEY = "esa.httpclient.hashedWheelTimer.tickDurationMs";
    private static final String HASHEDWHEELTIMER_SIZE_KEY = "esa.httpclient.hashedWheelTimer.size";

    private static final Timer READ_TIMEOUT_TIMER;
    private static final ServerSelector SERVER_SELECTOR = ServerSelector.DEFAULT;

    private static final H1TransceiverHandle H1_HANDLE = new H1TransceiverHandle();
    private static final H2TransceiverHandle H2_HANDLE = new H2TransceiverHandle();

    private final EventLoopGroup ioThreads;
    private final ChannelPools channelPools;
    private final HttpClientBuilder builder;
    private final SslEngineFactory sslEngineFactory;

    static {
        READ_TIMEOUT_TIMER = new HashedWheelTimer(ThreadFactories
                .namedThreadFactory("HttpClient-ReadTimout-Checker-", true),
                SystemPropertyUtil.getLong(HASHEDWHEELTIMER_TICKDURATION_KEY, 30L),
                TimeUnit.MILLISECONDS,
                SystemPropertyUtil.getInt(HASHEDWHEELTIMER_SIZE_KEY, 512));
    }

    NettyTransceiver(EventLoopGroup ioThreads,
                     ChannelPools channelPools,
                     HttpClientBuilder builder,
                     SslEngineFactory sslEngineFactory) {
        Checks.checkNotNull(ioThreads, "IOThreads must not be null");
        Checks.checkNotNull(channelPools, "ChannelPools must not be null");
        Checks.checkNotNull(builder, "HttpClientBuilder must not be null");
        Checks.checkNotNull(sslEngineFactory, "SslEngineFactory must not be null");
        this.ioThreads = ioThreads;
        this.channelPools = channelPools;
        this.builder = builder;
        this.sslEngineFactory = sslEngineFactory;
    }

    @Override
    public CompletableFuture<HttpResponse> handle(HttpRequest request,
                                                  Context ctx,
                                                  BiFunction<Listener, CompletableFuture<HttpResponse>,
                                                          NettyHandle> handle,
                                                  final Listener listener,
                                                  int readTimeout) {
        listener.onFiltersEnd(request, ctx);

        final SocketAddress address = selectServer(request, ctx);
        ChannelPool channelPool;
        listener.onConnectionPoolAttempt(request, ctx, address);

        // TODO: a better way to save chunk writer is wanted.
        // Save chunk write for further using.
        final CompletableFuture<RequestWriter> chunkWriterPromise;
        if (RequestType.CHUNK == request.type()) {
            chunkWriterPromise = new CompletableFuture<>();
            ctx.setAttr(CHUNK_WRITER, chunkWriterPromise);
        } else {
            chunkWriterPromise = null;
        }

        try {
            channelPool = getChannelPool(request, address);
        } catch (Throwable ex) {
            listener.onAcquireConnectionPoolFailed(request, ctx, address, ex);
            endRequestWriter(chunkWriterPromise, ex);
            return Futures.completed(ex);
        }

        listener.onConnectionPoolAcquired(request, ctx, address);
        listener.onConnectionAttempt(request, ctx, address);

        final RequestWriter writer = RequestWriter.getByType(request.type());
        final Future<Channel> channel = channelPool.acquire();

        final CompletableFuture<HttpResponse> response = new CompletableFuture<>();
        if (channel.isDone()) {
            this.handle0(request,
                    address,
                    ctx,
                    channelPool,
                    channel,
                    handle,
                    listener,
                    readTimeout,
                    response,
                    writer,
                    chunkWriterPromise);
        } else {
            channel.addListener(channel0 -> this.handle0(request,
                    address,
                    ctx,
                    channelPool,
                    channel,
                    handle,
                    listener,
                    readTimeout,
                    response,
                    writer,
                    chunkWriterPromise));
        }

        return response;
    }

    static void closeTimer() {
        Set<Timeout> tasks = READ_TIMEOUT_TIMER.stop();
        for (Timeout item : tasks) {
            if (item.task() instanceof ReadTimeoutTask) {
                ((ReadTimeoutTask) item.task()).cancel();
            }
        }
    }

    private static SocketAddress selectServer(HttpRequest request, Context ctx) {
        return SERVER_SELECTOR.select(request, ctx);
    }

    void handle0(HttpRequest request,
                 SocketAddress address,
                 Context ctx,
                 ChannelPool channelPool,
                 Future<Channel> channel,
                 BiFunction<Listener, CompletableFuture<HttpResponse>, NettyHandle> handle,
                 Listener listener,
                 int readTimeout,
                 CompletableFuture<HttpResponse> response,
                 RequestWriter writer,
                 CompletableFuture<RequestWriter> chunkWriterPromise) {
        if (!channel.isSuccess()) {
            this.onAcquireConnectionFailed(request,
                    address,
                    ctx,
                    channel,
                    listener,
                    response,
                    chunkWriterPromise);
            return;
        }

        Channel channel0 = channel.getNow();
        try {
            ChannelFuture handshake = channel0.attr(ChannelPoolHandler.HANDSHAKE_FUTURE).get();
            if (handshake.isDone()) {
                this.doWrite(request,
                        ctx,
                        channelPool,
                        channel0,
                        handshake,
                        handle,
                        listener,
                        readTimeout,
                        response,
                        writer,
                        chunkWriterPromise);
            } else {
                // Make sure the unexpected error will be handled when execute listeners.
                handshake.addListener(future -> {
                    try {
                        this.doWrite(request,
                                ctx,
                                channelPool,
                                channel0,
                                handshake,
                                handle,
                                listener,
                                readTimeout,
                                response,
                                writer,
                                chunkWriterPromise);
                    } catch (Throwable th) {
                        channelPool.release(channel0);
                        endWithError(request, ctx, listener, response, chunkWriterPromise, th);
                    }
                });
            }
        } catch (Throwable th) {
            channelPool.release(channel0);
            endWithError(request, ctx, listener, response, chunkWriterPromise, th);
        }
    }

    void doWrite(HttpRequest request,
                 Context ctx,
                 ChannelPool channelPool,
                 Channel channel,
                 ChannelFuture handshake,
                 BiFunction<Listener, CompletableFuture<HttpResponse>, NettyHandle> handle,
                 Listener listener,
                 int readTimeout,
                 CompletableFuture<HttpResponse> response,
                 RequestWriter writer,
                 CompletableFuture<RequestWriter> chunkWriterPromise) {
        listener.onConnectionAcquired(request, ctx, channel.remoteAddress());

        if (!handshake.isSuccess()) {
            channelPool.release(channel);
            endWithError(request, ctx, listener, response, chunkWriterPromise, handshake.cause());
            return;
        }

        final boolean http2 = isHttp2(channel);
        final esa.commons.http.HttpVersion version;
        if (http2) {
            version = esa.commons.http.HttpVersion.HTTP_2;
        } else {
            version = (esa.commons.http.HttpVersion.HTTP_1_0 == builder.version()
                    ? esa.commons.http.HttpVersion.HTTP_1_0 : esa.commons.http.HttpVersion.HTTP_1_1);
        }

        if (!channel.isActive()) {
            channel.close();
            channelPool.release(channel);
            endWithError(request, ctx, listener, response, chunkWriterPromise,
                    ConnectionInactiveException.INSTANCE);
            return;
        }

        // Stop writing when the write buffer has full, otherwise it will cause OOM
        if (!channel.isWritable()) {
            channelPool.release(channel);
            endWithError(request, ctx, listener, response, chunkWriterPromise,
                    WriteBufFullException.INSTANCE);
            return;
        }

        try {
            TimeoutHandle h = buildTimeoutHandle(http2, channel, channelPool, listener, version);
            this.doWrite0(request,
                    ctx,
                    channel,
                    handle,
                    h,
                    http2,
                    version,
                    readTimeout,
                    response,
                    writer,
                    chunkWriterPromise);
        } catch (Throwable ex) {
            channelPool.release(channel);
            endWithError(request, ctx, listener, response, chunkWriterPromise, ex);
        }
    }

    private void onAcquireConnectionFailed(HttpRequest request,
                                           SocketAddress address,
                                           Context ctx,
                                           Future<Channel> channel,
                                           Listener listener,
                                           CompletableFuture<HttpResponse> response,
                                           CompletableFuture<RequestWriter> chunkWriterPromise) {
        Throwable cause = channel.cause();

        // Maybe caused by too many acquires or channel has closed.
        if (cause instanceof IllegalStateException) {
            cause = new IOException("Error while acquiring channel", cause);
        } else if (cause instanceof TimeoutException) {
            // Connection timeout
            cause = new ConnectException(cause.getMessage());
        }

        response.completeExceptionally(cause);
        endRequestWriter(chunkWriterPromise, cause);

        listener.onAcquireConnectionFailed(request, ctx, address, cause);
        listener.onError(request, ctx, cause);
    }

    void doWrite0(HttpRequest request,
                  Context ctx,
                  Channel channel,
                  BiFunction<Listener, CompletableFuture<HttpResponse>, NettyHandle> handle,
                  final TimeoutHandle h,
                  boolean http2,
                  esa.commons.http.HttpVersion version,
                  int readTimeout,
                  CompletableFuture<HttpResponse> response,
                  RequestWriter writer,
                  CompletableFuture<RequestWriter> chunkWriterPromise) throws IOException {
        final HandleRegistry registry = detectRegistry(channel);
        setKeepAlive((Http1HeadersImpl) request.headers(), version);

        h.onWriteAttempt(request, ctx, readTimeout);

        // we should add response handle before writing because that the inbound
        // message may arrive before completing writing.
        final int requestId = addRspHandle(request,
                ctx,
                channel,
                h,
                handle.apply(h, response),
                http2,
                registry,
                response);
        @SuppressWarnings("unchecked")
        final ChannelFuture result = writer.writeAndFlush(request,
                channel,
                ctx,
                getValue(request.config().uriEncodeEnabled(), builder.isUriEncodeEnabled()),
                esa.commons.http.HttpVersion.HTTP_1_1 == version
                        ? HttpVersion.HTTP_1_1 : HttpVersion.HTTP_1_0,
                http2);

        if (chunkWriterPromise != null) {
            chunkWriterPromise.complete(writer);
        }

        if (result.isDone()) {
            this.onWriteDone(requestId,
                    request,
                    ctx,
                    result,
                    h,
                    registry,
                    response,
                    chunkWriterPromise,
                    readTimeout);
        } else {
            result.addListener(f -> {
                try {
                    this.onWriteDone(requestId,
                            request,
                            ctx,
                            result,
                            h,
                            registry,
                            response,
                            chunkWriterPromise,
                            readTimeout);
                } catch (Throwable ex) {
                    endWithError(request, ctx, h, response, chunkWriterPromise, ex);
                }
            });
        }
    }

    ChannelPool getChannelPool(HttpRequest request, SocketAddress address) {
        esa.httpclient.core.netty.ChannelPool channelPool = channelPools.getIfPresent(address);
        if (channelPool != null) {
            return channelPool.underlying;
        }

        final boolean ssl = Scheme.HTTPS.name0().equals(request.scheme());
        return channelPools.getOrCreate(ssl,
                address,
                ioThreads,
                builder.copy(),
                () -> {
                    final SslOptions sslOptions = builder.sslOptions();
                    SSLEngine sslEngine = sslEngineFactory.create(sslOptions,
                            ((InetSocketAddress) address).getHostName(),
                            ((InetSocketAddress) address).getPort() > 0
                                    ? ((InetSocketAddress) address).getPort()
                                    : ssl ? Scheme.HTTPS.port() : Scheme.HTTP.port());
                    if (sslOptions != null && sslOptions.enabledProtocols().length > 0) {
                        sslEngine.setEnabledProtocols(sslOptions.enabledProtocols());
                    }

                    SslHandler sslHandler = new SslHandler(sslEngine);
                    if (sslOptions != null && sslOptions.handshakeTimeoutMillis() > 0) {
                        sslHandler.setHandshakeTimeoutMillis(sslOptions.handshakeTimeoutMillis());
                    } else {
                        int connectTimeout = builder.connectTimeout();
                        if (connectTimeout > 0) {
                            sslHandler.setHandshakeTimeoutMillis(Duration.ofSeconds(connectTimeout).toMillis());
                        }
                    }

                    return sslHandler;
                }).underlying;
    }

    private boolean isHttp2(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(Http2ConnectionHandler.class) != null) {
            return true;
        } else if (pipeline.get(Http1ChannelHandler.class) != null) {
            return false;
        }

        throw new IllegalStateException("Unable to recognize http version by last handler in pipeline: "
                + channel.pipeline());
    }

    private TimeoutHandle buildTimeoutHandle(boolean http2,
                                             Channel channel,
                                             ChannelPool channelPool,
                                             Listener delegate,
                                             esa.commons.http.HttpVersion version) {
        if (http2) {
            return H2_HANDLE.buildTimeoutHandle(channel, channelPool, delegate,
                    esa.commons.http.HttpVersion.HTTP_2);
        }

        return H1_HANDLE.buildTimeoutHandle(channel, channelPool, delegate, version);
    }

    private int addRspHandle(HttpRequest request,
                             Context ctx,
                             Channel channel,
                             Listener listener,
                             NettyHandle handle,
                             boolean http2,
                             HandleRegistry registry,
                             CompletableFuture<HttpResponse> response) {
        if (http2) {
            return H2_HANDLE.addRspHandle(
                    request,
                    ctx,
                    channel,
                    listener,
                    handle,
                    registry,
                    response);
        } else {
            return H1_HANDLE.addRspHandle(
                    request,
                    ctx,
                    channel,
                    listener,
                    handle,
                    registry,
                    response);
        }
    }

    private HandleRegistry detectRegistry(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        Http1ChannelHandler handler1;
        if ((handler1 = pipeline.get(Http1ChannelHandler.class)) != null) {
            return handler1.getRegistry();
        }

        Http2ConnectionHandler handler2;
        if ((handler2 = pipeline.get(Http2ConnectionHandler.class)) != null) {
            return handler2.getRegistry();
        }

        throw new IllegalStateException("Unable to detect handler registry by last handler in pipeline: "
                + channel.pipeline());
    }

    private void onWriteDone(int requestId,
                             HttpRequest request,
                             Context ctx,
                             ChannelFuture result,
                             TimeoutHandle handle,
                             HandleRegistry registry,
                             CompletableFuture<HttpResponse> response,
                             CompletableFuture<RequestWriter> chunkWriterPromise,
                             int readTimeout) {
        if (result.isSuccess()) {
            handle.onWriteDone(request, ctx, readTimeout);

            Timeout timeout = READ_TIMEOUT_TIMER.newTimeout(new ReadTimeoutTask(requestId,
                    request.uri().toString(),
                    result.channel(),
                    registry),
                    TimeUnit.MILLISECONDS.toNanos(readTimeout), TimeUnit.NANOSECONDS);
            handle.addCancelTask(timeout);
            return;
        }

        final Throwable cause = new IOException("Failed to write request: " + request + " to channel: "
                + result.channel(), result.cause());

        LoggerUtils.logger().error(cause.getMessage(), cause.getCause());
        handle.onWriteFailed(request, ctx, result.cause());
        endWithError(request, ctx, handle, response, chunkWriterPromise, cause);
    }

    /**
     * Set keepAlive to given headers.
     *
     * @param headers   headers
     * @param version   version
     */
    private void setKeepAlive(Http1HeadersImpl headers, esa.commons.http.HttpVersion version) {
        if (esa.commons.http.HttpVersion.HTTP_2 == builder.version()) {
            headers.remove(HttpHeaderNames.CONNECTION);
        }

        if (headers.contains(HttpHeaderNames.CONNECTION)) {
            return;
        }

        final boolean keepAlive = builder.isKeepAlive();
        HttpUtil.setKeepAlive(headers,
                esa.commons.http.HttpVersion.HTTP_1_1 == version
                        ? HttpVersion.HTTP_1_1 : HttpVersion.HTTP_1_0,
                keepAlive);
    }

    private static void endWithError(HttpRequest request,
                                     Context ctx,
                                     Listener listener,
                                     CompletableFuture<HttpResponse> response,
                                     CompletableFuture<RequestWriter> chunkWriterPromise,
                                     Throwable cause) {
        response.completeExceptionally(cause);
        if (chunkWriterPromise != null) {
            chunkWriterPromise.completeExceptionally(cause);
        }
        listener.onError(request, ctx, cause);
    }

    private static void endRequestWriter(CompletableFuture<RequestWriter> requestWriterPromise,
                                         Throwable cause) {
        if (requestWriterPromise == null) {
            return;
        }

        requestWriterPromise.completeExceptionally(cause);
    }

}
