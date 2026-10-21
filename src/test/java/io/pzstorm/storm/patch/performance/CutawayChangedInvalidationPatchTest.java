package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Checks the weave of {@link CutawayChangedInvalidationPatch} and {@link CutawayFlagClearTagPatch}
 * against the vanilla class files, and pins the set of cutaway flag writers the change-detection
 * audit relies on. A failure here after a PZ update means the audit has to be redone.
 *
 * <p>Uses ByteBuddy's bundled ASM because the standalone ASM test dependency cannot read the game's
 * class file version.
 */
class CutawayChangedInvalidationPatchTest implements UnitTest {

    private static final String CUTAWAYS = "zombie/iso/fboRenderChunk/FBORenderCutaways";
    private static final String LEVELS_DATA = CUTAWAYS + "$ChunkLevelsData";
    private static final String FILTER = WeaveCheck.FILTER;
    private static final String SQUARE = WeaveCheck.SQUARE;
    private static final String RENDER_LEVELS = WeaveCheck.RENDER_LEVELS;
    private static final String FAST_PATH =
            "io/pzstorm/storm/advice/cutawayvisit/CutawayVisitFastPath";

    private static final String VISIT = "doCutawayVisitSquares";
    private static final String PUBLIC_VISIT = "(ILjava/util/ArrayList;)V";
    private static final String PRIVATE_VISIT =
            "(Lzombie/iso/IsoGridSquare;JLjava/util/ArrayList;)V";

    @Test
    void publicOverloadRoutesItsWritesAndInvalidationsThroughTheFilter() throws Exception {
        byte[] woven = new CutawayChangedInvalidationPatch().transform(read(CUTAWAYS));

        assertEquals(1, count(woven, VISIT, PUBLIC_VISIT, FILTER, "begin"));
        assertEquals(1, count(woven, VISIT, PUBLIC_VISIT, FILTER, "end"));
        assertEquals(1, count(woven, VISIT, PUBLIC_VISIT, FILTER, "setFlag"));
        assertEquals(2, count(woven, VISIT, PUBLIC_VISIT, FILTER, "addFlag"));
        assertEquals(3, count(woven, VISIT, PUBLIC_VISIT, FILTER, "ownInvalidate"));
        assertEquals(0, count(woven, VISIT, PUBLIC_VISIT, SQUARE, "setPlayerCutawayFlag"));
        assertEquals(0, count(woven, VISIT, PUBLIC_VISIT, SQUARE, "addPlayerCutawayFlag"));
        assertEquals(0, count(woven, VISIT, PUBLIC_VISIT, RENDER_LEVELS, "invalidateLevel"));
    }

    @Test
    void publicOverloadKeepsEveryOtherVanillaCall() throws Exception {
        byte[] raw = read(CUTAWAYS);
        byte[] woven = new CutawayChangedInvalidationPatch().transform(raw);

        Map<String, Integer> expected = calls(raw, VISIT, PUBLIC_VISIT);
        assertEquals(1, expected.remove(SQUARE + ".setPlayerCutawayFlag(IIJ)V"));
        assertEquals(2, expected.remove(SQUARE + ".addPlayerCutawayFlag(IIJ)V"));
        assertEquals(3, expected.remove(RENDER_LEVELS + ".invalidateLevel(IJ)V"));
        assertEquals(4, expected.get(CUTAWAYS + ".invalidateChunk(ILzombie/iso/IsoChunkMap;III)V"));
        assertEquals(
                1, expected.get(LEVELS_DATA + ".invalidateOccludedSquaresMaskForSeenRooms(II)V"));

        Map<String, Integer> actual = calls(woven, VISIT, PUBLIC_VISIT);
        actual.keySet().removeIf(call -> call.startsWith(FILTER + "."));
        assertEquals(expected, actual);
    }

    @Test
    void privateOverloadIsUntouched() throws Exception {
        byte[] raw = read(CUTAWAYS);
        byte[] woven = new CutawayChangedInvalidationPatch().transform(raw);

        Map<String, Integer> vanilla = calls(raw, VISIT, PRIVATE_VISIT);
        assertTrue(!vanilla.isEmpty(), "the private overload must exist");
        assertEquals(vanilla, calls(woven, VISIT, PRIVATE_VISIT));
    }

