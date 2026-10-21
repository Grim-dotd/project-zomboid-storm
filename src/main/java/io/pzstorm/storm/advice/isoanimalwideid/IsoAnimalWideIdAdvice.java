package io.pzstorm.storm.advice.isoanimalwideid;

import java.util.concurrent.ThreadLocalRandom;
import net.bytebuddy.asm.Advice;
import zombie.characters.animals.IsoAnimal;

/**
 * Advice for {@code IsoAnimal.init(AnimalBreed)} that replaces vanilla's {@code Rand.Next(10000)}
 * animal ID with one drawn from {@code [100000, Integer.MAX_VALUE)}.
 *
 * <p>{@code checkForChickenpocalypse()} deletes an animal whenever another animal within an 8x8 box
 * shares its ID, and {@code AnimalManagerWorker} drops animals whose ID already exists in the
 * virtual zone. With a 10000-value range and a few thousand animals in a world those collisions are
 * routine; the wide range makes them negligible. Loaded animals keep the ID read from their save,
 * so widening only affects animals created from here on.
 *
 * <p>Runs in both JVMs so single-player gets the same fix. In multiplayer the client overwrites the
 * ID with the server's through {@code IsoAnimal.load}, so the client-side draw is unused.
 */
public class IsoAnimalWideIdAdvice {

    public static final int VANILLA_ID_BOUND = 10000;
    public static final int WIDE_ID_MIN = 100000;

    public static int allocate() {
        return ThreadLocalRandom.current().nextInt(WIDE_ID_MIN, Integer.MAX_VALUE);
    }

    public static boolean isVanillaRange(int id) {
        return id >= 0 && id < VANILLA_ID_BOUND;
    }

    @Advice.OnMethodExit
    public static void onExit(@Advice.This IsoAnimal self) {
        if (isVanillaRange(self.getAnimalID())) {
            self.setAnimalID(allocate());
        }
    }
}
