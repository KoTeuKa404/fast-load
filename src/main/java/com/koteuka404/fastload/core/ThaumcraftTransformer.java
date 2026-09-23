package com.koteuka404.fastload.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class ThaumcraftTransformer implements net.minecraft.launchwrapper.IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final String TARGET = "thaumcraft.common.lib.research.ResearchManager";
    private static final String FAST_IO = "com/koteuka404/fastload/resource/FastResourceIO";
    private static final String FAST_OPEN_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/io/InputStream;";

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("fastload.researchClasspathCache", "true"));

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!ENABLED || basicClass == null || !TARGET.equals(transformedName)) {
            return basicClass;
        }

        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(basicClass).accept(classNode, 0);
            int patched = 0;

            for (Object methodObject : classNode.methods) {
                MethodNode method = (MethodNode) methodObject;
                if (!"parseAllResearch".equals(method.name) || !"()V".equals(method.desc)) {
                    continue;
                }
                for (org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null;
                     instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode)) {
                        continue;
                    }
                    MethodInsnNode invoke = (MethodInsnNode) instruction;
                    if (invoke.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "java/lang/Class".equals(invoke.owner)
                            && "getResourceAsStream".equals(invoke.name)
                            && "(Ljava/lang/String;)Ljava/io/InputStream;".equals(invoke.desc)) {
                        invoke.setOpcode(Opcodes.INVOKESTATIC);
                        invoke.owner = FAST_IO;
                        invoke.name = "openClasspathResource";
                        invoke.desc = FAST_OPEN_DESC;
                        invoke.itf = false;
                        patched++;
                    }
                }
            }

            if (patched == 0) {
                LOGGER.warn("Could not locate Thaumcraft research resource lookup; direct research cache disabled.");
                return basicClass;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            LOGGER.info("FastLoad {}: redirected {} Thaumcraft research classpath lookup(s) to the persistent index.",
                    com.koteuka404.fastload.FastLoad.VERSION, patched);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.error("Failed to install Thaumcraft research resource cache; keeping original behavior.", t);
            return basicClass;
        }
    }
}