    @Test
    void writersOutsideTheCallBumpTheEpoch() throws Exception {
        byte[] woven = new CutawayChangedInvalidationPatch().transform(read(CUTAWAYS));

        assertEquals(1, count(woven, "invalidateChunk", null, FILTER, "edgeInvalidate"));
        assertEquals(1, count(woven, "checkExteriorWalls", null, FILTER, "externalInvalidate"));
        assertEquals(1, count(woven, "checkSlopedSurfaces", null, FILTER, "externalInvalidate"));
        assertEquals(4, count(woven, "checkOrphanStructures", null, FILTER, "externalInvalidate"));
        assertEquals(1, count(woven, "squareChanged", null, FILTER, "onSquareChanged"));
        assertEquals(1, count(woven, "checkPlayerRoom", null, FILTER, "onViewpointCheck"));

        assertEquals(1, count(woven, "checkOccludedRooms", null, RENDER_LEVELS, "invalidateLevel"));
        assertEquals(1, count(woven, null, null, RENDER_LEVELS, "invalidateLevel"));
        assertEquals(6, count(woven, null, null, FILTER, "externalInvalidate"));
    }

    @Test
    void composesWithTheVisitFastPathInEitherOrder() throws Exception {
        byte[] raw = read(CUTAWAYS);
        byte[] filterLast =
                new CutawayChangedInvalidationPatch()
                        .transform(new CutawayVisitFastPathPatch().transform(raw));
        byte[] filterFirst =
                new CutawayVisitFastPathPatch()
                        .transform(new CutawayChangedInvalidationPatch().transform(raw));

        for (byte[] woven : new byte[][] {filterLast, filterFirst}) {
            assertEquals(1, count(woven, "cutawayVisit", null, FAST_PATH, "visit"));
            assertEquals(3, count(woven, VISIT, PUBLIC_VISIT, FILTER, "ownInvalidate"));
            assertEquals(1, count(woven, VISIT, PUBLIC_VISIT, FILTER, "begin"));
            assertEquals(1, count(woven, VISIT, PUBLIC_VISIT, FILTER, "end"));
        }
    }

    @Test
    void secondWeaveIsRefusedBecauseTheShapeNoLongerMatches() throws Exception {
        byte[] woven = new CutawayChangedInvalidationPatch().transform(read(CUTAWAYS));

        assertThrows(
                IllegalStateException.class,
                () -> new CutawayChangedInvalidationPatch().transform(woven));
    }

