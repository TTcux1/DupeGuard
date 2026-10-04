package dev.dupeguard.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Простые поведенческие проверки: флуд кликами, всплеск подбора ценных предметов
 * и переполненные стаки. Не потокобезопасен.
 */
public final class ActivityDetector {

    private final DetectionSettings settings;
    private final Clock clock;
    private final SlidingWindowCounter<UUID> clicks;
    private final SlidingWindowCounter<PickupKey> pickups;

    public ActivityDetector(DetectionSettings settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clicks = new SlidingWindowCounter<>(Duration.ofSeconds(1), clock);
        this.pickups = new SlidingWindowCounter<>(settings.pickupWindow(), clock);
    }

    /** Зарегистрировать клик в инвентаре. Срабатывает один раз при превышении порога. */
    public Optional<Finding> onClick(UUID playerId, String playerName, String location) {
        long inWindow = clicks.record(playerId, 1);
        if (inWindow <= settings.maxClicksPerSecond()) {
            return Optional.empty();
        }
        clicks.clear(playerId);
        return Optional.of(new Finding(clock.instant(), playerId, playerName,
                FindingType.CLICK_FLOOD, Severity.LOW, location,
                "Более %d кликов в инвентаре за секунду".formatted(settings.maxClicksPerSecond())));
    }

    /** Зарегистрировать подбор предмета. Учитываются только предметы из списка ценных. */
    public Optional<Finding> onPickup(UUID playerId, String playerName, String item, long amount,
                                      String location) {
        if (!settings.watchedItems().contains(item)) {
            return Optional.empty();
        }
        PickupKey key = new PickupKey(playerId, item);
        long inWindow = pickups.record(key, amount);
        if (inWindow <= settings.maxPickupsInWindow()) {
            return Optional.empty();
        }
        pickups.clear(key);
        return Optional.of(new Finding(clock.instant(), playerId, playerName,
                FindingType.PICKUP_BURST, Severity.MEDIUM, location,
                "Подобрано %d × %s за %d с (порог %d)".formatted(
                        inWindow, item, settings.pickupWindow().toSeconds(),
                        settings.maxPickupsInWindow())));
    }

    /** Проверить стак на превышение максимального размера. */
    public Optional<Finding> checkStack(UUID playerId, String playerName, String item, int amount,
                                        int maxStackSize, String location) {
        if (maxStackSize <= 0 || amount <= maxStackSize) {
            return Optional.empty();
        }
        return Optional.of(new Finding(clock.instant(), playerId, playerName,
                FindingType.OVERSTACK, Severity.HIGH, location,
                "%s × %d при максимуме %d".formatted(item, amount, maxStackSize)));
    }

    /** Забыть данные игрока (выход с сервера). */
    public void forget(UUID playerId) {
        clicks.clear(playerId);
        // Окна подбора по предметам очистятся сами в purgeIdle().
    }

    /** Освободить память от неактивных окон. */
    public void purgeIdle() {
        clicks.purgeIdle();
        pickups.purgeIdle();
    }

    private record PickupKey(UUID playerId, String item) {
    }
}
