package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-side, main-thread half of ground-item flush batching. Adds exit advice to {@code
 * WorldItemAtlas$ItemTexture.render(float x9)}, the method that allocates an {@code
 * ItemTextureDepthDrawer} and appends it to the populating sprite state, so {@link
 * io.pzstorm.storm.advice.grounditems.StormGroundItemBatch} can see whether the item sits right
 * behind another item of the same atlas. The render-thread half is {@link
 * GroundItemBatchFlushPatch}; without it the marks are never consulted and this patch is inert.
 *
 * <p>PZ-update re-validation: {@code ItemTexture.render} still submitting exactly one {@code
 * ItemTextureDepthDrawer} through {@code SpriteRenderer.drawGeneric} on the {@code fboRenderChunk}
 * path, and {@code GenericSpriteRenderState} still exposing {@code sprite[]} and {@code
 * numSprites}.
 */
public class GroundItemBatchSubmitPatch extends StormClassTransformer {

    static final String HELPER = "io.pzstorm.storm.advice.grounditems.StormGroundItemBatch";
    static final String HELPER_INTERNAL = HELPER.replace('.', '/');
    static final String RENDER_DESCRIPTOR = "(FFFFFFFFF)V";

    private static final String ADVICE =
            "io.pzstorm.storm.advice.grounditems.GroundItemSubmitAdvice";

    public GroundItemBatchSubmitPatch() {
        super("zombie.core.skinnedmodel.model.WorldItemAtlas$ItemTexture");
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] woven = super.transform(rawClass);
        try {
            WeaveCheck.require(
                    woven, "render", RENDER_DESCRIPTOR, HELPER_INTERNAL, "afterSubmit", 1);
            WeaveCheck.require(woven, null, null, HELPER_INTERNAL, "afterSubmit", 1);
        } catch (IllegalStateException e) {
            LOGGER.error(
                    "GroundItemBatchSubmitPatch: {}; leaving WorldItemAtlas$ItemTexture vanilla",
                    e.getMessage());
            return rawClass;
        }
        return woven;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("render")
                                        .and(ElementMatchers.takesArguments(9))));
    }
}