    @Test
    void clearWritersAreTaggedAndCompose() throws Exception {
        byte[] raw = read(LEVELS_DATA);
        byte[] tagLast =
                new CutawayFlagClearTagPatch()
                        .transform(new CutawayLevelDataArrayCachePatch().transform(raw));
        byte[] tagFirst =
                new CutawayLevelDataArrayCachePatch()
                        .transform(new CutawayFlagClearTagPatch().transform(raw));

        for (byte[] woven : new byte[][] {tagLast, tagFirst}) {
            assertEquals(
                    1, count(woven, "clearPlayerCutawayFlags", null, FILTER, "externalInvalidate"));
            assertEquals(
                    1,
                    count(woven, "clearPlayerCutawayFlags2", null, FILTER, "externalInvalidate"));
            assertEquals(0, count(woven, null, null, RENDER_LEVELS, "invalidateLevel"));
            Map<String, Integer> fields = fields(woven);
            assertEquals(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    fields.get("storm$cutawayWriteTagged")
                            & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            assertNotNull(fields.get("storm$levelCache"));
        }
    }

    /**
     * The filter sees a flag write only through the sites this test lists. A new writer in any
     * class under {@code FBORenderCutaways} would change flags behind its back.
     */
    @Test
    void flagWritersMatchTheAudit() throws Exception {
        Map<String, Integer> writers = new TreeMap<>();
        List<String> classes = nestedClassSuffixes(read(CUTAWAYS));
        assertTrue(classes.contains("$CutawayWall") && classes.contains("$SlopedSurface"));
        classes.add("");
        for (String nested : classes) {
            byte[] bytes = read(CUTAWAYS + nested);
            for (String callee :
                    new String[] {
                        "setPlayerCutawayFlag", "addPlayerCutawayFlag", "clearPlayerCutawayFlag"
                    }) {
                collectCallers(bytes, nested, SQUARE, callee, writers);
            }
        }

        Map<String, Integer> expected = new TreeMap<>();
        expected.put(".doCutawayVisitSquares>setPlayerCutawayFlag", 1);
        expected.put(".doCutawayVisitSquares>addPlayerCutawayFlag", 2);
        expected.put("$CutawayWall.setPlayerCutawayFlag>addPlayerCutawayFlag", 2);
        expected.put("$CutawayWall.setPlayerCutawayFlag>clearPlayerCutawayFlag", 2);
        expected.put("$SlopedSurface.setPlayerCutawayFlag>addPlayerCutawayFlag", 2);
        expected.put("$SlopedSurface.setPlayerCutawayFlag>clearPlayerCutawayFlag", 2);
        assertEquals(expected, writers);
    }

    /**
     * Defines the woven classes in an isolated loader and links them, which runs the bytecode
     * verifier over every woven call site without executing any game initialiser.
     */
    @Test
    void wovenClassesPassBytecodeVerification() throws Exception {
        Map<String, byte[]> woven = new HashMap<>();
        woven.put(
                CUTAWAYS.replace('/', '.'),
                new CutawayChangedInvalidationPatch()
                        .transform(new CutawayVisitFastPathPatch().transform(read(CUTAWAYS))));
        woven.put(
                LEVELS_DATA.replace('/', '.'),
                new CutawayFlagClearTagPatch()
                        .transform(
                                new CutawayLevelDataArrayCachePatch()
                                        .transform(read(LEVELS_DATA))));

        ClassLoader parent = getClass().getClassLoader();
        ClassLoader loader =
                new ClassLoader(parent) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.startsWith(CUTAWAYS.replace('/', '.'))) {
                            return super.loadClass(name, resolve);
                        }
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> loaded = findLoadedClass(name);
                            if (loaded != null) {
                                return loaded;
                            }
                            byte[] bytes = woven.get(name);
                            if (bytes == null) {
                                try {
                                    bytes = read(name.replace('.', '/'));
                                } catch (IOException e) {
                                    throw new ClassNotFoundException(name, e);
                                }
                            }
                            return defineClass(name, bytes, 0, bytes.length);
                        }
                    }
                };

        Class<?> cutaways = Class.forName(CUTAWAYS.replace('/', '.'), false, loader);
        assertTrue(cutaways.getDeclaredMethods().length > 0);
        Class<?> levelsData = Class.forName(LEVELS_DATA.replace('/', '.'), false, loader);
        assertTrue(levelsData.getDeclaredMethods().length > 0);
        assertTrue(
                Modifier.isStatic(levelsData.getField("storm$cutawayWriteTagged").getModifiers()));
    }

    private byte[] read(String internalName) throws IOException {
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int count(
            byte[] bytes, String method, String descriptor, String owner, String callee) {
        return WeaveCheck.count(bytes, method, descriptor, owner, callee);
    }

    /** Histogram of {@code owner.name+descriptor} for every call made by one method. */
    private static Map<String, Integer> calls(byte[] bytes, String method, String descriptor) {
        Map<String, Integer> calls = new TreeMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String desc,
                                    String signature,
                                    String[] exceptions) {
                                if (!method.equals(name) || !descriptor.equals(desc)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String callee,
                                            String calleeDesc,
                                            boolean isInterface) {
                                        calls.merge(
                                                owner + "." + callee + calleeDesc, 1, Integer::sum);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return calls;
    }

    private static void collectCallers(
            byte[] bytes,
            String classLabel,
            String owner,
            String callee,
            Map<String, Integer> out) {
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String desc,
                                    String signature,
                                    String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String insnOwner,
                                            String insnName,
                                            String insnDesc,
                                            boolean isInterface) {
                                        if (owner.equals(insnOwner) && callee.equals(insnName)) {
                                            out.merge(
                                                    classLabel + "." + name + ">" + callee,
                                                    1,
                                                    Integer::sum);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    }

    private static List<String> nestedClassSuffixes(byte[] outerBytes) {
        List<String> suffixes = new ArrayList<>();
        new ClassReader(outerBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitInnerClass(
                                    String name, String outerName, String innerName, int access) {
                                if (name.startsWith(CUTAWAYS + "$")) {
                                    suffixes.add(name.substring(CUTAWAYS.length()));
                                }
                            }
                        },
                        ClassReader.SKIP_CODE);
        return suffixes;
    }

    private static Map<String, Integer> fields(byte[] bytes) {
        Map<String, Integer> fields = new HashMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public net.bytebuddy.jar.asm.FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Object value) {
                                fields.put(name, access);
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE);
        return fields;
    }
}
