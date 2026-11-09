package io.github.roman6666861.swdb.storage;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Снимки живых владельцев и история зафиксированных записей диапазонов.
 * Пока не подключены к транзакциям. Не захватывает блокировки и не создаёт снимки.
 * <p>
 * Все вызовы должны выполняться под общим монитором менеджера блокировок.
 * Создание настоящего снимка и его регистрация тоже должны быть под этим монитором:
 * регистрация старого снимка после очистки истории уже не восстановит защиту.
 */
final class RangeWriteHistory {

    private record CommittedWrite(LockRange range, long sequence) {
    }

    private final Map<RangeLockManager.Owner, Long> snapshots = new IdentityHashMap<>();
    private final List<CommittedWrite> writes = new ArrayList<>();

    void register(RangeLockManager.Owner owner, long sequence) {
        Objects.requireNonNull(owner, "owner");
        requireSequence(sequence);
        if (snapshots.putIfAbsent(owner, sequence) != null) {
            throw new IllegalStateException("owner already has a snapshot");
        }
    }

    void refreshSnapshot(RangeLockManager.Owner owner, long sequence) {
        long previous = snapshotOf(owner);
        requireSequence(sequence);
        if (sequence < previous) {
            throw new IllegalArgumentException("snapshot must not move backwards");
        }
        snapshots.put(owner, sequence);
        prune();
    }

    /** Вызывается только после успешного COMMIT, до освобождения блокировок владельца. */
    void committed(RangeLockManager.Owner owner, long sequence, List<LockRange> ranges) {
        long snapshot = snapshotOf(owner);
        requireSequence(sequence);
        if (sequence < snapshot) {
            throw new IllegalArgumentException("commit sequence must not precede snapshot");
        }
        // Проверяем весь список до изменения состояния, включая null внутри списка.
        List<LockRange> checked = List.copyOf(ranges);
        for (LockRange range : checked) {
            if (!range.isEmpty()) {
                writes.add(new CommittedWrite(range, sequence));
            }
        }
        snapshots.remove(owner);
        prune();
    }

    /** Откат/закрытие: удаляет снимок без добавления записей; повторный вызов допустим. */
    void unregister(RangeLockManager.Owner owner) {
        Objects.requireNonNull(owner, "owner");
        snapshots.remove(owner);
        prune();
    }

    boolean hasConflict(RangeLockManager.Owner owner, LockRange range) {
        long snapshot = snapshotOf(owner);
        Objects.requireNonNull(range, "range");
        return writes.stream().anyMatch(write -> write.sequence() > snapshot && write.range().overlaps(range));
    }

    /** Число сохраняемых записей для проверки ограничений очистки. */
    int retainedWriteCount() {
        return writes.size();
    }

    private long snapshotOf(RangeLockManager.Owner owner) {
        Objects.requireNonNull(owner, "owner");
        Long sequence = snapshots.get(owner);
        if (sequence == null) {
            throw new IllegalStateException("owner has no registered snapshot");
        }
        return sequence;
    }

    private void prune() {
        if (snapshots.isEmpty()) {
            writes.clear();
            return;
        }
        long oldest = snapshots.values().stream().mapToLong(Long::longValue).min().orElseThrow();
        writes.removeIf(write -> write.sequence() <= oldest);
    }

    private static void requireSequence(long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
    }
}
