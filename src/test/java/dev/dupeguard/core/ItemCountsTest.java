package dev.dupeguard.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemCountsTest {

    @Test
    void gainsIgnoreLossesAndReportOnlyIncreases() {
        ItemCounts before = ItemCounts.of("DIAMOND", 10, "DIRT", 64);
        ItemCounts after = ItemCounts.of("DIAMOND", 12, "DIRT", 1, "ELYTRA", 1);

        Map<String, Long> gains = after.gainsOver(before);

        assertEquals(Map.of("DIAMOND", 2L, "ELYTRA", 1L), gains);
    }

    @Test
    void identicalSnapshotsHaveNoGains() {
        ItemCounts snapshot = ItemCounts.of("DIAMOND", 5);
        assertTrue(snapshot.gainsOver(ItemCounts.of("DIAMOND", 5)).isEmpty());
    }

    @Test
    void nonPositiveAmountsAreIgnored() {
        ItemCounts counts = new ItemCounts();
        counts.add("DIAMOND", 0);
        counts.add("DIAMOND", -3);
        counts.add(null, 5);
        assertTrue(counts.isEmpty());
    }

    @Test
    void amountsAreSummedPerKey() {
        ItemCounts counts = new ItemCounts();
        counts.add("DIAMOND", 3);
        counts.add("DIAMOND", 4);
        counts.addAll(ItemCounts.of("DIAMOND", 1, "EMERALD", 2));
        assertEquals(8, counts.get("DIAMOND"));
        assertEquals(2, counts.get("EMERALD"));
    }

    @Test
    void describeGainsSortsByAmountDescending() {
        String text = ItemCounts.describeGains(Map.of("ELYTRA", 1L, "DIAMOND", 32L, "BEACON", 1L));
        assertEquals("+32 DIAMOND, +1 BEACON, +1 ELYTRA", text);
    }
}
