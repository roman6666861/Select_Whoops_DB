package io.github.roman6666861.swdb.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static io.github.roman6666861.swdb.storage.RangeLockManager.Mode.EXCLUSIVE;
import static io.github.roman6666861.swdb.storage.RangeLockManager.Mode.SHARED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RangeLockWaitTest {

    private static final LockRange RANGE = LockRange.point(new byte[]{10});
    private final RangeLockManager manager = new RangeLockManager();
    private final RangeLockManager.Owner first = new RangeLockManager.Owner();
    private final RangeLockManager.Owner second = new RangeLockManager.Owner();
    private final RangeLockManager.Owner third = new RangeLockManager.Owner();

    @Test
    void waiterWakesWhenOwnerReleasesRange() throws Exception {
        assertThat(manager.tryAcquire(first, RANGE, EXCLUSIVE)).isTrue();
        try (Waiter waiter = startWaiter()) {
            manager.releaseAll(first);
            assertThat(waiter.result().get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.tryAcquire(third, RANGE, SHARED)).isFalse();
        }
    }

    @Test
    void writerWaitsUntilAllReadersReleaseRange() throws Exception {
        assertThat(manager.tryAcquire(first, RANGE, SHARED)).isTrue();
        assertThat(manager.tryAcquire(third, RANGE, SHARED)).isTrue();
        try (Waiter waiter = startWaiter()) {
            manager.releaseAll(first);
            // Даже если пробуждение ещё не обработано, захватить диапазон нельзя.
            assertThat(manager.tryAcquire(second, RANGE, EXCLUSIVE)).isFalse();
            assertThat(waiter.result().isDone()).isFalse();
            manager.releaseAll(third);
            assertThat(waiter.result().get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void timeoutPreservesEarlierLocksWithoutAcquiringRequestedRange() throws Exception {
        LockRange other = LockRange.point(new byte[]{20});
        assertThat(manager.tryAcquire(first, RANGE, EXCLUSIVE)).isTrue();
        assertThat(manager.tryAcquire(second, other, SHARED)).isTrue();
        assertThat(manager.acquire(second, RANGE, EXCLUSIVE, Duration.ofMillis(50))).isFalse();
        assertThat(manager.tryAcquire(third, other, EXCLUSIVE)).isFalse();
        manager.releaseAll(first);
        assertThat(manager.tryAcquire(third, RANGE, EXCLUSIVE)).isTrue();
    }

    @Test
    void zeroTimeoutAllowsOnlyImmediateAcquisition() throws Exception {
        assertThat(manager.acquire(first, RANGE, EXCLUSIVE, Duration.ZERO)).isTrue();
        assertThat(manager.acquire(second, RANGE, SHARED, Duration.ZERO)).isFalse();
        assertThatThrownBy(() -> manager.acquire(second, RANGE, SHARED, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interruptionStopsWaitingWithoutAcquiringRange() throws Exception {
        assertThat(manager.tryAcquire(first, RANGE, EXCLUSIVE)).isTrue();
        try (Waiter waiter = startWaiter()) {
            waiter.thread().interrupt();
            assertThatThrownBy(() -> waiter.result().get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(InterruptedException.class);
            manager.releaseAll(first);
            assertThat(manager.tryAcquire(third, RANGE, EXCLUSIVE)).isTrue();
        }
    }

    private Waiter startWaiter() throws Exception {
        FutureTask<Boolean> result = new FutureTask<>(
                () -> manager.acquire(second, RANGE, EXCLUSIVE, Duration.ofSeconds(10)));
        Waiter waiter = new Waiter(Thread.ofVirtual().start(result), result);
        try {
            long started = System.nanoTime();
            while (waiter.thread().getState() != Thread.State.TIMED_WAITING
                    && !result.isDone() && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5)) {
                Thread.sleep(5);
            }
            assertThat(waiter.thread().getState()).as("поток должен действительно ожидать блокировку")
                    .isEqualTo(Thread.State.TIMED_WAITING);
            return waiter;
        } catch (Exception | AssertionError e) {
            waiter.close();
            throw e;
        }
    }

    private record Waiter(Thread thread, FutureTask<Boolean> result) implements AutoCloseable {
        @Override
        public void close() {
            thread.interrupt();
            try {
                assertThat(thread.join(Duration.ofSeconds(5))).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("прервано завершение тестового потока", e);
            }
        }
    }
}
