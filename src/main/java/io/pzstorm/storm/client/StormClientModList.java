package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.util.StormEnv;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.gameStates.ChooseGameInfo;
import zombie.modding.ActiveMods;
import zombie.network.GameClient;

/**
 * Builds the mod list a client loads for a server, replacing vanilla's connect-time branch of
 * {@code ZomboidFileSystem.loadMods(String)}.
 *
 * <p>Vanilla loads the player's locally enabled "translation mods" (mods whose only media files are
 * {@code media/lua/shared/translate} text) on top of the server's list, and puts them <i>first</i>.
 * The loader chases every mod's {@code require} before placing it, so a translation mod that
 * requires a content mod drags that content mod to the front of the client's order. The server's
 * Lua checksum is compared file by file in load order, so the join is refused with a misleading
 * "File doesn't exist on the client" naming whichever file the server has at that position. An
 * empty optional mod shipped beside a content mod (Improved Hair Menu's {@code ihm_picker}) is
 * enough to lock a player out of every server that loads the content mod anywhere but first. A
 * translation mod whose required content mod the server does not load at all adds Lua the server
 * lacks and fails the same way.
 *
 * <p>Here the server's list goes first and the local translation mods come after it, so the chase
 * finds their requirements already placed and the Lua order equals the server's. A translation mod
 * is only kept when everything it requires is either loaded by the server or an already-kept
 * translation mod; the rest are logged and left out for the session. Translation mods ship no
 * checksummed files, so appending them never changes the compared set. The same list feeds the
 * launcher's boot-time substitution ({@link StormJoinPrewarm}), whose booted order the connect-time
 * fast path keeps.
 */
public final class StormClientModList {

    private StormClientModList() {}

    /**
     * Called from Storm's advice on {@code ZomboidFileSystem.loadMods(String)}. Returns {@code
     * true} when this was the connect-time client load and the list was built here — the caller
     * then skips the vanilla body. Any failure while building the list runs vanilla untouched.
     */
    public static boolean loadForServer(Object fileSystem, String activeMods) {
        ArrayList<String> toLoad;
        try {
            if (StormEnv.isStormServer()
                    || !GameClient.client
                    || GameClient.instance == null
                    || !Core.optionModsEnabled) {
                return false;
            }
            toLoad =
                    serverThenLocalTranslation(
                            (ZomboidFileSystem) fileSystem, GameClient.instance.serverMods);
        } catch (Throwable t) {
            LOGGER.error("Client mod list build failed - using the vanilla mod order", t);
            return false;
        }
        ((ZomboidFileSystem) fileSystem).loadMods(toLoad);
        return true;
    }

    /**
     * The list to hand {@code ZomboidFileSystem.loadMods(ArrayList)}: {@code serverMods} as given,
     * then the locally enabled translation mods that add nothing the server lacks.
     */
    public static ArrayList<String> serverThenLocalTranslation(
            ZomboidFileSystem fs, List<String> serverMods) throws Exception {
        ArrayList<String> serverResolved = new ArrayList<>();
        fs.loadModsAux(new ArrayList<>(serverMods), serverResolved);

        ActiveMods defaults = (ActiveMods) invokePrivate(fs, "readDefaultModsTxt", new Class<?>[0]);
        List<String> localOrdered = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        if (fs.loadModsAux(defaults.getMods(), localOrdered) == null) {
            accepted =
                    admissibleTranslationMods(
                            localOrdered,
                            new HashSet<>(serverResolved),
                            StormClientModList::requiresOf,
                            id -> isTranslationMod(fs, id));
        }
        if (!accepted.isEmpty()) {
            LOGGER.info(
                    "Loading {} local translation mod(s) after the server's list: {}",
                    accepted.size(),
                    String.join(" ", accepted));
        }
        ArrayList<String> toLoad = new ArrayList<>(serverMods);
        toLoad.addAll(accepted);
        return toLoad;
    }

    /**
     * The entries of {@code localOrdered} (a require-first ordered list, as {@code loadModsAux}
     * produces) worth appending after the server's list: translation mods the server does not
     * already load, whose every requirement is in {@code serverResolved} or an earlier accepted
     * entry. Content mods enabled locally are ignored, as vanilla ignores them.
     */
    static List<String> admissibleTranslationMods(
            List<String> localOrdered,
            Set<String> serverResolved,
            Function<String, List<String>> requiresOf,
            Predicate<String> isTranslationMod) {
        Set<String> accepted = new LinkedHashSet<>();
        for (String modId : localOrdered) {
            if (serverResolved.contains(modId) || !isTranslationMod.test(modId)) {
                continue;
            }
            String missing = null;
            List<String> requires = requiresOf.apply(modId);
            if (requires != null) {
                for (String required : requires) {
                    if (!serverResolved.contains(required) && !accepted.contains(required)) {
                        missing = required;
                        break;
                    }
                }
            }
            if (missing != null) {
                LOGGER.info(
                        "Local mod '{}' is enabled in this computer's Mods list but requires '{}',"
                                + " which this server does not load - leaving it out for this"
                                + " session",
                        modId,
                        missing);
                continue;
            }
            accepted.add(modId);
        }
        return new ArrayList<>(accepted);
    }

    private static @Nullable List<String> requiresOf(String modId) {
        ChooseGameInfo.Mod info = ChooseGameInfo.getAvailableModDetails(modId);
        return info == null ? null : info.getRequire();
    }

    private static boolean isTranslationMod(ZomboidFileSystem fs, String modId) {
        try {
            return (Boolean)
                    invokePrivate(fs, "isTranslationMod", new Class<?>[] {String.class}, modId);
        } catch (Exception e) {
            throw new IllegalStateException("ZomboidFileSystem.isTranslationMod", e);
        }
    }

    static Object invokePrivate(
            Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
