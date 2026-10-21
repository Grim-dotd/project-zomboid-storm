package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Gives newly created animals an ID from {@code [100000, Integer.MAX_VALUE)} instead of vanilla's
 * {@code Rand.Next(10000)}.
 *
 * <p>Vanilla treats a shared animal ID within an 8x8 box as a duplicated animal and deletes it
 * ({@code IsoAnimal.checkForChickenpocalypse}), and {@code AnimalManagerWorker} drops an animal
 * whose ID already exists in its virtual zone. Both fire on honest random collisions once a world
 * holds a few thousand animals; the ATF server lost 259 babies at their growth stage in six weeks
 * this way. Every consumer of the ID is an {@code int} (cell maps, mother links, trailer lookups,
 * Lua display), so a wider range changes nothing else.
 *
 * <p>Advises {@code IsoAnimal.init(AnimalBreed)} on exit; {@code load} runs after {@code init} and
 * keeps the saved ID, so existing animals are untouched. {@link AnimalDataGrowWaterGuardPatch}
 * handles the animals that already carry colliding IDs.
 */
public class IsoAnimalWideIdPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isoanimalwideid.";

    public IsoAnimalWideIdPatch() {
        super("zombie.characters.animals.IsoAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoAnimalWideIdAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("init")
                                        .and(ElementMatchers.takesArguments(1))
                                        .and(
                                                ElementMatchers.takesArgument(
                                                        0,
                                                        ElementMatchers.named(
                                                                "zombie.characters.animals.datas.AnimalBreed")))));
    }
}
