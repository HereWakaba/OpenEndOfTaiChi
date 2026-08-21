package com.ryjs.coremod.Agent.transformers;

import com.ryjs.coremod.ImmediateWindowProvider.EartyLoading;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AllReturn implements ClassFileTransformer {
    private static final String ENTITY_INTERNAL = "net/minecraft/world/entity/Entity";
    private static final String ITEM_INTERNAL = "net/minecraft/world/item/Item";
    private static final String RENDERER_INTERNAL = "net/minecraft/client/renderer/entity/EntityRenderer";
    private static final String MODEL_INTERNAL = "net/minecraft/client/model/EntityModel";
    private final Map<String, ClassInfo> classInfoCache = new ConcurrentHashMap<>();
    private static final String ALLRETURNUTIL_CLASS_NAME = "com.ryjs.agent.AllReturnUtil";
    public static final Set<String> transformedClassName = new HashSet<>();
    private static final Set<String> REGISTRATION_EVENT_TYPES = new HashSet<>(Arrays.asList(
            "Lnet/minecraftforge/registries/RegisterEvent;",
            "Lnet/minecraftforge/registries/NewRegistryEvent;",
            "Lnet/minecraftforge/client/event/EntityRenderersEvent$RegisterRenderers;",
            "Lnet/minecraftforge/client/event/EntityRenderersEvent$RegisterLayerDefinitions;",
            "Lnet/minecraftforge/client/event/EntityRenderersEvent$AddLayers;",
            "Lnet/minecraftforge/client/event/EntityRenderersEvent$CreateSkullModels;",
            "Lnet/minecraftforge/client/event/ModelEvent$RegisterAdditional;",
            "Lnet/minecraftforge/client/event/RegisterColorHandlersEvent$Item;",
            "Lnet/minecraftforge/client/event/RegisterColorHandlersEvent$Block;",
            "Lnet/minecraftforge/client/event/RegisterShadersEvent;",
            "Lnet/minecraftforge/event/BuildCreativeModeTabContentsEvent;",
            "Lnet/minecraftforge/event/entity/EntityAttributeCreationEvent;",
            "Lnet/minecraftforge/event/entity/EntityAttributeModificationEvent;",
            "Lnet/minecraftforge/event/entity/SpawnPlacementRegisterEvent;",
            "Lnet/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent;"
    ));
    
    private static final Set<String> REGISTRATION_CALLS = new HashSet<>(Arrays.asList(
            "net/minecraftforge/registries/DeferredRegister.register(Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/minecraftforge/registries/RegistryObject;",
            "net/minecraftforge/registries/DeferredRegister.register(Lnet/minecraftforge/eventbus/api/IEventBus;)V",
            "net/minecraftforge/registries/RegisterEvent.register(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceLocation;Ljava/util/function/Supplier;)V",
            "net/minecraftforge/registries/RegisterEvent.register(Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Consumer;)V",
            "net/minecraftforge/registries/RegisterEvent$RegisterHelper.register(Ljava/lang/String;Ljava/lang/Object;)V",
            "net/minecraftforge/registries/RegisterEvent$RegisterHelper.register(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;)V",
            "net/minecraftforge/registries/RegisterEvent$RegisterHelper.register(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)V",
            "net/minecraft/world/entity/EntityType$Builder.build(Ljava/lang/String;)Lnet/minecraft/world/entity/EntityType;",
            "net/minecraft/client/renderer/entity/EntityRenderers.register(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/client/renderer/entity/EntityRenderers$EntityRendererProvider;)V",
            "net/minecraft/client/renderer/blockentity/BlockEntityRenderers.register(Lnet/minecraft/world/level/block/entity/BlockEntityType;Lnet/minecraft/client/renderer/blockentity/BlockEntityRendererProvider;)V",
            "net/minecraftforge/client/event/EntityRenderersEvent$RegisterRenderers.registerEntityRenderer(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/client/renderer/entity/EntityRendererProvider;)V",
            "net/minecraftforge/client/event/EntityRenderersEvent$RegisterRenderers.registerBlockEntityRenderer(Lnet/minecraft/world/level/block/entity/BlockEntityType;Lnet/minecraft/client/renderer/blockentity/BlockEntityRendererProvider;)V",
            "net/minecraft/client/renderer/ItemBlockRenderTypes.setRenderLayer(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/client/renderer/RenderType;)V",
            "net/minecraft/client/renderer/ItemBlockRenderTypes.setRenderLayer(Lnet/minecraft/world/level/block/Block;Ljava/util/function/Predicate;)V",
            "net/minecraft/client/renderer/ItemBlockRenderTypes.setRenderLayer(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/client/renderer/ChunkRenderTypeSet;)V",
            "net/minecraft/client/renderer/ItemBlockRenderTypes.setRenderLayer(Lnet/minecraft/world/level/fluid/Fluid;Lnet/minecraft/client/renderer/RenderType;)V",
            "net/minecraftforge/client/event/EntityRenderersEvent$RegisterLayerDefinitions.registerLayerDefinition(Lnet/minecraft/client/model/geom/ModelLayerLocation;Ljava/util/function/Supplier;)V",
            "net/minecraftforge/client/event/ModelEvent$RegisterAdditional.register(Lnet/minecraft/resources/ResourceLocation;)V"
    ));

    private static int transformCallCount = 0;
    private static int transformedCount = 0;
    private static int skippedCount = 0;
    private static volatile boolean firstGateSkipLogged = false;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;
        if (!com.ryjs.agent.DefenseConfig.interceptAllReturn()) {
            return null;
        }
        
        if (!EartyLoading.targetClasses.contains(className)) {
            return null;
        }

        String simpleClassName = className.contains("/")
                ? className.substring(className.lastIndexOf('/') + 1)
                : className;
        if (simpleClassName.startsWith("__")) return null;

        if (com.ryjs.agent.CompatWhitelist.isWhitelistedClass(className)) return null;
        try {
            Class.forName(ALLRETURNUTIL_CLASS_NAME, false, loader);
        } catch (Throwable e) {
            if (!firstGateSkipLogged) {
                firstGateSkipLogged = true;
                System.out.println("目标类加载器看不到AllReturnUtil，该类跳过: " + className);
            }
            return null;
        }

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, ClassReader.EXPAND_FRAMES);
            ClassInfo info = analyzeClassHierarchy(classNode, loader);
            String rendererCheck = className.substring(className.lastIndexOf('/') + 1);
            boolean isRenderClass = info.ancestorClasses.contains(RENDERER_INTERNAL)
                    || info.ancestorClasses.contains(MODEL_INTERNAL)
                    || rendererCheck.endsWith("Renderer")
                    || rendererCheck.endsWith("Model")
                    || rendererCheck.endsWith("Overlay");
            if (isMixinClass(classNode)) {
                return null;
            }

            Map<String, String> decisions = new HashMap<>();
            boolean isEntityOrItem = info.ancestorClasses.contains(ENTITY_INTERNAL)
                    || info.ancestorClasses.contains(ITEM_INTERNAL);

            Set<String> ctorCalledByInit = new HashSet<>();
            if (isEntityOrItem) {
                for (MethodNode m : classNode.methods) {
                    if ("<init>".equals(m.name) && m.instructions != null) {
                        for (AbstractInsnNode insn : m.instructions) {
                            if (insn instanceof MethodInsnNode) {
                                MethodInsnNode mi = (MethodInsnNode) insn;
                                if (classNode.name.equals(mi.owner) && !"<init>".equals(mi.name)) {
                                    ctorCalledByInit.add(mi.name + mi.desc);
                                }
                            }
                        }
                    }
                }
            }
            for (MethodNode method : classNode.methods) {
                if (isSpecialMethod(method)) continue;
                String key = method.name + method.desc;
                if (isRegistrationMethod(method)) continue;
                if (isRenderMethod(method)) continue;

                if (isRenderClass && isRenderFamilyMethod(method)) continue;
                if (isEntityOrItem) {

                    if (info.superMethods.contains(key)) {
                        decisions.put(key, "DELETE");
                        continue;
                    }

                    if (ctorCalledByInit.contains(key)) continue;

                    if (!info.superMethods.contains(key) && info.interfaceMethods.contains(key)) continue;

                    if ((method.access & Opcodes.ACC_STATIC) != 0) {
                        if ("()Lnet/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder;".equals(method.desc)) {
                            String attrOwner = !info.vanillaAttrSupplierOwners.isEmpty()
                                    ? info.vanillaAttrSupplierOwners.iterator().next()
                                    : "net/minecraft/world/entity/Mob";
                            decisions.put(key, "RESET_ATTR:" + attrOwner);
                        } else {
                            Type staticRt = Type.getReturnType(method.desc);
                            if (isTargetReturnType(staticRt)) {
                                decisions.put(key, "DIRECT_EMPTY");
                            }
                        }
                        continue;
                    }

                    if (info.synchedEntityDataDefineMethods.contains(key)) continue;

                    if (info.superMethods.contains(key)) {
                        decisions.put(key, "DELETE");
                        continue;
                    }
                    Type customRt = Type.getReturnType(method.desc);
                    if (isTargetReturnType(customRt)) {
                        decisions.put(key, "DIRECT_EMPTY");
                    }
                    continue;
                } else if (isOverrideMethod(classNode, method, info)) {
                    String owner = resolveSuperTarget(classNode, method, info);
                    if (owner == null) {
                        Type rt = Type.getReturnType(method.desc);
                        if (isTargetReturnType(rt)) {
                            decisions.put(key, "DIRECT_EMPTY");
                        }
                        continue;
                    }
                    decisions.put(key, "DIRECT_SUPER:" + owner);
                } else {
                    Type returnType = Type.getReturnType(method.desc);
                    if (!isTargetReturnType(returnType)) continue;
                    decisions.put(key, "DIRECT_EMPTY");
                }
            }

            if (decisions.isEmpty()) {
                return null;
            }
            
            com.ryjs.asm.SafeClassWriter writer = new com.ryjs.asm.SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            writer.setTargetLoader(loader);
            ClassVisitor cv = new DispatchClassVisitor(writer, decisions, className);
            reader.accept(cv, 0); // 不 EXPAND_FRAMES，触发未改动方法的逐字复制优化
            return writer.toByteArray();

        } catch (Throwable e) {
            System.err.println(" ERROR transforming " + className + ": " + e.getClass().getName() + " - " + e.getMessage());
            if (e instanceof NoClassDefFoundError || e instanceof ClassNotFoundException) {
                System.err.println(">>> 依赖缺失! AllReturn ClassLoader = " + AllReturn.class.getClassLoader());
            }
        }

        return null;
    }

        private String resolveSuperTarget(ClassNode classNode, MethodNode method, ClassInfo info) {
        String superOwner = classNode.superName;
        if (superOwner == null) superOwner = "java/lang/Object";
        if ("java/lang/Object".equals(superOwner) && !isRealObjectMethod(method.name, method.desc)) {
            return null;
        }
        if (info != null) {
            String methodKey = method.name + method.desc;
            if (!info.superMethods.contains(methodKey) && !isRealObjectMethod(method.name, method.desc)) {
                return null;
            }

            if (info.abstractMethods.contains(methodKey)) {
                return null;
            }
        }
        return superOwner;
    }


    private static final class DispatchClassVisitor extends ClassVisitor {
        private final Map<String, String> decisions;
        private final String className;

        DispatchClassVisitor(ClassVisitor writer, Map<String, String> decisions, String className) {
            super(Opcodes.ASM9, writer);
            this.decisions = decisions;
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {

            if ("DELETE".equals(decisions.get(name + descriptor))) {
                return null;
            }
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) {
                return null;
            }

            if (!hasRegistrationEventParam(descriptor)) {
                mv = new SubscribeEventFilterMethodVisitor(mv);
            }
            String dec = decisions.get(name + descriptor);
            if (dec == null) {
                return mv; // 未命中：逐字复制，保留原帧
            }
            transformedClassName.add(className);
            return new InjectMethodVisitor(mv, access, name, descriptor, dec);
        }
    }

    private static boolean hasRegistrationEventParam(String descriptor) {
        for (Type at : Type.getArgumentTypes(descriptor)) {
            if (REGISTRATION_EVENT_TYPES.contains(at.getDescriptor())) {
                return true;
            }
        }
        return false;
    }

    private static final class SubscribeEventFilterMethodVisitor extends MethodVisitor {
        SubscribeEventFilterMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (visible && "Lnet/minecraftforge/eventbus/api/SubscribeEvent;".equals(descriptor)) {
                return null; // 丢弃 @SubscribeEvent
            }
            return super.visitAnnotation(descriptor, visible);
        }
    }


    public static void preload() {
        Class<?>[] touch = { ClassInfo.class, InjectMethodVisitor.class, DispatchClassVisitor.class,
                SubscribeEventFilterMethodVisitor.class };
        if (touch.length != 4) {
            throw new IllegalStateException("unreachable");
        }
        System.out.println("preload 完成（内部类已加载）");
    }


    private static final class InjectMethodVisitor extends MethodVisitor {
        private final int access;
        private final String name;
        private final String desc;
        private final Type returnType;
        private final boolean directSuper;
        private final String superOwner;
        private final String resetAttrOwner;

        InjectMethodVisitor(MethodVisitor mv, int access, String name, String desc, String decision) {
            super(Opcodes.ASM9, mv);
            this.access = access;
            this.name = name;
            this.desc = desc;
            this.returnType = Type.getReturnType(desc);
            this.directSuper = decision.startsWith("DIRECT_SUPER:");
            this.superOwner = directSuper ? decision.substring("DIRECT_SUPER:".length()) : null;
            this.resetAttrOwner = decision.startsWith("RESET_ATTR:")
                    ? decision.substring("RESET_ATTR:".length()) : null;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (directSuper) {
                emitSuperCallAndReturn(superOwner);
            } else if (resetAttrOwner != null) {
                emitResetAttributes(resetAttrOwner);
            } else {
                emitDefaultReturn();
            }
        }


        private void emitResetAttributes(String owner) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false);
            super.visitInsn(Opcodes.ARETURN);
        }


        private void emitDefaultReturn() {
            switch (returnType.getSort()) {
                case Type.VOID:
                    super.visitInsn(Opcodes.RETURN);
                    break;
                case Type.BOOLEAN:
                case Type.CHAR:
                case Type.BYTE:
                case Type.SHORT:
                case Type.INT:
                    super.visitInsn(Opcodes.ICONST_0);
                    super.visitInsn(Opcodes.IRETURN);
                    break;
                case Type.FLOAT:
                    super.visitInsn(Opcodes.FCONST_0);
                    super.visitInsn(Opcodes.FRETURN);
                    break;
                case Type.LONG:
                    super.visitInsn(Opcodes.LCONST_0);
                    super.visitInsn(Opcodes.LRETURN);
                    break;
                case Type.DOUBLE:
                    super.visitInsn(Opcodes.DCONST_0);
                    super.visitInsn(Opcodes.DRETURN);
                    break;
                default:
                    super.visitInsn(Opcodes.ACONST_NULL);
                    super.visitInsn(Opcodes.ARETURN);
                    break;
            }
        }

        private void emitSuperCallAndReturn(String superOwner) {
            int idx = 0;
            if ((access & Opcodes.ACC_STATIC) == 0) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                idx = 1;
            }
            for (Type at : Type.getArgumentTypes(desc)) {
                super.visitVarInsn(at.getOpcode(Opcodes.ILOAD), idx);
                idx += at.getSize();
            }
            super.visitMethodInsn(Opcodes.INVOKESPECIAL, superOwner, name, desc, false);
            switch (returnType.getSort()) {
                case Type.VOID:
                    super.visitInsn(Opcodes.RETURN);
                    break;
                case Type.BOOLEAN:
                case Type.CHAR:
                case Type.BYTE:
                case Type.SHORT:
                case Type.INT:
                    super.visitInsn(Opcodes.IRETURN);
                    break;
                case Type.FLOAT:
                    super.visitInsn(Opcodes.FRETURN);
                    break;
                case Type.LONG:
                    super.visitInsn(Opcodes.LRETURN);
                    break;
                case Type.DOUBLE:
                    super.visitInsn(Opcodes.DRETURN);
                    break;
                default:
                    super.visitInsn(Opcodes.ARETURN);
                    break;
            }
        }

        @Override public void visitInsn(int opcode) { }
        @Override public void visitIntInsn(int opcode, int operand) { }
        @Override public void visitVarInsn(int opcode, int var) { }
        @Override public void visitTypeInsn(int opcode, String type) { }
        @Override public void visitFieldInsn(int opcode, String owner, String n, String d) { }
        @Override public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) { }
        @Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle bsm, Object... a) { }
        @Override public void visitJumpInsn(int opcode, Label label) { }
        @Override public void visitLabel(Label label) { }
        @Override public void visitLdcInsn(Object value) { }
        @Override public void visitIincInsn(int var, int increment) { }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { }
        @Override public void visitMultiANewArrayInsn(String d, int dims) { }
        @Override public void visitTryCatchBlock(Label s, Label e, Label h, String t) { }
        @Override public void visitFrame(int type, int nl, Object[] l, int ns, Object[] s) { }
        @Override public void visitLineNumber(int line, Label start) { }
        @Override public void visitLocalVariable(String n, String d, String sig, Label s, Label e, int i) { }
        @Override public void visitMaxs(int maxStack, int maxLocals) { super.visitMaxs(0, 0); }
    }

    private boolean shouldSkipClass(String className) {
        if (className == null) return true;

        String simpleClassName = className.contains("/")
                ? className.substring(className.lastIndexOf('/') + 1)
                : className;
        if (simpleClassName.startsWith("__")) return true;

        return className.startsWith("java/") ||
                className.startsWith("javax/") ||
                className.startsWith("sun/") ||
                className.startsWith("com/sun/") ||
                className.startsWith("jdk/") ||
                className.startsWith("org/objectweb/asm/") ||
                className.startsWith("net/minecraftforge/");
    }

    private boolean isSpecialMethod(MethodNode method) {
        String name = method.name;
        int access = method.access;

        return "<init>".equals(name) ||
                "<clinit>".equals(name) ||
                (access & Opcodes.ACC_ABSTRACT) != 0 ||
                (access & Opcodes.ACC_NATIVE) != 0 ||
                (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    private boolean isTargetReturnType(Type returnType) {
        return Type.VOID_TYPE.equals(returnType) ||
                Type.BOOLEAN_TYPE.equals(returnType);
    }


    private boolean isRegistrationMethod(MethodNode method) {
        for (Type at : Type.getArgumentTypes(method.desc)) {
            if (REGISTRATION_EVENT_TYPES.contains(at.getDescriptor())) {
                return true;
            }
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (REGISTRATION_CALLS.contains(m.owner + "." + m.name + m.desc)) {
                    return true;
                }
            }
        }
        return false;
    }


    private boolean isRenderMethod(MethodNode method) {
        if (!"render".equals(method.name)) return false;
        if (!Type.VOID_TYPE.equals(Type.getReturnType(method.desc))) return false;
        boolean hasPoseStack = false;
        boolean hasBuffer = false;
        for (Type at : Type.getArgumentTypes(method.desc)) {
            String d = at.getDescriptor();
            if ("Lcom/mojang/blaze3d/vertex/PoseStack;".equals(d)) hasPoseStack = true;
            if ("Lnet/minecraft/client/renderer/MultiBufferSource;".equals(d)) hasBuffer = true;
        }
        return hasPoseStack && hasBuffer;
    }

    private boolean isRenderFamilyMethod(MethodNode method) {
        if (isRenderMethod(method)) return true;
        String n = method.name;
        if ("renderToBuffer".equals(n)) return true;
        if ("setupAnim".equals(n)) return true;
        if ("root".equals(n)) return true;
        if ("getPart".equals(n)) return true;
        if ("activeAt".equals(n)) return true;
        if ("getTextureLocation".equals(n)) return true;
        if ("shouldRender".equals(n)) return true;
        if ("renderLayers".equals(n)) return true;
        if ("explodeModelParts".equals(n)) return true;
        return false;
    }

    private boolean isMixinClass(ClassNode classNode) {
        if (classNode.visibleAnnotations != null) {
            for (AnnotationNode ann : classNode.visibleAnnotations) {
                if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc)) {
                    return true;
                }
            }
        }
        if (classNode.interfaces != null) {
            for (String iface : classNode.interfaces) {
                if ("org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin".equals(iface)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean referencesSynchedEntityData(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if ("net/minecraft/network/syncher/SynchedEntityData".equals(m.owner)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isOverrideMethod(ClassNode classNode, MethodNode method, ClassInfo info) {
        // 检查是否有@Override注解
        if (method.visibleAnnotations != null) {
            for (AnnotationNode ann : method.visibleAnnotations) {
                if ("Ljava/lang/Override;".equals(ann.desc)) {
                    return true;
                }
            }
        }
        if (method.invisibleAnnotations != null) {
            for (AnnotationNode ann : method.invisibleAnnotations) {
                if ("Ljava/lang/Override;".equals(ann.desc)) {
                    return true;
                }
            }
        }


        String methodKey = method.name + method.desc;


        if (info.superMethods.contains(methodKey)) {
            return true;
        }


        if (info.interfaceMethods.contains(methodKey)) {
            return true;
        }


        if ((method.access & Opcodes.ACC_BRIDGE) != 0) {
            return true;
        }


        if (isRealObjectMethod(method.name, method.desc)) {
            return true;
        }

        return false;
    }


    private boolean isRealObjectMethod(String name, String desc) {

        if ("toString".equals(name) && "()Ljava/lang/String;".equals(desc)) return true;
        if ("equals".equals(name) && "(Ljava/lang/Object;)Z".equals(desc)) return true;
        if ("hashCode".equals(name) && "()I".equals(desc)) return true;
        if ("getClass".equals(name) && "()Ljava/lang/Class;".equals(desc)) return true;


        if ("clone".equals(name) && "()Ljava/lang/Object;".equals(desc)) return true;
        if ("finalize".equals(name) && "()V".equals(desc)) return true;


        if ("wait".equals(name) && "()V".equals(desc)) return true;
        if ("wait".equals(name) && "(J)V".equals(desc)) return true;
        if ("wait".equals(name) && "(JI)V".equals(desc)) return true;


        if ("notify".equals(name) && "()V".equals(desc)) return true;
        if ("notifyAll".equals(name) && "()V".equals(desc)) return true;
        return false;
    }

    /* ===== 以下 7 个旧 tree-API 注入方法（clearMethodBodyWithCheck / transformOverrideMethodWithCheck /
       insertEmptyOverrideWithCheck / insertSuperCall / emitEmptyReturn / transformSimpleMethodWithCheck /
       insertEmptySimpleWithCheck）已被 InjectMethodVisitor 取代，仅注释保留备查，切勿调回：
       它们走 COMPUTE_FRAMES 整类重写，会连带重算 lambda 等未改动方法的栈帧 → VerifyError。=====
    private void clearMethodBodyWithCheck(MethodNode method, Type returnType) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;

        InsnList newInsns = new InsnList();
        LabelNode startLabel = new LabelNode();
        LabelNode continueLabel = new LabelNode();
        LabelNode endLabel = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchHandler = new LabelNode();

        newInsns.add(startLabel);
        newInsns.add(tryStart);

        // Class.forName("net.rain.agent.AllReturnUtil")
        newInsns.add(new LdcInsnNode(ALLRETURNUTIL_CLASS_NAME));
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "java/lang/Class",
        "forName",
        "(Ljava/lang/String;)Ljava/lang/Class;",
        false
        ));
        newInsns.add(new InsnNode(Opcodes.POP));

        // shouldAR()
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        ALLRETURNUTIL_INTERNAL,
        "shouldAR",
        "()Z",
        false
        ));

        // 如果 false，跳转到 continue
        newInsns.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // true：返回
        if (Type.VOID_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.RETURN));
        } else if (Type.BOOLEAN_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.ICONST_0));
            newInsns.add(new InsnNode(Opcodes.IRETURN));
        }

        // catch ClassNotFoundException
        newInsns.add(tryEnd);
        newInsns.add(catchHandler);
        newInsns.add(new InsnNode(Opcodes.POP)); // pop exception
        newInsns.add(new JumpInsnNode(Opcodes.GOTO, continueLabel));

        // continue: 也返回（危险方法直接返回）
        newInsns.add(continueLabel);
        if (Type.VOID_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.RETURN));
        } else if (Type.BOOLEAN_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.ICONST_0));
            newInsns.add(new InsnNode(Opcodes.IRETURN));
        }

        newInsns.add(endLabel);

        method.instructions = newInsns;
        method.tryCatchBlocks = new ArrayList<>();
        method.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, "java/lang/ClassNotFoundException"));

        method.maxStack = 2;
        method.maxLocals = 1;
    }

    private boolean transformOverrideMethodWithCheck(ClassNode classNode, MethodNode method, Type returnType, ClassInfo info) {
        InsnList oldInsns = method.instructions;
        if (oldInsns == null || oldInsns.size() == 0) {
            return insertEmptyOverrideWithCheck(classNode, method, returnType, info);
        }

        InsnList newInsns = new InsnList();
        Map<LabelNode, LabelNode> labelMap = new HashMap<>();

        for (AbstractInsnNode insn = oldInsns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                LabelNode newLabel = new LabelNode();
                labelMap.put((LabelNode) insn, newLabel);
            }
        }

        LabelNode continueLabel = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchHandler = new LabelNode();

        // try 开始
        newInsns.add(tryStart);

        // Class.forName
        newInsns.add(new LdcInsnNode(ALLRETURNUTIL_CLASS_NAME));
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "java/lang/Class",
        "forName",
        "(Ljava/lang/String;)Ljava/lang/Class;",
        false
        ));
        newInsns.add(new InsnNode(Opcodes.POP));

        // shouldAR()
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        ALLRETURNUTIL_INTERNAL,
        "shouldAR",
        "()Z",
        false
        ));

        // 如果 false，跳转到 continue
        newInsns.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // true：调用 super 并返回
        insertSuperCall(newInsns, classNode, method, returnType, info);

        if (Type.VOID_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.RETURN));
        } else if (Type.BOOLEAN_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.IRETURN));
        }

        // try 结束
        newInsns.add(tryEnd);

        // catch
        newInsns.add(catchHandler);
        newInsns.add(new InsnNode(Opcodes.POP));
        newInsns.add(new JumpInsnNode(Opcodes.GOTO, continueLabel));

        // continue：执行原方法
        newInsns.add(continueLabel);

        for (AbstractInsnNode insn = oldInsns.getFirst(); insn != null; insn = insn.getNext()) {
            AbstractInsnNode clone = insn.clone(labelMap);
            newInsns.add(clone);
        }

        // 更新 try-catch
        if (method.tryCatchBlocks == null) {
            method.tryCatchBlocks = new ArrayList<>();
        }

        List<TryCatchBlockNode> newTryCatch = new ArrayList<>();
        for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
            TryCatchBlockNode newTcb = new TryCatchBlockNode(
            labelMap.get(tcb.start),
            labelMap.get(tcb.end),
            labelMap.get(tcb.handler),
            tcb.type
            );
            newTryCatch.add(newTcb);
        }

        newTryCatch.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, "java/lang/ClassNotFoundException"));
        method.tryCatchBlocks = newTryCatch;
        method.instructions = newInsns;
        method.localVariables = null; // 清除 LocalVariableTable，防止克隆 Label 后产生重复条目导致 ClassFormatError

        return true;
    }

    private boolean insertEmptyOverrideWithCheck(ClassNode classNode, MethodNode method, Type returnType, ClassInfo info) {
        InsnList newInsns = new InsnList();
        LabelNode startLabel = new LabelNode();
        LabelNode continueLabel = new LabelNode();
        LabelNode endLabel = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchHandler = new LabelNode();

        newInsns.add(startLabel);
        newInsns.add(tryStart);

        // Class.forName
        newInsns.add(new LdcInsnNode(ALLRETURNUTIL_CLASS_NAME));
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "java/lang/Class",
        "forName",
        "(Ljava/lang/String;)Ljava/lang/Class;",
        false
        ));
        newInsns.add(new InsnNode(Opcodes.POP));

        // shouldAR()
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        ALLRETURNUTIL_INTERNAL,
        "shouldAR",
        "()Z",
        false
        ));

        // 如果 false，跳转到 continue
        newInsns.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // true：调用 super
        insertSuperCall(newInsns, classNode, method, returnType, info);

        if (Type.VOID_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.RETURN));
        } else if (Type.BOOLEAN_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.IRETURN));
        }

        // catch
        newInsns.add(tryEnd);
        newInsns.add(catchHandler);
        newInsns.add(new InsnNode(Opcodes.POP));
        newInsns.add(new JumpInsnNode(Opcodes.GOTO, continueLabel));

        // continue：调用 super（原方法体为空）
        newInsns.add(continueLabel);
        insertSuperCall(newInsns, classNode, method, returnType, info);

        if (Type.VOID_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.RETURN));
        } else if (Type.BOOLEAN_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.IRETURN));
        }

        newInsns.add(endLabel);

        method.instructions = newInsns;
        method.tryCatchBlocks = new ArrayList<>();
        method.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, "java/lang/ClassNotFoundException"));

        method.maxStack = Math.max(2, Type.getArgumentTypes(method.desc).length + 2);
        method.maxLocals = 1;

        return true;
    }

    private void insertSuperCall(InsnList insns, ClassNode classNode, MethodNode method, Type returnType, ClassInfo info) {
        Type[] argTypes = Type.getArgumentTypes(method.desc);
        Type superReturnType = Type.getReturnType(method.desc);

        String superOwner = classNode.superName;
        if (superOwner == null) {
            superOwner = "java/lang/Object";
        }

        // 安全保护1：如果父类是 Object 但该方法不是 Object 真正拥有的方法
        // （如 Forge EventBus 动态代理类的 invoke(Event)），不能发出 INVOKESPECIAL，
        // 否则运行时会抛出 NoSuchMethodError。直接生成空返回即可。
        if ("java/lang/Object".equals(superOwner) && !isRealObjectMethod(method.name, method.desc)) {
            emitEmptyReturn(insns, returnType);
            return;
        }

        // 安全保护2：如果父类/接口中该方法是 abstract 的，不能用 INVOKESPECIAL 调用，
        // 否则运行时会抛出 AbstractMethodError（如 Entity.m_7380_ 等抽象方法）。
        String methodKey = method.name + method.desc;
        if (info != null && info.abstractMethods.contains(methodKey)) {
            emitEmptyReturn(insns, returnType);
            return;
        }

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));

        int varIndex = 1;
        for (Type argType : argTypes) {
            int opcode = argType.getOpcode(Opcodes.ILOAD);
            insns.add(new VarInsnNode(opcode, varIndex));
            varIndex += argType.getSize();
        }

        insns.add(new MethodInsnNode(
        Opcodes.INVOKESPECIAL,
        superOwner,
        method.name,
        method.desc,
        false
        ));

        if (!Type.VOID_TYPE.equals(superReturnType) && Type.VOID_TYPE.equals(returnType)) {
            if (superReturnType.getSize() == 2) {
                insns.add(new InsnNode(Opcodes.POP2));
            } else {
                insns.add(new InsnNode(Opcodes.POP));
            }
        }
    }

    // 向指令列表末尾追加一条与返回类型匹配的空返回指令
    private void emitEmptyReturn(InsnList insns, Type returnType) {
        if (Type.VOID_TYPE.equals(returnType)) {
            insns.add(new InsnNode(Opcodes.RETURN));
        } else if (Type.BOOLEAN_TYPE.equals(returnType)) {
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new InsnNode(Opcodes.IRETURN));
        }
    }

    private boolean transformSimpleMethodWithCheck(MethodNode method, Type returnType) {
        InsnList oldInsns = method.instructions;
        if (oldInsns == null || oldInsns.size() == 0) {
            return insertEmptySimpleWithCheck(method, returnType);
        }

        InsnList newInsns = new InsnList();
        Map<LabelNode, LabelNode> labelMap = new HashMap<>();

        for (AbstractInsnNode insn = oldInsns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                LabelNode newLabel = new LabelNode();
                labelMap.put((LabelNode) insn, newLabel);
            }
        }

        LabelNode continueLabel = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchHandler = new LabelNode();

        // try 开始
        newInsns.add(tryStart);

        // Class.forName
        newInsns.add(new LdcInsnNode(ALLRETURNUTIL_CLASS_NAME));
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "java/lang/Class",
        "forName",
        "(Ljava/lang/String;)Ljava/lang/Class;",
        false
        ));
        newInsns.add(new InsnNode(Opcodes.POP));

        // shouldAR()
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        ALLRETURNUTIL_INTERNAL,
        "shouldAR",
        "()Z",
        false
        ));

        // 如果 false，跳转到 continue
        newInsns.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // true：返回 false/void
        if (Type.VOID_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.RETURN));
        } else if (Type.BOOLEAN_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.ICONST_0));
            newInsns.add(new InsnNode(Opcodes.IRETURN));
        }

        // try 结束
        newInsns.add(tryEnd);

        // catch
        newInsns.add(catchHandler);
        newInsns.add(new InsnNode(Opcodes.POP));
        newInsns.add(new JumpInsnNode(Opcodes.GOTO, continueLabel));

        // continue：执行原方法
        newInsns.add(continueLabel);

        for (AbstractInsnNode insn = oldInsns.getFirst(); insn != null; insn = insn.getNext()) {
            AbstractInsnNode clone = insn.clone(labelMap);
            newInsns.add(clone);
        }

        // 更新 try-catch
        if (method.tryCatchBlocks == null) {
            method.tryCatchBlocks = new ArrayList<>();
        }

        List<TryCatchBlockNode> newTryCatch = new ArrayList<>();
        for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
            TryCatchBlockNode newTcb = new TryCatchBlockNode(
            labelMap.get(tcb.start),
            labelMap.get(tcb.end),
            labelMap.get(tcb.handler),
            tcb.type
            );
            newTryCatch.add(newTcb);
        }

        newTryCatch.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, "java/lang/ClassNotFoundException"));
        method.tryCatchBlocks = newTryCatch;
        method.instructions = newInsns;
        method.localVariables = null; // 清除 LocalVariableTable，防止克隆 Label 后产生重复条目导致 ClassFormatError

        return true;
    }

    private boolean insertEmptySimpleWithCheck(MethodNode method, Type returnType) {
        InsnList newInsns = new InsnList();
        LabelNode startLabel = new LabelNode();
        LabelNode continueLabel = new LabelNode();
        LabelNode endLabel = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchHandler = new LabelNode();

        newInsns.add(startLabel);
        newInsns.add(tryStart);

        // Class.forName
        newInsns.add(new LdcInsnNode(ALLRETURNUTIL_CLASS_NAME));
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "java/lang/Class",
        "forName",
        "(Ljava/lang/String;)Ljava/lang/Class;",
        false
        ));
        newInsns.add(new InsnNode(Opcodes.POP));

        // shouldAR()
        newInsns.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        ALLRETURNUTIL_INTERNAL,
        "shouldAR",
        "()Z",
        false
        ));

        // 如果 false，跳转到 continue
        newInsns.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // true：返回
        if (Type.VOID_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.RETURN));
        } else if (Type.BOOLEAN_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.ICONST_0));
            newInsns.add(new InsnNode(Opcodes.IRETURN));
        }

        // catch
        newInsns.add(tryEnd);
        newInsns.add(catchHandler);
        newInsns.add(new InsnNode(Opcodes.POP));
        newInsns.add(new JumpInsnNode(Opcodes.GOTO, continueLabel));

        // continue：也返回（空方法）
        newInsns.add(continueLabel);
        if (Type.VOID_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.RETURN));
        } else if (Type.BOOLEAN_TYPE.equals(returnType)) {
            newInsns.add(new InsnNode(Opcodes.ICONST_0));
            newInsns.add(new InsnNode(Opcodes.IRETURN));
        }

        newInsns.add(endLabel);

        method.instructions = newInsns;
        method.tryCatchBlocks = new ArrayList<>();
        method.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, "java/lang/ClassNotFoundException"));

        method.maxStack = 2;
        method.maxLocals = 1;

        return true;
    }
    ===== 旧 tree-API 注入实现结束 ===== */

    private ClassInfo analyzeClassHierarchy(ClassNode classNode, ClassLoader loader) {
        String className = classNode.name;

        ClassInfo cached = classInfoCache.get(className);
        if (cached != null) {
            return cached;
        }

        ClassInfo info = new ClassInfo();

        String superName = classNode.superName;
        if (superName != null && !"java/lang/Object".equals(superName)) {
            collectMethodsFromClass(superName, info.superMethods, info.abstractMethods, info.ancestorClasses,
                    info.synchedEntityDataDefineMethods, info.vanillaAttrSupplierOwners, loader, true);
        }

        if (classNode.interfaces != null) {
            for (String iface : classNode.interfaces) {
                collectMethodsFromClass(iface, info.interfaceMethods, info.abstractMethods, info.ancestorClasses,
                        info.synchedEntityDataDefineMethods, info.vanillaAttrSupplierOwners, loader, false);
            }
        }

        classInfoCache.put(className, info);
        return info;
    }

    private void collectMethodsFromClass(String className, Set<String> methods,
            Set<String> abstractMethods, Set<String> ancestors, Set<String> synchedEntityDataDefineMethods,
            Set<String> vanillaAttrSupplierOwners,
            ClassLoader loader, boolean isClass) {
        try {
            InputStream is = null;
            if (loader != null) {
                is = loader.getResourceAsStream(className + ".class");
            }
            if (is == null) {
                is = ClassLoader.getSystemResourceAsStream(className + ".class");
            }

            if (is != null) {
                ClassReader reader = new ClassReader(is);
                ClassNode node = new ClassNode();

                reader.accept(node, ClassReader.SKIP_DEBUG);
                if (isClass) {
                    ancestors.add(className);
                }

                for (MethodNode method : node.methods) {

                    if ("()Lnet/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder;".equals(method.desc)
                            && isClass && className.startsWith("net/minecraft/")
                            && ("createAttributes".equals(method.name) || method.name.startsWith("m_"))) {
                        vanillaAttrSupplierOwners.add(className);
                    }
                    if ((method.access & Opcodes.ACC_PRIVATE) != 0) continue;
                    if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
                    if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
                    if ("<init>".equals(method.name)) continue;
                    if ("<clinit>".equals(method.name)) continue;

                    String key = method.name + method.desc;

                    if (isClass && "()V".equals(method.desc)
                            && (method.access & Opcodes.ACC_ABSTRACT) == 0
                            && referencesSynchedEntityData(method)) {
                        synchedEntityDataDefineMethods.add(key);
                    }

                    if (!methods.contains(key)) {
                        if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
                            abstractMethods.add(key);
                        } else {
                            abstractMethods.remove(key);
                        }
                    }
                    methods.add(key);
                }

                if (isClass && node.superName != null && !"java/lang/Object".equals(node.superName)) {
                    collectMethodsFromClass(node.superName, methods, abstractMethods, ancestors,
                            synchedEntityDataDefineMethods, vanillaAttrSupplierOwners, loader, true);
                }

                if (node.interfaces != null) {
                    for (String iface : node.interfaces) {
                        collectMethodsFromClass(iface, methods, abstractMethods, ancestors,
                                synchedEntityDataDefineMethods, vanillaAttrSupplierOwners, loader, false);
                    }
                }

                is.close();
            }
        } catch (Exception e) {
            // 忽略无法分析的类
        }
    }

    private static class ClassInfo {
        final Set<String> superMethods = new HashSet<>();
        final Set<String> interfaceMethods = new HashSet<>();

        final Set<String> abstractMethods = new HashSet<>();
        final Set<String> ancestorClasses = new HashSet<>();

        final Set<String> synchedEntityDataDefineMethods = new HashSet<>();

        final LinkedHashSet<String> vanillaAttrSupplierOwners = new LinkedHashSet<>();
    }
}
