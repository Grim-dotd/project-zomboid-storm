package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the live cell-warming setters fed by the {@code Storm.CellWarming} and {@code
 * Storm.MaxWarmCells} sandbox options.
 */
class StormCellWarmingConfigTest implements UnitTest {

    private boolean savedEnabled;
    private int savedMaxWarmCells;
    private int savedWarmCellCount;

    @BeforeEach
    void captureState() {
        savedEnabled = StormCellWarmingConfig.isEnabled();
        savedMaxWarmCells = StormCellWarmingConfig.maxWarmCells();
        savedWarmCellCount = StormCellWarmingConfig.warmCellCount();
        StormCellWarmingConfig.setEnabledForTest(StormCellWarmingConfig.DEFAULT_ENABLED);
        StormCellWarmingConfig.setMaxWarmCellsForTest(
                StormCellWarmingConfig.DEFAULT_MAX_WARM_CELLS);
        StormCellWarmingConfig.setWarmCellCountFromWarmer(0);
    }

    @AfterEach
    void restoreState() {
        StormCellWarmingConfig.setEnabledForTest(savedEnabled);
        StormCellWarmingConfig.setMaxWarmCellsForTest(savedMaxWarmCells);
        StormCellWarmingConfig.setWarmCellCountFromWarmer(savedWarmCellCount);
    }

    // -------- setEnabledFromSandbox() --------

    @Test
    void warmingShipsDisabled() {
        // Off-by-default is the compatibility contract: servers keep vanilla unload semantics
        // until an admin opts in via the option or the launch flag.
        assertFalse(StormCellWarmingConfig.DEFAULT_ENABLED);
    }

    @Test
    void enableFromSandboxAppliesAndPersists() {
        assertTrue(StormCellWarmingConfig.setEnabledFromSandbox(true, false));
        assertTrue(StormCellWarmingConfig.isEnabled());
    }

    @Test
    void disableFromSandboxAppliesAndPersists() {
        StormCellWarmingConfig.setEnabledForTest(true);
        assertFalse(StormCellWarmingConfig.setEnabledFromSandbox(false, false));
        assertFalse(StormCellWarmingConfig.isEnabled());
    }

    @Test
    void enableFromSandboxIsIdempotent() {
        StormCellWarmingConfig.setEnabledForTest(true);
        assertTrue(StormCellWarmingConfig.setEnabledFromSandbox(true, false));
        assertTrue(StormCellWarmingConfig.isEnabled());
    }

    @Test
    void launchFlagPinsEnabledOnAgainstSandboxDisable() {
        StormCellWarmingConfig.setEnabledForTest(true);
        assertTrue(
                StormCellWarmingConfig.setEnabledFromSandbox(false, true),
                "forced-by-property apply must return the pinned state");
        assertTrue(StormCellWarmingConfig.isEnabled(), "pinned state must not change");
    }

    @Test
    void launchFlagPinsEnabledOffAgainstSandboxEnable() {
        // -Dstorm.cells.keepWarm=false is an explicit operator choice — presence pins both ways.
        StormCellWarmingConfig.setEnabledForTest(false);
        assertFalse(StormCellWarmingConfig.setEnabledFromSandbox(true, true));
        assertFalse(StormCellWarmingConfig.isEnabled());
    }

    // -------- setMaxWarmCellsFromSandbox() --------

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

    // -------- warmCellCount mirror --------

    @Test
    void warmCellCountReflectsWarmerPublishes() {
        StormCellWarmingConfig.setWarmCellCountFromWarmer(3);
        assertEquals(3, StormCellWarmingConfig.warmCellCount());
        StormCellWarmingConfig.setWarmCellCountFromWarmer(0);
        assertEquals(0, StormCellWarmingConfig.warmCellCount());
    }

    @Test
    void warmCellCountStartsAtZeroContract() {
        // Documents the empty-set invariant the advices rely on: with no warmer publishes the
        // mirror reads 0, so a never-warming server's fast path stays isEnabled() alone and
        // StormCellWarmer is never class-loaded.
        assertEquals(0, StormCellWarmingConfig.warmCellCount());
    }
}
