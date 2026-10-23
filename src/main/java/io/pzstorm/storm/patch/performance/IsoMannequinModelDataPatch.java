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
 * Client-side. The per-frame branch of {@code IsoMannequin.render} (a mannequin outside the chunk
 * FBO cache, drawn through the translucent pass) calls {@code AnimatedModel.setModelData} on every
 * frame, which rebuilds the model instance and re-runs the texture pipeline each time. This patch
 * redirects that one call site to {@link
 * io.pzstorm.storm.advice.mannequin.StormMannequinModelData}, which runs it on first sight and
 * after each {@code syncModel()}, and adds exit advice to {@code syncModel()} so the gate learns
 * when the visuals changed. The {@code animate} branch and the call inside {@code syncModel} itself
 * are untouched.
 *
 * <p>Fails soft at two levels. {@link #transform} leaves the class vanilla when the woven {@code
 * render} does not hold exactly one redirected call and no remaining direct call, or when {@code
 * syncModel} did not take the advice; at run time the gate latches itself back to the vanilla call
 * on the first throwable.
 *
 * <p>PZ-update re-validation: {@code render(float, float, float, ColorInfo, boolean, boolean,
 * Shader)} still calling {@code setModelData(BaseVisual, ItemVisuals)} exactly once; {@code
 * syncModel()} still being the only place that rewrites {@code humanVisual}, {@code itemVisuals},
 * pose or sex; and {@code AnimatedModel.setModelData} still copying the visual instead of comparing
 * references.
 */
public class IsoMannequinModelDataPatch extends StormClassTransformer {

    static final String HELPER = "io.pzstorm.storm.advice.mannequin.StormMannequinModelData";
    static final String HELPER_INTERNAL = HELPER.replace('.', '/');
    static final String ANIMATED_MODEL = "zombie.core.skinnedmodel.advancedanimation.AnimatedModel";
    static final String ANIMATED_MODEL_INTERNAL = ANIMATED_MODEL.replace('.', '/');
    static final String RENDER_DESCRIPTOR =
            "(FFFLzombie/core/textures/ColorInfo;ZZLzombie/core/opengl/Shader;)V";

    private static final String ADVICE =
            "io.pzstorm.storm.advice.mannequin.MannequinSyncModelAdvice";

    public IsoMannequinModelDataPatch() {
        super("zombie.iso.objects.IsoMannequin");
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] woven = super.transform(rawClass);
        try {
            WeaveCheck.require(
                    woven, "render", RENDER_DESCRIPTOR, HELPER_INTERNAL, "setModelData", 1);
            WeaveCheck.require(
                    woven, "render", RENDER_DESCRIPTOR, ANIMATED_MODEL_INTERNAL, "setModelData", 0);
            WeaveCheck.require(woven, "syncModel", "()V", HELPER_INTERNAL, "markDirty", 1);
        } catch (IllegalStateException e) {
            LOGGER.error(
                    "IsoMannequinModelDataPatch: {}; leaving IsoMannequin vanilla", e.getMessage());
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
                                ElementMatchers.named("setModelData")
                                        .and(ElementMatchers.takesArguments(3)))
                        .getOnly();
        return builder.visit(
                        MemberSubstitution.relaxed()
                                .method(
                                        ElementMatchers.named("setModelData")
                                                .and(ElementMatchers.takesArguments(2))
                                                .and(
                                                        ElementMatchers.isDeclaredBy(
                                                                ElementMatchers.named(
                                                                        ANIMATED_MODEL))))
                                .replaceWith(gate)
                                .on(
                                        ElementMatchers.named("render")
                                                .and(ElementMatchers.takesArguments(7))))
                .visit(
                        Advice.to(typePool.describe(ADVICE).resolve(), locator)
                                .on(
                                        ElementMatchers.named("syncModel")
                                                .and(ElementMatchers.takesArguments(0))));
    }
}
