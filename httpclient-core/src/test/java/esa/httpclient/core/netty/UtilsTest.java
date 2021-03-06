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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UtilsTest {

    @Test
    void testRunInChannel() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final AtomicInteger count = new AtomicInteger();
        Utils.runInChannel(channel, count::incrementAndGet);
        channel.finish();
        then(count.get()).isEqualTo(1);
    }

    @Test
    void testStandardHeaders() {
        final Http2Headers headers = new DefaultHttp2Headers();
        headers.method("GET");
        headers.scheme("HTTPS");
        headers.path("/abc");
        headers.status(HttpResponseStatus.OK.codeAsText());
        headers.authority("127.0.0.1");

        headers.add("a", "b");

        Utils.standardHeaders(headers);
        then(headers.size()).isEqualTo(1);
        then(headers.get("a")).isEqualTo("b");

        then(headers.method()).isNull();
        then(headers.scheme()).isNull();
        then(headers.path()).isNull();
        then(headers.status()).isNull();
        then(headers.authority()).isNull();
    }

    @Test
    void testGetValue() {
        then(Utils.getValue(Boolean.TRUE, false)).isTrue();
        then(Utils.getValue(Boolean.FALSE, true)).isFalse();
    }

    @Test
    void testHandleException() {
        Utils.handleException(null, null, true);

        final NettyHandle handle = mock(NettyHandle.class);
        Utils.handleException(handle, new RuntimeException(), false);
        verify(handle).onError(any(Throwable.class));
    }

    @Test
    void testTryRelease() {
        Utils.tryRelease(null);

        final ByteBuf buf = Unpooled.buffer();
        Utils.tryRelease(buf);
        then(buf.refCnt()).isEqualTo(0);
        Utils.tryRelease(buf);
    }
}
