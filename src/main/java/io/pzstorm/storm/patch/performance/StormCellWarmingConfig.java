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
 * <p>Both knobs are live sandbox options, applied at {@code OnServerStarted} and re-applied on
 * every admin sandbox push:
 *
 * <ul>
 *   <li>{@code Storm.CellWarming} feeds {@link #setEnabledFromSandbox(boolean)}. Off by default so
 *       existing servers retain vanilla unload semantics until they explicitly choose otherwise.
 *       Enabling live starts warming on the next {@code ServerMap.postupdate} tick; disabling live
 *       stops new warms and drains the existing warm set through the vanilla destructive unload
 *       path (throttled — see {@code StormCellWarmer#evictOverBudget}). The warm-path advices stay
 *       engaged until the drain completes, gated on {@code isEnabled() || warmCount() > 0}.
 *   <li>{@code Storm.MaxWarmCells} feeds {@link #setMaxWarmCellsFromSandbox(int)}; the eviction
 *       pass re-reads {@link #maxWarmCells()} every tick. A warm cell keeps its full chunk/square
 *       state resident, so without a cap the warm set grows with every cell any player has ever
 *       walked away from. When the cap is exceeded the oldest-warmed cells are evicted through the
 *       vanilla destructive unload path.
 * </ul>
 *
 * <p>The launch flags pin their option: while {@code -Dstorm.cells.keepWarm} is present (any value)
 * the {@code Storm.CellWarming} option is ignored, and while {@code -Dstorm.cells.maxWarm} is
 * present the {@code Storm.MaxWarmCells} option is ignored — preserving pre-option server configs
 * verbatim. {@code -Dstorm.cells.maxWarm} also remains the only way to run unbounded ({@code <=
 * 0}); the sandbox option's floor is {@link #MIN_SANDBOX_MAX_WARM_CELLS}. Omit a flag to make its
 * knob sandbox-tunable at runtime.
 *
 * <p>This class must never call into {@code StormCellWarmer}: the warmer's class initializer
 * reaches into {@code ServerMap.ServerCell} reflectively, so touching it from here would couple
 * config mutation (which may run on a network thread, or in unit tests with no game classes) to
 * game-class loading. All warm-set mutation stays on the server main thread inside {@code
 * StormCellWarmer.runPostUpdate}.
 */
public final class StormCellWarmingConfig {

    /** Cell warming ships disabled — vanilla unload semantics until an admin opts in. */
    public static final boolean DEFAULT_ENABLED = false;

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

    /**
     * True when {@code -Dstorm.cells.keepWarm} is present at JVM start, with any value. The launch
     * flag always wins over the {@code Storm.CellWarming} sandbox option — {@link
     * #setEnabledFromSandbox} no-ops while this is set. Presence pins in both directions: {@code
     * =true} pins warming on, an explicit {@code =false} (or any other value — {@link
     * Boolean#getBoolean} treats non-"true" as false) pins it off.
     */
    private static final boolean ENABLED_FORCED_BY_PROPERTY =
            System.getProperty("storm.cells.keepWarm") != null;

    /**
     * Live enable state. Volatile because the sandbox applier may push updates from outside the
     * main thread; consumers only ever read the flag per call.
     */
    private static volatile boolean currentEnabled = Boolean.getBoolean("storm.cells.keepWarm");

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
        return currentEnabled;
    }

    /** Whether {@code -Dstorm.cells.keepWarm} pins the enable state against sandbox changes. */
    public static boolean isEnabledForcedByProperty() {
        return ENABLED_FORCED_BY_PROPERTY;
    }

    /**
     * Applies the {@code Storm.CellWarming} sandbox option and pushes the applied state to the
     * Prometheus gauge — unless {@code -Dstorm.cells.keepWarm} was present at JVM start, in which
     * case the launch flag always wins and the state is left untouched. Returns the state in effect
     * afterwards.
     *
     * <p>Single mutation point — sandbox apply and tests both funnel through here. The setter only
     * flips the volatile; enabling takes effect on the next {@code ServerMap.postupdate} tick, and
     * disabling leaves the drain of any existing warm set to the server main thread inside {@code
     * StormCellWarmer.runPostUpdate} (the warm-path advices stay engaged until it empties).
     */
    public static boolean setEnabledFromSandbox(boolean requested) {
        return setEnabledFromSandbox(requested, ENABLED_FORCED_BY_PROPERTY);
    }

    /** Visible for tests — in production {@code forcedByProperty} is fixed at class init. */
    static boolean setEnabledFromSandbox(boolean requested, boolean forcedByProperty) {
        if (forcedByProperty) {
            LOGGER.info(
                    "Storm: ignoring sandbox Storm.CellWarming={} — -Dstorm.cells.keepWarm takes"
                            + " precedence ({} in effect)",
                    requested,
                    currentEnabled);
            return currentEnabled;
        }
        boolean previous = currentEnabled;
        currentEnabled = requested;
        StormCellWarmingMetrics.setCellWarmingEnabled(requested);
        if (!previous && requested) {
            LOGGER.info(
                    "Storm: cell warming enabled — cells leaving player relevance will be kept"
                            + " warm (bound {})",
                    currentMaxWarmCells);
        } else if (previous && !requested) {
            LOGGER.info(
                    "Storm: cell warming disabled — existing warm cells drain through the vanilla"
                            + " unload path over the next ticks");
        }
        return requested;
    }

    /** Test-only — overrides the live enable state without logging or gauge push. */
    static void setEnabledForTest(boolean value) {
        currentEnabled = value;
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
