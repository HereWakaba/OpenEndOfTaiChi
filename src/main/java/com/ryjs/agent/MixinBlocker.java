package com.ryjs.agent;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;


public final class MixinBlocker implements ClassFileTransformer {


    private final boolean enabled;

    public MixinBlocker(boolean enabled) {
        this.enabled = enabled;
    }


    public static void install(Instrumentation inst) {
        if (inst == null) return;
        try {
            if (!DefenseConfig.fullFilterMixin()) {
                System.out.println("Mixin 拦截未启用");
                return;
            }
            inst.addTransformer(new MixinBlocker(true), true);
            System.out.println("Mixin 拦截已注册");
        } catch (Throwable t) {
            System.err.println("Mixin 拦截注册失败: " + t);
        }
    }


    public static void preload() {
        Class<?>[] touch = { StripMixinClassVisitor.class, StripMethodVisitor.class, StripFieldVisitor.class };
        if (touch.length != 3) {
            throw new IllegalStateException("unreachable");
        }
        System.out.println("MixinBlocker preload 完成");
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain domain,
                            byte[] classfileBuffer) {
        if (!enabled || className == null) return null;

        if (!className.startsWith("com/ryjs/")
                && !className.startsWith("net/minecraftforge/")
                && !className.startsWith("org/spongepowered/")
                && !className.startsWith("cpw/mods/")
                && !className.startsWith("net/minecraft/")) {
            try {
                return stripMixinAnnotations(classfileBuffer);
            } catch (Throwable t) {
                System.err.println("[FullFilter] mixin 注解过滤失败 " + className + ": " + t);
                return null;
            }
        }
        return null;
    }

    private static byte[] stripMixinAnnotations(byte[] basicClass) {
        ClassReader cr = new ClassReader(basicClass);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new StripMixinClassVisitor(cw), 0);
        return cw.toByteArray();
    }


    private static final class StripMixinClassVisitor extends ClassVisitor {
        StripMixinClassVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.startsWith("Lorg/spongepowered/asm/mixin/")) return null;
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new StripMethodVisitor(mv);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
            return new StripFieldVisitor(fv);
        }
    }

    private static final class StripMethodVisitor extends MethodVisitor {
        StripMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (desc.startsWith("Lorg/spongepowered/asm/mixin/")) return null;
            return super.visitAnnotation(desc, visible);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
            if (desc.startsWith("Lorg/spongepowered/asm/mixin/")) return null;
            return super.visitParameterAnnotation(parameter, desc, visible);
        }
    }

    private static final class StripFieldVisitor extends FieldVisitor {
        StripFieldVisitor(FieldVisitor fv) {
            super(Opcodes.ASM9, fv);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (desc.startsWith("Lorg/spongepowered/asm/mixin/")) return null;
            return super.visitAnnotation(desc, visible);
        }
    }
}
