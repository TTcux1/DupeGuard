package dev.dupeguard.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Пороги детекции. Значения читаются из config.yml в слое плагина.
 *
 * @param watchedItems         ценные предметы, за подбором которых следим
 * @param maxClicksPerSecond   порог кликов в инвентаре за секунду
 * @param pickupWindow         окно для подсчёта подбора ценных предметов
 * @param maxPickupsInWindow   сколько ценных предметов можно подобрать за окно
 * @param minContainerGain     минимальный прирост, который считается находкой
 * @param alertCooldown        как часто повторять алерт одного типа по одному игроку
 * @param minAlertSeverity     находки ниже этого уровня пишутся в базу, но не алертятся
 */
public record DetectionSettings(
        Set<String> watchedItems,
        int maxClicksPerSecond,
        Duration pickupWindow,
        int maxPickupsInWindow,
        long minContainerGain,
        Duration alertCooldown,
        Severity minAlertSeverity) {

    public DetectionSettings {
        watchedItems = Set.copyOf(Objects.requireNonNull(watchedItems, "watchedItems"));
        requirePositive(maxClicksPerSecond, "maxClicksPerSecond");
        requirePositive(maxPickupsInWindow, "maxPickupsInWindow");
        requirePositive(minContainerGain, "minContainerGain");
        Objects.requireNonNull(pickupWindow, "pickupWindow");
        Objects.requireNonNull(alertCooldown, "alertCooldown");
        Objects.requireNonNull(minAlertSeverity, "minAlertSeverity");
        if (pickupWindow.isZero() || pickupWindow.isNegative()) {
            throw new IllegalArgumentException("pickupWindow должен быть положительным");
        }
    }

    public static DetectionSettings defaults() {
        return new DetectionSettings(
                Set.of("DIAMOND", "DIAMOND_BLOCK", "NETHERITE_INGOT", "NETHERITE_BLOCK",
                        "ELYTRA", "TOTEM_OF_UNDYING", "ENCHANTED_GOLDEN_APPLE", "BEACON"),
                25,
                Duration.ofSeconds(10),
                64,
                1,
                Duration.ofSeconds(30),
                Severity.MEDIUM);
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " должен быть больше нуля");
        }
    }
}
