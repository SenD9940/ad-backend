package com.orinan.api.common.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 외부 API를 한 줄로 세우고, 429면 Retry-After 후 재시도합니다.
 */
@Slf4j
public class RateLimitedRetryInterceptor implements ClientHttpRequestInterceptor {

    private static final long MAX_WAIT_MS = 60_000L;

    private final String name;
    private final long minIntervalMs;
    private final int maxAttempts;
    private final Object lock = new Object();
    private long nextAllowedAtMs;

    public RateLimitedRetryInterceptor(String name, long minIntervalMs, int maxAttempts) {
        this.name = name;
        this.minIntervalMs = Math.max(0L, minIntervalMs);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        IOException lastIo = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            acquire();
            ClientHttpResponse response;
            try {
                response = execution.execute(request, body);
            } catch (IOException e) {
                lastIo = e;
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn(
                        "{} request io failed; retry {}/{}. {} {}",
                        name,
                        attempt,
                        maxAttempts,
                        request.getMethod(),
                        request.getURI()
                );
                sleep(backoffMs(attempt, minIntervalMs));
                continue;
            }
            if (!isTooManyRequests(response) || attempt >= maxAttempts) {
                return response;
            }
            long waitMs = retryDelayMs(response, attempt);
            log.warn(
                    "{} 429; retry {}/{} after {}ms. {} {}",
                    name,
                    attempt,
                    maxAttempts,
                    waitMs,
                    request.getMethod(),
                    request.getURI()
            );
            response.close();
            sleep(waitMs);
        }
        if (lastIo != null) {
            throw lastIo;
        }
        throw new IOException(name + " rate limited");
    }

    private void acquire() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long waitMs = nextAllowedAtMs - now;
            if (waitMs > 0) {
                sleep(Math.min(waitMs, MAX_WAIT_MS));
                now = System.currentTimeMillis();
            }
            nextAllowedAtMs = now + minIntervalMs;
        }
    }

    private long retryDelayMs(ClientHttpResponse response, int attempt) {
        long retryAfterMs = parseRetryAfterMs(response);
        return Math.max(minIntervalMs, Math.max(retryAfterMs, backoffMs(attempt, 2_000L)));
    }

    private static long backoffMs(int attempt, long baseMs) {
        int shift = Math.min(Math.max(attempt - 1, 0), 5);
        return Math.min(MAX_WAIT_MS, baseMs * (1L << shift));
    }

    private static long parseRetryAfterMs(ClientHttpResponse response) {
        String header = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header == null || header.isBlank()) {
            return 0L;
        }
        String value = header.strip();
        try {
            return Math.min(MAX_WAIT_MS, Long.parseLong(value) * 1000L);
        } catch (NumberFormatException ignored) {
            try {
                long at = ZonedDateTime
                        .parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US))
                        .toInstant()
                        .toEpochMilli();
                return Math.min(MAX_WAIT_MS, Math.max(0L, at - System.currentTimeMillis()));
            } catch (RuntimeException ignoredDate) {
                return 0L;
            }
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rate limit wait interrupted", e);
        }
    }

    private static boolean isTooManyRequests(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
    }
}
