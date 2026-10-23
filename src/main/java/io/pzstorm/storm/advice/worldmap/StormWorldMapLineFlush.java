package io.pzstorm.storm.advice.worldmap;

import zombie.core.opengl.VBORenderer;

/**
 * Substitute for the {@code VBORenderer.flush()} at the top of {@code
 * WorldMapRenderer$Drawer.drawLineString}. That method draws nothing after the flush (it fills a
 * float array that nothing reads and sets texture/blend state that every later run re-applies), so
 * the flush only pays to upload and draw whatever map geometry happens to be pending, once per line
 * feature per frame. Every map draw goes through the same {@code VBORenderer} singleton in
 * submission order, so leaving the pending runs for the next real flush draws them in the same
 * order under the same per-run state. {@link #setEnabled(boolean)} restores the vanilla flush for
 * A/B checks; {@link #stats()} counts.
 */
public final class StormWorldMapLineFlush {

    private static volatile boolean enabled = true;

    private static long skipped;
    private static long flushed;

    private StormWorldMapLineFlush() {}

    public static void flush(VBORenderer vbo) {
        if (enabled) {
            skipped++;
            return;
        }
        flushed++;
        vbo.flush();
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static String stats() {
        return "skipped=" + skipped + " flushed=" + flushed + " enabled=" + enabled;
    }
}
