package io.pzstorm.storm.advice.grounditems;

import net.bytebuddy.asm.Advice;

/**
 * Exit advice on {@code WorldItemAtlas$ItemTexture.render(float x9)}, which has just appended the
 * item's depth drawer to the populating sprite state.
 */
public class GroundItemSubmitAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object itemTexture) {
        StormGroundItemBatch.afterSubmit(itemTexture);
    }
}
