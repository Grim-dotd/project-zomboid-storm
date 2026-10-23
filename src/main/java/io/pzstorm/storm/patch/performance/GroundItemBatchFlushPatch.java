package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-side, render-thread half of ground-item flush batching. In {@code
 * WorldItemAtlas$ItemTextureDepthDrawer}:
 *
 * <ul>
 *   <li>the one {@code VBORenderer.flush()} call inside {@code render()} goes to {@link
 *       io.pzstorm.storm.advice.grounditems.StormGroundItemBatch#flush}, which skips it when the
 *       main thread marked this drawer as continued by a same-atlas item;
 *   <li>{@code render()} is bracketed so the substitute knows which drawer is rendering;
 *   <li>{@code postRender()} drops any mark the drawer still carries before it returns to the pool.
 * </ul>
 *
 * <p>Fails soft at two levels: the class stays vanilla when the woven shape is not exactly one
 * redirected flush and no direct one, and at run time the gate latches itself back to a flush per
 * item on the first throwable.
 *
 * <p>PZ-update re-validation: {@code render()} still binding the atlas depth texture on unit 1,
 * setting depth mask/func and blend func, recording one run with per-run program and uniforms, and
 * calling {@code VBORenderer.flush()} exactly once; {@code VBORenderer.flush} still replaying its
 * command list in run order; {@code addQuad} still flushing on its own when the buffer is full.
 */
public class GroundItemBatchFlushPatch extends StormClassTransformer {

    static final String HELPER = GroundItemBatchSubmitPatch.HELPER;
    static final String HELPER_INTERNAL = GroundItemBatchSubmitPatch.HELPER_INTERNAL;
    static final String VBO_RENDERER = "zombie.core.opengl.VBORenderer";
    static final String VBO_RENDERER_INTERNAL = VBO_RENDERER.replace('.', '/');

    private static final String RENDER_ADVICE =
            "io.pzstorm.storm.advice.grounditems.GroundItemRenderAdvice";
    private static final String POST_RENDER_ADVICE =
            "io.pzstorm.storm.advice.grounditems.GroundItemPostRenderAdvice";

    public GroundItemBatchFlushPatch() {
        super("zombie.core.skinnedmodel.model.WorldItemAtlas$ItemTextureDepthDrawer");
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] woven = super.transform(rawClass);
        try {
            WeaveCheck.require(woven, "render", "()V", HELPER_INTERNAL, "flush", 1);
            WeaveCheck.require(woven, "render", "()V", VBO_RENDERER_INTERNAL, "flush", 0);
            WeaveCheck.require(woven, "render", "()V", HELPER_INTERNAL, "beginRender", 1);
            WeaveCheck.require(woven, "postRender", "()V", HELPER_INTERNAL, "released", 1);
            WeaveCheck.require(woven, null, null, HELPER_INTERNAL, "flush", 1);
        } catch (IllegalStateException e) {
            LOGGER.error(
                    "GroundItemBatchFlushPatch: {}; leaving WorldItemAtlas$ItemTextureDepthDrawer"
                            + " vanilla",
                    e.getMessage());
            return rawClass;
        }
        return woven;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription helper = typePool.describe(HELPER).resolve();
        MethodDescription gate =
                helper.getDeclaredMethods()
                        .filter(
                                ElementMatchers.named("flush")
                                        .and(ElementMatchers.takesArguments(1)))
                        .getOnly();
        return builder.visit(
                        MemberSubstitution.relaxed()
                                .method(
                                        ElementMatchers.named("flush")
                                                .and(ElementMatchers.takesArguments(0))
                                                .and(
                                                        ElementMatchers.isDeclaredBy(
                                                                ElementMatchers.named(
                                                                        VBO_RENDERER))))
                                .replaceWith(gate)
                                .on(
                                        ElementMatchers.named("render")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(typePool.describe(RENDER_ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("render")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(typePool.describe(POST_RENDER_ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("postRender")
                                                .and(ElementMatchers.takesArguments(0))));
    }
}
