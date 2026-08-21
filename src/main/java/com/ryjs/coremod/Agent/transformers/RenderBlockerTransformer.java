package com.ryjs.coremod.Agent.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.apache.logging.log4j.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class RenderBlockerTransformer implements ClassFileTransformer {

    private static final Logger log = LogManager.getLogger();

    @Override
    public byte[] transform(ClassLoader loader, String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {

        if (className == null) return null;

        // 只转换 mods 目录扫描出的目标类
        if (!com.ryjs.coremod.ImmediateWindowProvider.EartyLoading.targetClasses.contains(className)) {
            return null;
        }

        try {
            // ========== 渲染相关拦截 ==========
            // 拦截EntityRenderer
            if (className.equals("net/minecraft/client/renderer/entity/EntityRenderer")) {
                log.info("[RenderBlocker] Transforming EntityRenderer");
                AllReturn.transformedClassName.add(className);
                return transformEntityRenderer(classfileBuffer);
            }

            // 拦截EntityRenderDispatcher
            if (className.equals("net/minecraft/client/renderer/entity/EntityRenderDispatcher")) {
                log.info("[RenderBlocker] Transforming EntityRenderDispatcher");
                AllReturn.transformedClassName.add(className);
                return transformEntityRenderDispatcher(classfileBuffer);
            }

            // 拦截LevelRenderer
            if (className.equals("net/minecraft/client/renderer/LevelRenderer")) {
                log.info("[RenderBlocker] Transforming LevelRenderer");
                AllReturn.transformedClassName.add(className);
                return transformLevelRenderer(classfileBuffer);
            }

            if (className.equals("net/minecraftforge/client/gui/overlay/ForgeGui") || className.contains("overlay/ForgeGui")) {
                AllReturn.transformedClassName.add(className);
                log.info("[RenderBlocker] Transforming ForgeGui");
                return transformRenderBossHealth(classfileBuffer);
            }

            // ========== Boss事件拦截 ==========
            // 拦截BossHealthOverlay
            if (className.equals("net/minecraft/client/gui/components/BossHealthOverlay")) {
                log.info("[RenderBlocker] Transforming BossHealthOverlay");
                AllReturn.transformedClassName.add(className);
                return transformBossHealthOverlay(classfileBuffer);
            }

            // ========== 实体AI和Tick拦截 ==========
            // 拦截Entity基类
            if (className.equals("net/minecraft/world/entity/Entity")) {
                log.info("[RenderBlocker] Transforming Entity (tick methods)");
                AllReturn.transformedClassName.add(className);
                return transformEntity(classfileBuffer);
            }

            // 拦截LivingEntity
            if (className.equals("net/minecraft/world/entity/LivingEntity")) {
                log.info("[RenderBlocker] Transforming LivingEntity (AI)");
                AllReturn.transformedClassName.add(className);
                return transformLivingEntity(classfileBuffer);
            }

            // 拦截Mob (AI控制)
            if (className.equals("net/minecraft/world/entity/Mob")) {
                log.info("[RenderBlocker] Transforming Mob (AI)");
                AllReturn.transformedClassName.add(className);
                return transformMob(classfileBuffer);
            }

            // ========== Forge事件拦截 ==========
            // 拦截包含RenderLevelStageEvent的类
            if (className.contains("RenderLevelStageEvent")) {
                AllReturn.transformedClassName.add(className);
                return transformForgeEvent(classfileBuffer);
            }

            // 拦截包含CustomizeGuiOverlayEvent的类
            if (className.contains("CustomizeGuiOverlayEvent")) {
                AllReturn.transformedClassName.add(className);
                return transformForgeEvent(classfileBuffer);
            }

        } catch (Exception e) {
            log.error("[RenderBlocker] Error transforming class: " + className, e);
        }

        return null;
    }

    // ==================== 渲染拦截转换 ====================

    /** 转换EntityRenderer类 - 增强版 拦截3个关键方法：render, shouldRender, shouldShowName */
    private byte[] transformEntityRenderer(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            // 1. render方法 - 完全阻止渲染
            // 1.20.1混淆名: m_7392_
            if ((method.name.equals("m_7392_") || method.name.equals("render")) &&
                    method.desc.startsWith("(Lnet/minecraft/world/entity/Entity;")) {
                injectRenderCheck(method, 1);
                log.info("[RenderBlocker] ✓ Injected into EntityRenderer.render (m_7392_)");
                modified = true;
            }

            // 2. shouldRender方法 - 视锥剔除判断
            // 1.20.1混淆名: m_5523_
            if ((method.name.equals("m_5523_") || method.name.equals("shouldRender")) &&
                    method.desc.contains("(Lnet/minecraft/world/entity/Entity;") &&
                    method.desc.endsWith(")Z")) {
                injectShouldRenderCheck(method, 1);
                log.info("[RenderBlocker] ✓ Injected into EntityRenderer.shouldRender (m_5523_)");
                modified = true;
            }

            // 3. shouldShowName方法 - 名称显示判断
            // 1.20.1混淆名: m_6512_
            if ((method.name.equals("m_6512_") || method.name.equals("shouldShowName")) &&
                    method.desc.equals("(Lnet/minecraft/world/entity/Entity;)Z")) {
                injectShouldShowNameCheck(method, 1);
                log.info("[RenderBlocker] ✓ Injected into EntityRenderer.shouldShowName (m_6512_)");
                modified = true;
            }
        }

        if (!modified) {
            log.warn("[RenderBlocker] ⚠ No methods modified in EntityRenderer");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    /** 转换EntityRenderDispatcher类 - 增强版 */
    private byte[] transformEntityRenderDispatcher(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            // render方法
            // 1.20.1混淆名: m_114384_
            if ((method.name.equals("m_114384_") || method.name.equals("render")) &&
                    method.desc.startsWith("(Lnet/minecraft/world/entity/Entity;")) {
                injectRenderCheck(method, 1);
                log.info("[RenderBlocker] ✓ Injected into EntityRenderDispatcher.render (m_114384_)");
                modified = true;
            }

            if (method.name.equals("m_114384_") || method.name.equals("renderShadow") || method.name.equals("renderHitbox") || method.name.equals("renderFlame") || method.name.equals("m_114453_") || method.name.equals("m_114441_") || method.name.equals("m_114457_")) {
                injectAllRenderCheck(method);
                log.info("[RenderBlocker] ✓ Injected into EntityRenderDispatcher.renderShadow  || EntityRenderDispatcher.renderHitbox || EntityRenderDispatcher.renderFlame");
                modified = true;
            }

            // onEntityAdded方法
            // 1.20.1混淆名: m_114398_
            if ((method.name.equals("m_114398_") || method.name.equals("onEntityAdded")) &&
                    method.desc.startsWith("(Lnet/minecraft/world/entity/Entity;")) {
                injectRenderCheck(method, 1);
                log.info("[RenderBlocker] ✓ Injected into EntityRenderDispatcher.onEntityAdded (m_114398_)");
                modified = true;
            }

            // shouldRender方法
            // 1.20.1混淆名: m_114397_
            if ((method.name.equals("m_114397_") || method.name.equals("shouldRender")) &&
                    method.desc.startsWith("(Lnet/minecraft/world/entity/Entity;")) {
                injectShouldRenderCheck(method, 1);
                log.info("[RenderBlocker] ✓ Injected into EntityRenderDispatcher.shouldRender (m_114397_)");
                modified = true;
            }
        }

        if (!modified) {
            log.warn("[RenderBlocker] ⚠ No methods modified in EntityRenderDispatcher");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    /** 转换LevelRenderer类 */
    private byte[] transformLevelRenderer(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            // renderEntity方法
            // 1.20.1混淆名: m_109517_
            if ((method.name.equals("m_109517_") || method.name.equals("renderEntity")) &&
                    method.desc.startsWith("(Lnet/minecraft/world/entity/Entity;")) {
                injectRenderCheck(method, 1);
                log.info("[RenderBlocker] ✓ Injected into LevelRenderer.renderEntity (m_109517_)");
                modified = true;
            }

            // renderLevel方法 - 拦截整个渲染阶段
            // 1.20.1混淆名: m_109599_
            if (method.name.equals("m_109599_") || method.name.equals("renderLevel")) {
                injectRenderStageCheck(method);
                log.info("[RenderBlocker] ✓ Injected stage check into LevelRenderer.renderLevel (m_109599_)");
                modified = true;
            }
        }

        if (!modified) {
            log.warn("[RenderBlocker] ⚠ No methods modified in LevelRenderer");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    // ==================== Boss事件拦截转换 ====================

    /** 转换BossHealthOverlay类 - 拦截Boss血条渲染 */
    private byte[] transformBossHealthOverlay(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            // render方法 - 渲染Boss血条
            // 1.20.1混淆名: m_280421_
            if (method.name.equals("m_280421_") || method.name.equals("render")) {
                injectBossBarRenderCheck(method);
                log.info("[RenderBlocker] ✓ Injected into BossHealthOverlay.render (m_280421_)");
                modified = true;
            }

            // update方法 - 更新Boss血条
            // 1.20.1混淆名: m_93701_
            if (method.name.equals("m_93701_") || method.name.equals("update")) {
                injectBossBarRenderCheck(method);
                log.info("[RenderBlocker] ✓ Injected into BossHealthOverlay.update (m_93701_)");
                modified = true;
            }

            // drawBar方法 - 绘制血条
            // 可能的混淆名: m_280293_ 或其他
            if (method.name.contains("drawBar") || method.name.equals("m_280293_")) {
                injectBossBarRenderCheck(method);
                log.info("[RenderBlocker] ✓ Injected into BossHealthOverlay.drawBar");
                modified = true;
            }

            if (method.name.contains("shouldCreateWorldFog") || method.name.equals("m_93715_")) {
                injectShouldFog(method);
                log.info("[RenderBlocker] ✓ Injected into BossHealthOverlay.shouldCreateWorldFog");
                modified = true;
            }

            if (method.name.contains("shouldDarkenScreen") || method.name.equals("m_93714_")) {
                injectShouldDarken(method);
                log.info("[RenderBlocker] ✓ Injected into BossHealthOverlay.shouldDarkenScreen");
                modified = true;
            }

            if (method.name.contains("shouldPlayMusic") || method.name.equals("m_93713_")) {
                injectShouldPlayMusic(method);
                log.info("[RenderBlocker] ✓ Injected into BossHealthOverlay.shouldPlayMusic");
                modified = true;
            }
        }

        if (!modified) {
            log.warn("[RenderBlocker] ⚠ No methods modified in BossHealthOverlay");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    // ==================== 实体Tick和AI拦截转换 ====================

    /** 转换Entity类 - 拦截tick和baseTick */
    private byte[] transformEntity(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            // tick方法
            // 1.20.1混淆名: m_8119_
            if (method.name.equals("m_8119_") || method.name.contains("tick")) {
                injectTickCheck(method);
                log.info("[RenderBlocker] ✓ Injected into Entity.tick (m_8119_)");
                modified = true;
            }

            // baseTick方法
            // 1.20.1混淆名: m_5670_
            if (method.name.equals("m_5670_") || method.name.equals("baseTick")) {
                injectBaseTickCheck(method);
                log.info("[RenderBlocker] ✓ Injected into Entity.baseTick (m_5670_)");
                modified = true;
            }
        }

        if (!modified) {
            log.warn("[RenderBlocker] ⚠ No methods modified in Entity");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    /** 转换LivingEntity类 - 拦截AI相关 */
    private byte[] transformLivingEntity(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            // aiStep方法 - AI步进
            // 1.20.1混淆名: m_8024_
            if (method.name.equals("m_8024_") || method.name.equals("aiStep")) {
                injectAICheck(method);
                log.info("[RenderBlocker] ✓ Injected into LivingEntity.aiStep (m_8024_)");
                modified = true;
            }

            // tick方法 (覆盖Entity的)
            // 1.20.1混淆名: m_8119_
            if (method.name.equals("m_8119_") || method.name.equals("tick")) {
                injectTickCheck(method);
                log.info("[RenderBlocker] ✓ Injected into LivingEntity.tick (m_8119_)");
                modified = true;
            }
        }

        if (!modified) {
            log.warn("[RenderBlocker] ⚠ No methods modified in LivingEntity");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    /** 转换Mob类 - 拦截AI系统 */
    private byte[] transformMob(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            // customServerAiStep方法 - 服务端AI
            // 1.20.1混淆名: m_8032_
            if (method.name.equals("m_8032_") || method.name.equals("customServerAiStep")) {
                injectAICheck(method);
                log.info("[RenderBlocker] ✓ Injected into Mob.customServerAiStep (m_8032_)");
                modified = true;
            }

            // serverAiStep方法
            // 1.20.1可能的混淆名: m_8024_ 或其他
            if (method.name.equals("serverAiStep") || method.name.equals("m_8024_")) {
                injectAICheck(method);
                log.info("[RenderBlocker] ✓ Injected into Mob.serverAiStep");
                modified = true;
            }
        }

        if (!modified) {
            log.warn("[RenderBlocker] ⚠ No methods modified in Mob");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    // ==================== Forge事件拦截转换 ====================

    /** 转换Forge事件类 - 拦截所有包含特定事件参数的方法 */
    private byte[] transformForgeEvent(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            if (method.name.equals("<init>")) {
                continue;
            }
            // 检查方法描述符中是否包含RenderLevelStageEvent
            if (method.desc.contains("RenderLevelStageEvent")) {
                injectRenderStageEventCheck(method);
                log.info("[RenderBlocker] ✓ Injected into method with RenderLevelStageEvent: " + method.name);
                modified = true;
            }

            // 检查方法描述符中是否包含BossEventProgress
            if (method.desc.contains("BossEventProgress")) {
                injectBossEventProgressCheck(method);
                log.info("[RenderBlocker] ✓ Injected into method with BossEventProgress: " + method.name);
                modified = true;
            }
        }

        if (modified) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            return cw.toByteArray();
        }

        return null;
    }

    private byte[] transformRenderBossHealth(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        boolean modified = false;

        if (classNode.name.equals("net/minecraftforge/client/gui/overlay/ForgeGui") || classNode.name.contains("overlay/ForgeGui")) {

            for (MethodNode method : classNode.methods) {
                if (method.name.equals("<init>")) {
                        continue;
                }

                // 检查方法名中是否包含renderBossHealth
                if (method.name.contains("renderBossHealth")) {
                    injectBossEventProgressCheck(method);
                    log.info("[RenderBlocker] ✓ Injected into method with renderBossHealth: " + method.name);
                    modified = true;
                }
            }
        }

        if (modified) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            return cw.toByteArray();
        }

        return null;
    }

    // ==================== 注入方法 ====================

    /** 注入实体渲染检查(用于void方法) 在方法开始处插入： if (EntityMaker.shouldBlockEntityRender(entity)) return; */
    private void injectRenderCheck(MethodNode method, int entityParamIndex) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 加载entity参数
        patch.add(new VarInsnNode(Opcodes.ALOAD, entityParamIndex));

        // 调用 EntityMaker.shouldBlockEntityRender(entity)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockEntityRender",
        "(Lnet/minecraft/world/entity/Entity;)Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，直接return
        if (method.desc.endsWith(")V")) {
            patch.add(new InsnNode(Opcodes.RETURN));
        } else {
            // 有返回值的方法
            patch.add(new InsnNode(Opcodes.ACONST_NULL));
            patch.add(new InsnNode(Opcodes.ARETURN));
        }

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    /**
     * 注入shouldRender方法检查 shouldRender返回boolean，如果实体被阻止则强制返回false
     *
     * <p>在方法开始处插入： if (EntityMaker.shouldBlockEntityRender(entity)) return false;
     */
    private void injectShouldRenderCheck(MethodNode method, int entityParamIndex) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 加载entity参数
        patch.add(new VarInsnNode(Opcodes.ALOAD, entityParamIndex));

        // 调用 EntityMaker.shouldBlockEntityRender(entity)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockEntityRender",
        "(Lnet/minecraft/world/entity/Entity;)Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，返回false(不应该渲染)
        patch.add(new InsnNode(Opcodes.ICONST_0)); // false
        patch.add(new InsnNode(Opcodes.IRETURN));

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    private void injectAllRenderCheck(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        Type[] argTypes = Type.getArgumentTypes(method.desc);

        // 查找 Entity 参数的索引
        int entityVarIndex = -1;
        int currentVarIndex; // 非静态方法从1开始（0是this）
        
        if(method.name.equals("m_114441_") || method.name.equals("m_114457_")) {
            currentVarIndex = 0;
        } else {
            currentVarIndex = 1;
        }

        log.info("[DEBUG] Processing method: " + method.name + " desc: " + method.desc);
        for (int i = 0; i < argTypes.length; i++) {
            log.info("[DEBUG] Arg " + i + " type: " + argTypes[
                    i].getInternalName() + " sort: " + argTypes[i].getSort());
        }

        for (int i = 0; i < argTypes.length; i++) {
            Type argType = argTypes[i];

            // 只处理对象类型
            if (argType.getSort() == Type.OBJECT) {
                // getInternalName() 返回斜杠分隔的内部名，如: net/minecraft/world/entity/Entity
                String internalName = argType.getInternalName();

                // 检查是否是 Entity 或其子类
                // 通过描述符判断：net/minecraft/world/entity/Entity 或其子类
                if (internalName.equals("net/minecraft/world/entity/Entity") ||
                        internalName.startsWith("net/minecraft/world/entity/")) {

                    // 进一步检查是否是 LivingEntity, Mob, Player 等 Entity 的子类
                    // 或者你可以直接接受所有 entity 包下的类
                    entityVarIndex = currentVarIndex;
                    log.info("[RenderBlocker] Found Entity-like type: " + internalName + " at index " + currentVarIndex);
                    break;
                }
            }

            // 计算下一个变量的索引（考虑 long/double 占2个槽位）
            currentVarIndex += argType.getSize();
        }

        // 如果没找到 Entity 参数，直接返回不做修改
        if (entityVarIndex == -1) {
            log.warn("[RenderBlocker] No Entity parameter found in method: " + method.name + method.desc);
            return;
        }

        log.info("[RenderBlocker] Found Entity at var index: " + entityVarIndex + " in " + method.name);

        // 加载 Entity 参数
        patch.add(new VarInsnNode(Opcodes.ALOAD, entityVarIndex));

        // 调用 EntityMaker.shouldBlockEntityAllRender(Entity entity)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockEntityAllRender",
        "(Lnet/minecraft/world/entity/Entity;)Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 根据返回类型决定如何返回
        Type returnType = Type.getReturnType(method.desc);
        if (returnType == Type.VOID_TYPE) {
            patch.add(new InsnNode(Opcodes.RETURN));
        } else if (returnType == Type.BOOLEAN_TYPE || returnType == Type.INT_TYPE) {
            patch.add(new InsnNode(Opcodes.ICONST_0));
            patch.add(new InsnNode(Opcodes.IRETURN));
        } else if (returnType == Type.LONG_TYPE) {
            patch.add(new InsnNode(Opcodes.LCONST_0));
            patch.add(new InsnNode(Opcodes.LRETURN));
        } else if (returnType == Type.FLOAT_TYPE) {
            patch.add(new InsnNode(Opcodes.FCONST_0));
            patch.add(new InsnNode(Opcodes.FRETURN));
        } else if (returnType == Type.DOUBLE_TYPE) {
            patch.add(new InsnNode(Opcodes.DCONST_0));
            patch.add(new InsnNode(Opcodes.DRETURN));
        } else {
            patch.add(new InsnNode(Opcodes.ACONST_NULL));
            patch.add(new InsnNode(Opcodes.ARETURN));
        }

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    private void injectShouldPlayMusic(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 调用 EntityMaker.shouldBlockEntityRender(entity)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockBossMusic",
        "()Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，返回false(不应该渲染)
        patch.add(new InsnNode(Opcodes.ICONST_0)); // false
        patch.add(new InsnNode(Opcodes.IRETURN));

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    private void injectShouldFog(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 加载entity参数

        // 调用 EntityMaker.shouldBlockEntityRender(entity)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockBossFog",
        "()Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，返回false(不应该渲染)
        patch.add(new InsnNode(Opcodes.ICONST_0)); // false
        patch.add(new InsnNode(Opcodes.IRETURN));

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    private void injectShouldDarken(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 调用 EntityMaker.shouldBlockEntityRender(entity)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockBossDarken",
        "()Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，返回false(不应该渲染)
        patch.add(new InsnNode(Opcodes.ICONST_0)); // false
        patch.add(new InsnNode(Opcodes.IRETURN));

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    /**
     * 注入shouldShowName方法检查 shouldShowName返回boolean，如果实体被阻止则强制返回false(不显示名称)
     *
     * <p>在方法开始处插入： if (EntityMaker.shouldBlockEntityRender(entity)) return false;
     */
    private void injectShouldShowNameCheck(MethodNode method, int entityParamIndex) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 加载entity参数
        patch.add(new VarInsnNode(Opcodes.ALOAD, entityParamIndex));

        // 调用 EntityMaker.shouldBlockEntityRender(entity)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockEntityRender",
        "(Lnet/minecraft/world/entity/Entity;)Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，返回false(不显示名称)
        patch.add(new InsnNode(Opcodes.ICONST_0)); // false
        patch.add(new InsnNode(Opcodes.IRETURN));

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    /** 注入渲染阶段检查 在renderLevel方法开始处插入： if (EntityMaker.shouldBlockRenderStage()) return; */
    private void injectRenderStageCheck(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 调用 EntityMaker.shouldBlockRenderStage()
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockRenderStage",
        "()Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，直接return
        patch.add(new InsnNode(Opcodes.RETURN));

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    /** 注入Boss血条渲染检查 在方法开始处插入： if (EntityMaker.shouldBlockBossBar()) return; */
    private void injectBossBarRenderCheck(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 调用 EntityMaker.shouldBlockBossBar()
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockBossBar",
        "()Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，直接return
        if (method.desc.endsWith(")V")) {
            patch.add(new InsnNode(Opcodes.RETURN));
        } else if (method.desc.endsWith(")Z")) {
            patch.add(new InsnNode(Opcodes.ICONST_0));
            patch.add(new InsnNode(Opcodes.IRETURN));
        } else {
            patch.add(new InsnNode(Opcodes.ACONST_NULL));
            patch.add(new InsnNode(Opcodes.ARETURN));
        }

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    /** 注入AI检查 在方法开始处插入： if (EntityMaker.shouldBlockEntityAI(this)) return; */
    private void injectAICheck(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 加载this
        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));

        // 调用 EntityMaker.shouldBlockEntityAI(this)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockEntityAI",
        "(Lnet/minecraft/world/entity/Entity;)Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，直接return
        patch.add(new InsnNode(Opcodes.RETURN));

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    /** 注入Tick检查 在方法开始处插入： if (EntityMaker.shouldBlockEntityTick(this)) return; */
    private void injectTickCheck(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 加载this
        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));

        // 调用 EntityMaker.shouldBlockEntityTick(this)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockEntityTick",
        "(Lnet/minecraft/world/entity/Entity;)Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，直接return
        patch.add(new InsnNode(Opcodes.RETURN));

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    /** 注入BaseTick检查 在方法开始处插入： if (EntityMaker.shouldBlockEntityBaseTick(this)) return; */
    private void injectBaseTickCheck(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 加载this
        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));

        // 调用 EntityMaker.shouldBlockEntityBaseTick(this)
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockEntityBaseTick",
        "(Lnet/minecraft/world/entity/Entity;)Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，直接return
        patch.add(new InsnNode(Opcodes.RETURN));

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    /** 注入RenderLevelStageEvent检查 拦截所有参数包含RenderLevelStageEvent的方法 */
    private void injectRenderStageEventCheck(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 调用 EntityMaker.shouldBlockRenderStage()
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockRenderStageEvent",
        "()Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，直接return
        if (method.desc.endsWith(")V")) {
            patch.add(new InsnNode(Opcodes.RETURN));
        } else {
            patch.add(new InsnNode(Opcodes.ACONST_NULL));
            patch.add(new InsnNode(Opcodes.ARETURN));
        }

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }

    /** 注入BossEventProgress检查 拦截所有参数包含CustomizeGuiOverlayEvent.BossEventProgress的方法 */
    private void injectBossEventProgressCheck(MethodNode method) {
        InsnList patch = new InsnList();
        LabelNode continueLabel = new LabelNode();

        // 调用 EntityMaker.shouldBlockBossOverlayEvent()
        patch.add(new MethodInsnNode(
        Opcodes.INVOKESTATIC,
        "cpw/mods/modlauncher/MyCore/EntityMaker",
        "shouldBlockBossOverlayEvent",
        "()Z",
        false
        ));

        // 如果返回false(不阻止)，跳转继续执行
        patch.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));

        // 如果返回true(阻止)，直接return
        if (method.desc.endsWith(")V")) {
            patch.add(new InsnNode(Opcodes.RETURN));
        } else {
            patch.add(new InsnNode(Opcodes.ACONST_NULL));
            patch.add(new InsnNode(Opcodes.ARETURN));
        }

        // 继续执行标签
        patch.add(continueLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(patch);
    }
}
