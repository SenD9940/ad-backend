package com.orinan.api.domain.navercommerce.client;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.client.NaverReadExecutor.Quota;
import com.orinan.api.domain.navercommerce.client.NaverReadExecutor.Resource;
import com.orinan.api.domain.navercommerce.client.NaverReadExecutor.UpstreamFailure;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaverReadExecutorTest {
    private final AtomicLong now = new AtomicLong();
    private final Instant base = Instant.parse("2026-09-28T00:00:00Z");
    private final NaverReadExecutor executor = new NaverReadExecutor(now::get, () -> base.plusNanos(now.get()), now::addAndGet);

    @Test
    void callsShareApplicationPacingWhileDifferentResourcesAndApplicationsRemainIndependent() {
        var starts = new ArrayList<Long>();
        executor.execute("app-A", Resource.ORDERS, deadline(10), timeout -> { starts.add(now.get()); return "old-token"; });
        executor.execute("app-A", Resource.ORDERS, deadline(10), timeout -> { starts.add(now.get()); return "new-token"; });
        executor.execute("app-A", Resource.PRODUCTS, deadline(10), timeout -> { starts.add(now.get()); return "products"; });
        executor.execute("app-B", Resource.ORDERS, deadline(10), timeout -> { starts.add(now.get()); return "different-app"; });
        assertThat(starts).containsExactly(0L, 600_000_000L, 600_000_000L, 600_000_000L);
    }

    @Test
    void transientStatusesRetryAtMostTwiceWithOneThenTwoSecondBackoff() {
        for (int status : List.of(429, 500, 502, 503, 504)) {
            var clock = new AtomicLong();
            var reads = new NaverReadExecutor(clock::get, () -> base, clock::addAndGet);
            var starts = new ArrayList<Long>();
            assertThatThrownBy(() -> reads.execute("app", Resource.ORDERS, Duration.ofSeconds(30).toNanos(), timeout -> {
                starts.add(clock.get());
                throw new UpstreamFailure(status, Quota.NONE, null);
            })).isInstanceOf(ApiException.class).hasNoCause();
            assertThat(starts).containsExactly(0L, 1_000_000_000L, 3_000_000_000L);
        }
    }

    @Test
    void deltaAndHttpDateRetryAfterAreHonoredWithoutTruncatingLargeDelays() {
        assertThat(executor.retryAfter("5")).isEqualTo(Duration.ofSeconds(5));
        assertThat(executor.retryAfter("Mon, 28 Sep 2026 00:00:05 GMT")).isEqualTo(Duration.ofSeconds(5));
        assertThat(executor.retryAfter("999999999999999999")).isEqualTo(Duration.ofSeconds(999999999999999999L));
        assertThat(executor.retryAfter(Long.toString(Long.MAX_VALUE))).isEqualTo(Duration.ofSeconds(Long.MAX_VALUE));
        assertThat(executor.retryAfter("9".repeat(30))).isEqualTo(Duration.ofSeconds(Long.MAX_VALUE));
        for (String invalid : new String[]{null, "", "-1", "NaN", "5x", "9".repeat(129)}) assertThat(executor.retryAfter(invalid)).isNull();
        var starts = new ArrayList<Long>();
        String result = executor.execute("app", Resource.ORDERS, deadline(20), timeout -> {
            starts.add(now.get());
            if (starts.size() == 1) throw new UpstreamFailure(429, Quota.NONE, executor.retryAfter("Mon, 28 Sep 2026 00:00:05 GMT"));
            return "complete";
        });
        assertThat(result).isEqualTo("complete");
        assertThat(starts).containsExactly(0L, 5_000_000_000L);
    }

    @Test
    void finalFailureStillDelaysTheNextCallerByTheProviderRetryAfter() {
        var count = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute("app", Resource.ORDERS, deadline(20), timeout -> {
            int attempt = count.incrementAndGet();
            throw new UpstreamFailure(429, Quota.NONE, attempt == 3 ? Duration.ofSeconds(7) : null);
        })).isInstanceOf(ApiException.class);
        assertThat(count).hasValue(3);
        assertThat(now).hasValue(3_000_000_000L);
        long started = executor.execute("app", Resource.ORDERS, deadline(20), timeout -> now.get());
        assertThat(started).isEqualTo(10_000_000_000L);
    }

    @Test
    void insufficientDeadlineDoesNotRetryEarlyAndCooldownSurvivesForOtherCallers() {
        var count = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute("app", Resource.ORDERS, deadline(4), timeout -> {
            count.incrementAndGet();
            throw new UpstreamFailure(429, Quota.NONE, Duration.ofSeconds(5));
        })).isInstanceOf(ApiException.class);
        assertThat(count).hasValue(1);
        assertThat(now).hasValue(0);
        long started = executor.execute("app", Resource.ORDERS, deadline(10), timeout -> now.get());
        assertThat(started).isEqualTo(5_000_000_000L);

        assertThatThrownBy(() -> executor.execute("huge-delay-app", Resource.ORDERS, deadline(45), timeout -> {
            count.incrementAndGet();
            throw new UpstreamFailure(429, Quota.NONE, Duration.ofSeconds(Long.MAX_VALUE));
        })).isInstanceOf(ApiException.class).hasNoCause();
        assertThatThrownBy(() -> executor.execute("huge-delay-app", Resource.ORDERS, deadline(45), timeout -> {
            count.incrementAndGet(); return "must-not-run";
        })).isInstanceOf(ApiException.class).hasNoCause();
        assertThat(count).hasValue(2);
    }

    @Test
    void roundAndUnknownQuotasAndNonTransientErrorsAreNotRetried() {
        for (Quota quota : List.of(Quota.ROUND, Quota.UNKNOWN)) {
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> executor.execute(quota.name(), Resource.ORDERS, deadline(10), timeout -> {
                calls.incrementAndGet(); throw new UpstreamFailure(429, quota, null);
            })).isInstanceOf(ApiException.class).hasMessageContaining("할당량");
            assertThat(calls).hasValue(1);
        }
        for (int status : List.of(400, 401, 403, 404, 501, 308)) {
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> executor.execute("status-" + status, Resource.ORDERS, deadline(10), timeout -> {
                calls.incrementAndGet(); throw new UpstreamFailure(status, Quota.NONE, null);
            })).isInstanceOf(ApiException.class);
            assertThat(calls).hasValue(1);
        }
        var failure = new ApiException(ApiCode.SERVER_ERROR, "invalid response");
        assertThatThrownBy(() -> executor.execute("parse", Resource.ORDERS, deadline(10), timeout -> { throw failure; })).isSameAs(failure);
    }

    @Test
    void secondsQuotaCanRecoverAfterOneRetry() {
        var calls = new AtomicInteger();
        assertThat(executor.<String>execute("quota", Resource.ORDERS, deadline(10), timeout -> {
            if (calls.incrementAndGet() == 1) throw new UpstreamFailure(429, Quota.SECONDS, null);
            return "ok";
        })).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(now).hasValue(1_000_000_000L);
    }

    @Test
    void deadlineCoversPacingAndBoundsEachHttpTimeout() {
        assertThat(executor.<Duration>execute("app", Resource.ORDERS, deadline(30), timeout -> timeout)).isEqualTo(Duration.ofSeconds(10));
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute("app", Resource.ORDERS, Duration.ofMillis(500).toNanos(), timeout -> {
            calls.incrementAndGet(); return timeout;
        })).isInstanceOf(ApiException.class);
        assertThat(calls).hasValue(0);
        assertThat(executor.<Duration>execute("another", Resource.ORDERS, deadline(3), timeout -> timeout)).isEqualTo(Duration.ofSeconds(3));
        assertThatThrownBy(() -> executor.execute("late-response", Resource.ORDERS, deadline(3), timeout -> {
            now.addAndGet(Duration.ofSeconds(4).toNanos()); return "too late";
        })).isInstanceOf(ApiException.class).hasMessageContaining("조회 시간");
    }

    @Test
    void interruptedBackoffRestoresInterruptFlagAndDoesNotMakeAnotherRequest() {
        var reads = new NaverReadExecutor(now::get, () -> base, nanos -> { throw new InterruptedException(); });
        var calls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> reads.execute("app", Resource.ORDERS, deadline(10), timeout -> {
                calls.incrementAndGet(); throw new UpstreamFailure(429, Quota.NONE, null);
            })).isInstanceOf(ApiException.class).hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls).hasValue(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void concurrentCallerCannotOverlapAnActiveRequestAndStopsWaitingAtItsDeadline() throws Exception {
        var reads = new NaverReadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> reads.execute("app", Resource.ORDERS, System.nanoTime() + Duration.ofSeconds(5).toNanos(), timeout -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                return "first";
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> reads.execute("app", Resource.ORDERS, System.nanoTime() + Duration.ofMillis(20).toNanos(), timeout -> {
                calls.incrementAndGet(); return "second";
            })).isInstanceOf(ApiException.class).hasMessageContaining("조회 시간");
            assertThat(calls).hasValue(0);
            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private long deadline(int seconds) { return now.get() + Duration.ofSeconds(seconds).toNanos(); }
}
