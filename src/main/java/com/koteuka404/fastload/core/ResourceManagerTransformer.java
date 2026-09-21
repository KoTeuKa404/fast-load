package com.koteuka404.fastload.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ResourceManagerTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");

    private static final String FALLBACK = "net.minecraft.client.resources.FallbackResourceManager";
    private static final String SIMPLE = "net.minecraft.client.resources.SimpleReloadableResourceManager";

    /*
     * Do not put Minecraft classes in helper descriptors here. The production
     * 1.12.2 jar is reobfuscated, while these descriptor strings are ordinary
     * string constants and are not remapped by ForgeGradle.
     */
    private static final String FAST_OPEN_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/io/InputStream;";
    private static final String FAST_EXISTS_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String RELOAD_RESOURCES_DESC = "(Ljava/util/List;)V";

    private static final String JAVA_FNFE = "java/io/FileNotFoundException";
    private static final String FAST_FNFE = "com/koteuka404/fastload/resource/FastFileNotFoundException";
    private static final String FAST_IO = "com/koteuka404/fastload/resource/FastResourceIO";

    private static final boolean KEEP_VANILLA_LEAK_TRACKING =
            Boolean.parseBoolean(System.getProperty("fastload.resourceLeakTracking", "false"));
    private static final boolean FAST_MISSING_EXCEPTIONS =
            Boolean.parseBoolean(System.getProperty("fastload.fastMissingResources", "true"));
    private static final boolean RESOURCE_EXISTS_CACHE =
            Boolean.parseBoolean(System.getProperty("fastload.resourceExistsCache", "true"));

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || (!FALLBACK.equals(transformedName) && !SIMPLE.equals(transformedName))) {
            return basicClass;
        }

        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(basicClass).accept(classNode, 0);

            int fastMissPatches = FAST_MISSING_EXCEPTIONS ? replaceFileNotFoundExceptions(classNode) : 0;
            int resourceExistsPatches = 0;
            boolean streamPatch = false;
            boolean invalidationPatch = false;

            if (FALLBACK.equals(transformedName)) {
                if (!KEEP_VANILLA_LEAK_TRACKING) {
                    streamPatch = replaceDebugLeakTracking(classNode);
                }
                if (RESOURCE_EXISTS_CACHE) {
                    resourceExistsPatches = replaceResourceExistsCalls(classNode);
                }
            } else if (SIMPLE.equals(transformedName) && RESOURCE_EXISTS_CACHE) {
                invalidationPatch = installReloadInvalidation(classNode);
            }

            if (!streamPatch && !invalidationPatch && fastMissPatches == 0 && resourceExistsPatches == 0) {
                return basicClass;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);

            if (streamPatch) {
                LOGGER.info("FastLoad v0.7: disabled vanilla per-resource debug leak stacktrace tracking.");
            }
            if (fastMissPatches > 0) {
                LOGGER.info("FastLoad v0.7: installed {} fast missing-resource exception site(s) in {}.",
                        fastMissPatches, transformedName);
            }
            if (resourceExistsPatches > 0) {
                LOGGER.info("FastLoad v0.7: cached {} resourceExists call site(s).", resourceExistsPatches);
            }
            if (invalidationPatch) {
                LOGGER.info("FastLoad v0.7: resource-existence cache will invalidate on every resource reload.");
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
            Type returnType = Type.getReturnType(method.desc);
            Type[] args = Type.getArgumentTypes(method.desc);

            boolean isStreamHelper =
                    "getInputStream".equals(method.name)
                    || "func_177245_a".equals(method.name);

            if (isStreamHelper
                    && (method.access & Opcodes.ACC_STATIC) == 0
                    && (method.access & Opcodes.ACC_ABSTRACT) == 0
                    && "java.io.InputStream".equals(returnType.getClassName())
                    && args.length == 2) {
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
                FAST_IO,
                "open",
                FAST_OPEN_DESC,
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

    private static int replaceResourceExistsCalls(ClassNode classNode) {
        int replacements = 0;

        for (Object methodObject : classNode.methods) {
            MethodNode method = (MethodNode) methodObject;

            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }

                MethodInsnNode invoke = (MethodInsnNode) insn;
                if (invoke.getOpcode() != Opcodes.INVOKEINTERFACE) {
                    continue;
                }

                Type returnType = Type.getReturnType(invoke.desc);
                Type[] args = Type.getArgumentTypes(invoke.desc);

                // Match only IResourcePack.resourceExists. In production 1.12.2
                // this is the SRG name func_110589_b; in a dev environment it is
                // resourceExists. Do not use a shape-only match here: List.contains
                // and other boolean interface methods have the same broad shape.
                boolean isResourceExists =
                        "resourceExists".equals(invoke.name)
                        || "func_110589_b".equals(invoke.name);

                if (isResourceExists
                        && Type.BOOLEAN_TYPE.equals(returnType)
                        && args.length == 1) {
                    invoke.setOpcode(Opcodes.INVOKESTATIC);
                    invoke.owner = FAST_IO;
                    invoke.name = "resourceExists";
                    invoke.desc = FAST_EXISTS_DESC;
                    invoke.itf = false;
                    replacements++;
                }
            }
        }

        return replacements;
    }

    private static boolean installReloadInvalidation(ClassNode classNode) {
        for (Object methodObject : classNode.methods) {
            MethodNode method = (MethodNode) methodObject;

            if (RELOAD_RESOURCES_DESC.equals(method.desc)
                    && (method.access & Opcodes.ACC_STATIC) == 0
                    && (method.access & Opcodes.ACC_PUBLIC) != 0) {
                InsnList prefix = new InsnList();
                prefix.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        FAST_IO,
                        "invalidateExistenceCache",
                        "()V",
                        false
                ));
                method.instructions.insert(prefix);
                return true;
            }
        }

        LOGGER.warn("Could not locate SimpleReloadableResourceManager reload method; resource-existence cache disabled for reload safety.");
        return false;
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
