package io.pzstorm.storm.advice.mannequin;

import net.bytebuddy.asm.Advice;

/**
 * Exit advice on {@code IsoMannequin.syncModel()}. Hands the mannequin's {@code animatedModel}
 * (null before the first render) to {@link StormMannequinModelData#markDirty(Object)}.
 */
public class MannequinSyncModelAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.FieldValue("animatedModel") Object model) {
        StormMannequinModelData.markDirty(model);
    }
}
