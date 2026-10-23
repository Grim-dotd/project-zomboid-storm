package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-side. {@code WorldMapRenderer$Drawer.drawLineString} is called once per line feature of
 * every visible map cell by {@code WorldMapLineStyleLayer}, and the first thing it does is {@code
 * vboLines.flush()}. Nothing after that flush draws: the method fills a scratch float array that no
 * code reads and sets texture/blend state that the next run overrides. The flush therefore only
 * uploads and draws whatever polygon fills and texture quads were pending, once per road, rail or
 * stream segment per frame, each time with two {@code glBufferData} uploads and the attribute and
 * shader restore. This patch redirects that one call to {@link
 * io.pzstorm.storm.advice.worldmap.StormWorldMapLineFlush}, which drops it; the pending runs go out
 * with the next real flush (end of the cell grid, end of the map render, or the buffer-full flush
 * inside {@code reserve}) in the same order under the same per-run state. The map's other flushes
 * and {@code WorldMapVisited}'s are untouched.
 *
 * <p>Fails soft: the class stays vanilla when the woven method does not hold exactly one redirected
 * flush and no direct one.
 *
 * <p>PZ-update re-validation: {@code drawLineString} still drawing nothing after its flush (if a
 * later build gives line features real geometry, this patch must go); all map drawing still going
 * through the one {@code VBORenderer} singleton; blending still enabled for the whole map render.
 */
public class WorldMapLineStringFlushPatch extends StormClassTransformer {

    static final String HELPER = "io.pzstorm.storm.advice.worldmap.StormWorldMapLineFlush";
    static final String HELPER_INTERNAL = HELPER.replace('.', '/');
    static final String VBO_RENDERER = "zombie.core.opengl.VBORenderer";
    static final String VBO_RENDERER_INTERNAL = VBO_RENDERER.replace('.', '/');
    static final String DRAW_LINE_STRING_DESCRIPTOR =
            "(Lzombie/worldMap/styles/WorldMapStyleLayer$RenderArgs;"
                    + "Lzombie/worldMap/WorldMapFeature;"
                    + "Lzombie/worldMap/styles/WorldMapStyleLayer$RGBAf;F)V";

    public WorldMapLineStringFlushPatch() {
        super("zombie.worldMap.WorldMapRenderer$Drawer");
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] woven = super.transform(rawClass);
        try {
            WeaveCheck.require(
                    woven,
                    "drawLineString",
                    DRAW_LINE_STRING_DESCRIPTOR,
                    HELPER_INTERNAL,
                    "flush",
                    1);
            WeaveCheck.require(
                    woven,
                    "drawLineString",
                    DRAW_LINE_STRING_DESCRIPTOR,
                    VBO_RENDERER_INTERNAL,
                    "flush",
                    0);
            WeaveCheck.require(woven, null, null, HELPER_INTERNAL, "flush", 1);
        } catch (IllegalStateException e) {
            LOGGER.error(
                    "WorldMapLineStringFlushPatch: {}; leaving WorldMapRenderer$Drawer vanilla",
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
                                                        ElementMatchers.named(VBO_RENDERER))))
                        .replaceWith(gate)
                        .on(
                                ElementMatchers.named("drawLineString")
                                        .and(ElementMatchers.takesArguments(4))));
    }
}
