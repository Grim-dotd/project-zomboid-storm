package io.pzstorm.storm.patch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ZomboidFileSystemPatch} injects the boot-mods substitution and the
 * server-first mod list hooks into {@code ZomboidFileSystem.loadMods(String)} and not into the
 * {@code loadMods(ArrayList)} overload both helpers call — a leak there would recurse.
 *
 * <p>Detection signal: the inlined advice calls {@code StormJoinPrewarm.substituteBootMods} and
 * {@code StormClientModList.loadForServer} via INVOKESTATIC; vanilla contains no such calls.
 */
class ZomboidFileSystemPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/ZomboidFileSystem";
    private static final String[][] HELPERS = {
        {"io/pzstorm/storm/client/StormJoinPrewarm", "substituteBootMods"},
        {"io/pzstorm/storm/client/StormClientModList", "loadForServer"},
    };

    private static final String TARGET_METHOD = "loadMods";
    private static final String TARGET_DESC = "(Ljava/lang/String;)V";
    private static final String SIBLING_DESC = "(Ljava/util/ArrayList;)V";

    @Test
    void patchInjectsPrewarmIntoProfileLoadModsOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new ZomboidFileSystemPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        for (String[] helper : HELPERS) {
            String owner = helper[0];
            String method = helper[1];
            assertEquals(
                    0,
                    countHelperCalls(rawClass, TARGET_METHOD, TARGET_DESC, owner, method),
                    "Vanilla loadMods(String) must not already call " + owner);
            assertTrue(
                    countHelperCalls(transformed, TARGET_METHOD, TARGET_DESC, owner, method) >= 1,
                    "Patched loadMods(String) must contain >=1 INVOKESTATIC "
                            + owner
                            + "."
                            + method);
            assertEquals(
                    0,
                    countHelperCalls(transformed, TARGET_METHOD, SIBLING_DESC, owner, method),
                    "Advice must not leak into loadMods(ArrayList) — the helpers call it");
        }
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countHelperCalls(
            byte[] classBytes, String method, String desc, String helperOwner, String helperName) {
        int[] hits = new int[1];
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (!method.equals(name) || !desc.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && helperOwner.equals(owner)
                                                && helperName.equals(mName)) {
                                            hits[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return hits[0];
    }
}
