package io.pzstorm.storm.patch.fixes;

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
 * Verifies that {@link IsoAnimalWideIdPatch} injects the wide-ID draw into {@code
 * IsoAnimal.init(AnimalBreed)} and only there.
 *
 * <p>Detection signal: the inlined advice calls the advice class's {@code allocate()} helper.
 * Vanilla {@code init} never calls it; {@code initType}, which also takes the breed, must stay
 * untouched.
 */
class IsoAnimalWideIdPatchTest implements UnitTest {

    private static final String ISO_ANIMAL = "zombie/characters/animals/IsoAnimal";
    private static final String ADVICE =
            "io/pzstorm/storm/advice/isoanimalwideid/IsoAnimalWideIdAdvice";
    private static final String ALLOCATE = "allocate";

    private static final String BREED_DESC = "(Lzombie/characters/animals/datas/AnimalBreed;)V";
    private static final String TARGET_METHOD = "init";
    private static final String SIBLING_METHOD = "initType";

    @Test
    void patchInjectsAdviceIntoInitOnly() throws Exception {
        byte[] rawClass = readClassBytes(ISO_ANIMAL + ".class");
        byte[] transformed = new IsoAnimalWideIdPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(0, countAllocateCalls(rawClass, TARGET_METHOD, BREED_DESC));
        assertTrue(
                countAllocateCalls(transformed, TARGET_METHOD, BREED_DESC) >= 1,
                "Patched init must call the wide-id allocator");
        assertEquals(0, countAllocateCalls(rawClass, SIBLING_METHOD, BREED_DESC));
        assertEquals(
                0,
                countAllocateCalls(transformed, SIBLING_METHOD, BREED_DESC),
                "Advice must not leak into initType");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countAllocateCalls(byte[] classBytes, String method, String desc) {
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
                                        if (ADVICE.equals(owner) && ALLOCATE.equals(mName)) {
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
