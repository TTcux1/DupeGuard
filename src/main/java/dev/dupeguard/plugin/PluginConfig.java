package dev.dupeguard.plugin;

import dev.dupeguard.core.DetectionSettings;
import dev.dupeguard.core.Severity;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.inventory.InventoryType;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/** Типизированное представление config.yml. */
record PluginConfig(
        DetectionSettings detection,
        Set<InventoryType> auditedContainers,
        String discordWebhookUrl,
        String serverName) {

    static PluginConfig load(FileConfiguration config, Logger logger) {
        DetectionSettings defaults = DetectionSettings.defaults();

        Set<String> watched = new HashSet<>();
        for (String item : config.getStringList("detection.watched-items")) {
            watched.add(item.trim().toUpperCase(Locale.ROOT));
        }
        if (watched.isEmpty()) {
            watched.addAll(defaults.watchedItems());
        }

        Severity minAlert = parseSeverity(
                config.getString("alerts.min-severity", defaults.minAlertSeverity().name()),
                defaults.minAlertSeverity(), logger);

        DetectionSettings detection = new DetectionSettings(
                watched,
                Math.max(1, config.getInt("detection.max-clicks-per-second", defaults.maxClicksPerSecond())),
                Duration.ofSeconds(Math.max(1, config.getInt("detection.pickup-window-seconds",
                        (int) defaults.pickupWindow().toSeconds()))),
                Math.max(1, config.getInt("detection.max-pickups-in-window", defaults.maxPickupsInWindow())),
                Math.max(1, config.getLong("detection.min-container-gain", defaults.minContainerGain())),
                Duration.ofSeconds(Math.max(0, config.getInt("alerts.cooldown-seconds",
                        (int) defaults.alertCooldown().toSeconds()))),
                minAlert);

        Set<InventoryType> containers = EnumSet.noneOf(InventoryType.class);
        List<String> configured = config.getStringList("detection.audited-containers");
        for (String name : configured) {
            try {
                containers.add(InventoryType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger.warning("Неизвестный тип контейнера в config.yml: " + name);
            }
        }
        if (containers.isEmpty()) {
            containers = EnumSet.of(InventoryType.CHEST, InventoryType.BARREL,
                    InventoryType.SHULKER_BOX, InventoryType.ENDER_CHEST);
        }

        return new PluginConfig(
                detection,
                containers,
                config.getString("alerts.discord-webhook-url", "").trim(),
                config.getString("alerts.server-name", "Minecraft"));
    }

    private static Severity parseSeverity(String value, Severity fallback, Logger logger) {
        try {
            return Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            logger.warning("Неизвестный уровень серьёзности '" + value + "', используется " + fallback);
            return fallback;
        }
    }
}
