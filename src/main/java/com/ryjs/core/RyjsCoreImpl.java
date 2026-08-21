package com.ryjs.core;

import com.ryjs.hook.transformer.LoaderAwareClassWriter;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;


public final class RyjsCoreImpl implements RyjsCore {


    private static final Map<String, byte[]> ORIGINAL = new ConcurrentHashMap<>();

    public RyjsCoreImpl() {
    }

    @Override
    public boolean isHidden() {
        return getClass().isHidden();
    }

    @Override
    public String describe() {
        return "impl=" + getClass().getName() + " hidden=" + getClass().isHidden()
                + " loader=" + getClass().getClassLoader();
    }

    @Override
    public boolean isMemoryEntity(Object entity) {
        if (!(entity instanceof Entity e)) {
            return false;
        }
        try {
            if (e.isAddedToWorld()) {
                return false;
            }
            if (e.level() == null) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isJarOriginalTarget(String className) {
        return "net/minecraftforge/server/command/EntityCommand$EntityListCommand".equals(className)
            || className.startsWith("com/ryjs/");
    }

    @Override
    public byte[] restoreBaseline(String className, ClassLoader loader) {
        byte[] bytes = originalBytes(className, loader);
        if (bytes == null) {
            return null;
        }
        if ("net/minecraftforge/server/command/EntityCommand$EntityListCommand".equals(className)) {
            return publicize(bytes, loader);
        }
        return bytes;
    }

    @Override
    public boolean isModified(byte[] current, byte[] baseline) {
        return !java.util.Arrays.equals(current, baseline);
    }

    @Override
    public boolean isSemanticallyModified(byte[] current, byte[] baseline) {
        if (java.util.Arrays.equals(current, baseline)) {
            return false;
        }
        try {
            ClassNode in = readNode(current);
            ClassNode base = readNode(baseline);
            if (!in.name.equals(base.name)
                    || !java.util.Objects.equals(in.superName, base.superName)
                    || !in.interfaces.equals(base.interfaces)) {
                return true;
            }
            if (fieldsDiff(in, base) || methodsDiff(in, base)) {
                return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }


    private static boolean fieldsDiff(ClassNode a, ClassNode b) {
        java.util.Set<String> ka = new java.util.HashSet<>();
        java.util.Set<String> kb = new java.util.HashSet<>();
        for (FieldNode f : a.fields) {
            ka.add(f.name + f.desc);
        }
        for (FieldNode f : b.fields) {
            kb.add(f.name + f.desc);
        }
        return !ka.equals(kb);
    }


    private static boolean methodsDiff(ClassNode a, ClassNode b) {
        java.util.Map<String, Integer> ma = new java.util.HashMap<>();
        java.util.Map<String, Integer> mb = new java.util.HashMap<>();
        for (MethodNode m : a.methods) {
            ma.put(m.name + m.desc, m.instructions.size());
        }
        for (MethodNode m : b.methods) {
            mb.put(m.name + m.desc, m.instructions.size());
        }
        if (!ma.keySet().equals(mb.keySet())) {
            return true;
        }
        for (java.util.Map.Entry<String, Integer> e : ma.entrySet()) {
            if (!mb.get(e.getKey()).equals(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static ClassNode readNode(byte[] bytes) {
        ClassNode node = new ClassNode(589824);
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }

   private static byte[] originalBytes(String internalName, ClassLoader loader) {
        byte[] cached = ORIGINAL.get(internalName);
        if (cached != null) {
            return cached;
        }
        try {
            String path = internalName + ".class";
            InputStream in = (loader != null)
                    ? loader.getResourceAsStream(path)
                    : ClassLoader.getSystemResourceAsStream(path);
            if (in == null) {
                return null;
            }
            byte[] bytes;
            try (InputStream is = in) {
                bytes = is.readAllBytes();
            }
            ORIGINAL.put(internalName, bytes);
            return bytes;
        } catch (Throwable t) {
            return null;
        }
    }


    private static byte[] publicize(byte[] bytes, ClassLoader loader) {
        try {
            ClassNode node = new ClassNode(589824);
            new ClassReader(bytes).accept(node, 0);
            node.access = (node.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            for (FieldNode f : node.fields) {
                f.access = (f.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC;
            }
            for (MethodNode m : node.methods) {
                m.access = (m.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC;
            }
            ClassWriter writer = new LoaderAwareClassWriter(ClassWriter.COMPUTE_MAXS, loader);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            return bytes;
        }
    }
}

