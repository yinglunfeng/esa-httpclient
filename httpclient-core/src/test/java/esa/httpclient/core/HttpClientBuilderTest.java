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
package esa.httpclient.core;

import esa.commons.http.HttpVersion;
import esa.httpclient.core.config.Decompression;
import esa.httpclient.core.config.Http1Options;
import esa.httpclient.core.config.Http2Options;
import esa.httpclient.core.config.NetOptions;
import esa.httpclient.core.config.RetryOptions;
import esa.httpclient.core.exec.ExpectContinueInterceptor;
import esa.httpclient.core.exec.FilteringExec;
import esa.httpclient.core.exec.RedirectInterceptor;
import esa.httpclient.core.exec.RetryInterceptor;
import esa.httpclient.core.filter.DuplexFilter;
import esa.httpclient.core.filter.FilterContext;
import esa.httpclient.core.resolver.HostResolver;
import esa.httpclient.core.spi.ChannelPoolOptionsProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.BDDAssertions.then;

class HttpClientBuilderTest {

    @Test
    void testBasic() {
        final HostResolver resolver = inetHost -> null;
        final boolean h2ClearTextUpgrade = ThreadLocalRandom.current().nextBoolean();
        final int connectTimeout = ThreadLocalRandom.current().nextInt(10, 10000);
        final int readTimeout = ThreadLocalRandom.current().nextInt(10, 10000);
        final boolean keepAlive = ThreadLocalRandom.current().nextBoolean();
        final HttpVersion version = HttpVersion.HTTP_2;
        final int connectionPoolSize = ThreadLocalRandom.current().nextInt(1, 1000);
        final int connectionPoolWaitQueueSize = ThreadLocalRandom.current().nextInt(1, 1000);
        final boolean useDecompress = ThreadLocalRandom.current().nextBoolean();
        final Decompression decompression = Decompression.GZIP_DEFLATE;
        final boolean expectContinueEnabled = ThreadLocalRandom.current().nextBoolean();
        final boolean uriEncodeEnabled = ThreadLocalRandom.current().nextBoolean();
        final ChannelPoolOptionsProvider channelPoolOptionsProvider = key -> null;
        final NetOptions netOptions = NetOptions.ofDefault();
        final Http1Options http1Options = Http1Options.ofDefault();
        final Http2Options http2Options = Http2Options.ofDefault();
        final RetryOptions retryOptions = RetryOptions.ofDefault();
        final int maxRedirects = ThreadLocalRandom.current().nextInt(10, 1000);
        final long maxContentLength = ThreadLocalRandom.current().nextLong(10000);

        final HttpClientBuilder builder = new HttpClientBuilder();
        builder.resolver(resolver);
        builder.h2ClearTextUpgrade(h2ClearTextUpgrade);
        builder.connectTimeout(connectTimeout);
        builder.readTimeout(readTimeout);
        builder.keepAlive(keepAlive);
        builder.version(version);
        builder.connectionPoolSize(connectionPoolSize);
        builder.connectionPoolWaitingQueueLength(connectionPoolWaitQueueSize);
        builder.useDecompress(useDecompress);
        builder.decompression(decompression);
        builder.expectContinueEnabled(expectContinueEnabled);
        builder.uriEncodeEnabled(uriEncodeEnabled);
        builder.channelPoolOptionsProvider(channelPoolOptionsProvider);
        builder.netOptions(netOptions);
        builder.http1Options(http1Options);
        builder.http2Options(http2Options);
        builder.retryOptions(retryOptions);
        builder.maxRedirects(maxRedirects);
        builder.maxContentLength(maxContentLength);

        then(builder.resolver()).isSameAs(resolver);
        then(builder.ish2ClearTextUpgrade()).isEqualTo(h2ClearTextUpgrade);
        then(builder.connectTimeout()).isEqualTo(connectTimeout);
        then(builder.readTimeout()).isEqualTo(readTimeout);
        then(builder.isKeepAlive()).isEqualTo(keepAlive);
        then(builder.version()).isSameAs(version);
        then(builder.connectionPoolSize()).isEqualTo(connectionPoolSize);
        then(builder.connectionPoolWaitingQueueLength()).isEqualTo(connectionPoolWaitQueueSize);
        then(builder.isUseDecompress()).isEqualTo(useDecompress);
        then(builder.decompression()).isSameAs(decompression);
        then(builder.isExpectContinueEnabled()).isEqualTo(expectContinueEnabled);
        then(builder.isUriEncodeEnabled()).isEqualTo(uriEncodeEnabled);
        then(builder.channelPoolOptionsProvider()).isSameAs(channelPoolOptionsProvider);
        then(builder.netOptions()).isSameAs(netOptions);
        then(builder.http1Options()).isSameAs(http1Options);
        then(builder.http2Options()).isSameAs(http2Options);
        then(builder.retryOptions()).isSameAs(retryOptions);
        then(builder.maxRedirects()).isEqualTo(maxRedirects);
        then(builder.maxContentLength()).isEqualTo(maxContentLength);
    }

