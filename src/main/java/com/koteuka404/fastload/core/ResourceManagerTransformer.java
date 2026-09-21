package com.koteuka404.fastload.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnNode;

public final class ResourceManagerTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");

    private static final String FALLBACK = "net.minecraft.client.resources.FallbackResourceManager";
    private static final String SIMPLE = "net.minecraft.client.resources.SimpleReloadableResourceManager";

    private static final String INPUT_STREAM_DESC =
            "(Lnet/minecraft/util/ResourceLocation;Lnet/minecraft/client/resources/IResourcePack;)Ljava/io/InputStream;";

    private static final String JAVA_FNFE = "java/io/FileNotFoundException";
    private static final String FAST_FNFE = "com/koteuka404/fastload/resource/FastFileNotFoundException";

    private static final boolean KEEP_VANILLA_LEAK_TRACKING =
            Boolean.parseBoolean(System.getProperty("fastload.resourceLeakTracking", "false"));
    private static final boolean FAST_MISSING_EXCEPTIONS =
            Boolean.parseBoolean(System.getProperty("fastload.fastMissingResources", "true"));

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || (!FALLBACK.equals(transformedName) && !SIMPLE.equals(transformedName))) {
            return basicClass;
        }

        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(basicClass).accept(classNode, 0);

            int fastMissPatches = FAST_MISSING_EXCEPTIONS ? replaceFileNotFoundExceptions(classNode) : 0;
            boolean streamPatch = false;

            if (FALLBACK.equals(transformedName) && !KEEP_VANILLA_LEAK_TRACKING) {
                streamPatch = replaceDebugLeakTracking(classNode);
            }

            if (!streamPatch && fastMissPatches == 0) {
                return basicClass;
            }

            ClassWriter writer = new ClassWriter(0);
            classNode.accept(writer);

            if (streamPatch) {
                LOGGER.info("FastLoad v0.5: disabled vanilla per-resource debug leak stacktrace tracking.");
            }
            if (fastMissPatches > 0) {
                LOGGER.info("FastLoad v0.5: installed {} fast missing-resource exception site(s) in {}.",
                        fastMissPatches, transformedName);
            }

            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.error("Failed to optimize resource manager {}; keeping vanilla behavior.", transformedName, t);
            return basicClass;
        }
    }

    private static boolean replaceDebugLeakTracking(ClassNode classNode) {
        MethodNode target = null;

        for (Object methodObject : classNode.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (INPUT_STREAM_DESC.equals(method.desc)
                    && (method.access & Opcodes.ACC_STATIC) == 0
                    && (method.access & Opcodes.ACC_ABSTRACT) == 0) {
                target = method;
                break;
            }
        }

        if (target == null) {
            LOGGER.warn("Could not locate FallbackResourceManager resource stream helper; leak-tracker optimization disabled.");
            return false;
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/koteuka404/fastload/resource/FastResourceIO",
                "open",
                INPUT_STREAM_DESC,
                false
        ));
        replacement.add(new InsnNode(Opcodes.ARETURN));

        target.instructions.clear();
        target.instructions.add(replacement);
        target.tryCatchBlocks.clear();
        target.localVariables = null;
        target.visibleLocalVariableAnnotations = null;
        target.invisibleLocalVariableAnnotations = null;
        target.maxStack = 2;
        target.maxLocals = 3;
        return true;
    }

    private static int replaceFileNotFoundExceptions(ClassNode classNode) {
        int replacements = 0;

        for (Object methodObject : classNode.methods) {
            MethodNode method = (MethodNode) methodObject;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof TypeInsnNode) {
                    TypeInsnNode type = (TypeInsnNode) insn;
                    if (type.getOpcode() == Opcodes.NEW && JAVA_FNFE.equals(type.desc)) {
                        type.desc = FAST_FNFE;
                        replacements++;
                    }
                } else if (insn instanceof MethodInsnNode) {
                    MethodInsnNode invoke = (MethodInsnNode) insn;
                    if (invoke.getOpcode() == Opcodes.INVOKESPECIAL
                            && JAVA_FNFE.equals(invoke.owner)
                            && "<init>".equals(invoke.name)) {
                        invoke.owner = FAST_FNFE;
                    }
                }
            }
        }

        return replacements;
    }
}
