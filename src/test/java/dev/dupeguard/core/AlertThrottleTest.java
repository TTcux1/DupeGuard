package dev.dupeguard.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertThrottleTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private MutableClock clock;
    private AlertThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        throttle = new AlertThrottle(Duration.ofSeconds(30), clock);
    }

    private Finding finding(FindingType type, Severity severity) {
        return new Finding(clock.instant(), PLAYER, "Notch", type, severity, "", "test");
    }

    @Test
    void repeatedAlertIsSuppressedDuringCooldown() {
        assertTrue(throttle.shouldAlert(finding(FindingType.CLICK_FLOOD, Severity.LOW)));
        assertFalse(throttle.shouldAlert(finding(FindingType.CLICK_FLOOD, Severity.LOW)));
        clock.advance(Duration.ofSeconds(30));
        assertTrue(throttle.shouldAlert(finding(FindingType.CLICK_FLOOD, Severity.LOW)));
    }

    @Test
    void differentTypesAreThrottledIndependently() {
        assertTrue(throttle.shouldAlert(finding(FindingType.CLICK_FLOOD, Severity.LOW)));
        assertTrue(throttle.shouldAlert(finding(FindingType.OVERSTACK, Severity.HIGH)));
    }

    @Test
    void escalationBypassesCooldown() {
        assertTrue(throttle.shouldAlert(finding(FindingType.CONTAINER_GAIN, Severity.MEDIUM)));
        assertTrue(throttle.shouldAlert(finding(FindingType.CONTAINER_GAIN, Severity.HIGH)));
        assertFalse(throttle.shouldAlert(finding(FindingType.CONTAINER_GAIN, Severity.MEDIUM)));
    }

    @Test
    void discordPayloadIsEscapedJson() {
        Finding f = new Finding(clock.instant(), PLAYER, "Bad\"Name\\", FindingType.OVERSTACK,
                Severity.CRITICAL, "world 0 0 0", "line1\nline2");
        String json = DiscordPayload.build(f, "Survival", ZoneOffset.UTC);

        assertTrue(json.contains("Bad\\\"Name\\\\"), json);
        assertTrue(json.contains("line1\\nline2"), json);
        assertTrue(json.contains("\"color\":" + 0xE74C3C), json);
        assertEquals("\\u0001", DiscordPayload.escape("\u0001"));
    }
}
