package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. Companion to
 * {@link CutawayChangedInvalidationPatch}.
 *
 * <p>{@code ChunkLevelsData.clearPlayerCutawayFlags} and {@code clearPlayerCutawayFlags2} clear
 * square flags when a chunk level is rebuilt and invalidate only that chunk. Walls in the
 * neighbouring chunks bake from those flags too, and vanilla refreshes them through the next
 * blanket pass. This patch routes the two invalidations through {@code
 * CutawayInvalidationFilter.externalInvalidate}, which makes the next pass a blanket one.
 *
 * <p>The static marker field tells the filter that this weave is in place. The filter stays in
 * pass-through without it, and {@link #transform} refuses a weave that did not redirect both sites.
 */
public class CutawayFlagClearTagPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.cutawayinvalidate.";
    private static final String TAG_FIELD = "storm$cutawayWriteTagged";

    public CutawayFlagClearTagPatch() {
        super("zombie.iso.fboRenderChunk.FBORenderCutaways$ChunkLevelsData");
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] woven = super.transform(rawClass);
        String filter = WeaveCheck.FILTER;
        String levels = WeaveCheck.RENDER_LEVELS;
        WeaveCheck.require(woven, "clearPlayerCutawayFlags", null, filter, "externalInvalidate", 1);
        WeaveCheck.require(
                woven, "clearPlayerCutawayFlags2", null, filter, "externalInvalidate", 1);
        WeaveCheck.require(woven, null, null, levels, "invalidateLevel", 0);
        return woven;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription filter = typePool.describe(PKG + "CutawayInvalidationFilter").resolve();
        MethodDescription hook = CutawayChangedInvalidationPatch.hook(filter, "externalInvalidate");
        return builder.defineField(TAG_FIELD, boolean.class, Visibility.PUBLIC, Ownership.STATIC)
                .visit(
                        MemberSubstitution.relaxed()
                                .method(
                                        ElementMatchers.named("invalidateLevel")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                int.class, long.class))
                                                .and(
                                                        ElementMatchers.isDeclaredBy(
                                                                ElementMatchers.named(
                                                                        "zombie.iso.fboRenderChunk.FBORenderLevels"))))
                                .replaceWith(hook)
                                .on(
                                        ElementMatchers.named("clearPlayerCutawayFlags")
                                                .or(
                                                        ElementMatchers.named(
                                                                "clearPlayerCutawayFlags2"))));
    }
}
