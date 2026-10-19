package io.pzstorm.storm.advice.propertycontainer;

import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import zombie.core.TilePropertyAliasMap;
import zombie.core.properties.PropertyContainer;

/**
 * Advice for {@code zombie.core.properties.PropertyContainer.has(String)}.
 *
 * <p>Vanilla resolves the property name through {@code
 * TilePropertyAliasMap.instance.getIDFromPropertyName(String)} — a {@code HashMap<String,Integer>}
 * lookup with string hashing and {@code Integer} boxing — on every call, then probes {@code
 * containsKey(short)}. The {@code FBORenderCell.isObjectRenderLayer_MinusFloor} / {@code
 * _MinusFloorSE} / {@code _Translucent} helpers query {@code door.getProperties().has("doorTrans")}
 * for every door object on every dirty chunk-level (the 4.4%-of-MainThread {@code
 * calculateObjectRenderLayer} hotspot), so the same interned literal is re-resolved thousands of
 * times per frame in busy scenes. The enum-keyed {@link PropertyContainerHasIdCacheAdvice} does not
 * cover this string overload.
 *
 * <p>The fast path caches the resolved short ID for the {@code "doorTrans"} literal together with
 * the {@link TilePropertyAliasMap.TileProperty} it resolved to, then goes straight to the live
 * Trove {@code containsKey(short)}. {@code TilePropertyAliasMap.Generate} rebuilds the map with
 * fresh {@code TileProperty} objects on every world load, so each call checks that the cached ID
 * still points at the cached {@code TileProperty} and re-resolves when it does not — the same
 * scheme as {@link PropertyContainerHasIdCacheAdvice}. The container's own map is consulted on
 * every call, so runtime {@code set}/{@code unset} from mods is reflected immediately.
 *
 * <p>Non-matching names fall through to the vanilla body after one reference compare plus a
 * length-gated {@code equals}. While the name is unresolved ({@code getIDFromPropertyName} returns
 * {@code -1}), the vanilla body runs and resolution is retried on the next call.
 *
 * <p>Return encoding for {@code skipOn = OnNonDefaultValue}: {@code 0} = run vanilla body, {@code
 * 1} = {@code false}, {@code 2} = {@code true} — same scheme as {@link
 * PropertyContainerHasIdCacheAdvice}.
 */
public class PropertyContainerHasStringIdCacheAdvice {

    // Compile-time constant: javac inlines the literal at the use site, so the identity compare
    // below tests against the same interned instance FBORenderCell's "doorTrans" literal uses.
    public static final String DOOR_TRANS = "doorTrans";

    /** Resolved alias-map ID for {@link #DOOR_TRANS}; {@code -1} = not resolved. */
    public static short doorTransId = -1;

    /** The alias-map entry {@link #doorTransId} resolved to. */
    public static TilePropertyAliasMap.TileProperty doorTransProp;

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This PropertyContainer self, @Advice.Argument(0) String name) {
        if (name != DOOR_TRANS && !DOOR_TRANS.equals(name)) {
            return 0;
        }
        short id = doorTransId;
        ArrayList<TilePropertyAliasMap.TileProperty> props =
                TilePropertyAliasMap.instance.properties;
        if (id < 0 || id >= props.size() || props.get(id) != doorTransProp) {
            int resolved = TilePropertyAliasMap.instance.getIDFromPropertyName(DOOR_TRANS);
            if (resolved == -1) {
                return 0;
            }
            id = (short) resolved;
            doorTransProp = props.get(id);
            doorTransId = id;
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
