package dev.dupeguard.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerAuditorTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private MutableClock clock;
    private ContainerAuditor auditor;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        auditor = new ContainerAuditor(DetectionSettings.defaults(), clock);
    }

    private void open(ItemCounts before, boolean shared) {
        auditor.open(PLAYER, "Steve", "CHEST", "world 10 64 -3", before, shared);
    }

    @Test
    void movingItemsBetweenInventoryAndChestIsNotSuspicious() {
        open(ItemCounts.of("DIAMOND", 20, "DIRT", 64), false);
        clock.advance(Duration.ofSeconds(5));

        // Игрок переложил предметы: в сумме по системе ничего не изменилось.
        Optional<Finding> finding = auditor.close(PLAYER, ItemCounts.of("DIAMOND", 20, "DIRT", 64), false);

        assertTrue(finding.isEmpty());
        assertFalse(auditor.hasSession(PLAYER));
    }

    @Test
    void lossesAreNotSuspicious() {
        open(ItemCounts.of("DIAMOND", 20), false);
        assertTrue(auditor.close(PLAYER, ItemCounts.of("DIAMOND", 3), false).isEmpty());
    }

    @Test
    void unexplainedGainProducesHighFinding() {
        open(ItemCounts.of("DIAMOND", 20), false);
        clock.advance(Duration.ofMillis(4200));

        Finding finding = auditor.close(PLAYER, ItemCounts.of("DIAMOND", 40), false).orElseThrow();

        assertEquals(FindingType.CONTAINER_GAIN, finding.type());
        assertEquals(Severity.HIGH, finding.severity());
        assertEquals("Steve", finding.playerName());
        assertEquals("world 10 64 -3", finding.location());
        assertEquals("CHEST: +20 DIAMOND за 4.2 с", finding.details());
    }

    @Test
    void pickupDuringSessionIsSubtractedFromGain() {
        open(ItemCounts.of("DIAMOND", 20), false);
        auditor.recordExternalInput(PLAYER, "DIAMOND", 5);

        assertTrue(auditor.close(PLAYER, ItemCounts.of("DIAMOND", 25), false).isEmpty());
    }

    @Test
    void gainBeyondExternalInputIsStillReported() {
        open(ItemCounts.of("DIAMOND", 20), false);
        auditor.recordExternalInput(PLAYER, "DIAMOND", 5);

        Finding finding = auditor.close(PLAYER, ItemCounts.of("DIAMOND", 27), false).orElseThrow();

        assertTrue(finding.details().contains("+2 DIAMOND"), finding.details());
    }

    @Test
    void sharedContainerLowersSeverity() {
        open(ItemCounts.of("DIAMOND", 20), true);
        Finding finding = auditor.close(PLAYER, ItemCounts.of("DIAMOND", 21), false).orElseThrow();
        assertEquals(Severity.MEDIUM, finding.severity());
        assertTrue(finding.details().contains("нескольких игроков"));
    }

    @Test
    void markSharedAfterOpenAlsoLowersSeverity() {
        open(ItemCounts.of("DIAMOND", 20), false);
        auditor.markShared(PLAYER);
        Finding finding = auditor.close(PLAYER, ItemCounts.of("DIAMOND", 21), false).orElseThrow();
        assertEquals(Severity.MEDIUM, finding.severity());
    }

    @Test
    void gainOnDisconnectIsCritical() {
        open(ItemCounts.of("SHULKER_BOX", 1), false);
        Finding finding = auditor.close(PLAYER, ItemCounts.of("SHULKER_BOX", 2), true).orElseThrow();
        assertEquals(FindingType.QUIT_WITH_GAIN, finding.type());
        assertEquals(Severity.CRITICAL, finding.severity());
    }

    @Test
    void closeWithoutSessionReturnsEmpty() {
        assertTrue(auditor.close(PLAYER, ItemCounts.of("DIAMOND", 99), false).isEmpty());
    }

    @Test
    void minimumGainThresholdIsRespected() {
        DetectionSettings d = DetectionSettings.defaults();
        DetectionSettings tolerant = new DetectionSettings(d.watchedItems(), d.maxClicksPerSecond(),
                d.pickupWindow(), d.maxPickupsInWindow(), 5, d.alertCooldown(), d.minAlertSeverity());
        ContainerAuditor tolerantAuditor = new ContainerAuditor(tolerant, clock);

        tolerantAuditor.open(PLAYER, "Steve", "BARREL", "", ItemCounts.of("DIRT", 10), false);
        assertTrue(tolerantAuditor.close(PLAYER, ItemCounts.of("DIRT", 14), false).isEmpty());

        tolerantAuditor.open(PLAYER, "Steve", "BARREL", "", ItemCounts.of("DIRT", 10), false);
        assertTrue(tolerantAuditor.close(PLAYER, ItemCounts.of("DIRT", 15), false).isPresent());
    }

    @Test
    void durationFormatting() {
        assertEquals("0.5 с", ContainerAuditor.formatDuration(Duration.ofMillis(500)));
        assertEquals("2 мин 5 с", ContainerAuditor.formatDuration(Duration.ofSeconds(125)));
    }
}
