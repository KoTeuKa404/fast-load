package com.koteuka404.fastload.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class CraftTweakerTransformer implements net.minecraft.launchwrapper.IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final String TARGET = "crafttweaker.mc1120.proxies.ClientProxy";
    private static final String API_OWNER = "crafttweaker/CraftTweakerAPI";

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("fastload.skipCraftTweakerSearchTreeRebuild", "true"));

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
                if (!"fixRecipeBook".equals(method.name) || !"()V".equals(method.desc)) {
                    continue;
                }
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null;
                     instruction = instruction.getNext()) {
                    if (!(instruction instanceof FieldInsnNode)) {
                        continue;
                    }
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (field.getOpcode() == Opcodes.GETSTATIC
                            && API_OWNER.equals(field.owner)
                            && "ENABLE_SEARCH_TREE_RECALCULATION".equals(field.name)
                            && "Z".equals(field.desc)) {
                        method.instructions.set(instruction, new InsnNode(Opcodes.ICONST_0));
                        patched++;
                    }
                }
            }

            if (patched == 0) {
                LOGGER.warn("Could not locate CraftTweaker search-tree toggle; CraftTweaker remains unmodified.");
                return basicClass;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            LOGGER.info("FastLoad {}: deferred CraftTweaker search-tree rebuild to the final ModIdMapping step.",
                    com.koteuka404.fastload.FastLoad.VERSION);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.error("Failed to defer CraftTweaker search-tree rebuild; keeping original behavior.", t);
            return basicClass;
        }
    }
}
