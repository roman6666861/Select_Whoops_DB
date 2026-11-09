package io.github.roman6666861.swdb.storage;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Учёт блокировок диапазонов в памяти одного экземпляра БД.
 * Проверка конфликтов и регистрация блокировки выполняются атомарно.
 * tryAcquire возвращает false при конфликте, acquire ожидает до тайм-аута.
 * <p>
 * {@link KvStore} создаёт общий менеджер, а {@link KvTransaction} освобождает
 * своего владельца при завершении. При прямом использовании менеджера
 * вызывающий сам освобождает владельца. Историю записей и конфликты со снимками
 * этот компонент не отслеживает.
 */
public final class RangeLockManager {

    /** Отдельный объект на владельца; идентичность определяется по ссылке. */
    public static final class Owner {
    }

    public enum Mode {
        SHARED,
        EXCLUSIVE
    }

    private record HeldLock(Owner owner, LockRange range, Mode mode) {
    }

    private record WaitRequest(LockRange range, Mode mode) {
    }

    private final List<HeldLock> locks = new ArrayList<>();
    private final Map<Owner, WaitRequest> waiting = new IdentityHashMap<>();

    /**
     * Пересекающиеся SHARED совместимы; EXCLUSIVE конфликтует с любым чужим режимом.
     * Собственные блокировки не мешают повторному захвату или усилению.
     * Неудачное усиление сохраняет все ранее полученные блокировки.
     */
    public synchronized boolean tryAcquire(Owner owner, LockRange range, Mode mode) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(mode, "mode");
        if (range.isEmpty()) {
            return true;
        }
        for (HeldLock held : locks) {
            if (held.owner() != owner && held.range().overlaps(range)
                    && (held.mode() == Mode.EXCLUSIVE || mode == Mode.EXCLUSIVE)) {
                return false;
            }
        }
        locks.add(new HeldLock(owner, range, mode));
        return true;
    }

    /**
     * Ожидает освобождения диапазона; false означает истечение тайм-аута.
     * Нулевой тайм-аут разрешает только немедленный захват, отрицательный запрещён.
     * При прерывании, тайм-ауте или взаимной блокировке прежние блокировки сохраняются.
     * Запрос, замыкающий цикл ожидания, получает DEADLOCK_DETECTED: вызывающий
     * должен откатить транзакцию, чтобы освободить её блокировки.
     * У одного владельца допустимо одно ожидание. Очереди по порядку поступления нет.
     */
    public synchronized boolean acquire(Owner owner, LockRange range, Mode mode, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos = timeout.toNanos();
        long started = System.nanoTime();
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (waiting.containsKey(owner)) {
            throw new IllegalStateException("owner is already waiting for a range lock");
        }
        try {
            while (!tryAcquire(owner, range, mode)) {
                long remaining = timeoutNanos - (System.nanoTime() - started);
                if (remaining <= 0) {
                    return false;
                }
                waiting.put(owner, new WaitRequest(range, mode));
                if (hasWaitCycle(owner)) {
                    throw new SwdbException(SqlStates.DEADLOCK_DETECTED, "range lock deadlock detected");
                }
                // wait освобождает монитор: другие транзакции могут снять блокировки.
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        } finally {
            waiting.remove(owner);
        }
    }

    /** Проверяем пути ожидания по текущим блокировкам, не сохраняя устаревающие рёбра. */
    private boolean hasWaitCycle(Owner start) {
        ArrayDeque<Owner> pending = new ArrayDeque<>();
        HashSet<Owner> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            Owner owner = pending.removeLast();
            if (!visited.add(owner)) {
                continue;
            }
            WaitRequest request = waiting.get(owner);
            if (request == null) {
                continue;
            }
            for (HeldLock held : locks) {
                if (held.owner() != owner && held.range().overlaps(request.range())
                        && (held.mode() == Mode.EXCLUSIVE || request.mode() == Mode.EXCLUSIVE)) {
                    if (held.owner() == start) {
                        return true;
                    }
                    pending.add(held.owner());
                }
            }
        }
        return false;
    }

    /** Освобождает все диапазоны владельца, не затрагивая остальных. Повторный вызов допустим. */
    public synchronized void releaseAll(Owner owner) {
        Objects.requireNonNull(owner, "owner");
        if (locks.removeIf(held -> held.owner() == owner)) {
            notifyAll();
        }
    }
}
