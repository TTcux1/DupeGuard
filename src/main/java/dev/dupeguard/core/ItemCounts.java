package dev.dupeguard.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Количество предметов по ключу (обычно имя материала).
 * <p>
 * Используется как «снимок» содержимого инвентарей. Ядро ничего не знает о Bukkit,
 * поэтому логику детекции можно покрыть обычными юнит-тестами.
 */
public final class ItemCounts {

    private final Map<String, Long> counts = new HashMap<>();

    public static ItemCounts of(Object... keyAmountPairs) {
        if (keyAmountPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Ожидаются пары ключ/количество");
        }
        ItemCounts result = new ItemCounts();
        for (int i = 0; i < keyAmountPairs.length; i += 2) {
            result.add((String) keyAmountPairs[i], ((Number) keyAmountPairs[i + 1]).longValue());
        }
        return result;
    }

    public void add(String key, long amount) {
        if (key == null || amount <= 0) {
            return;
        }
        counts.merge(key, amount, Long::sum);
    }

    public void addAll(ItemCounts other) {
        other.counts.forEach(this::add);
    }

    public long get(String key) {
        return counts.getOrDefault(key, 0L);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public Map<String, Long> asMap() {
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Ключи, количество которых в этом снимке больше, чем в {@code baseline},
     * и величина прироста. Убыль игнорируется: предметы могут законно исчезать
     * (выброс, воронка, сгорание), а вот появляться из ниоткуда не должны.
     */
    public Map<String, Long> gainsOver(ItemCounts baseline) {
        Map<String, Long> gains = new TreeMap<>();
        counts.forEach((key, amount) -> {
            long diff = amount - baseline.get(key);
            if (diff > 0) {
                gains.put(key, diff);
            }
        });
        return gains;
    }

    /** Человекочитаемая строка вида {@code +2 DIAMOND, +1 ELYTRA}, по убыванию прироста. */
    public static String describeGains(Map<String, Long> gains) {
        Map<String, Long> sorted = new LinkedHashMap<>();
        gains.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        StringBuilder sb = new StringBuilder();
        sorted.forEach((key, amount) -> {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append('+').append(amount).append(' ').append(key);
        });
        return sb.toString();
    }

    @Override
    public String toString() {
        return new TreeMap<>(counts).toString();
    }
}
