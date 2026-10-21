package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}.
 *
 * <p>Vanilla {@code FBORenderCutaways.doCutawayVisitSquares(int, ArrayList)} marks every chunk that
 * held a cutaway square before or after the call as dirty, so its texture is re-baked even when no
 * flag moved. This patch redirects the flag writes and the {@code invalidateLevel} calls of that
 * method to {@link io.pzstorm.storm.advice.cutawayinvalidate.CutawayInvalidationFilter}, which
 * keeps the writes as they are and issues an invalidation only where a flag changed or where state
 * the bake reads may have moved. {@link
 * io.pzstorm.storm.advice.cutawayinvalidate.CutawayChangeTracker} holds the rules and the parity
 * argument.
 *
 * <p>The weave is call-site substitution plus enter/exit advice, so the vanilla control flow, the
 * per-chunk dedupe set, the occluded-mask reset and the edge-boolean loop all run unchanged. The
 * other hooks tell the filter when it has to fall back to the vanilla blanket. The range
 * transitions in {@code checkExteriorWalls}, {@code checkSlopedSurfaces} and {@code
 * checkOrphanStructures} bump an epoch, {@code squareChanged} reports could-see flips, and {@code
 * checkPlayerRoom} samples the player's room and level each frame.
 *
 * <p>The patch fails soft at two levels. {@link #transform} refuses the weave when the call-site
 * counts differ from the audited vanilla shape, which leaves the class vanilla. At run time the
 * filter latches itself into pass-through on the first {@link Throwable}.
 */
public class CutawayChangedInvalidationPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.cutawayinvalidate.";
    private static final String VISIT_DESCRIPTOR = "(ILjava/util/ArrayList;)V";

    public CutawayChangedInvalidationPatch() {
        super("zombie.iso.fboRenderChunk.FBORenderCutaways");
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] woven = super.transform(rawClass);
        String visit = "doCutawayVisitSquares";
        String filter = WeaveCheck.FILTER;
        String square = WeaveCheck.SQUARE;
        String levels = WeaveCheck.RENDER_LEVELS;
        WeaveCheck.require(woven, visit, VISIT_DESCRIPTOR, filter, "begin", 1);
        WeaveCheck.require(woven, visit, VISIT_DESCRIPTOR, filter, "end", 1);
        WeaveCheck.require(woven, visit, VISIT_DESCRIPTOR, filter, "setFlag", 1);
        WeaveCheck.require(woven, visit, VISIT_DESCRIPTOR, filter, "addFlag", 2);
        WeaveCheck.require(woven, visit, VISIT_DESCRIPTOR, filter, "ownInvalidate", 3);
        WeaveCheck.require(woven, "invalidateChunk", null, filter, "edgeInvalidate", 1);
        WeaveCheck.require(woven, "checkExteriorWalls", null, filter, "externalInvalidate", 1);
        WeaveCheck.require(woven, "checkSlopedSurfaces", null, filter, "externalInvalidate", 1);
        WeaveCheck.require(woven, "checkOrphanStructures", null, filter, "externalInvalidate", 4);
        WeaveCheck.require(woven, "squareChanged", null, filter, "onSquareChanged", 1);
        WeaveCheck.require(woven, "checkPlayerRoom", null, filter, "onViewpointCheck", 1);

        // Any site left over is a writer or an invalidation the audit has not seen.
        WeaveCheck.require(woven, null, null, square, "setPlayerCutawayFlag", 0);
        WeaveCheck.require(woven, null, null, square, "addPlayerCutawayFlag", 0);
        WeaveCheck.require(woven, null, null, square, "clearPlayerCutawayFlag", 0);
        WeaveCheck.require(woven, "checkOccludedRooms", null, levels, "invalidateLevel", 1);
        WeaveCheck.require(woven, null, null, levels, "invalidateLevel", 1);
        return woven;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription filter = typePool.describe(PKG + "CutawayInvalidationFilter").resolve();

        ElementMatcher.Junction<MethodDescription> visitSquares =
                ElementMatchers.named("doCutawayVisitSquares")
                        .and(ElementMatchers.takesArguments(int.class, ArrayList.class));
        ElementMatcher.Junction<MethodDescription> invalidateLevel =
                ElementMatchers.named("invalidateLevel")
                        .and(ElementMatchers.takesArguments(int.class, long.class))
                        .and(
                                ElementMatchers.isDeclaredBy(
                                        ElementMatchers.named(
                                                "zombie.iso.fboRenderChunk.FBORenderLevels")));

        return builder.visit(
                        MemberSubstitution.relaxed()
                                .method(squareWrite("setPlayerCutawayFlag"))
                                .replaceWith(hook(filter, "setFlag"))
                                .method(squareWrite("addPlayerCutawayFlag"))
                                .replaceWith(hook(filter, "addFlag"))
                                .method(invalidateLevel)
                                .replaceWith(hook(filter, "ownInvalidate"))
                                .on(visitSquares))
                .visit(
                        MemberSubstitution.relaxed()
                                .method(invalidateLevel)
                                .replaceWith(hook(filter, "edgeInvalidate"))
                                .on(
                                        ElementMatchers.named("invalidateChunk")
                                                .and(ElementMatchers.takesArguments(5))))
                .visit(
                        MemberSubstitution.relaxed()
                                .method(invalidateLevel)
                                .replaceWith(hook(filter, "externalInvalidate"))
                                .on(
                                        ElementMatchers.named("checkExteriorWalls")
                                                .or(ElementMatchers.named("checkSlopedSurfaces"))
                                                .or(
                                                        ElementMatchers.named(
                                                                "checkOrphanStructures"))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "CutawayVisitSquaresAdvice")
                                                .resolve(),
                                        locator)
                                .on(visitSquares))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "CutawaySquareChangedAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("squareChanged")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "CutawayViewpointAdvice").resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("checkPlayerRoom")
                                                .and(ElementMatchers.takesArguments(int.class))));
    }

    private static ElementMatcher.Junction<MethodDescription> squareWrite(String name) {
        return ElementMatchers.named(name)
                .and(ElementMatchers.takesArguments(int.class, int.class, long.class))
                .and(
                        ElementMatchers.isDeclaredBy(
                                ElementMatchers.named("zombie.iso.IsoGridSquare")));
    }

    static MethodDescription hook(TypeDescription filter, String name) {
        return filter.getDeclaredMethods().filter(ElementMatchers.named(name)).getOnly();
    }
}
