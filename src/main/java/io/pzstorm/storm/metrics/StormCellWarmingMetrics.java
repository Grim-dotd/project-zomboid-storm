package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;

/**
 * Prometheus instruments for Storm's cell warming feature (see {@code
 * io.pzstorm.storm.patch.performance.StormCellWarmingConfig}).
 *
 * <ul>
 *   <li>{@code storm_cell_warmed_total} — cells transitioned to warm (Unload short-circuited).
 *   <li>{@code storm_cell_rewarmed_total} — cells re-attached from the warm map (avoided disk read
 *       + binary parse + RecalcAll2).
 *   <li>{@code storm_cell_warm_count} — current number of warm cells held in memory.
 *   <li>{@code storm_cell_max_warm_cells} — configured bound on the warm set ({@code
 *       Storm.MaxWarmCells} sandbox option, or {@code -Dstorm.cells.maxWarm} when that flag pins
 *       it; {@code <= 0} = unbounded, flag-forced only).
 *   <li>{@code storm_cell_warm_evicted_total} — warm cells destructively unloaded because the warm
 *       set exceeded the configured bound.
 *   <li>{@code storm_cell_warm_eligibility_fail_total} — cells that fell through to vanilla unload
 *       because the eligibility predicate rejected them, labelled by {@code reason}.
 *   <li>{@code storm_cell_warm_duration_seconds} — time a cell spent in warm state before either
 *       being rewarmed or fully unloaded.
 *   <li>{@code storm_cell_warm_op_duration_seconds} — wall-clock time spent inside the warm
 *       operation itself (chunk disconnect + animal/dead-body drain).
 *   <li>{@code storm_cell_rewarm_op_duration_seconds} — wall-clock time spent inside the rewarm
 *       operation itself (chunk reconnect + animal/dead-body restore + event dispatch).
 * </ul>
 */
public final class StormCellWarmingMetrics {

    private static final Counter CELLS_WARMED =
            Counter.builder()
                    .name("storm_cell_warmed_total")
                    .help("Cells whose ServerCell.Unload was short-circuited into the warm map.")
                    .register(StormPrometheus.registry());

    private static final Counter CELLS_REWARMED =
            Counter.builder()
                    .name("storm_cell_rewarmed_total")
                    .help(
                            "Cells re-attached from the warm map by ServerMap.loadOrKeepRelevent,"
                                    + " avoiding disk read and RecalcAll2.")
                    .register(StormPrometheus.registry());

    private static final Gauge CELLS_WARM_COUNT =
            Gauge.builder()
                    .name("storm_cell_warm_count")
                    .help("Current number of cells held warm in memory.")
                    .register(StormPrometheus.registry());

    private static final Gauge MAX_WARM_CELLS =
            Gauge.builder()
                    .name("storm_cell_max_warm_cells")
                    .help(
                            "Configured bound on the warm-cell set. Sourced from the"
                                    + " Storm.MaxWarmCells sandbox option, unless"
                                    + " -Dstorm.cells.maxWarm pins it (the launch flag always"
                                    + " wins). Values <= 0 mean unbounded and can only come from"
                                    + " the launch flag. Compare against storm_cell_warm_count.")
                    .register(StormPrometheus.registry());

    private static final Counter CELLS_EVICTED =
            Counter.builder()
                    .name("storm_cell_warm_evicted_total")
                    .help(
                            "Warm cells destructively unloaded because the warm set exceeded"
                                    + " the configured bound (Storm.MaxWarmCells sandbox option or"
                                    + " -Dstorm.cells.maxWarm launch flag).")
                    .register(StormPrometheus.registry());

    private static final Counter ELIGIBILITY_FAILS =
            Counter.builder()
                    .name("storm_cell_warm_eligibility_fail_total")
                    .help(
                            "ServerCell.Unload calls where the eligibility predicate rejected"
                                    + " warming and vanilla destructive unload ran instead.")
                    .labelNames("reason")
                    .register(StormPrometheus.registry());

    private static final Histogram WARM_DURATION =
            Histogram.builder()
                    .name("storm_cell_warm_duration_seconds")
                    .help(
                            "Time a cell spent in warm state before being rewarmed or fully"
                                    + " unloaded.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram WARM_OP_DURATION =
            Histogram.builder()
                    .name("storm_cell_warm_op_duration_seconds")
                    .help(
                            "Wall-clock time spent inside StormCellWarmer.warm() per cell (chunk"
                                    + " disconnect + animal/dead-body drain).")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram REWARM_OP_DURATION =
            Histogram.builder()
                    .name("storm_cell_rewarm_op_duration_seconds")
                    .help(
                            "Wall-clock time spent inside StormCellWarmer.rewarm() per cell (chunk"
                                    + " reconnect + animal/dead-body restore + event dispatch).")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    static {
        // Reflect the seeded bound (compiled default or -Dstorm.cells.maxWarm) before the sandbox
        // applier has run — a scrape must never report a bound the eviction pass is not using.
        MAX_WARM_CELLS.set(StormCellWarmingConfig.maxWarmCells());
    }

    private StormCellWarmingMetrics() {}

    public static void incCellsWarmed() {
        CELLS_WARMED.inc();
    }

    public static void setMaxWarmCells(int max) {
        MAX_WARM_CELLS.set(max);
    }

    public static void incCellsRewarmed() {
        CELLS_REWARMED.inc();
    }

    public static void setWarmCount(int count) {
        CELLS_WARM_COUNT.set(count);
    }

    public static void incCellsEvicted() {
        CELLS_EVICTED.inc();
    }

    public static void incEligibilityFail(String reason) {
        ELIGIBILITY_FAILS.labelValues(reason).inc();
    }

    public static void recordWarmDurationNanos(long nanos) {
        WARM_DURATION.observe(nanos / 1e9);
    }

    public static void recordWarmOpNanos(long nanos) {
        WARM_OP_DURATION.observe(nanos / 1e9);
    }

    public static void recordRewarmOpNanos(long nanos) {
        REWARM_OP_DURATION.observe(nanos / 1e9);
    }
}
