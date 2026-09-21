package com.koteuka404.fastload.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ModelLoaderTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");

    private static final String REGISTRY = "net.minecraftforge.client.model.ModelLoaderRegistry";
    private static final String LOADER = "net.minecraftforge.client.model.ModelLoader";
    private static final String LOADER_EXCEPTION = "net.minecraftforge.client.model.ModelLoaderRegistry$LoaderException";

    private static final String MISSING_DESC =
            "(Lnet/minecraft/util/ResourceLocation;Ljava/lang/Throwable;)Lnet/minecraftforge/client/model/IModel;";
    private static final String CACHE_DESC = "Ljava/util/Map;";
    private static final String FAST_MODEL_EXCEPTION = "com/koteuka404/fastload/model/FastModelException";

    private static final boolean NEGATIVE_CACHE =
            Boolean.parseBoolean(System.getProperty("fastload.negativeModelCache", "true"));
    private static final boolean FAST_MODEL_EXCEPTIONS =
            Boolean.parseBoolean(System.getProperty("fastload.fastModelExceptions", "true"));

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null
                || (!REGISTRY.equals(transformedName)
                && !LOADER.equals(transformedName)
                && !LOADER_EXCEPTION.equals(transformedName))) {
            return basicClass;
        }

        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(basicClass).accept(classNode, 0);

            boolean changed = false;

            if (REGISTRY.equals(transformedName) && NEGATIVE_CACHE) {
                changed |= cacheMissingModels(classNode);
            }

            if (LOADER.equals(transformedName) && FAST_MODEL_EXCEPTIONS) {
                changed |= replaceModelWrapperExceptions(classNode) > 0;
            }

            if (LOADER_EXCEPTION.equals(transformedName) && FAST_MODEL_EXCEPTIONS) {
                changed |= installStacklessFillInStackTrace(classNode);
            }

            if (!changed) {
                return basicClass;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.error("Failed to optimize model loader {}; keeping Forge behavior.", transformedName, t);
            return basicClass;
        }
    }

    private static boolean cacheMissingModels(ClassNode classNode) {
        MethodNode target = null;

        for (Object methodObject : classNode.methods) {
            MethodNode method = (MethodNode) methodObject;
            if ("getMissingModel".equals(method.name) && MISSING_DESC.equals(method.desc)) {
                target = method;
                break;
            }
        }

        if (target == null) {
            LOGGER.warn("Could not locate ModelLoaderRegistry.getMissingModel(location,cause); negative model cache disabled.");
            return false;
        }

        int tempLocal = target.maxLocals++;
        int patchedReturns = 0;

        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.ARETURN) {
                continue;
            }

            InsnList cachePut = new InsnList();
            cachePut.add(new VarInsnNode(Opcodes.ASTORE, tempLocal));
            cachePut.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cachePut.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/koteuka404/fastload/model/FastModelStats",
                    "recordNegative",
                    "(Lnet/minecraft/util/ResourceLocation;)V",
                    false
            ));
            cachePut.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "net/minecraftforge/client/model/ModelLoaderRegistry",
                    "cache",
                    CACHE_DESC
            ));
            cachePut.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cachePut.add(new VarInsnNode(Opcodes.ALOAD, tempLocal));
            cachePut.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true
            ));
            cachePut.add(new InsnNode(Opcodes.POP));
            cachePut.add(new VarInsnNode(Opcodes.ALOAD, tempLocal));

            target.instructions.insertBefore(insn, cachePut);
            patchedReturns++;
        }

        if (patchedReturns == 0) {
            LOGGER.warn("ModelLoaderRegistry missing-model method had no ARETURN; negative cache disabled.");
            return false;
        }

        LOGGER.info("FastLoad v0.6: installed per-reload negative model cache.");
        return true;
    }

    private static int replaceModelWrapperExceptions(ClassNode classNode) {
        int replacements = 0;

        for (Object methodObject : classNode.methods) {
            MethodNode method = (MethodNode) methodObject;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof TypeInsnNode) {
                    TypeInsnNode type = (TypeInsnNode) insn;
                    if (type.getOpcode() == Opcodes.NEW && "java/lang/Exception".equals(type.desc)) {
                        type.desc = FAST_MODEL_EXCEPTION;
                        replacements++;
                    }
                } else if (insn instanceof MethodInsnNode) {
                    MethodInsnNode invoke = (MethodInsnNode) insn;
                    if (invoke.getOpcode() == Opcodes.INVOKESPECIAL
                            && "java/lang/Exception".equals(invoke.owner)
                            && "<init>".equals(invoke.name)
                            && "(Ljava/lang/String;Ljava/lang/Throwable;)V".equals(invoke.desc)) {
                        invoke.owner = FAST_MODEL_EXCEPTION;
                    }
                }
            }
        }

        if (replacements > 0) {
            LOGGER.info("FastLoad v0.6: installed {} stackless model wrapper exception site(s).", replacements);
        }
        return replacements;
    }

    private static boolean installStacklessFillInStackTrace(ClassNode classNode) {
        for (Object methodObject : classNode.methods) {
            MethodNode method = (MethodNode) methodObject;
            if ("fillInStackTrace".equals(method.name)
                    && "()Ljava/lang/Throwable;".equals(method.desc)) {
                return false;
            }
        }

        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
                "fillInStackTrace",
                "()Ljava/lang/Throwable;",
                null,
                null
        );
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        classNode.methods.add(method);

        LOGGER.info("FastLoad v0.6: made Forge ModelLoaderRegistry.LoaderException stackless.");
        return true;
    }
}
