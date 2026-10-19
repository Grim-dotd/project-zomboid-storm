package io.pzstorm.storm.advice.propertycontainer;

import java.util.ArrayList;
import java.util.Arrays;
import net.bytebuddy.asm.Advice;
import zombie.core.TilePropertyAliasMap;
import zombie.core.properties.IsoPropertyType;
import zombie.core.properties.PropertyContainer;

/**
 * Advice for {@code zombie.core.properties.PropertyContainer.has(IsoPropertyType)}.
 *
 * <p>Vanilla dispatches through {@code has(String)}, which calls {@code
 * TilePropertyAliasMap.instance.getIDFromPropertyName(String)} — a {@code HashMap<String,Integer>}
 * lookup — every call, then {@code containsKey(short)}. Live-client sampling attributed 1.75% of
 * MainThread to the {@code has} path under {@code ParameterFootstepMaterial.getMaterial}, which
 * walks every object on the character's square each FMOD parameter update — the same enum passed
 * thousands of times per frame.
 *
 * <p>The fast path caches the resolved short ID per enum ordinal in {@link #IDS}, alongside the
 * {@link TilePropertyAliasMap.TileProperty} that ID resolved to in {@link #PROPS}. {@code
 * TilePropertyAliasMap.Generate} rebuilds the map with fresh {@code TileProperty} objects on every
 * world load, and IDs can shift between loads (the second join in one client JVM). Each call checks
 * that the cached ID still points at the cached {@code TileProperty}; a stale entry fails that
 * reference compare and is re-resolved. While the name is unresolved the vanilla body runs.
 *
 * <p>Return encoding for {@code skipOn = OnNonDefaultValue}: enter returns {@code 0} to run the
 * vanilla body, {@code 1} for {@code false}, {@code 2} for {@code true}. Exit unpacks the flag into
 * the original {@code boolean} return.
 */
public class PropertyContainerHasIdCacheAdvice {

    public static final short[] IDS = init();

    public static final TilePropertyAliasMap.TileProperty[] PROPS =
            new TilePropertyAliasMap.TileProperty[IsoPropertyType.values().length];

    private static short[] init() {
        short[] a = new short[IsoPropertyType.values().length];
        Arrays.fill(a, (short) -1);
        return a;
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This PropertyContainer self, @Advice.Argument(0) IsoPropertyType type) {
        if (type == null) {
            return 0;
        }
        int ord = type.ordinal();
        short id = IDS[ord];
        ArrayList<TilePropertyAliasMap.TileProperty> props =
                TilePropertyAliasMap.instance.properties;
        if (id < 0 || id >= props.size() || props.get(id) != PROPS[ord]) {
            int resolved = TilePropertyAliasMap.instance.getIDFromPropertyName(type.getName());
            if (resolved == -1) {
                return 0;
            }
            id = (short) resolved;
            PROPS[ord] = props.get(id);
            IDS[ord] = id;
        }
        return self.containsKey(id) ? 2 : 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int flag, @Advice.Return(readOnly = false) boolean ret) {
        if (flag == 2) {
            ret = true;
        } else if (flag == 1) {
            ret = false;
        }
    }
}