    @Test
    void testCopy() {
        final HostResolver resolver = inetHost -> null;
        final boolean h2ClearTextUpgrade = ThreadLocalRandom.current().nextBoolean();
        final int connectTimeout = ThreadLocalRandom.current().nextInt(10, 10000);
        final int readTimeout = ThreadLocalRandom.current().nextInt(10, 10000);
        final boolean keepAlive = ThreadLocalRandom.current().nextBoolean();
        final HttpVersion version = HttpVersion.HTTP_2;
        final int connectionPoolSize = ThreadLocalRandom.current().nextInt(1, 1000);
        final int connectionPoolWaitQueueSize = ThreadLocalRandom.current().nextInt(1, 1000);
        final boolean useDecompress = ThreadLocalRandom.current().nextBoolean();
        final Decompression decompression = Decompression.GZIP_DEFLATE;
        final boolean expectContinueEnabled = ThreadLocalRandom.current().nextBoolean();
        final boolean uriEncodeEnabled = ThreadLocalRandom.current().nextBoolean();
        final ChannelPoolOptionsProvider channelPoolOptionsProvider = key -> null;
        final NetOptions netOptions = NetOptions.ofDefault();
        final Http1Options http1Options = Http1Options.ofDefault();
        final Http2Options http2Options = Http2Options.ofDefault();
        final RetryOptions retryOptions = RetryOptions.ofDefault();
        final int maxRedirects = ThreadLocalRandom.current().nextInt(10, 1000);
        final long maxContentLength = ThreadLocalRandom.current().nextLong(10000);

        final HttpClientBuilder builder = new HttpClientBuilder();
        builder.resolver(resolver);
        builder.h2ClearTextUpgrade(h2ClearTextUpgrade);
        builder.connectTimeout(connectTimeout);
        builder.readTimeout(readTimeout);
        builder.keepAlive(keepAlive);
        builder.version(version);
        builder.connectionPoolSize(connectionPoolSize);
        builder.connectionPoolWaitingQueueLength(connectionPoolWaitQueueSize);
        builder.useDecompress(useDecompress);
        builder.decompression(decompression);
        builder.expectContinueEnabled(expectContinueEnabled);
        builder.uriEncodeEnabled(uriEncodeEnabled);
        builder.channelPoolOptionsProvider(channelPoolOptionsProvider);
        builder.netOptions(netOptions);
        builder.http1Options(http1Options);
        builder.http2Options(http2Options);
        builder.retryOptions(retryOptions);
        builder.maxRedirects(maxRedirects);
        builder.maxContentLength(maxContentLength);

        final HttpClientBuilder builder1 = builder.copy();

        then(builder1.resolver()).isSameAs(resolver);
        then(builder1.ish2ClearTextUpgrade()).isEqualTo(h2ClearTextUpgrade);
        then(builder1.connectTimeout()).isEqualTo(connectTimeout);
        then(builder1.readTimeout()).isEqualTo(readTimeout);
        then(builder1.isKeepAlive()).isEqualTo(keepAlive);
        then(builder1.version()).isSameAs(version);
        then(builder1.connectionPoolSize()).isEqualTo(connectionPoolSize);
        then(builder1.connectionPoolWaitingQueueLength()).isEqualTo(connectionPoolWaitQueueSize);
        then(builder1.isUseDecompress()).isEqualTo(useDecompress);
        then(builder1.decompression()).isSameAs(decompression);
        then(builder1.isExpectContinueEnabled()).isEqualTo(expectContinueEnabled);
        then(builder1.isUriEncodeEnabled()).isEqualTo(uriEncodeEnabled);
        then(builder1.channelPoolOptionsProvider()).isSameAs(channelPoolOptionsProvider);
        then(builder1.netOptions()).isNotSameAs(netOptions);
        then(builder1.http1Options()).isNotSameAs(http1Options);
        then(builder1.http2Options()).isNotSameAs(http2Options);
        then(builder1.retryOptions()).isNotSameAs(retryOptions);
        then(builder1.maxRedirects()).isEqualTo(maxRedirects);
        then(builder1.maxContentLength()).isEqualTo(maxContentLength);
    }

