package io.pzstorm.storm.advice.worldmapsymbols;

import io.pzstorm.storm.worldmap.StormWorldMapSymbolVisibilityMemo;
import net.bytebuddy.asm.Advice;

/**
 * Brackets a method that runs {@code WorldMapBaseSymbol.isVisible} over every symbol of a world map
 * ({@code SymbolsRenderData.renderMain}, {@code WorldMapSymbols.hitTest}) with a {@link
 * StormWorldMapSymbolVisibilityMemo} batch.
 */
public class WorldMapSymbolsBatchAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        StormWorldMapSymbolVisibilityMemo.begin();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        StormWorldMapSymbolVisibilityMemo.end();
    }
}
