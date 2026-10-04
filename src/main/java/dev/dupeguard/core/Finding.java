package dev.dupeguard.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Одна подозрительная операция.
 *
 * @param time       когда обнаружено
 * @param playerId   UUID игрока
 * @param playerName ник на момент обнаружения
 * @param type       тип находки
 * @param severity   серьёзность
 * @param location   где произошло (мир и координаты), может быть пустой строкой
 * @param details    подробности для стаффа
 */
public record Finding(
        Instant time,
        UUID playerId,
        String playerName,
        FindingType type,
        Severity severity,
        String location,
        String details) {

    public Finding {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        location = location == null ? "" : location;
        details = details == null ? "" : details;
    }
}
