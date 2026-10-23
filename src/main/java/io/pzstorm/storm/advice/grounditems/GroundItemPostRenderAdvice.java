package io.pzstorm.storm.advice.grounditems;

import net.bytebuddy.asm.Advice;

/**
 * Exit advice on {@code WorldItemAtlas$ItemTextureDepthDrawer.postRender()}, the point where the
 * drawer goes back to its pool.
 */
public class GroundItemPostRenderAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object drawer) {
        StormGroundItemBatch.released(drawer);
    }
}
