package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-side. Brackets {@code SymbolsRenderData.renderMain}, the per-frame pass that runs {@code
 * WorldMapBaseSymbol.isVisible} over every symbol of a world map, with a {@link
 * io.pzstorm.storm.worldmap.StormWorldMapSymbolVisibilityMemo} batch. See {@link
 * WorldMapSymbolVisibilityMemoPatch} for the lookups the batch serves.
 *
 * <p>Leaves the class vanilla when the woven method does not hold the batch open and close.
 *
 * <p>PZ-update re-validation: {@code renderMain} still being the one per-frame visibility pass of
 * the main map, with the signature {@code (UIWorldMap, WorldMapSymbols, SymbolsLayoutData,
 * WorldMapStyle)}.
 */
public class WorldMapSymbolsRenderBatchPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.worldmapsymbols.WorldMapSymbolsBatchAdvice";

    public WorldMapSymbolsRenderBatchPatch() {
        super("zombie.worldMap.symbols.SymbolsRenderData");
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] woven = super.transform(rawClass);
        if (!WeaveCheck.bracketed(
                woven, "renderMain", WorldMapSymbolVisibilityMemoPatch.MEMO_INTERNAL)) {
            LOGGER.error(
                    "WorldMapSymbolsRenderBatchPatch: renderMain is not bracketed by the memo"
                            + " batch; leaving SymbolsRenderData vanilla");
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
                                ElementMatchers.named("renderMain")
                                        .and(ElementMatchers.takesArguments(4))));
    }
}
