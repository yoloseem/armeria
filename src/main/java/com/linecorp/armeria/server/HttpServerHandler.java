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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServiceCodec.DecodeResult;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

final class HttpServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);

    private static final AsciiString STREAM_ID = HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text();
    private static final AsciiString ERROR_CONTENT_TYPE = new AsciiString("text/plain; charset=UTF-8");

    private static final Pattern IGNORABLE_ERROR_MESSAGE = Pattern.compile(
            "^.*(?:connection.*(?:reset|closed|abort|broken)|broken.*pipe).*$", Pattern.CASE_INSENSITIVE);

    private static final ChannelFutureListener CLOSE = future -> {
        final Throwable cause = future.cause();
        final Channel ch = future.channel();
        if (cause != null) {
            logUnexpectedException(ch, cause);
        }
        ch.close();
    };

    private static final ChannelFutureListener CLOSE_ON_FAILURE = future -> {
        final Throwable cause = future.cause();
        if (cause != null) {
            final Channel ch = future.channel();
            logUnexpectedException(ch, cause);
            ch.close();
        }
    };

    private static final Exception SERVICE_NOT_FOUND = new ServiceNotFoundException();

    private final ServerConfig config;
    private SessionProtocol sessionProtocol;

    private boolean isReading;

    // When head-of-line blocking is enabled (i.e. HTTP/1 without extension),
    // We assign a monotonically increasing integer ('request sequence') to each received request, and
    // assign the integer of the same value when creating its response.

    private boolean useHeadOfLineBlocking = true;

    /**
     * The request sequence of the most recently received request.
     * Incremented when a new request is received.
     */
    private int reqSeq;
    /**
     * The request sequence of the request which was received least recently and has no corresponding response.
     */
    private int resSeq;

    /**
     * The map which maps a sequence number to its related pending response.
     */
    private final IntObjectMap<FullHttpResponse> pendingResponses = new IntObjectHashMap<>();

    private boolean handledLastRequest;

    HttpServerHandler(ServerConfig config, SessionProtocol sessionProtocol) {
        assert sessionProtocol == SessionProtocol.H1 ||
               sessionProtocol == SessionProtocol.H1C ||
               sessionProtocol == SessionProtocol.H2;

        this.config = requireNonNull(config, "config");
        this.sessionProtocol = requireNonNull(sessionProtocol, "protocol");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        isReading = true;

        if (msg instanceof Http2Settings) {
            logger.debug("{} HTTP/2 settings: {}", ctx.channel(), msg);

            useHeadOfLineBlocking = false;
            switch (sessionProtocol) {
            case H1:
                sessionProtocol = SessionProtocol.H2;
                break;
            case H1C:
                sessionProtocol = SessionProtocol.H2C;
                break;
            default:
                logger.warn("{} Duplicate upgrade? {}", ctx.channel(), sessionProtocol);
                break;
            }
            return;
        }

        final FullHttpRequest req = (FullHttpRequest) msg;
        boolean invoked = false;
        try {
            // Ignore the request received after the last request,
            // because we are going to close the connection after sending the last response.
            if (handledLastRequest) {
                return;
            }

            // If we received the message with keep-alive disabled,
            // we should not accept a request anymore.
            if (!HttpUtil.isKeepAlive(req)) {
                handledLastRequest = true;
            }

            final int reqSeq = this.reqSeq++;

            if (!req.decoderResult().isSuccess()) {
                respond(ctx, reqSeq, req, HttpResponseStatus.BAD_REQUEST, req.decoderResult().cause());
                return;
            }

            if (req.method() == HttpMethod.CONNECT) {
                respond(ctx, reqSeq, req, HttpResponseStatus.METHOD_NOT_ALLOWED);
                return;
            }

            final String hostname = hostname(req);
            final VirtualHost host = config.findVirtualHost(hostname);
            final String path = stripQuery(req.uri());

            // Find the service that matches the path.
            final MappedService service = host.findService(path);
            if (!service.isPresent()) {
                respond(ctx, reqSeq, req, HttpResponseStatus.NOT_FOUND);
                return;
            }

            // Decode the request and create a new invocation context from it.
            final String mappedPath = service.mappedPath();
            final ServiceCodec codec = service.codec();
            final Promise<Object> promise = ctx.executor().newPromise();
            final DecodeResult decodeResult = codec.decodeRequest(
                    ctx.channel(), sessionProtocol, hostname, path, mappedPath, req.content(), req, promise);

            switch (decodeResult.type()) {
            case SUCCESS: {
                final ServiceInvocationContext iCtx = decodeResult.invocationContext();
                final long timeoutMillis = config.requestTimeoutPolicy().timeout(iCtx);

                // Perform the actual invocation.
                invoke(service, iCtx, promise);
                invoked = true;

                if (promise.isDone()) {
                    // If the invocation has been finished immediately,
                    // there's no need to schedule a timeout nor to add a listener to the promise.
                    handleInvocationResult(ctx, reqSeq, req, iCtx, codec, promise, null);
                } else {
                    final ScheduledFuture<?> timeoutFuture;
                    if (timeoutMillis > 0) {
                        timeoutFuture = ctx.executor().schedule(
                                () -> promise.tryFailure(new RequestTimeoutException(
                                        "request timed out after " + timeoutMillis + "ms: " + iCtx)),
                                timeoutMillis, TimeUnit.MILLISECONDS);
                    } else {
                        timeoutFuture = null;
                    }

                    promise.addListener((Future<Object> future) -> {
                        try {
                            handleInvocationResult(ctx, reqSeq, req, iCtx, codec, future, timeoutFuture);
                        } catch (Exception e) {
                            respond(ctx, reqSeq, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, e);
                        }
                    });
                }
                break;
            }
            case FAILURE: {
                // Could not create an invocation context.
                final Object errorResponse = decodeResult.errorResponse();
                if (errorResponse instanceof FullHttpResponse) {
                    FullHttpResponse httpResponse = (FullHttpResponse) errorResponse;
                    promise.tryFailure(new RequestDecodeException(
                            decodeResult.cause(), httpResponse.content().readableBytes()));
                    respond(ctx, reqSeq, req, (FullHttpResponse) errorResponse);
                } else {
                    ReferenceCountUtil.safeRelease(errorResponse);
                    promise.tryFailure(new RequestDecodeException(decodeResult.cause(), 0));
                    respond(ctx, reqSeq, req, HttpResponseStatus.BAD_REQUEST, decodeResult.cause());
                }
                break;
            }
            case NOT_FOUND:
                // Turned out that the request wasn't accepted by the service.
                promise.tryFailure(SERVICE_NOT_FOUND);
                respond(ctx, reqSeq, req, HttpResponseStatus.NOT_FOUND);
                break;
            }
        } finally {
            // If invocation has been started successfully, handleInvocationResult() will call
            // ReferenceCountUtil.safeRelease() when the invocation is done.
            if (!invoked) {
                ReferenceCountUtil.safeRelease(req);
            }
        }
    }

    private void invoke(Service service, ServiceInvocationContext iCtx, Promise<Object> promise) {
        ServiceInvocationContext.setCurrent(iCtx);
        try {
            service.handler().invoke(iCtx, config.blockingTaskExecutor(), promise);
        } catch (Throwable t) {
            if (!promise.tryFailure(t)) {
                logger.warn("{} invoke() failed with a finished promise: {}", iCtx, promise, t);
            }
        } finally {
            ServiceInvocationContext.removeCurrent();
        }
    }

    private static String hostname(FullHttpRequest req) {
        final String hostname = req.headers().getAsString(HttpHeaderNames.HOST);
        if (hostname == null) {
            return "";
        }

        final int hostnameColonIdx = hostname.lastIndexOf(':');
        if (hostnameColonIdx < 0) {
            return hostname;
        }

        return hostname.substring(0, hostnameColonIdx);
    }

    private static String stripQuery(String uri) {
        final int queryStart = uri.indexOf('?');
        return queryStart < 0 ? uri : uri.substring(0, queryStart);
    }

    private void handleInvocationResult(
            ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
            ServiceInvocationContext iCtx, ServiceCodec codec, Future<Object> future,
            ScheduledFuture<?> timeoutFuture) throws Exception {

        // Release the original request which was retained before the invocation.
        ReferenceCountUtil.safeRelease(req);

        // Cancel the associated timeout, if any.
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(true);
        }

        if (future.isSuccess()) {
            final Object res = future.getNow();
            if (res instanceof FullHttpResponse) {
                respond(ctx, reqSeq, req, (FullHttpResponse) res);
            } else {
                final ByteBuf encoded = codec.encodeResponse(iCtx, res);
                respond(ctx, reqSeq, req, encoded);
            }
        } else {
            final Throwable cause = future.cause();
            final ByteBuf encoded = codec.encodeFailureResponse(iCtx, cause);
            if (codec.failureResponseFailsSession(iCtx)) {
                respond(ctx, reqSeq, req, toHttpResponseStatus(cause), encoded);
            } else {
                respond(ctx, reqSeq, req, encoded);
            }
        }
    }

    private static HttpResponseStatus toHttpResponseStatus(Throwable cause) {
        if (cause instanceof RequestTimeoutException) {
            return HttpResponseStatus.SERVICE_UNAVAILABLE;
        }

        return HttpResponseStatus.INTERNAL_SERVER_ERROR;
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req, ByteBuf content) {
        respond(ctx, reqSeq, req, HttpResponseStatus.OK, content);
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
                         HttpResponseStatus status, ByteBuf content) {

        if (content == null) {
            content = Unpooled.EMPTY_BUFFER;
        }
        respond(ctx, reqSeq, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content));
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req, HttpResponseStatus status) {
        if (status.code() < 400) {
            respond(ctx, reqSeq, req, status, Unpooled.EMPTY_BUFFER);
        } else {
            respond(ctx, reqSeq, req, status, (Throwable) null);
        }
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req,
                         HttpResponseStatus status, Throwable cause) {

        assert status.code() >= 400;

        final String msg = errorMessage(status);
        if (cause != null) {
            logger.warn("{} Unexpected failure: {}", ctx.channel(), msg, cause);
        }

        final DefaultFullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(msg, StandardCharsets.UTF_8));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, ERROR_CONTENT_TYPE);

        respond(ctx, reqSeq, req, res);
    }

    private static String errorMessage(HttpResponseStatus status) {
        String reasonPhrase = status.reasonPhrase();
        StringBuilder buf = new StringBuilder(reasonPhrase.length() + 4);

        buf.append(status.code());
        buf.append(' ');
        buf.append(reasonPhrase);
        return buf.toString();
    }

    private void respond(ChannelHandlerContext ctx, int reqSeq, FullHttpRequest req, FullHttpResponse res) {
        String streamId = req.headers().getAsString(STREAM_ID);
        if (streamId != null) {
            // HTTP/2
            res.headers().set(STREAM_ID, streamId);
        }

        if (useHeadOfLineBlocking && !handlePendingResponses(ctx, reqSeq, res)) {
            return;
        }

        if (!handledLastRequest) {
            addKeepAliveHeaders(res);
            ctx.write(res).addListener(CLOSE_ON_FAILURE);
        } else {
            ctx.write(res).addListener(CLOSE);
        }

        if (!isReading) {
            ctx.flush();
        }
    }

    private boolean handlePendingResponses(ChannelHandlerContext ctx, int reqSeq, FullHttpResponse res) {
        final IntObjectMap<FullHttpResponse> pendingResponses = this.pendingResponses;
        while (reqSeq != resSeq) {
            FullHttpResponse pendingRes = pendingResponses.remove(resSeq);
            if (pendingRes == null) {
                // Stuck by head-of-line blocking; try again later.
                FullHttpResponse oldPendingRes = pendingResponses.put(reqSeq, res);
                if (oldPendingRes != null) {
                    // It is impossible to reach here as long as there are 2G+ pending responses.
                    logger.error("{} Orphaned pending response ({}): {}", reqSeq, oldPendingRes);
                    ReferenceCountUtil.safeRelease(oldPendingRes.release());
                }
                return false;
            }

            addKeepAliveHeaders(pendingRes);
            ctx.write(pendingRes);
            resSeq++;
        }

        // At this point, we have cleared all the pending responses. i.e. reqSeq = resSeq
        // Increment resSeq in preparation of the next request.
        resSeq++;
        return true;
    }

    private static void addKeepAliveHeaders(FullHttpResponse res) {
        // Add 'Content-Length' header only for a keep-alive connection.
        res.headers().set(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
        // Add keep alive header as per:
        // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
        res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        isReading = false;
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logUnexpectedException(ctx.channel(), cause);
        ctx.close();
    }

    private static void logUnexpectedException(Channel ch, Throwable cause) {
        if (!logger.isWarnEnabled()) {
            return;
        }

        if (IGNORABLE_ERROR_MESSAGE.matcher(cause.getMessage()).find()) {
            return;
        }

        logger.warn("{} Unexpected exception:", ch, cause);
    }
}
