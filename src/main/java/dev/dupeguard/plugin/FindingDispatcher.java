package dev.dupeguard.plugin;

import dev.dupeguard.core.AlertThrottle;
import dev.dupeguard.core.DiscordPayload;
import dev.dupeguard.core.Finding;
import dev.dupeguard.core.FindingType;
import dev.dupeguard.core.Severity;
import dev.dupeguard.storage.FindingStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Куда уходят находки: в базу (всегда), в консоль, стаффу в игре и в Discord.
 * Алерты проходят через {@link AlertThrottle}, чтобы не спамить.
 */
final class FindingDispatcher {

    static final String ALERT_PERMISSION = "dupeguard.alerts";

    private final FindingStore store;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Set<UUID> mutedStaff = ConcurrentHashMap.newKeySet();
    private final Map<FindingType, Integer> stats = new EnumMap<>(FindingType.class);

    private volatile PluginConfig config;
    private volatile AlertThrottle throttle;

    FindingDispatcher(FindingStore store, Logger logger, PluginConfig config, AlertThrottle throttle) {
        this.store = store;
        this.logger = logger;
        this.config = config;
        this.throttle = throttle;
    }

    void reconfigure(PluginConfig newConfig, AlertThrottle newThrottle) {
        this.config = newConfig;
        this.throttle = newThrottle;
    }

    /** Вызывать из главного потока. */
    void dispatch(Finding finding) {
        stats.merge(finding.type(), 1, Integer::sum);
        store.save(finding);

        Severity minSeverity = config.detection().minAlertSeverity();
        if (!finding.severity().isAtLeast(minSeverity) || !throttle.shouldAlert(finding)) {
            return;
        }

        logger.warning("[%s] %s: %s @ %s — %s".formatted(finding.severity(), finding.type().title(),
                finding.playerName(), finding.location(), finding.details()));

        Component message = formatAlert(finding);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(ALERT_PERMISSION) && !mutedStaff.contains(staff.getUniqueId())) {
                staff.sendMessage(message);
            }
        }

        sendToDiscord(finding);
    }

    /** @return true, если алерты теперь включены. */
    boolean toggleAlerts(UUID staffId) {
        if (mutedStaff.remove(staffId)) {
            return true;
        }
        mutedStaff.add(staffId);
        return false;
    }

    Map<FindingType, Integer> stats() {
        return Map.copyOf(stats);
    }

    static NamedTextColor colorFor(Severity severity) {
        return switch (severity) {
            case LOW -> NamedTextColor.GRAY;
            case MEDIUM -> NamedTextColor.YELLOW;
            case HIGH -> NamedTextColor.GOLD;
            case CRITICAL -> NamedTextColor.RED;
        };
    }

    private static Component formatAlert(Finding finding) {
        String lookup = "/dupeguard lookup " + finding.playerName();
        return Component.text("[DupeGuard] ", NamedTextColor.DARK_RED)
                .append(Component.text("[" + finding.severity() + "] ", colorFor(finding.severity())))
                .append(Component.text(finding.playerName(), NamedTextColor.WHITE))
                .append(Component.text(" — " + finding.type().title() + ": ", NamedTextColor.GRAY))
                .append(Component.text(finding.details(), NamedTextColor.WHITE))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Где: " + (finding.location().isEmpty() ? "—" : finding.location())
                                + "\nНажмите, чтобы открыть историю игрока", NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.runCommand(lookup));
    }

    private void sendToDiscord(Finding finding) {
        PluginConfig current = config;
        if (current.discordWebhookUrl().isEmpty()) {
            return;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(current.discordWebhookUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            DiscordPayload.build(finding, current.serverName(), ZoneId.systemDefault())))
                    .build();
        } catch (IllegalArgumentException e) {
            logger.warning("Некорректный discord-webhook-url в config.yml");
            return;
        }
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        logger.warning("Discord-вебхук недоступен: " + error.getMessage());
                    } else if (response.statusCode() >= 300) {
                        logger.warning("Discord-вебхук вернул код " + response.statusCode());
                    }
                });
    }
}
