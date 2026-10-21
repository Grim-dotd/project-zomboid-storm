package io.pzstorm.storm.worldmap;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.HashMap;
import java.util.List;
import zombie.characters.Faction;
import zombie.iso.areas.SafeHouse;

/**
 * Client-side, per-render-batch memo for the two username lookups inside {@code
 * WorldMapBaseSymbol.isVisible(UIWorldMap)}.
 *
 * <p>Every frame the world map (and the minimap) runs {@code isVisible} for every symbol it holds,
 * and for a shared symbol that is not visible to everyone that call resolves the local player's
 * faction and safehouse and the author's with {@code Faction.getPlayerFaction(String)} and {@code
 * SafeHouse.hasSafehouse(String)}. Both are linear scans over every faction's or safehouse's member
 * list, up to three calls each per symbol. On a server with 667 factions and 1,300 shared symbols
 * that pass measured ~6 ms of main-thread time per frame with nothing on screen.
 *
 * <p>{@link #begin()} opens a batch at the top of {@code SymbolsRenderData.renderMain} and {@code
 * WorldMapSymbols.hitTest}; the substituted lookups then answer from a username index that is built
 * on the first lookup of the batch and dropped by {@link #end()}. Nothing the lookups read can
 * change while a batch runs on the main thread, so the memo is exact: the index keeps the first
 * faction and the first safehouse in list order that owns or lists the name, which is what the
 * vanilla scans return. Outside a batch every call runs vanilla.
 *
 * <p>Always on. Any throwable latches the memo off for the rest of the process and the vanilla
 * scans run unchanged. {@link #setEnabled(boolean)} switches it off for A/B checks and {@link
 * #stats()} reports counters; both are reachable through Java eval.
 */
public final class StormWorldMapSymbolVisibilityMemo {

    private static final Index INDEX = new Index();

    private static int depth;
    private static boolean built;
    private static boolean disabled;
    private static boolean enabled = true;

    private static long builds;
    private static long memoLookups;
    private static long vanillaLookups;
    private static long buildNanos;

    private StormWorldMapSymbolVisibilityMemo() {}

    /** Opens a batch. Nested batches share one index. */
    public static void begin() {
        depth++;
    }

    /** Closes a batch; the index is dropped when the outermost batch ends. */
    public static void end() {
        if (depth > 0 && --depth == 0) {
            INDEX.clear();
            built = false;
        }
    }

    /** Substitute for {@code Faction.getPlayerFaction(String)}. */
    public static Faction getPlayerFaction(String username) {
        if (!ready()) {
            vanillaLookups++;
            return Faction.getPlayerFaction(username);
        }
        memoLookups++;
        return INDEX.faction(username);
    }

    /** Substitute for {@code SafeHouse.hasSafehouse(String)}. */
    public static SafeHouse hasSafehouse(String username) {
        if (!ready()) {
            vanillaLookups++;
            return SafeHouse.hasSafehouse(username);
        }
        memoLookups++;
        return INDEX.safehouse(username);
    }

    private static boolean ready() {
        if (depth == 0 || disabled || !enabled) {
            return false;
        }
        if (built) {
            return true;
        }
        long start = System.nanoTime();
        try {
            INDEX.clear();
            List<Faction> factions = Faction.getFactions();
            for (int i = 0; i < factions.size(); i++) {
                Faction faction = factions.get(i);
                INDEX.indexFaction(faction.getOwner(), faction.getPlayers(), faction);
            }
            List<SafeHouse> safehouses = SafeHouse.getSafehouseList();
            for (int i = 0; i < safehouses.size(); i++) {
                SafeHouse safehouse = safehouses.get(i);
                INDEX.indexSafehouse(safehouse.getOwner(), safehouse.getPlayers(), safehouse);
            }
            built = true;
            builds++;
            buildNanos += System.nanoTime() - start;
            return true;
        } catch (Throwable t) {
            disabled = true;
            INDEX.clear();
            built = false;
            LOGGER.error(
                    "Storm: world-map symbol visibility memo failed to build — reverting to the"
                            + " vanilla faction and safehouse scans for the rest of this session",
                    t);
            return false;
        }
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static String stats() {
        return "builds="
                + builds
                + " memoLookups="
                + memoLookups
                + " vanillaLookups="
                + vanillaLookups
                + " avgBuildMs="
                + (builds == 0 ? 0.0 : buildNanos / 1e6 / builds)
                + " depth="
                + depth
                + " built="
                + built
                + " enabled="
                + enabled
                + " disabled="
                + disabled;
    }

    static void resetForTest() {
        depth = 0;
        built = false;
        disabled = false;
        enabled = true;
        builds = 0;
        memoLookups = 0;
        vanillaLookups = 0;
        buildNanos = 0;
        INDEX.clear();
    }

    /**
     * Username-keyed view of the faction and safehouse lists. The first claim on a name wins, so
     * callers index in list order to reproduce the vanilla first-match scans. Null names are
     * skipped, where vanilla would throw on a null owner. PZ-static-free; unit tested.
     */
    static final class Index {
        private final HashMap<String, Faction> factions = new HashMap<>();
        private final HashMap<String, SafeHouse> safehouses = new HashMap<>();

        void clear() {
            factions.clear();
            safehouses.clear();
        }

        /** Mirrors {@code Faction.getPlayerFaction(String)}: owner or listed player, first wins. */
        void indexFaction(String owner, List<String> players, Faction faction) {
            if (owner != null) {
                factions.putIfAbsent(owner, faction);
            }
            for (int i = 0; i < players.size(); i++) {
                String player = players.get(i);
                if (player != null) {
                    factions.putIfAbsent(player, faction);
                }
            }
        }

        /** Mirrors {@code SafeHouse.hasSafehouse(String)}: listed player or owner, first wins. */
        void indexSafehouse(String owner, List<String> players, SafeHouse safehouse) {
            if (owner != null) {
                safehouses.putIfAbsent(owner, safehouse);
            }
            for (int i = 0; i < players.size(); i++) {
                String player = players.get(i);
                if (player != null) {
                    safehouses.putIfAbsent(player, safehouse);
                }
            }
        }

        Faction faction(String username) {
            return username == null ? null : factions.get(username);
        }

        SafeHouse safehouse(String username) {
            return username == null ? null : safehouses.get(username);
        }

        int size() {
            return factions.size() + safehouses.size();
        }
    }
}
