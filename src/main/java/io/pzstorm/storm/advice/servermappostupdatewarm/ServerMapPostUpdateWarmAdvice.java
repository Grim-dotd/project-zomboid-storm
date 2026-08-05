package io.pzstorm.storm.advice.servermappostupdatewarm;

import io.pzstorm.storm.patch.performance.StormCellWarmer;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/**
 * Body-replacement advice for {@code ServerMap.postupdate}. When cell warming is enabled, delegates
 * the entire postupdate loop to {@link StormCellWarmer#runPostUpdate(ServerMap)} so warm cells stay
 * resident in {@code cellMap}/{@code loadedCells} instead of being destructively unloaded. When
 * warming is disabled and no warm cells remain, returns 0 to let vanilla run unchanged.
 *
 * <p>The gate is {@code isEnabled() || warmCellCount() > 0} (a count mirrored into the config class
 * so this gate never forces {@code StormCellWarmer} to class-load on servers that never warm), not
 * {@code isEnabled()} alone: {@code Storm.CellWarming} is live, and a disable while cells are warm
 * must keep the warm-aware loop in charge until the drain finishes — vanilla postupdate would
 * {@code Unload()} warm cells without reconnecting their chunks or restoring their animal/dead-body
 * stash. In that draining state {@code runPostUpdate} warms nothing new and evicts the warm set
 * down to zero, after which this advice falls through to vanilla.
 *
 * <p>Stacks under the existing {@code ServerMapPostUpdateAdvice} timing wrapper — both advices'
 * enter/exit hooks fire regardless of body skip, so per-tick timings continue to record the elapsed
 * wall-clock even on the warm-aware code path.
 */
public class ServerMapPostUpdateWarmAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.This Object thisObj) {
        if (!GameServer.server) {
            return 0;
        }
        if (!StormCellWarmingConfig.isEnabled() && StormCellWarmingConfig.warmCellCount() == 0) {
            return 0;
        }
        if (!(thisObj instanceof ServerMap serverMap)) {
            return 0;
        }
        StormCellWarmer.runPostUpdate(serverMap);
        return 1;
    }
}
