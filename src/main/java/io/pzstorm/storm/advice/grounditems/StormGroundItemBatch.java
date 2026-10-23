package io.pzstorm.storm.advice.grounditems;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.model.WorldItemAtlas;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * Client-side batching of the per-ground-item {@code VBORenderer.flush()} inside {@code
 * WorldItemAtlas$ItemTextureDepthDrawer.render()}.
 *
 * <p>Every ground item drawn through the chunk-FBO path is one {@code GenericDrawer} that binds the
 * item atlas' depth texture, records one quad as a {@code VBORenderer} run with its own shader
 * uniforms, and then flushes: two {@code glBufferData} uploads, a draw, the attribute re-enable and
 * the GL state restore, once per item. Uniforms and the diffuse texture are per-run commands and
 * replay in order inside one flush; only the unit-1 depth texture, the depth mask/func and the
 * blend func are immediate state, and those are identical for every item of one atlas and are
 * re-applied by each drawer before its own flush.
 *
 * <p>So when the main thread submits an item drawer right behind another item drawer of the same
 * atlas, with nothing appended to the sprite state in between, the earlier drawer's flush is
 * redundant and this class marks it as such ({@link #afterSubmit}). On the render thread {@link
 * #flush} consults the mark and skips the flush; the next drawer's flush, or the buffer-full flush
 * inside {@code addQuad}, draws the accumulated runs under state that is the same as what the
 * skipped flush would have used. A drawer that is not followed by a same-atlas item flushes as
 * vanilla, so no run is ever left pending for a foreign drawer (vanilla or the ATF shaders mod) to
 * emit under its own state.
 *
 * <p>Marks are keyed by drawer identity and consumed when the drawer renders; {@link #released}
 * drops any mark that survives to {@code postRender}, so a mark can never follow a pooled drawer
 * into a later frame. Always on. Any throwable in the bookkeeping latches the gate off and every
 * drawer flushes as vanilla. {@link #setEnabled(boolean)} and {@link #stats()} are reachable
 * through Java eval.
 */
public final class StormGroundItemBatch {

    private static final String DEPTH_DRAWER =
            "zombie.core.skinnedmodel.model.WorldItemAtlas$ItemTextureDepthDrawer";

    private static final Map<Object, Boolean> CONTINUED = new ConcurrentHashMap<>();

    private static Class<?> depthDrawerClass;

    private static GenericSpriteRenderState lastState;
    private static int lastIndex = -1;
    private static Object lastDrawer;
    private static Object lastAtlas;

    private static Object rendering;

    private static volatile boolean disabled;
    private static volatile boolean enabled = true;

    private static long submitted;
    private static long marked;
    private static long skipped;
    private static long flushed;
    private static long dropped;

    private StormGroundItemBatch() {}

    /** Exit hook of {@code WorldItemAtlas$ItemTexture.render(...)} on the main thread. */
    public static void afterSubmit(Object itemTexture) {
        if (disabled || !enabled) {
            return;
        }
        try {
            GenericSpriteRenderState state =
                    SpriteRenderer.instance.states.getPopulatingActiveState();
            int index = state.numSprites - 1;
            if (index < 0) {
                forget();
                return;
            }
            TextureDraw draw = state.sprite[index];
            Object drawer = draw.drawer;
            if (draw.type != TextureDraw.Type.DrawModel
                    || drawer == null
                    || drawer.getClass() != depthDrawerClass()) {
                forget();
                return;
            }
            Texture tex = ((WorldItemAtlas.ItemTexture) itemTexture).getTexture();
            Object atlas = tex == null ? null : tex.getTextureId();
            if (atlas == null) {
                forget();
                return;
            }
            submitted++;
            if (state == lastState
                    && index == lastIndex + 1
                    && atlas == lastAtlas
                    && state.sprite[lastIndex].drawer == lastDrawer
                    && state.sprite[lastIndex].type == TextureDraw.Type.DrawModel) {
                CONTINUED.put(lastDrawer, Boolean.TRUE);
                marked++;
            }
            lastState = state;
            lastIndex = index;
            lastDrawer = drawer;
            lastAtlas = atlas;
        } catch (Throwable t) {
            latchOff(t);
        }
    }

    /** Enter hook of {@code ItemTextureDepthDrawer.render()} on the render thread. */
    public static void beginRender(Object drawer) {
        rendering = drawer;
    }

    /** Exit hook of {@code ItemTextureDepthDrawer.render()} on the render thread. */
    public static void endRender() {
        rendering = null;
    }

    /** Substitute for the {@code VBORenderer.flush()} call inside {@code render()}. */
    public static void flush(VBORenderer vbo) {
        Object drawer = rendering;
        if (drawer != null && !disabled) {
            Boolean mark;
            try {
                mark = CONTINUED.remove(drawer);
            } catch (Throwable t) {
                latchOff(t);
                mark = null;
            }
            if (mark != null && enabled) {
                skipped++;
                return;
            }
        }
        flushed++;
        vbo.flush();
    }

    /** Exit hook of {@code ItemTextureDepthDrawer.postRender()}. */
    public static void released(Object drawer) {
        if (drawer == null || CONTINUED.isEmpty()) {
            return;
        }
        try {
            if (CONTINUED.remove(drawer) != null) {
                dropped++;
            }
        } catch (Throwable t) {
            latchOff(t);
        }
    }

    private static Class<?> depthDrawerClass() throws ClassNotFoundException {
        Class<?> cls = depthDrawerClass;
        if (cls == null) {
            cls = Class.forName(DEPTH_DRAWER, false, StormGroundItemBatch.class.getClassLoader());
            depthDrawerClass = cls;
        }
        return cls;
    }

    private static void forget() {
        lastState = null;
        lastIndex = -1;
        lastDrawer = null;
        lastAtlas = null;
    }

    private static void latchOff(Throwable t) {
        disabled = true;
        CONTINUED.clear();
        forget();
        LOGGER.error(
                "Storm: ground-item flush batching failed — every ground item flushes its own VBO"
                        + " for the rest of this session",
                t);
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            CONTINUED.clear();
            forget();
        }
    }

    public static String stats() {
        return "submitted="
                + submitted
                + " marked="
                + marked
                + " skipped="
                + skipped
                + " flushed="
                + flushed
                + " dropped="
                + dropped
                + " pending="
                + CONTINUED.size()
                + " enabled="
                + enabled
                + " disabled="
                + disabled;
    }
}
