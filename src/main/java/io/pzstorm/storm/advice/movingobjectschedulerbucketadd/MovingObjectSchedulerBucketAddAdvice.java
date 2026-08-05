package io.pzstorm.storm.advice.movingobjectschedulerbucketadd;

import io.pzstorm.storm.patch.performance.StormCellWarmer;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import net.bytebuddy.asm.Advice;

/**
 * Skips the {@code MovingObjectUpdateSchedulerUpdateBucket.add(IsoMovingObject)} body when the
 * argument is an animal sitting inside a warmed cell. Returning {@code true} suppresses the body;
 * returning {@code false} runs vanilla. The downstream {@code update} / {@code postupdate} loops
 * iterate the buckets, so a warm animal that's never bucketed is fully excluded from per-tick
 * {@code IsoAnimal.update()} work.
 *
 * <p>The fast-path gate is {@code isEnabled() || warmCellCount() > 0} (a count mirrored into the
 * config class so this gate never forces {@code StormCellWarmer} to class-load on servers that
 * never warm), not {@code isEnabled()} alone: {@code Storm.CellWarming} is live, and after a
 * disable the stashed animals of still-warm cells must stay unbucketed until their cell drains or
 * rewarms — bucketing one would tick it in its drained state ({@code unloaded()}, no moving
 * square). Once the warm set empties the gate is two cheap reads and vanilla runs unconditionally.
 */
public class MovingObjectSchedulerBucketAddAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(0) Object obj) {
        if (!StormCellWarmingConfig.isEnabled() && StormCellWarmingConfig.warmCellCount() == 0) {
            return false;
        }
        return StormCellWarmer.isWarmedAnimal(obj);
    }
}
