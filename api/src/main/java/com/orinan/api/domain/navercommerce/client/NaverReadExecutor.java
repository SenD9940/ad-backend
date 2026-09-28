package com.orinan.api.domain.navercommerce.client;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Applies only to catalog/order reads. No token, application ID or response body is retained. */
@Component
public class NaverReadExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(NaverReadExecutor.class);
    private static final long INTERVAL_NANOS = Duration.ofMillis(600).toNanos();
    private static final long HTTP_TIMEOUT_NANOS = Duration.ofSeconds(10).toNanos();
    private static final int MAX_ATTEMPTS = 3;
    // Fixed stripes bound memory. Different applications may conservatively share a stripe;
    // resources are separate, and active gates are never evicted during an in-flight request.
    private final Gate[][] gates = new Gate[Resource.values().length][64];
    private final LongSupplier nanoTime;
    private final Supplier<Instant> wallTime;
    private final Sleeper sleeper;

    public NaverReadExecutor() {
        this(System::nanoTime, Instant::now, nanos -> TimeUnit.NANOSECONDS.sleep(nanos));
    }

    NaverReadExecutor(LongSupplier nanoTime, Supplier<Instant> wallTime, Sleeper sleeper) {
        this.nanoTime = nanoTime;
        this.wallTime = wallTime;
        this.sleeper = sleeper;
        for (var resource : gates) {
            for (int index = 0; index < resource.length; index++) resource[index] = new Gate();
        }
    }

    public <T> T execute(String applicationId, Resource resource, long deadlineNanos, Function<Duration, T> request) {
        if (applicationId == null || applicationId.isBlank()) {
            throw new ApiException(ApiCode.BAD_REQUEST, "네이버 애플리케이션 연결 정보를 확인해 주세요.");
        }
        Gate gate = gates[resource.ordinal()][Math.floorMod(applicationId.hashCode(), gates[0].length)];
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (!gate.lock.tryLock(remaining(deadlineNanos), TimeUnit.NANOSECONDS)) throw deadlineExceeded();
                try {
                    waitForCooldown(gate, deadlineNanos);
                    if (gate.initialized) waitUntil(gate.nextAllowedNanos, deadlineNanos);
                    long left = remaining(deadlineNanos);
                    gate.nextAllowedNanos = nanoTime.getAsLong() + INTERVAL_NANOS;
                    gate.initialized = true;
                    try {
                        T result = request.apply(Duration.ofNanos(Math.min(HTTP_TIMEOUT_NANOS, left)));
                        remaining(deadlineNanos);
                        return result;
                    } catch (UpstreamFailure failure) {
                        LOG.warn("Naver read response: resource={}, status={}, reason={}", resource, failure.status, failure.reason());
                        Duration delay = Duration.ofSeconds(Math.min(attempt, 2));
                        if (failure.retryAfter != null && failure.retryAfter.compareTo(delay) > 0) delay = failure.retryAfter;
                        if (failure.status == 429 || failure.status >= 500 || failure.retryAfter != null) {
                            // Keep the entire provider delay even if this caller cannot wait or has exhausted retries.
                            // A Duration avoids overflow for huge Retry-After values; convert only after the budget check.
                            gate.cooldown = delay;
                            gate.cooldownStartedNanos = nanoTime.getAsLong();
                        }
                        if (!failure.retryable() || attempt == MAX_ATTEMPTS
                                || delay.compareTo(Duration.ofNanos(remaining(deadlineNanos))) >= 0) throw failure.publicError();
                    }
                } finally {
                    gate.lock.unlock();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ApiException(ApiCode.SERVER_ERROR, "네이버 조회가 중단되었습니다. 다시 조회해 주세요.");
            }
        }
        throw deadlineExceeded();
    }

    public Duration retryAfter(String header) {
        if (header == null || header.isBlank() || header.length() > 128) return null;
        String value = header.strip();
        try {
            if (value.matches("[0-9]+")) {
                try { return Duration.ofSeconds(Long.parseLong(value)); }
                catch (NumberFormatException ignored) { return Duration.ofSeconds(Long.MAX_VALUE); }
            }
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)).toInstant();
            Duration delay = Duration.between(wallTime.get(), at);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void waitUntil(long target, long deadline) throws InterruptedException {
        long wait;
        while ((wait = target - nanoTime.getAsLong()) > 0) {
            if (wait >= remaining(deadline)) throw deadlineExceeded();
            sleeper.sleep(wait);
        }
    }

    private void waitForCooldown(Gate gate, long deadline) throws InterruptedException {
        while (gate.cooldown != null) {
            Duration wait = gate.cooldown.minusNanos(Math.max(0, nanoTime.getAsLong() - gate.cooldownStartedNanos));
            if (wait.isNegative() || wait.isZero()) { gate.cooldown = null; return; }
            if (wait.compareTo(Duration.ofNanos(remaining(deadline))) >= 0) throw deadlineExceeded();
            sleeper.sleep(wait.toNanos());
        }
    }

    private long remaining(long deadline) {
        long result = deadline - nanoTime.getAsLong();
        if (result <= 0) throw deadlineExceeded();
        return result;
    }

    private ApiException deadlineExceeded() {
        return new ApiException(ApiCode.SERVER_ERROR, "네이버 전체 조회 시간이 초과되었습니다. 기간을 줄여 다시 조회해 주세요.");
    }

    public enum Resource { PRODUCTS, ORDERS }
    public enum Quota { NONE, SECONDS, ROUND, UNKNOWN }

    @FunctionalInterface
    interface Sleeper { void sleep(long nanos) throws InterruptedException; }

    private static final class Gate {
        private final ReentrantLock lock = new ReentrantLock(true);
        private long nextAllowedNanos;
        private boolean initialized;
        private Duration cooldown;
        private long cooldownStartedNanos;
    }

    /** Only safe status/category metadata crosses from the HTTP reader into retry handling. */
    public static final class UpstreamFailure extends RuntimeException {
        private final int status;
        private final Quota quota;
        private final Duration retryAfter;

        public UpstreamFailure(int status, Quota quota, Duration retryAfter) {
            super("Naver read response HTTP " + status, null, false, false);
            this.status = status;
            this.quota = quota;
            this.retryAfter = retryAfter;
        }

        private boolean retryable() {
            return quota != Quota.ROUND && quota != Quota.UNKNOWN
                    && (status == 429 || status == 500 || status == 502 || status == 503 || status == 504);
        }

        private String reason() {
            if (quota != Quota.NONE) return "QUOTA_" + quota.name();
            if (status == 429) return "RATE_LIMIT";
            return status >= 500 ? "UPSTREAM_SERVER" : "REQUEST_REJECTED";
        }

        private ApiException publicError() {
            if (quota == Quota.ROUND) return new ApiException(ApiCode.SERVER_ERROR,
                    "네이버 구독 회차의 조회 할당량을 소진했습니다. 네이버 커머스API에서 이용 한도를 확인해 주세요.");
            if (quota == Quota.UNKNOWN) return new ApiException(ApiCode.SERVER_ERROR,
                    "네이버 조회 할당량을 초과했습니다. 네이버 커머스API에서 이용 한도를 확인해 주세요.");
            if (status == 429) return new ApiException(ApiCode.SERVER_ERROR,
                    "네이버 조회 요청량 제한에 도달했습니다. 잠시 후 다시 조회해 주세요.");
            if (status >= 500) return new ApiException(ApiCode.SERVER_ERROR,
                    "네이버 서버의 일시적인 오류로 조회하지 못했습니다. 잠시 후 다시 조회해 주세요.");
            return new ApiException(ApiCode.BAD_REQUEST,
                    "네이버 상품·주문 조회 권한과 애플리케이션 설정을 확인해 주세요.");
        }
    }
}
