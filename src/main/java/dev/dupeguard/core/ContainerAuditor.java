package dev.dupeguard.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Главный детектор: проверяет «закон сохранения предметов».
 * <p>
 * Когда игрок открывает контейнер, мы фиксируем суммарное содержимое системы
 * «инвентарь игрока + курсор + контейнер». Пока контейнер открыт, предметы могут
 * перемещаться между частями системы, но их общее количество не может вырасти:
 * взяться неоткуда. При закрытии сравниваем снимки. Если чего-то стало больше
 * и это не объясняется легальными внешними поступлениями (подбор с земли,
 * воронка в сундук), значит сработал дюп или рассинхрон.
 * <p>
 * Класс не потокобезопасен и рассчитан на вызовы из главного потока сервера.
 */
public final class ContainerAuditor {

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final DetectionSettings settings;
    private final Clock clock;

    public ContainerAuditor(DetectionSettings settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Начать сессию. Если у игрока уже была сессия, она молча заменяется. */
    public void open(UUID playerId, String playerName, String containerType, String location,
                     ItemCounts before, boolean shared) {
        Session session = new Session(playerName, containerType, location, clock.instant(), before);
        session.shared = shared;
        sessions.put(playerId, session);
    }

    public boolean hasSession(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public int activeSessions() {
        return sessions.size();
    }

    /**
     * Легальное поступление предметов в систему во время сессии:
     * игрок подобрал предмет с земли или воронка добавила его в контейнер.
     * Эти количества вычитаются из прироста при закрытии.
     */
    public void recordExternalInput(UUID playerId, String key, long amount) {
        Session session = sessions.get(playerId);
        if (session != null) {
            session.externalInput.add(key, amount);
        }
    }

    /**
     * Контейнер одновременно открыт у нескольких игроков. Предметы могут
     * перетекать между их системами, поэтому прирост менее однозначен.
     */
    public void markShared(UUID playerId) {
        Session session = sessions.get(playerId);
        if (session != null) {
            session.shared = true;
        }
    }

    /** Отменить сессию без проверки (например, игрок перешёл в креатив). */
    public void discard(UUID playerId) {
        sessions.remove(playerId);
    }

    /**
     * Завершить сессию и сравнить снимки.
     *
     * @param after        содержимое системы на момент закрытия
     * @param disconnected сессия закрылась из-за выхода игрока с сервера
     * @return находка, если обнаружен необъяснимый прирост
     */
    public Optional<Finding> close(UUID playerId, ItemCounts after, boolean disconnected) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return Optional.empty();
        }

        Map<String, Long> gains = new HashMap<>();
        after.gainsOver(session.before).forEach((key, gain) -> {
            long unexplained = gain - session.externalInput.get(key);
            if (unexplained >= settings.minContainerGain()) {
                gains.put(key, unexplained);
            }
        });

        if (gains.isEmpty()) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        Duration duration = Duration.between(session.openedAt, now);
        FindingType type = disconnected ? FindingType.QUIT_WITH_GAIN : FindingType.CONTAINER_GAIN;
        Severity severity = severityFor(session.shared, disconnected);

        String details = "%s: %s за %s%s".formatted(
                session.containerType,
                ItemCounts.describeGains(gains),
                formatDuration(duration),
                session.shared ? " (контейнер был открыт у нескольких игроков)" : "");

        return Optional.of(new Finding(now, playerId, session.playerName, type, severity,
                session.location, details));
    }

    private static Severity severityFor(boolean shared, boolean disconnected) {
        if (disconnected) {
            return shared ? Severity.HIGH : Severity.CRITICAL;
        }
        return shared ? Severity.MEDIUM : Severity.HIGH;
    }

    static String formatDuration(Duration duration) {
        long millis = Math.max(0, duration.toMillis());
        if (millis < 60_000) {
            return "%.1f с".formatted(millis / 1000.0).replace(',', '.');
        }
        return "%d мин %d с".formatted(millis / 60_000, (millis / 1000) % 60);
    }

    private static final class Session {
        private final String playerName;
        private final String containerType;
        private final String location;
        private final Instant openedAt;
        private final ItemCounts before;
        private final ItemCounts externalInput = new ItemCounts();
        private boolean shared;

        private Session(String playerName, String containerType, String location,
                        Instant openedAt, ItemCounts before) {
            this.playerName = playerName;
            this.containerType = containerType;
            this.location = location;
            this.openedAt = openedAt;
            this.before = before;
        }
    }
}
