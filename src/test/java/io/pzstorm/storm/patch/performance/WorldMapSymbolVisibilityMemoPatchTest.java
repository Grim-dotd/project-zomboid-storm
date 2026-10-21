package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the three world-map symbol patches weave the audited shape: the six lookups inside
 * {@code WorldMapBaseSymbol.isVisible} go to the memo and nowhere else, and {@code renderMain} and
 * {@code hitTest} each open and close one memo batch.
 */
class WorldMapSymbolVisibilityMemoPatchTest implements UnitTest {

    private static final String MEMO = WorldMapSymbolVisibilityMemoPatch.MEMO_INTERNAL;
    private static final String IS_VISIBLE =
            WorldMapSymbolVisibilityMemoPatch.IS_VISIBLE_DESCRIPTOR;

    private static byte[] classBytes(String internalName) throws Exception {
        try (InputStream is =
                WorldMapSymbolVisibilityMemoPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    @Test
    void isVisibleLookupsGoToTheMemoAndOnlyThere() throws Exception {
        byte[] raw = classBytes("zombie/worldMap/symbols/WorldMapBaseSymbol");
        assertEquals(
                3,
                WeaveCheck.count(
                        raw,
                        "isVisible",
                        IS_VISIBLE,
                        "zombie/characters/Faction",
                        "getPlayerFaction"));
        assertEquals(
                3,
                WeaveCheck.count(
                        raw,
                        "isVisible",
                        IS_VISIBLE,
                        "zombie/iso/areas/SafeHouse",
                        "hasSafehouse"));

        byte[] woven = new WorldMapSymbolVisibilityMemoPatch().transform(raw);
        assertTrue(woven != raw, "the patch should weave the vanilla class, not refuse it");
        assertEquals(3, WeaveCheck.count(woven, "isVisible", IS_VISIBLE, MEMO, "getPlayerFaction"));
        assertEquals(3, WeaveCheck.count(woven, "isVisible", IS_VISIBLE, MEMO, "hasSafehouse"));
        assertEquals(
                0,
                WeaveCheck.count(
                        woven,
                        "isVisible",
                        IS_VISIBLE,
                        "zombie/characters/Faction",
                        "getPlayerFaction"));
        assertEquals(
                0,
                WeaveCheck.count(
                        woven,
                        "isVisible",
                        IS_VISIBLE,
                        "zombie/iso/areas/SafeHouse",
                        "hasSafehouse"));
        assertEquals(
                3,
                WeaveCheck.count(woven, null, null, MEMO, "getPlayerFaction"),
                "no leak outside isVisible");
        assertEquals(
                3,
                WeaveCheck.count(woven, null, null, MEMO, "hasSafehouse"),
                "no leak outside isVisible");
    }

    @Test
    void renderMainIsBracketedByOneBatch() throws Exception {
        byte[] raw = classBytes("zombie/worldMap/symbols/SymbolsRenderData");
        byte[] woven = new WorldMapSymbolsRenderBatchPatch().transform(raw);
        assertTrue(woven != raw, "the patch should weave the vanilla class, not refuse it");
        assertTrue(WeaveCheck.bracketed(woven, "renderMain", MEMO));
        assertEquals(0, WeaveCheck.count(woven, "render", null, MEMO, "begin"));
    }

    @Test
    void hitTestIsBracketedByOneBatch() throws Exception {
        byte[] raw = classBytes("zombie/worldMap/symbols/WorldMapSymbols");
        byte[] woven = new WorldMapSymbolsHitTestBatchPatch().transform(raw);
        assertTrue(woven != raw, "the patch should weave the vanilla class, not refuse it");
        assertTrue(WeaveCheck.bracketed(woven, "hitTest", MEMO));
        assertEquals(0, WeaveCheck.count(woven, "isSymbolVisible", null, MEMO, "begin"));
    }

    @Test
    void wovenClassesPassBytecodeVerification() throws Exception {
        Map<String, byte[]> woven = new HashMap<>();
        woven.put(
                "zombie.worldMap.symbols.WorldMapBaseSymbol",
                new WorldMapSymbolVisibilityMemoPatch()
                        .transform(classBytes("zombie/worldMap/symbols/WorldMapBaseSymbol")));
        woven.put(
                "zombie.worldMap.symbols.SymbolsRenderData",
                new WorldMapSymbolsRenderBatchPatch()
                        .transform(classBytes("zombie/worldMap/symbols/SymbolsRenderData")));
        woven.put(
                "zombie.worldMap.symbols.WorldMapSymbols",
                new WorldMapSymbolsHitTestBatchPatch()
                        .transform(classBytes("zombie/worldMap/symbols/WorldMapSymbols")));

        ClassLoader loader =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.startsWith("zombie.worldMap.symbols.")) {
                            return super.loadClass(name, resolve);
                        }
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> loaded = findLoadedClass(name);
                            if (loaded != null) {
                                return loaded;
                            }
                            byte[] bytes = woven.get(name);
                            if (bytes == null) {
                                try {
                                    bytes = classBytes(name.replace('.', '/'));
                                } catch (Exception e) {
                                    throw new ClassNotFoundException(name, e);
                                }
                            }
                            return defineClass(name, bytes, 0, bytes.length);
                        }
                    }
                };
        for (String name : woven.keySet()) {
            Class<?> cls = Class.forName(name, false, loader);
            assertTrue(cls.getDeclaredMethods().length > 0, name);
        }
    }
}
