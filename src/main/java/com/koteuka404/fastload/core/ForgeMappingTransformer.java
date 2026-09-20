package com.koteuka404.fastload.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ForgeMappingTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final String TARGET = "net.minecraftforge.common.ForgeModContainer";
    private static final String METHOD_DESC =
            "(Lnet/minecraftforge/fml/common/event/FMLModIdMappingEvent;)V";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET.equals(transformedName)) {
            return basicClass;
        }

        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(basicClass).accept(classNode, 0);

            MethodNode target = null;
            for (Object methodObject : classNode.methods) {
                MethodNode method = (MethodNode) methodObject;
                if ("mappingChanged".equals(method.name) && METHOD_DESC.equals(method.desc)) {
                    target = method;
                    break;
                }
            }

            if (target == null) {
                LOGGER.warn("Could not locate ForgeModContainer.mappingChanged; detailed ModIdMapping profiling is disabled.");
                return basicClass;
            }

            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
            replacement.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/koteuka404/fastload/profiler/ForgeMappingProfiler",
                    "mappingChanged",
                    METHOD_DESC,
                    false
            ));
            replacement.add(new InsnNode(Opcodes.RETURN));

            target.instructions.clear();
            target.instructions.add(replacement);
            target.tryCatchBlocks.clear();
            target.localVariables = null;
            target.visibleLocalVariableAnnotations = null;
            target.invisibleLocalVariableAnnotations = null;
            target.maxStack = 1;
            target.maxLocals = 2;

            ClassWriter writer = new ClassWriter(0);
            classNode.accept(writer);
            LOGGER.info("Installed detailed Forge ModIdMapping profiler.");
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.error("Failed to install Forge ModIdMapping profiler; keeping original Forge behavior.", t);
            return basicClass;
        }
    }
}
