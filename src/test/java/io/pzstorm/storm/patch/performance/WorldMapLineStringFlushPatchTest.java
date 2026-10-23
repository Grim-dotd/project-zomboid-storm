package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the world-map line patch weaves the audited shape: the one {@code VBORenderer.flush()}
 * inside {@code WorldMapRenderer$Drawer.drawLineString} goes to the gate and nowhere else, and the
 * other flushes in the class stay vanilla.
 */
class WorldMapLineStringFlushPatchTest implements UnitTest {

    private static final String DRAWER = "zombie/worldMap/WorldMapRenderer$Drawer";
    private static final String HELPER = WorldMapLineStringFlushPatch.HELPER_INTERNAL;
    private static final String VBO = WorldMapLineStringFlushPatch.VBO_RENDERER_INTERNAL;
    private static final String DESCRIPTOR =
            WorldMapLineStringFlushPatch.DRAW_LINE_STRING_DESCRIPTOR;

    private static byte[] classBytes(String internalName) throws Exception {
        try (InputStream is =
                WorldMapLineStringFlushPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    @Test
    void lineStringFlushGoesToTheGateAndOtherFlushesStayVanilla() throws Exception {
        byte[] raw = classBytes(DRAWER);
        assertEquals(1, WeaveCheck.count(raw, "drawLineString", DESCRIPTOR, VBO, "flush"));
        int vanillaFlushes = WeaveCheck.count(raw, null, null, VBO, "flush");
        assertTrue(vanillaFlushes > 1, "the class flushes elsewhere too");

        byte[] woven = new WorldMapLineStringFlushPatch().transform(raw);
        assertTrue(woven != raw, "the patch should weave the vanilla class, not refuse it");
        assertEquals(1, WeaveCheck.count(woven, "drawLineString", DESCRIPTOR, HELPER, "flush"));
        assertEquals(0, WeaveCheck.count(woven, "drawLineString", DESCRIPTOR, VBO, "flush"));
        assertEquals(1, WeaveCheck.count(woven, null, null, HELPER, "flush"), "no leak");
        assertEquals(vanillaFlushes - 1, WeaveCheck.count(woven, null, null, VBO, "flush"));
    }

    @Test
    void wovenClassPassesBytecodeVerification() throws Exception {
        byte[] woven = new WorldMapLineStringFlushPatch().transform(classBytes(DRAWER));
        ClassLoader loader =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.equals(DRAWER.replace('/', '.'))) {
                            return super.loadClass(name, resolve);
                        }
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> loaded = findLoadedClass(name);
                            if (loaded != null) {
                                return loaded;
                            }
                            return defineClass(name, woven, 0, woven.length);
                        }
                    }
                };
        Class<?> cls = Class.forName(DRAWER.replace('/', '.'), false, loader);
        assertTrue(cls.getDeclaredMethods().length > 0);
    }
}