    @Test
    void testUnmodifiableInterceptors() {
        // Interceptor
        final HttpClientBuilder builder = new HttpClientBuilder();
        then(builder.unmodifiableInterceptors().length).isEqualTo(4);

        builder.addInterceptor((request, next) -> null);
        then(builder.unmodifiableInterceptors().length).isEqualTo(5);
        builder.addInterceptors(Collections.singletonList((request, next) -> null));
        then(builder.unmodifiableInterceptors().length).isEqualTo(6);

        // Filter
        final FilteringExec filteringExec = (FilteringExec) builder.unmodifiableInterceptors()[5];

        builder.addRequestFilter((request, ctx) -> null);
        then(builder.unmodifiableInterceptors()[5]).isInstanceOf(FilteringExec.class);
        then(builder.unmodifiableInterceptors()[5]).isNotSameAs(filteringExec);
        builder.addRequestFilters(Collections.singletonList((request, ctx) -> null));
        then(builder.unmodifiableInterceptors()[5]).isInstanceOf(FilteringExec.class);
        then(builder.unmodifiableInterceptors()[5]).isNotSameAs(filteringExec);
        builder.addResponseFilter((response, ctx) -> null);
        then(builder.unmodifiableInterceptors()[5]).isInstanceOf(FilteringExec.class);
        then(builder.unmodifiableInterceptors()[5]).isNotSameAs(filteringExec);
        builder.addResponseFilters(Collections.singletonList((response, ctx) -> null));
        then(builder.unmodifiableInterceptors()[5]).isInstanceOf(FilteringExec.class);
        then(builder.unmodifiableInterceptors()[5]).isNotSameAs(filteringExec);
        builder.addFilter(new DuplexFilter() {
            @Override
            public CompletableFuture<Void> doFilter(HttpRequest request, FilterContext ctx) {
                return null;
            }

            @Override
            public CompletableFuture<Void> doFilter(HttpResponse response, FilterContext ctx) {
                return null;
            }
        });
        then(builder.unmodifiableInterceptors()[5]).isInstanceOf(FilteringExec.class);
        then(builder.unmodifiableInterceptors()[5]).isNotSameAs(filteringExec);
        builder.addFilters(Collections.singletonList(new DuplexFilter() {
            @Override
            public CompletableFuture<Void> doFilter(HttpRequest request, FilterContext ctx) {
                return null;
            }

            @Override
            public CompletableFuture<Void> doFilter(HttpResponse response, FilterContext ctx) {
                return null;
            }
        }));
        then(builder.unmodifiableInterceptors()[5]).isInstanceOf(FilteringExec.class);
        then(builder.unmodifiableInterceptors()[5]).isNotSameAs(filteringExec);
    }

    @Test
    void testAbsentInterceptors() {
        final HttpClientBuilder builder = new HttpClientBuilder();
        then(builder.unmodifiableInterceptors().length).isEqualTo(4);

        builder.retryOptions(null);
        then(builder.unmodifiableInterceptors().length).isEqualTo(3);
    }

    @Test
    void testOverrideInterceptors() {
        final HttpClientBuilder builder = new HttpClientBuilder();

        final RetryOptions retryOptions = RetryOptions.ofDefault();
        final RetryInterceptor retryInterceptor = new RetryInterceptor(retryOptions.predicate(),
                retryOptions.intervalMillis());
        builder.addInterceptor(retryInterceptor);

        final RedirectInterceptor redirectInterceptor = new RedirectInterceptor();
        builder.addInterceptor(redirectInterceptor);

        final FilteringExec filteringExec = new FilteringExec(null, null);
        builder.addInterceptor(filteringExec);

        final ExpectContinueInterceptor expectContinueInterceptor = new SubExpectContinueInterceptor();
        builder.addInterceptor(expectContinueInterceptor);

        then(builder.interceptors().size()).isEqualTo(4);
        then(builder.interceptors().contains(retryInterceptor)).isTrue();
        then(builder.interceptors().contains(redirectInterceptor)).isTrue();
        then(builder.interceptors().contains(filteringExec)).isTrue();
        then(builder.interceptors().contains(expectContinueInterceptor)).isTrue();
    }

    private static final class SubExpectContinueInterceptor extends ExpectContinueInterceptor {

    }
}
