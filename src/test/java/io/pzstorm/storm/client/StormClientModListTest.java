package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StormClientModListTest implements UnitTest {

    private static final Set<String> SERVER = Set.of("storm-core-b42", "ihm42", "SomeMap");

    private static List<String> admissible(
            List<String> localOrdered,
            Map<String, List<String>> requires,
            Set<String> translationMods) {
        return StormClientModList.admissibleTranslationMods(
                localOrdered, SERVER, requires::get, translationMods::contains);
    }

    @Test
    void keepsTranslationModWhoseRequireTheServerLoads() {
        assertEquals(
                List.of("ihm_picker"),
                admissible(
                        List.of("ihm42", "ihm_picker"),
                        Map.of("ihm_picker", List.of("ihm42")),
                        Set.of("ihm_picker")));
    }

    @Test
    void dropsTranslationModWhoseRequireTheServerLacks() {
        assertEquals(
                List.of(),
                admissible(
                        List.of("OtherContent", "OtherTranslation"),
                        Map.of("OtherTranslation", List.of("OtherContent")),
                        Set.of("OtherTranslation")));
    }

    @Test
    void requireSatisfiedByAnEarlierAcceptedTranslationMod() {
        assertEquals(
                List.of("BaseLang", "ExtraLang"),
                admissible(
                        List.of("BaseLang", "ExtraLang"),
                        Map.of("ExtraLang", List.of("BaseLang")),
                        Set.of("BaseLang", "ExtraLang")));
    }

    @Test
    void ignoresLocalContentModsAndServerModsAndNullRequires() {
        assertEquals(
                List.of("FreeLang"),
                admissible(
                        List.of("LocalContent", "storm-core-b42", "FreeLang", "FreeLang"),
                        Map.of(),
                        Set.of("FreeLang", "storm-core-b42")));
    }
}
