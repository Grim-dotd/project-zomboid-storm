package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies both halves of ground-item flush batching weave the audited shapes: the submit hook
 * lands once in {@code ItemTexture.render(float x9)}, and in {@code ItemTextureDepthDrawer} the one
 * {@code VBORenderer.flush()} inside {@code render()} goes to the gate, {@code render()} is
 * bracketed, and {@code postRender()} reports to the gate.
 */
class GroundItemBatchPatchTest implements UnitTest {

    private static final String ITEM_TEXTURE =
            "zombie/core/skinnedmodel/model/WorldItemAtlas$ItemTexture";
    private static final String DEPTH_DRAWER =
            "zombie/core/skinnedmodel/model/WorldItemAtlas$ItemTextureDepthDrawer";
    private static final String HELPER = GroundItemBatchSubmitPatch.HELPER_INTERNAL;
    private static final String VBO = GroundItemBatchFlushPatch.VBO_RENDERER_INTERNAL;
    private static final String RENDER9 = GroundItemBatchSubmitPatch.RENDER_DESCRIPTOR;

    private static byte[] classBytes(String internalName) throws Exception {
        try (InputStream is =
                GroundItemBatchPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    @Test
    void submitHookLandsOnceInItemTextureRender() throws Exception {
        byte[] raw = classBytes(ITEM_TEXTURE);
        assertEquals(0, WeaveCheck.count(raw, null, null, HELPER, "afterSubmit"));

        byte[] woven = new GroundItemBatchSubmitPatch().transform(raw);
        assertTrue(woven != raw, "the patch should weave the vanilla class, not refuse it");
        assertEquals(1, WeaveCheck.count(woven, "render", RENDER9, HELPER, "afterSubmit"));
        assertEquals(1, WeaveCheck.count(woven, null, null, HELPER, "afterSubmit"));
    }

    @Test
    void depthDrawerFlushGoesToTheGateAndHooksLand() throws Exception {
        byte[] raw = classBytes(DEPTH_DRAWER);
        assertEquals(1, WeaveCheck.count(raw, "render", "()V", VBO, "flush"));

        byte[] woven = new GroundItemBatchFlushPatch().transform(raw);
        assertTrue(woven != raw, "the patch should weave the vanilla class, not refuse it");
        assertEquals(1, WeaveCheck.count(woven, "render", "()V", HELPER, "flush"));
        assertEquals(0, WeaveCheck.count(woven, "render", "()V", VBO, "flush"));
        assertEquals(1, WeaveCheck.count(woven, "render", "()V", HELPER, "beginRender"));
        assertEquals(1, WeaveCheck.count(woven, "postRender", "()V", HELPER, "released"));
        assertEquals(1, WeaveCheck.count(woven, null, null, HELPER, "flush"), "no leak");
        assertEquals(1, WeaveCheck.count(woven, null, null, HELPER, "released"), "no leak");
    }

    @Test
    void wovenClassesPassBytecodeVerification() throws Exception {
        byte[] itemTexture = new GroundItemBatchSubmitPatch().transform(classBytes(ITEM_TEXTURE));
        byte[] depthDrawer = new GroundItemBatchFlushPatch().transform(classBytes(DEPTH_DRAWER));
        ClassLoader loader =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        byte[] bytes;
                        if (name.equals(ITEM_TEXTURE.replace('/', '.'))) {
                            bytes = itemTexture;
                        } else if (name.equals(DEPTH_DRAWER.replace('/', '.'))) {
                            bytes = depthDrawer;
                        } else {
                            return super.loadClass(name, resolve);
                        }
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> loaded = findLoadedClass(name);
                            if (loaded != null) {
                                return loaded;
                            }
                            return defineClass(name, bytes, 0, bytes.length);
                        }
                    }
                };
        for (String name : new String[] {ITEM_TEXTURE, DEPTH_DRAWER}) {
            Class<?> cls = Class.forName(name.replace('/', '.'), false, loader);
            assertTrue(cls.getDeclaredMethods().length > 0);
        }
    }
}
