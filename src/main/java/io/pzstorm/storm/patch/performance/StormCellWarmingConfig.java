package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormCellWarmingMetrics;

/**
 * Runtime gate for Storm's cell-warming feature.
 *
 * <p>When enabled, Storm keeps {@code IsoCell} state resident in memory past the point at which
 * vanilla would unload it, eliminating the load/unload thrash that occurs when players walk the
 * same boundary repeatedly or when a chunk re-enters the active frustum shortly after eviction.
 *
 * <p>Opt-in via {@code -Dstorm.cells.keepWarm=true}; off by default so existing servers retain
 * vanilla unload semantics until they explicitly choose otherwise. The enable flag is read once at
 * class-load time — it is set on the JVM command line and cannot change at runtime.
 *
 * <p>The warm-set bound is live: the {@code Storm.MaxWarmCells} sandbox option feeds {@link
 * #setMaxWarmCellsFromSandbox(int)} at {@code OnServerStarted} and again on every admin sandbox
 * push, and the eviction pass re-reads {@link #maxWarmCells()} every tick. A warm cell keeps its
 * full chunk/square state resident, so without a cap the warm set grows with every cell any player
 * has ever walked away from. When the cap is exceeded the oldest-warmed cells are evicted through
 * the vanilla destructive unload path.
 *
 * <p>{@code -Dstorm.cells.maxWarm=<n>} pins the bound: while the launch flag is present the sandbox
 * option is ignored, preserving pre-option server configs verbatim. The flag also remains the only
 * way to run unbounded ({@code <= 0}); the sandbox option's floor is {@link
 * #MIN_SANDBOX_MAX_WARM_CELLS}.
 */
public final class StormCellWarmingConfig {

    /** Warm-set bound applied when neither the launch flag nor the sandbox option set one. */
    public static final int DEFAULT_MAX_WARM_CELLS = 64;

    /**
     * Sandbox floor — the option cannot express "unbounded"; that stays a {@code
     * -Dstorm.cells.maxWarm} escape hatch.
     */
    public static final int MIN_SANDBOX_MAX_WARM_CELLS = 1;

    /**
     * Sandbox ceiling. Each warm cell holds 8×8 chunks of square state resident, so memory grows
     * linearly with the bound; the UI stops well short of values that would eat the heap.
     */
    public static final int MAX_SANDBOX_MAX_WARM_CELLS = 256;

    private static final boolean ENABLED = Boolean.getBoolean("storm.cells.keepWarm");

    private static final Integer MAX_WARM_PROPERTY = Integer.getInteger("storm.cells.maxWarm");

    /**
     * True when {@code -Dstorm.cells.maxWarm} supplied a valid value at JVM start. The launch flag
     * always wins over the {@code Storm.MaxWarmCells} sandbox option — {@link
     * #setMaxWarmCellsFromSandbox} no-ops while this is set. An unparseable property value does not
     * count as forcing ({@link Integer#getInteger(String)} already fell back to null).
     */
    private static final boolean MAX_WARM_FORCED_BY_PROPERTY = MAX_WARM_PROPERTY != null;

    /**
     * Live bound, re-read by the eviction pass every tick. Volatile because the sandbox applier may
     * push updates from outside the main thread.
     */
    private static volatile int currentMaxWarmCells =
            MAX_WARM_PROPERTY != null ? MAX_WARM_PROPERTY : DEFAULT_MAX_WARM_CELLS;

    private StormCellWarmingConfig() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** Maximum number of warm cells held in memory; {@code <= 0} means unbounded. */
    public static int maxWarmCells() {
        return currentMaxWarmCells;
    }

    /** Whether {@code -Dstorm.cells.maxWarm} pins the bound against sandbox changes. */
    public static boolean isMaxWarmCellsForcedByProperty() {
        return MAX_WARM_FORCED_BY_PROPERTY;
    }

    /**
     * Applies the {@code Storm.MaxWarmCells} sandbox option: clamps to the option's declared
     * bounds, stores, and pushes the applied value to the Prometheus gauge — unless {@code
     * -Dstorm.cells.maxWarm} was set at JVM start, in which case the launch flag always wins and
     * the bound is left untouched. Returns the bound in effect afterwards.
     *
     * <p>Single mutation point — sandbox apply and tests both funnel through here. The setter only
     * flips the volatile; the actual drain of an over-budget warm set happens on the server main
     * thread inside {@code StormCellWarmer.runPostUpdate}.
     */
    public static int setMaxWarmCellsFromSandbox(int requested) {
        return setMaxWarmCellsFromSandbox(requested, MAX_WARM_FORCED_BY_PROPERTY);
    }

    /** Visible for tests — in production {@code forcedByProperty} is fixed at class init. */
    static int setMaxWarmCellsFromSandbox(int requested, boolean forcedByProperty) {
        if (forcedByProperty) {
            LOGGER.info(
                    "Storm: ignoring sandbox Storm.MaxWarmCells={} — -Dstorm.cells.maxWarm takes"
                            + " precedence ({} in effect)",
                    requested,
                    currentMaxWarmCells);
            return currentMaxWarmCells;
        }
        int clamped =
                Math.max(
                        MIN_SANDBOX_MAX_WARM_CELLS,
                        Math.min(MAX_SANDBOX_MAX_WARM_CELLS, requested));
        if (clamped != requested) {
            LOGGER.warn(
                    "Storm: Storm.MaxWarmCells={} outside [{}, {}], clamping to {}",
                    requested,
                    MIN_SANDBOX_MAX_WARM_CELLS,
                    MAX_SANDBOX_MAX_WARM_CELLS,
                    clamped);
        }
        currentMaxWarmCells = clamped;
        StormCellWarmingMetrics.setMaxWarmCells(clamped);
        LOGGER.info("Storm: max warm cells updated to {}", clamped);
        return clamped;
    }

    /** Test-only — overrides the live bound without clamping, logging, or gauge push. */
    static void setMaxWarmCellsForTest(int value) {
        currentMaxWarmCells = value;
    }
}
