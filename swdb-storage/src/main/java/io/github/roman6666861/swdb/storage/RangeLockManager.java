package io.github.roman6666861.swdb.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Учёт блокировок диапазонов в памяти одного экземпляра БД.
 * Проверка конфликтов и регистрация блокировки выполняются атомарно.
 * При конфликте вызов возвращает false без ожидания и изменения состояния.
 * <p>
 * Пока не подключён к транзакциям: вызывающий должен явно освободить владельца.
 * Историю записей и конфликты со снимками этот компонент не отслеживает.
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

    private final List<HeldLock> locks = new ArrayList<>();

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

    /** Освобождает все диапазоны владельца, не затрагивая остальных. Повторный вызов допустим. */
    public synchronized void releaseAll(Owner owner) {
        Objects.requireNonNull(owner, "owner");
        locks.removeIf(held -> held.owner() == owner);
    }
}
