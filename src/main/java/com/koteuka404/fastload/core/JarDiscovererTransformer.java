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

public final class JarDiscovererTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final String TARGET = "net.minecraftforge.fml.common.discovery.JarDiscoverer";
    private static final String METHOD_DESC = "(Lnet/minecraftforge/fml/common/discovery/ModCandidate;Lnet/minecraftforge/fml/common/discovery/ASMDataTable;)Ljava/util/List;";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET.equals(transformedName)) {
            return basicClass;
        }

        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(basicClass).accept(classNode, 0);

            MethodNode target = null;
            for (MethodNode method : classNode.methods) {
                if ("discover".equals(method.name) && METHOD_DESC.equals(method.desc)) {
                    target = method;
                    break;
                }
            }

            if (target == null) {
                LOGGER.error("Could not locate JarDiscoverer.discover; FastLoad cache is disabled for safety.");
                return basicClass;
            }

            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
            replacement.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/koteuka404/fastload/cache/FastJarScanner",
                    "discover",
                    METHOD_DESC,
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

            ClassWriter writer = new ClassWriter(0);
            classNode.accept(writer);
            LOGGER.info("Installed incremental cache hook into Forge JarDiscoverer.");
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.error("Failed to install JarDiscoverer hook; continuing with vanilla Forge discovery.", t);
            return basicClass;
        }
    }
}
