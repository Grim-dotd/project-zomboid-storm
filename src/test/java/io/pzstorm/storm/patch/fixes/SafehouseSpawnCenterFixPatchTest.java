package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Vanilla {@code processServer} reads the safehouse as {@code getX, getH, getY, getW} for the spawn
 * point. The patch must swap the two extent reads and leave every other method alone.
 */
class SafehouseSpawnCenterFixPatchTest implements UnitTest {

    private static final String TARGET = "zombie/network/packets/character/CreatePlayerPacket";
    private static final String SAFE_HOUSE = "zombie/iso/areas/SafeHouse";

    @Test
    void patchSwapsTheExtentReadsInProcessServerOnly() throws Exception {
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(TARGET + ".class")) {
            assertNotNull(is, "CreatePlayerPacket.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }
        byte[] transformed = new SafehouseSpawnCenterFixPatch().transform(rawClass);

        assertEquals(List.of("getX", "getH", "getY", "getW"), safehouseGetters(rawClass, true));
        assertEquals(List.of("getX", "getW", "getY", "getH"), safehouseGetters(transformed, true));
        assertEquals(
                safehouseGetters(rawClass, false),
                safehouseGetters(transformed, false),
                "methods other than processServer are untouched");
    }

    private static List<String> safehouseGetters(byte[] classBytes, boolean inProcessServer) {
        List<String> calls = new ArrayList<>();
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
                                if ("processServer".equals(name) != inProcessServer) {
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
                                        if (SAFE_HOUSE.equals(owner) && "()I".equals(mDesc)) {
                                            calls.add(mName);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return calls;
    }
}
