package io.pzstorm.storm.advice.animaldatagrowwaterguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.advice.isoanimalwideid.IsoAnimalWideIdAdvice;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;

/**
 * Advice for {@code AnimalData.grow(String)}.
 *
 * <p>On a water square the growth step is skipped: the {@code IsoAnimal} constructor bails before
 * {@code init(breed)} there, the replacement comes back with {@code data == null}, and vanilla's
 * next line NPEs every tick. The parent grows once it moves.
 *
 * <p>When another animal within the 8x8 box used by {@code checkForChickenpocalypse} shares the
 * parent's ID, the parent gets a fresh wide ID before the body runs. Vanilla's own check inside
 * {@code grow} would otherwise delete the parent and then the replacement, so the animal vanished
 * at every growth stage boundary. The babies' mother link follows the new ID.
 *
 * <p>Server only. Water deferrals log at most once per animal ID per {@link #LOG_INTERVAL_MS}. The
 * inlined body stays free of lambdas and streams; the scan lives in ordinary static helpers.
 */
public class AnimalDataGrowWaterGuardAdvice {

    public static final long LOG_INTERVAL_MS = 10L * 60L * 1000L;
    public static final ConcurrentHashMap<Integer, Long> LAST_LOG = new ConcurrentHashMap<>();

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.FieldValue("parent") IsoAnimal parent, @Advice.Argument(0) String newType) {
        if (!GameServer.server || parent == null) {
            return false;
        }
        if (parent.checkForWater()) {
            logWaterDeferral(parent, newType);
            return true;
        }
        reassignDuplicateId(parent, newType);
        return false;
    }

    public static void logWaterDeferral(IsoAnimal parent, String newType) {
        int id = parent.getAnimalID();
        long now = System.currentTimeMillis();
        Long last = LAST_LOG.get(id);
        if (last != null && now - last < LOG_INTERVAL_MS) {
            return;
        }
        LAST_LOG.put(id, now);
        LOGGER.warn(
                "AnimalDataGrowWaterGuardPatch: deferring grow to {} for animalId={} type={}"
                        + " at {},{},{} reason=water",
                newType,
                id,
                parent.getAnimalType(),
                parent.getXi(),
                parent.getYi(),
                parent.getZi());
    }

    public static void reassignDuplicateId(IsoAnimal parent, String newType) {
        try {
            IsoAnimal twin = findSameIdNeighbour(parent, parent.getAnimalID());
            if (twin == null) {
                return;
            }
            int oldId = parent.getAnimalID();
            int newId = IsoAnimalWideIdAdvice.allocate();
            while (findSameIdNeighbour(parent, newId) != null) {
                newId = IsoAnimalWideIdAdvice.allocate();
            }
            parent.setAnimalID(newId);
            ArrayList<IsoAnimal> babies = parent.getBabies();
            if (babies != null) {
                for (int i = 0; i < babies.size(); i++) {
                    IsoAnimal baby = babies.get(i);
                    if (baby != null && baby.motherId == oldId) {
                        baby.motherId = newId;
                    }
                }
            }
            LOGGER.warn(
                    "AnimalDataGrowWaterGuardPatch: animalId {} -> {} for {} growing to {} at"
                            + " {},{},{}; {} at {},{} shared the id",
                    oldId,
                    newId,
                    parent.getAnimalType(),
                    newType,
                    parent.getXi(),
                    parent.getYi(),
                    parent.getZi(),
                    twin.getAnimalType(),
                    twin.getXi(),
                    twin.getYi());
        } catch (Throwable t) {
            LOGGER.error("AnimalDataGrowWaterGuardPatch: duplicate id check failed", t);
        }
    }

    /** Same box as {@code IsoAnimal.checkForChickenpocalypse}, without the delete. */
    public static IsoAnimal findSameIdNeighbour(IsoAnimal parent, int id) {
        IsoGridSquare origin = parent.getSquare();
        if (origin == null) {
            return null;
        }
        for (int x = origin.getX() - 4; x < origin.getX() + 4; x++) {
            for (int y = origin.getY() - 4; y < origin.getY() + 4; y++) {
                IsoGridSquare sq = origin.getCell().getGridSquare(x, y, origin.getZ());
                if (sq == null) {
                    continue;
                }
                ArrayList<IsoAnimal> animals = sq.getAnimals();
                for (int i = 0; i < animals.size(); i++) {
                    IsoAnimal other = animals.get(i);
                    if (other != null && other != parent && other.getAnimalID() == id) {
                        return other;
                    }
                }
            }
        }
        return null;
    }
}
