package io.pzstorm.storm.advice.grounditems;

import net.bytebuddy.asm.Advice;

/**
 * Brackets {@code WorldItemAtlas$ItemTextureDepthDrawer.render()} so the substituted flush knows
 * which drawer is rendering.
 */
public class GroundItemRenderAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object drawer) {
        StormGroundItemBatch.beginRender(drawer);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        StormGroundItemBatch.endRender();
    }
}
