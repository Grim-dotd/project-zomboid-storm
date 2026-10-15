package io.pzstorm.storm.patch.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnLoadModsEvent;
import io.pzstorm.storm.util.StormEnv;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Patches {@link zombie.ZomboidFileSystem} */
public class ZomboidFileSystemPatch extends StormClassTransformer {

    public ZomboidFileSystemPatch() {
        super("zombie.ZomboidFileSystem");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        builder =
                builder.visit(
                        Advice.to(LoadModsAdvice.class)
                                .on(
                                        ElementMatchers.named("loadMods")
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0, ArrayList.class))));
        builder =
                builder.visit(
                        Advice.to(LoadModsProfileAdvice.class)
                                .on(
                                        ElementMatchers.named("loadMods")
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0, String.class))));
        if (StormEnv.isStormServer()) {
            builder =
                    builder.visit(
                            Advice.to(DeleteDirectoryGuardAdvice.class)
                                    .on(
                                            ElementMatchers.named("deleteDirectory")
                                                    .and(
                                                            ElementMatchers.takesArgument(
                                                                    0, String.class))));
        }
        return builder;
    }

    /**
     * Server-only guard for {@code deleteDirectory(String)}, whose sole external caller is the Fail
     * branch of {@code GameServerWorkshopItems.Install}: a null path skips the call - vanilla NPEs
     * in {@code new File(null)} when the failing workshop item was never installed, killing the
     * server with a stack trace instead of the fail-branch's own retries and failure report.
     */
    public static class DeleteDirectoryGuardAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(@Advice.Argument(0) String dirPath) {
            boolean skip = false;
            if (dirPath == null) {
                LOGGER.warn(
                        "deleteDirectory(null) called (failed workshop item with no install"
                                + " folder) - skipping instead of throwing NPE");
                skip = true;
            }
            return skip;
        }
    }

    public static class LoadModsAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) ArrayList<String> mods) {
            List<String> modsList = Objects.requireNonNullElse(mods, Collections.emptyList());
            LOGGER.debug("OnLoadMods: {}", String.join(" ", modsList));
            StormEventDispatcher.dispatchEvent(new OnLoadModsEvent(modsList));
        }
    }

    /**
     * Client-side hook on {@code loadMods(String)} (the profile-name overload), two replacements in
     * turn. When the Storm Launcher opted this JVM in ({@code -Dstorm.join.bootmods=true}), the
     * boot-time {@code loadMods("default")} call loads the target server's mod set instead, so
     * connect-time {@code ResetLua} finds all flag-independent content already loaded ({@link
     * io.pzstorm.storm.client.StormJoinPrewarm#substituteBootMods}). Otherwise, while connected to
     * a server, the list is built server-first with locally enabled translation mods appended,
     * instead of vanilla's translation-mods-first order that breaks the Lua checksum ({@link
     * io.pzstorm.storm.client.StormClientModList#loadForServer}). Both hold every gate and never
     * throw; a {@code false} return runs the vanilla body untouched, so a boot without the property
     * and the dedicated server pay two boolean checks.
     */
    public static class LoadModsProfileAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(
                @Advice.This Object self, @Advice.Argument(0) String activeMods) {
            return io.pzstorm.storm.client.StormJoinPrewarm.substituteBootMods(self, activeMods)
                    || io.pzstorm.storm.client.StormClientModList.loadForServer(self, activeMods);
        }
    }
}
