package io.pzstorm.storm.patch.performance;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * Counts call sites in woven bytes so a call-site patch can refuse a weave whose shape no longer
 * matches the audited vanilla method. A relaxed member substitution matches nothing without
 * complaint, and a half-woven class would filter invalidations it cannot see.
 */
final class WeaveCheck {

    static final String FILTER =
            "io/pzstorm/storm/advice/cutawayinvalidate/CutawayInvalidationFilter";
    static final String SQUARE = "zombie/iso/IsoGridSquare";
    static final String RENDER_LEVELS = "zombie/iso/fboRenderChunk/FBORenderLevels";

    private WeaveCheck() {}

    /**
     * @param method name of the enclosing method, or {@code null} for the whole class
     * @param descriptor descriptor of the enclosing method, or {@code null} for any overload
     */
    static int count(
            byte[] classBytes, String method, String descriptor, String owner, String callee) {
        int[] count = {0};
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String desc,
                                    String signature,
                                    String[] exceptions) {
                                if (method != null && !method.equals(name)) {
                                    return null;
                                }
                                if (descriptor != null && !descriptor.equals(desc)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String insnOwner,
                                            String insnName,
                                            String insnDesc,
                                            boolean isInterface) {
                                        if (owner.equals(insnOwner) && callee.equals(insnName)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return count[0];
    }

    /**
     * Counts {@code PUTFIELD}/{@code PUTSTATIC} writes of {@code owner.field} like {@link #count}.
     */
    static int countFieldWrites(
            byte[] classBytes, String method, String descriptor, String owner, String field) {
        int[] count = {0};
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String desc,
                                    String signature,
                                    String[] exceptions) {
                                if (method != null && !method.equals(name)) {
                                    return null;
                                }
                                if (descriptor != null && !descriptor.equals(desc)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode,
                                            String insnOwner,
                                            String insnName,
                                            String insnDesc) {
                                        boolean write =
                                                opcode == Opcodes.PUTFIELD
                                                        || opcode == Opcodes.PUTSTATIC;
                                        if (write
                                                && owner.equals(insnOwner)
                                                && field.equals(insnName)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return count[0];
    }

    static void requireFieldWrites(
            byte[] classBytes,
            String method,
            String descriptor,
            String owner,
            String field,
            int expected) {
        int actual = countFieldWrites(classBytes, method, descriptor, owner, field);
        if (actual != expected) {
            throw new IllegalStateException(
                    "Expected "
                            + expected
                            + " write(s) to "
                            + owner
                            + "."
                            + field
                            + " in "
                            + (method == null ? "the class" : method)
                            + " but found "
                            + actual
                            + "; the vanilla method changed shape, so the patch is not applied");
        }
    }

    /**
     * True when {@code method} holds one {@code begin} and at least one {@code end} call on {@code
     * owner}.
     */
    static boolean bracketed(byte[] classBytes, String method, String owner) {
        return count(classBytes, method, null, owner, "begin") == 1
                && count(classBytes, method, null, owner, "end") >= 1
                && count(classBytes, null, null, owner, "begin") == 1;
    }

    static void require(
            byte[] classBytes,
            String method,
            String descriptor,
            String owner,
            String callee,
            int expected) {
        int actual = count(classBytes, method, descriptor, owner, callee);
        if (actual != expected) {
            throw new IllegalStateException(
                    "Expected "
                            + expected
                            + " call(s) to "
                            + owner
                            + "."
                            + callee
                            + " in "
                            + (method == null ? "the class" : method)
                            + " but found "
                            + actual
                            + "; the vanilla method changed shape, so the patch is not applied");
        }
    }
}
