package com.premiumcars.common;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP calls with a deadline for the whole exchange. {@link HttpRequest#timeout} only covers the wait for the
 * response headers: a server that stops sending in the middle of the body keeps {@link HttpClient#send} waiting for
 * ever (the automatic import's queue once stood for hours on one photo). Here the call is cancelled when the whole
 * response, body included, has not arrived within {@code deadline}.
 */
public final class HttpCalls {

    private HttpCalls() {
    }

    /**
     * Like {@code client.send(request, handler)}, but gives up after {@code deadline}.
     *
     * @throws HttpTimeoutException when the deadline passes (the exchange is cancelled)
     */
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                           Duration deadline) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> call = client.sendAsync(request, handler);
        try {
            return call.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new HttpTimeoutException("No complete answer from " + request.uri().getHost() + " within "
                    + deadline.toSeconds() + " s");
        } catch (InterruptedException e) {
            call.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            throw new IOException(cause);
        }
    }
}
