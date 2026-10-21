package io.pzstorm.storm.worldmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.worldmap.StormWorldMapSymbolVisibilityMemo.Index;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.characters.Faction;
import zombie.iso.areas.SafeHouse;

class StormWorldMapSymbolVisibilityMemoTest implements UnitTest {

    private static Faction faction(String owner, String... players) {
        Faction f = new Faction();
        f.setOwner(owner);
        f.getPlayers().addAll(Arrays.asList(players));
        return f;
    }

    private static SafeHouse safehouse(String owner, String... players) {
        SafeHouse s = new SafeHouse(0, 0, 1, 1, owner);
        s.getPlayers().addAll(Arrays.asList(players));
        return s;
    }

    private static List<String> list(String... names) {
        return new ArrayList<>(Arrays.asList(names));
    }

    @BeforeEach
    @AfterEach
    void reset() {
        StormWorldMapSymbolVisibilityMemo.resetForTest();
        Faction.getFactions().clear();
        SafeHouse.getSafehouseList().clear();
    }

    @Test
    void indexResolvesFactionToFirstMatchInListOrder() {
        Faction first = faction("alice", "bob");
        Faction second = faction("carol", "bob", "dave");
        Index index = new Index();
        index.indexFaction(first.getOwner(), first.getPlayers(), first);
        index.indexFaction(second.getOwner(), second.getPlayers(), second);
        assertSame(first, index.faction("alice"));
        assertSame(first, index.faction("bob"));
        assertSame(second, index.faction("carol"));
        assertSame(second, index.faction("dave"));
        assertNull(index.faction("erin"));
        assertNull(index.faction(null));
    }

    @Test
    void indexResolvesSafehouseToFirstMatchInListOrder() {
        SafeHouse first = safehouse("alice", "bob");
        SafeHouse second = safehouse("carol", "bob", "dave");
        Index index = new Index();
        index.indexSafehouse(first.getOwner(), first.getPlayers(), first);
        index.indexSafehouse(second.getOwner(), second.getPlayers(), second);
        assertSame(first, index.safehouse("alice"));
        assertSame(first, index.safehouse("bob"));
        assertSame(second, index.safehouse("carol"));
        assertSame(second, index.safehouse("dave"));
        assertNull(index.safehouse("erin"));
        assertNull(index.safehouse(null));
    }

    @Test
    void indexSkipsNullNames() {
        Faction f = faction(null, "bob");
        SafeHouse s = safehouse("carol");
        Index index = new Index();
        index.indexFaction(null, list("bob", null), f);
        index.indexSafehouse(null, list(null, "carol"), s);
        assertSame(f, index.faction("bob"));
        assertSame(s, index.safehouse("carol"));
        assertEquals(2, index.size());
        assertNull(index.faction(null));
        assertNull(index.safehouse(null));
    }

    @Test
    void outsideABatchEveryLookupRunsVanilla() {
        Faction f = faction("alice", "bob");
        Faction.getFactions().add(f);
        SafeHouse s = safehouse("alice", "bob");
        SafeHouse.getSafehouseList().add(s);

        assertSame(f, StormWorldMapSymbolVisibilityMemo.getPlayerFaction("bob"));
        assertSame(s, StormWorldMapSymbolVisibilityMemo.hasSafehouse("bob"));
        assertTrue(StormWorldMapSymbolVisibilityMemo.stats().contains("vanillaLookups=2"));
        assertTrue(StormWorldMapSymbolVisibilityMemo.stats().contains("built=false"));
    }

    @Test
    void insideABatchLookupsMatchVanillaAndTheIndexBuildsOnce() {
        Faction first = faction("alice", "bob");
        Faction second = faction("carol", "bob", "dave");
        Faction.getFactions().add(first);
        Faction.getFactions().add(second);
        SafeHouse s1 = safehouse("erin", "dave");
        SafeHouse s2 = safehouse("frank", "dave", "alice");
        SafeHouse.getSafehouseList().add(s1);
        SafeHouse.getSafehouseList().add(s2);

        StormWorldMapSymbolVisibilityMemo.begin();
        for (String name :
                new String[] {"alice", "bob", "carol", "dave", "erin", "frank", "zed", null}) {
            assertSame(
                    Faction.getPlayerFaction(name),
                    StormWorldMapSymbolVisibilityMemo.getPlayerFaction(name),
                    "faction for " + name);
            assertSame(
                    SafeHouse.hasSafehouse(name),
                    StormWorldMapSymbolVisibilityMemo.hasSafehouse(name),
                    "safehouse for " + name);
        }
        String stats = StormWorldMapSymbolVisibilityMemo.stats();
        assertTrue(stats.contains("builds=1"), stats);
        assertTrue(stats.contains("memoLookups=16"), stats);
        assertTrue(stats.contains("built=true"), stats);
        StormWorldMapSymbolVisibilityMemo.end();
        assertTrue(StormWorldMapSymbolVisibilityMemo.stats().contains("built=false"));
    }

    @Test
    void nestedBatchesShareOneIndexUntilTheOutermostEnds() {
        Faction.getFactions().add(faction("alice"));
        StormWorldMapSymbolVisibilityMemo.begin();
        StormWorldMapSymbolVisibilityMemo.getPlayerFaction("alice");
        StormWorldMapSymbolVisibilityMemo.begin();
        StormWorldMapSymbolVisibilityMemo.getPlayerFaction("alice");
        StormWorldMapSymbolVisibilityMemo.end();
        assertTrue(StormWorldMapSymbolVisibilityMemo.stats().contains("built=true"));
        StormWorldMapSymbolVisibilityMemo.end();
        assertTrue(StormWorldMapSymbolVisibilityMemo.stats().contains("built=false"));
        assertTrue(StormWorldMapSymbolVisibilityMemo.stats().contains("builds=1"));
    }

    @Test
    void aBatchSeesListChangesMadeBeforeItsFirstLookup() {
        StormWorldMapSymbolVisibilityMemo.begin();
        StormWorldMapSymbolVisibilityMemo.end();
        Faction late = faction("alice");
        Faction.getFactions().add(late);
        StormWorldMapSymbolVisibilityMemo.begin();
        assertSame(late, StormWorldMapSymbolVisibilityMemo.getPlayerFaction("alice"));
        StormWorldMapSymbolVisibilityMemo.end();
    }

    @Test
    void disabledMemoRunsVanillaInsideABatch() {
        Faction f = faction("alice");
        Faction.getFactions().add(f);
        StormWorldMapSymbolVisibilityMemo.setEnabled(false);
        StormWorldMapSymbolVisibilityMemo.begin();
        assertSame(f, StormWorldMapSymbolVisibilityMemo.getPlayerFaction("alice"));
        assertTrue(StormWorldMapSymbolVisibilityMemo.stats().contains("vanillaLookups=1"));
        assertTrue(StormWorldMapSymbolVisibilityMemo.stats().contains("builds=0"));
        StormWorldMapSymbolVisibilityMemo.end();
    }
}
