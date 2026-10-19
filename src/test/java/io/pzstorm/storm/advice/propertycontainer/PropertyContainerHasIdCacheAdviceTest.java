package io.pzstorm.storm.advice.propertycontainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import zombie.core.TilePropertyAliasMap;
import zombie.core.properties.IsoPropertyType;
import zombie.core.properties.PropertyContainer;

/**
 * Both {@code has} caches must follow {@code TilePropertyAliasMap.Generate}, which rebuilds the map
 * on every world load. A cache that kept the first load's IDs made {@code has(FACING)} and {@code
 * has(GARAGE_DOOR)} answer for unrelated keys on a second join in the same client JVM, so {@code
 * get} returned null for a property {@code has} claimed and loading crashed in {@code
 * IsoWorld.LoadTileDefinitions} and {@code IsoDoor.getGarageDoorIndex}.
 */
class PropertyContainerHasIdCacheAdviceTest implements UnitTest {

    private static final int RUN_VANILLA = 0;
    private static final int FALSE = 1;
    private static final int TRUE = 2;

    @AfterEach
    void clearAliasMap() {
        TilePropertyAliasMap.instance.properties.clear();
        TilePropertyAliasMap.instance.propertyToId.clear();
    }

    @Test
    void enumCacheFollowsAliasMapRebuild() {
        generate("Facing", "GarageDoor");
        PropertyContainer facingOnly = containerWithKey(0);
        assertEquals(TRUE, enumHas(facingOnly, IsoPropertyType.FACING));
        assertEquals(FALSE, enumHas(facingOnly, IsoPropertyType.GARAGE_DOOR));

        generate("GarageDoor", "Facing");
        PropertyContainer garageOnly = containerWithKey(0);
        assertEquals(FALSE, enumHas(garageOnly, IsoPropertyType.FACING));
        assertEquals(TRUE, enumHas(garageOnly, IsoPropertyType.GARAGE_DOOR));
    }

    @Test
    void enumCacheRunsVanillaWhileUnresolved() {
        assertEquals(RUN_VANILLA, enumHas(new PropertyContainer(), IsoPropertyType.FACING));
    }

    @Test
    void doorTransCacheFollowsAliasMapRebuild() {
        generate("doorTrans", "Facing");
        PropertyContainer first = containerWithKey(0);
        assertEquals(TRUE, PropertyContainerHasStringIdCacheAdvice.onEnter(first, "doorTrans"));

        generate("Facing", "doorTrans");
        PropertyContainer second = containerWithKey(0);
        assertEquals(FALSE, PropertyContainerHasStringIdCacheAdvice.onEnter(second, "doorTrans"));
    }

    private static int enumHas(PropertyContainer container, IsoPropertyType type) {
        return PropertyContainerHasIdCacheAdvice.onEnter(container, type);
    }

    private static PropertyContainer containerWithKey(int id) {
        PropertyContainer container = new PropertyContainer();
        container.put((short) id, (short) 0);
        return container;
    }

    private static void generate(String... names) {
        TilePropertyAliasMap map = TilePropertyAliasMap.instance;
        map.properties.clear();
        map.propertyToId.clear();
        for (String name : names) {
            TilePropertyAliasMap.TileProperty prop = new TilePropertyAliasMap.TileProperty();
            prop.propertyName = name;
            map.propertyToId.put(name, map.properties.size());
            map.properties.add(prop);
        }
    }
}
