package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the mannequin patch weaves the audited shape: the one {@code setModelData} call inside
 * {@code IsoMannequin.render} goes to the gate and nowhere else, the call inside {@code syncModel}
 * stays vanilla, and {@code syncModel} reports to the gate on exit.
 */
class IsoMannequinModelDataPatchTest implements UnitTest {

    private static final String MANNEQUIN = "zombie/iso/objects/IsoMannequin";
    private static final String HELPER = IsoMannequinModelDataPatch.HELPER_INTERNAL;
    private static final String MODEL = IsoMannequinModelDataPatch.ANIMATED_MODEL_INTERNAL;
    private static final String RENDER = IsoMannequinModelDataPatch.RENDER_DESCRIPTOR;

    private static byte[] classBytes(String internalName) throws Exception {
        try (InputStream is =
                IsoMannequinModelDataPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    @Test
    void renderCallGoesToTheGateAndSyncModelStaysVanilla() throws Exception {
        byte[] raw = classBytes(MANNEQUIN);
        assertEquals(1, WeaveCheck.count(raw, "render", RENDER, MODEL, "setModelData"));
        assertEquals(1, WeaveCheck.count(raw, "syncModel", "()V", MODEL, "setModelData"));

        byte[] woven = new IsoMannequinModelDataPatch().transform(raw);
        assertTrue(woven != raw, "the patch should weave the vanilla class, not refuse it");
        assertEquals(1, WeaveCheck.count(woven, "render", RENDER, HELPER, "setModelData"));
        assertEquals(0, WeaveCheck.count(woven, "render", RENDER, MODEL, "setModelData"));
        assertEquals(1, WeaveCheck.count(woven, "syncModel", "()V", MODEL, "setModelData"));
        assertEquals(1, WeaveCheck.count(woven, "syncModel", "()V", HELPER, "markDirty"));
        assertEquals(
                1,
                WeaveCheck.count(woven, null, null, HELPER, "setModelData"),
                "no leak outside render");
        assertEquals(
                1,
                WeaveCheck.count(woven, null, null, HELPER, "markDirty"),
                "no leak outside syncModel");
    }

    @Test
    void wovenClassPassesBytecodeVerification() throws Exception {
        byte[] woven = new IsoMannequinModelDataPatch().transform(classBytes(MANNEQUIN));
        ClassLoader loader =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.equals("zombie.iso.objects.IsoMannequin")) {
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
        Class<?> cls = Class.forName("zombie.iso.objects.IsoMannequin", false, loader);
        assertTrue(cls.getDeclaredMethods().length > 0);
    }
}
