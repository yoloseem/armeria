/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client;

import static junit.framework.TestCase.assertFalse;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public class HttpClientIdleTimeoutHandlerTest {
    private static final long idleTimeoutMillis = 100;

    private static final FullHttpRequest httpRequest =
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    private static final FullHttpResponse httpResponse =
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

    private EmbeddedChannel ch;

    @Before
    public void before() {
        ch = new EmbeddedChannel(new HttpClientIdleTimeoutHandler(idleTimeoutMillis));
        assertTrue(ch.isOpen());
    }

    @After
    public void after() {
        assertFalse(ch.finish());
    }

    @Test
    public void testIdleTimeoutWithoutRequest() throws Exception {
        waitUntilTimeout();
        assertFalse(ch.isOpen());
    }

    @Test
    public void testIdleTimeout() throws Exception {
        writeRequest();
        readResponse();
        waitUntilTimeout();
        assertFalse(ch.isOpen());
    }

    @Test
    public void testPendingRequestExists() throws Exception {
        writeRequest();
        Thread.sleep(idleTimeoutMillis);
        ch.runPendingTasks();
        assertTrue(ch.isOpen());
    }

    @Test
    public void testIdleTimeoutOccuredTwice() throws Exception {
        writeRequest();
        waitUntilTimeout();
        //padding request count is 1
        assertTrue(ch.isOpen());

        readResponse();
        waitUntilTimeout();
        //padding request count turns to 0
        assertFalse(ch.isOpen());
    }

    private void waitUntilTimeout() throws InterruptedException {
        Thread.sleep(idleTimeoutMillis * 3 / 2);
        ch.runPendingTasks();
    }

    private void readResponse() {
        ch.writeInbound(httpResponse);
        assertThat(ch.readInbound(), equalTo(httpRequest));
    }

    private void writeRequest() {
        ch.writeOutbound(httpRequest);
        assertThat(ch.readOutbound(), equalTo(httpRequest));
    }
}
