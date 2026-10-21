package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.iso.areas.SafeHouse;

/**
 * Fixes the safehouse respawn point in {@code CreatePlayerPacket.processServer}, which vanilla
 * computes as {@code (x + h/2, y + w/2)} even though {@code w} is the x extent. Swaps the {@code
 * SafeHouse.getH()} and {@code getW()} call sites inside that method so the spawn lands at the true
 * center. Server-only.
 */
public class SafehouseSpawnCenterFixPatch extends StormClassTransformer {

    public SafehouseSpawnCenterFixPatch() {
        super("zombie.network.packets.character.CreatePlayerPacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        try {
            return builder.visit(
                    MemberSubstitution.relaxed()
                            .method(
                                    ElementMatchers.isDeclaredBy(SafeHouse.class)
                                            .and(ElementMatchers.named("getH"))
                                            .and(ElementMatchers.takesNoArguments()))
                            .replaceWith(SafeHouse.class.getMethod("getW"))
                            .method(
                                    ElementMatchers.isDeclaredBy(SafeHouse.class)
                                            .and(ElementMatchers.named("getW"))
                                            .and(ElementMatchers.takesNoArguments()))
                            .replaceWith(SafeHouse.class.getMethod("getH"))
                            .on(ElementMatchers.named("processServer")));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to setup safehouse spawn center substitution", e);
        }
    }
}
