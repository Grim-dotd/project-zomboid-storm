package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the depth-write patch weaves the audited shape: the one {@code targetDepth} write inside
 * {@code AnimatedModel.initRenderData} goes to the gate helper, no direct write remains there, and
 * the render-thread writer {@code setTargetDepth} stays vanilla.
 */
class AnimatedModelTargetDepthPatchTest implements UnitTest {

    private static final String MODEL = "zombie/core/skinnedmodel/advancedanimation/AnimatedModel";
    private static final String INSTANCE = AnimatedModelTargetDepthPatch.MODEL_INSTANCE_INTERNAL;
    private static final String HELPER = AnimatedModelTargetDepthPatch.HELPER_INTERNAL;
    private static final String METHOD = AnimatedModelTargetDepthPatch.METHOD;
    private static final String DESCRIPTOR = AnimatedModelTargetDepthPatch.DESCRIPTOR;

    private static byte[] classBytes(String internalName) throws Exception {
        try (InputStream is =
                AnimatedModelTargetDepthPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    @Test
    void mainThreadDepthWriteGoesToTheGateAndRenderThreadWriteStaysVanilla() throws Exception {
        byte[] raw = classBytes(MODEL);
        assertEquals(
                1, WeaveCheck.countFieldWrites(raw, METHOD, DESCRIPTOR, INSTANCE, "targetDepth"));
        int renderWrites =
                WeaveCheck.countFieldWrites(raw, "setTargetDepth", "(F)V", INSTANCE, "targetDepth");
        assertEquals(1, renderWrites);

        byte[] woven = new AnimatedModelTargetDepthPatch().transform(raw);
        assertTrue(woven != raw, "the patch should weave the vanilla class, not refuse it");
        assertEquals(
                0, WeaveCheck.countFieldWrites(woven, METHOD, DESCRIPTOR, INSTANCE, "targetDepth"));
        assertEquals(
                1, WeaveCheck.count(woven, METHOD, DESCRIPTOR, HELPER, "setDefaultTargetDepth"));
        assertEquals(
                1,
                WeaveCheck.countFieldWrites(
                        woven, "setTargetDepth", "(F)V", INSTANCE, "targetDepth"));
        assertEquals(
                1,
                WeaveCheck.count(woven, null, null, HELPER, "setDefaultTargetDepth"),
                "no leak outside initRenderData");
    }

    @Test
    void wovenClassPassesBytecodeVerification() throws Exception {
        byte[] woven = new AnimatedModelTargetDepthPatch().transform(classBytes(MODEL));
        ClassLoader loader =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.equals(AnimatedModelTargetDepthPatch.ANIMATED_MODEL)) {
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
        Class<?> cls = Class.forName(AnimatedModelTargetDepthPatch.ANIMATED_MODEL, false, loader);
        assertTrue(cls.getDeclaredMethods().length > 0);
    }
}
