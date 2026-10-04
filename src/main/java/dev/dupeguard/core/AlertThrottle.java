package dev.dupeguard.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Защита стаффа от спама: одна и та же пара «игрок + тип находки» алертится
 * не чаще, чем раз в {@code cooldown}. В базу при этом пишется всё.
 * <p>
 * Более серьёзная находка проходит даже во время кулдауна.
 */
public final class AlertThrottle {

    private final Duration cooldown;
    private final Clock clock;
    private final Map<Key, Last> last = new HashMap<>();

    public AlertThrottle(Duration cooldown, Clock clock) {
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized boolean shouldAlert(Finding finding) {
        Instant now = clock.instant();
        Key key = new Key(finding.playerId(), finding.type());
        Last previous = last.get(key);
        boolean allowed = previous == null
                || !now.isBefore(previous.time.plus(cooldown))
                || finding.severity().compareTo(previous.severity) > 0;
        if (allowed) {
            last.put(key, new Last(now, finding.severity()));
        }
        return allowed;
    }

    public synchronized void forget(UUID playerId) {
        last.keySet().removeIf(key -> key.playerId.equals(playerId));
    }

    private record Key(UUID playerId, FindingType type) {
    }

    private record Last(Instant time, Severity severity) {
    }
}
