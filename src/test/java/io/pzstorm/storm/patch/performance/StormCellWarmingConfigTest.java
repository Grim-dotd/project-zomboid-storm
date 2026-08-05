package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the live warm-set bound setter fed by the {@code Storm.MaxWarmCells} sandbox option. */
class StormCellWarmingConfigTest implements UnitTest {

    private int savedMaxWarmCells;

    @BeforeEach
    void captureState() {
        savedMaxWarmCells = StormCellWarmingConfig.maxWarmCells();
        StormCellWarmingConfig.setMaxWarmCellsForTest(
                StormCellWarmingConfig.DEFAULT_MAX_WARM_CELLS);
    }

    @AfterEach
    void restoreState() {
        StormCellWarmingConfig.setMaxWarmCellsForTest(savedMaxWarmCells);
    }

    @Test
    void defaultSitsInsideSandboxBounds() {
        assertTrue(
                StormCellWarmingConfig.DEFAULT_MAX_WARM_CELLS
                        >= StormCellWarmingConfig.MIN_SANDBOX_MAX_WARM_CELLS);
        assertTrue(
                StormCellWarmingConfig.DEFAULT_MAX_WARM_CELLS
                        <= StormCellWarmingConfig.MAX_SANDBOX_MAX_WARM_CELLS);
    }

    @Test
    void setFromSandboxAppliesAndPersists() {
        int applied = StormCellWarmingConfig.setMaxWarmCellsFromSandbox(128, false);
        assertEquals(128, applied);
        assertEquals(128, StormCellWarmingConfig.maxWarmCells());
    }

    @Test
    void setFromSandboxClampsBelowFloor() {
        // The sandbox option cannot express "unbounded" — 0 and negatives clamp to the floor
        // instead of adopting the launch flag's <= 0 = unbounded semantics.
        for (int requested : new int[] {0, -1, -1000}) {
            int applied = StormCellWarmingConfig.setMaxWarmCellsFromSandbox(requested, false);
            assertEquals(StormCellWarmingConfig.MIN_SANDBOX_MAX_WARM_CELLS, applied);
            assertEquals(
                    StormCellWarmingConfig.MIN_SANDBOX_MAX_WARM_CELLS,
                    StormCellWarmingConfig.maxWarmCells());
        }
    }

    @Test
    void setFromSandboxClampsAboveCeiling() {
        int applied = StormCellWarmingConfig.setMaxWarmCellsFromSandbox(99_999, false);
        assertEquals(StormCellWarmingConfig.MAX_SANDBOX_MAX_WARM_CELLS, applied);
        assertEquals(
                StormCellWarmingConfig.MAX_SANDBOX_MAX_WARM_CELLS,
                StormCellWarmingConfig.maxWarmCells());
    }

    @Test
    void setFromSandboxAcceptsBoundaryValues() {
        assertEquals(
                StormCellWarmingConfig.MIN_SANDBOX_MAX_WARM_CELLS,
                StormCellWarmingConfig.setMaxWarmCellsFromSandbox(
                        StormCellWarmingConfig.MIN_SANDBOX_MAX_WARM_CELLS, false));
        assertEquals(
                StormCellWarmingConfig.MAX_SANDBOX_MAX_WARM_CELLS,
                StormCellWarmingConfig.setMaxWarmCellsFromSandbox(
                        StormCellWarmingConfig.MAX_SANDBOX_MAX_WARM_CELLS, false));
    }

    @Test
    void launchFlagPinsBoundAgainstSandbox() {
        StormCellWarmingConfig.setMaxWarmCellsForTest(500);
        int inEffect = StormCellWarmingConfig.setMaxWarmCellsFromSandbox(32, true);
        assertEquals(500, inEffect, "forced-by-property apply must return the pinned bound");
        assertEquals(500, StormCellWarmingConfig.maxWarmCells(), "pinned bound must not change");
    }

    @Test
    void launchFlagPinPreservesUnboundedSentinel() {
        // -Dstorm.cells.maxWarm=0 (or negative) means unbounded; a sandbox push while pinned
        // must not replace the sentinel with a clamped positive bound.
        StormCellWarmingConfig.setMaxWarmCellsForTest(0);
        int inEffect = StormCellWarmingConfig.setMaxWarmCellsFromSandbox(64, true);
        assertEquals(0, inEffect);
        assertEquals(0, StormCellWarmingConfig.maxWarmCells());
    }
}
