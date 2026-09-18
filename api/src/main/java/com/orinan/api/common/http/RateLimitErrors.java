package com.orinan.api.common.http;

import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI / Threads / Tavily 429를 예약 실패로 쌓지 않기 위한 판별.
 */
public final class RateLimitErrors {

    private RateLimitErrors() {
    }

    public static boolean isRateLimited(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RestClientResponseException rest
                    && rest.getStatusCode().value() == 429) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("429")
                        || lower.contains("too many request")
                        || lower.contains("rate limit")
                        || lower.contains("rate_limit")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
