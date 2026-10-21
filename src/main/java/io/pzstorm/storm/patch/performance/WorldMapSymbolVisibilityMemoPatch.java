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
 * Client-side. Redirects the {@code Faction.getPlayerFaction(String)} and {@code
 * SafeHouse.hasSafehouse(String)} call sites inside {@code
 * WorldMapBaseSymbol.isVisible(UIWorldMap)} to {@link
 * io.pzstorm.storm.worldmap.StormWorldMapSymbolVisibilityMemo}, which answers them from a
 * per-render-batch username index. The vanilla method body and control flow are unchanged; only the
 * two lookups are swapped, and outside a batch they run vanilla. {@link
 * WorldMapSymbolsRenderBatchPatch} and {@link WorldMapSymbolsHitTestBatchPatch} open the batches.
 *
 * <p>Fails soft at two levels. {@link #transform} leaves the class vanilla when the woven method
 * does not hold exactly the audited three calls of each lookup, and at run time the memo latches
 * itself back to the vanilla scans on the first throwable.
 *
 * <p>PZ-update re-validation: {@code isVisible(UIWorldMap)} still calling {@code
 * Faction.getPlayerFaction(String)} three times and {@code SafeHouse.hasSafehouse(String)} three
 * times, and both vanilla scans still returning the first list-order match on owner or member.
 */
public class WorldMapSymbolVisibilityMemoPatch extends StormClassTransformer {

    static final String MEMO = "io.pzstorm.storm.worldmap.StormWorldMapSymbolVisibilityMemo";
    static final String MEMO_INTERNAL = MEMO.replace('.', '/');
    static final String IS_VISIBLE_DESCRIPTOR = "(Lzombie/worldMap/UIWorldMap;)Z";

    private static final String FACTION = "zombie.characters.Faction";
    private static final String SAFEHOUSE = "zombie.iso.areas.SafeHouse";

    public WorldMapSymbolVisibilityMemoPatch() {
        super("zombie.worldMap.symbols.WorldMapBaseSymbol");
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] woven = super.transform(rawClass);
        String method = "isVisible";
        String descriptor = IS_VISIBLE_DESCRIPTOR;
        try {
            WeaveCheck.require(woven, method, descriptor, MEMO_INTERNAL, "getPlayerFaction", 3);
            WeaveCheck.require(woven, method, descriptor, MEMO_INTERNAL, "hasSafehouse", 3);
            WeaveCheck.require(
                    woven, method, descriptor, FACTION.replace('.', '/'), "getPlayerFaction", 0);
            WeaveCheck.require(
                    woven, method, descriptor, SAFEHOUSE.replace('.', '/'), "hasSafehouse", 0);
        } catch (IllegalStateException e) {
            LOGGER.error(
                    "WorldMapSymbolVisibilityMemoPatch: {}; leaving WorldMapBaseSymbol vanilla",
                    e.getMessage());
            return rawClass;
        }
        return woven;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription memo = typePool.describe(MEMO).resolve();
        MethodDescription faction = lookup(memo, "getPlayerFaction");
        MethodDescription safehouse = lookup(memo, "hasSafehouse");
        return builder.visit(
                MemberSubstitution.relaxed()
                        .method(
                                ElementMatchers.named("getPlayerFaction")
                                        .and(ElementMatchers.takesArguments(String.class))
                                        .and(
                                                ElementMatchers.isDeclaredBy(
                                                        ElementMatchers.named(FACTION))))
                        .replaceWith(faction)
                        .method(
                                ElementMatchers.named("hasSafehouse")
                                        .and(ElementMatchers.takesArguments(String.class))
                                        .and(
                                                ElementMatchers.isDeclaredBy(
                                                        ElementMatchers.named(SAFEHOUSE))))
                        .replaceWith(safehouse)
                        .on(
                                ElementMatchers.named("isVisible")
                                        .and(ElementMatchers.takesArguments(1))));
    }

    private static MethodDescription lookup(TypeDescription memo, String name) {
        return memo.getDeclaredMethods()
                .filter(
                        ElementMatchers.named(name)
                                .and(ElementMatchers.takesArguments(String.class)))
                .getOnly();
    }
}
