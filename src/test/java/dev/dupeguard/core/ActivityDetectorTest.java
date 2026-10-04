package dev.dupeguard.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityDetectorTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private MutableClock clock;
    private ActivityDetector detector;
    private DetectionSettings settings;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        settings = DetectionSettings.defaults();
        detector = new ActivityDetector(settings, clock);
    }

    @Test
    void clicksBelowLimitAreFine() {
        for (int i = 0; i < settings.maxClicksPerSecond(); i++) {
            assertTrue(detector.onClick(PLAYER, "Alex", "").isEmpty());
        }
    }

    @Test
    void clickFloodIsReportedOnceThenWindowResets() {
        Optional<Finding> result = Optional.empty();
        for (int i = 0; i <= settings.maxClicksPerSecond(); i++) {
            result = detector.onClick(PLAYER, "Alex", "");
        }
        assertEquals(FindingType.CLICK_FLOOD, result.orElseThrow().type());
        assertTrue(detector.onClick(PLAYER, "Alex", "").isEmpty(), "окно сброшено после срабатывания");
    }

    @Test
    void clicksSpreadOverTimeAreFine() {
        for (int i = 0; i < 200; i++) {
            assertTrue(detector.onClick(PLAYER, "Alex", "").isEmpty());
            clock.advance(Duration.ofMillis(100)); // 10 кликов в секунду
        }
    }

    @Test
    void pickupOfUnwatchedItemsIsIgnored() {
        assertTrue(detector.onPickup(PLAYER, "Alex", "COBBLESTONE", 10_000, "").isEmpty());
    }

    @Test
    void pickupBurstOfWatchedItemIsReported() {
        assertTrue(detector.onPickup(PLAYER, "Alex", "DIAMOND", 64, "").isEmpty());
        Finding finding = detector.onPickup(PLAYER, "Alex", "DIAMOND", 1, "").orElseThrow();
        assertEquals(FindingType.PICKUP_BURST, finding.type());
        assertTrue(finding.details().contains("65 × DIAMOND"), finding.details());
    }

    @Test
    void pickupWindowExpires() {
        assertTrue(detector.onPickup(PLAYER, "Alex", "DIAMOND", 64, "").isEmpty());
        clock.advance(settings.pickupWindow().plusMillis(1));
        assertTrue(detector.onPickup(PLAYER, "Alex", "DIAMOND", 64, "").isEmpty());
    }

    @Test
    void overstackIsDetected() {
        assertTrue(detector.checkStack(PLAYER, "Alex", "DIAMOND", 64, 64, "").isEmpty());
        Finding finding = detector.checkStack(PLAYER, "Alex", "TOTEM_OF_UNDYING", 5, 1, "").orElseThrow();
        assertEquals(FindingType.OVERSTACK, finding.type());
        assertEquals(Severity.HIGH, finding.severity());
    }

    @Test
    void purgeIdleDropsExpiredWindows() {
        SlidingWindowCounter<String> counter = new SlidingWindowCounter<>(Duration.ofSeconds(1), clock);
        counter.record("a", 1);
        counter.record("b", 1);
        clock.advance(Duration.ofSeconds(2));
        counter.purgeIdle();
        assertEquals(0, counter.trackedKeys());
    }
}
