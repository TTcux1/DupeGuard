package dev.dupeguard.core;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Счётчик событий в скользящем окне времени, отдельно для каждого ключа.
 * Не потокобезопасен.
 *
 * @param <K> ключ (игрок или пара игрок + предмет)
 */
public final class SlidingWindowCounter<K> {

    private final Duration window;
    private final Clock clock;
    private final Map<K, Deque<Entry>> events = new HashMap<>();

    public SlidingWindowCounter(Duration window, Clock clock) {
        this.window = Objects.requireNonNull(window, "window");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Добавить событие и вернуть сумму в окне с его учётом. */
    public long record(K key, long amount) {
        long now = clock.millis();
        Deque<Entry> deque = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        evictOld(deque, now);
        deque.addLast(new Entry(now, amount));
        long sum = 0;
        for (Entry entry : deque) {
            sum += entry.amount;
        }
        return sum;
    }

    /** Сбросить окно ключа (например, после срабатывания или выхода игрока). */
    public void clear(K key) {
        events.remove(key);
    }

    /** Удалить ключи, по которым в окне не осталось событий. Вызывать периодически. */
    public void purgeIdle() {
        long now = clock.millis();
        events.values().removeIf(deque -> {
            evictOld(deque, now);
            return deque.isEmpty();
        });
    }

    public int trackedKeys() {
        return events.size();
    }

    private void evictOld(Deque<Entry> deque, long now) {
        long threshold = now - window.toMillis();
        while (!deque.isEmpty() && deque.peekFirst().time <= threshold) {
            deque.pollFirst();
        }
    }

    private record Entry(long time, long amount) {
    }
}
