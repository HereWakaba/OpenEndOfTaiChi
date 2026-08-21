package com.ryjs.core.impl;

import com.ryjs.hook.transformer.LoaderAwareClassWriter;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;


public final class ClassRestoreGuard {


    private static final long INITIAL_DELAY_MS = 60000L;

    private static final long PERIOD_MS = 5000L;

    private static final int REDEFINE_THRESHOLD = 2;

    private static final int REDEFINE_COOLDOWN = -6;

    private static final int PROBE_SHARDS = 6;
    private static final java.util.concurrent.atomic.AtomicInteger PROBE_CURSOR = new java.util.concurrent.atomic.AtomicInteger();

    private static boolean isJarOriginalTarget(String className) {
        return "net/minecraftforge/server/command/EntityCommand$EntityListCommand".equals(className)
            || className.startsWith("com/ryjs/");
    }

    private static volatile Instrumentation inst;
    private static volatile boolean running = false;


    private static final Map<String, byte[]> ORIGINAL = new ConcurrentHashMap<>();

    private static final Map<String, byte[]> LAST_INJECTED = new ConcurrentHashMap<>();

    private static final Map<String, Integer> UNMARKED_ROUNDS = new ConcurrentHashMap<>();

    private ClassRestoreGuard() {
    }


    public static void start(Instrumentation instrumentation) {
        if (running) {
            return;
        }
        inst = instrumentation;

        if (inst == null && !useJvmti()) {
            return;
        }
        running = true;
        // 预注册守护类：实体查询/枚举路径平台类（防调用点级过滤/内联——链尾守卫以 jar 原版为基准还原）
        String[] guardList = {
            "net/minecraftforge/server/command/EntityCommand$EntityListCommand", // /forge entity list 统计路径
            "com/ryjs/reflection/util/AdvancedKillUtils",
            "net/minecraft/server/level/ServerLevel",
            "net/minecraft/client/multiplayer/ClientLevel",
            "net/minecraft/world/level/Level",
            "net/minecraft/world/level/entity/EntityLookup",
            "net/minecraft/world/level/entity/LevelEntityGetterAdapter",
            "net/minecraft/world/level/entity/EntitySection",
            "net/minecraft/world/level/entity/EntityTickList",
            "net/minecraft/world/level/entity/PersistentEntitySectionManager",
            "net/minecraft/world/level/entity/TransientEntitySectionManager",
            "net/minecraft/server/level/ChunkMap",
            "net/minecraft/client/renderer/LevelRenderer",
            "net/minecraft/client/renderer/entity/EntityRenderDispatcher",
            "net/minecraft/client/renderer/GameRenderer",
            "net/minecraft/client/renderer/LightTexture",
        };
        for (String n : guardList) {
            HookRegistry.recordGuardClass(n);
        }

        preloadTransformDependencies();
        if (useJvmti()) {
            System.out.println("start: useJvmti=true（channel="
                    + com.ryjs.agent.DefenseConfig.restoreGuardChannel() + "），RestoreTransformer 挂 jvmti 链");
            com.ryjs.core.JvmtiBridge.addTransformer(new RestoreTransformer());
        } else {
            System.out.println("start: useJvmti=false，RestoreTransformer 挂 inst 链");
            inst.addTransformer(new RestoreTransformer(), true);
        }
        Thread t = new Thread(ClassRestoreGuard::loop, "ClassRestoreGuard");
        t.setDaemon(true);
        t.start();
        System.out.println("类还原守卫已注册（链尾原版对比仲裁 + 5s 分片轮询 + redefine 兜底，守护类=" + guardList.length + "）");
    }

    /** 预加载 transform 内部引用的接管包类（防回调内懒解析递归——ClassCircularityError）。 */
    private static void preloadTransformDependencies() {
        String[] deps = {
            "com.ryjs.core.impl.HookTransformer",
            "com.ryjs.core.impl.HookRegistry",
            "com.ryjs.hook.transformer.ClassByteSource",
            "com.ryjs.hook.transformer.BytecodeHierarchy",
        };
        ClassLoader cl = ClassRestoreGuard.class.getClassLoader();
        for (String name : deps) {
            try {
                Class.forName(name, true, cl);
            } catch (Throwable ignored) {
                // 未就绪跳过（下轮 transform 时仍可能解析失败，由 dispatchTransform 的 com/ryjs catch 兜底）
            }
        }
    }

    // ---- JVMTI 优先封装（2026-08-16 零-JVMTI 化：通道选择已废弃，恒走 JvmtiBridge——
    // transformer/观察名单/零通道 retransform/redefine 全在桥内；INST 仅 fallback）----
    /** 通道解析：恒 auto（restoreGuardChannel 已废弃选择——见 DefenseConfig）。 */
    private static boolean useJvmti() {
        return com.ryjs.core.JvmtiBridge.isAvailable();
    }

    private static boolean isModifiable(Class<?> c) {
        if (useJvmti()) {
            return com.ryjs.core.JvmtiBridge.isModifiable(c);
        }
        return inst != null && inst.isModifiableClass(c);
    }

    private static void retransform(Class<?> c) throws Throwable {
        if (useJvmti()) {
            com.ryjs.core.JvmtiBridge.retransform(c);
        } else if (inst != null) {
            inst.retransformClasses(c);
        }
    }

    private static void redefine(Class<?> c, byte[] bytes) throws Throwable {
        if (useJvmti()) {
            com.ryjs.core.JvmtiBridge.redefineClass(c, bytes);
        } else if (inst != null) {
            inst.redefineClasses(new ClassDefinition(c, bytes));
        }
    }

    /**
     * 外部 redefine 事件触发恢复（RestoreTriggerHooks 回调）：对方全量重定义/还原抹掉我方 Hook 后
     * 立即重注入——秒级响应，替代等下一轮轮询。与轮询线程互斥（probeAll 已同步）。
     */
    public static void triggerRestore() {
        if (!running) {
            return;
        }
        try {
            System.out.println("外部 redefine 事件：立即恢复我方注入");
            probeAll();
        } catch (Throwable t) {
            System.err.println("事件恢复失败（已隔离）: " + t);
        }
    }

    private static void loop() {
        try {
            Thread.sleep(INITIAL_DELAY_MS);
        } catch (InterruptedException e) {
            return;
        }
        while (running) {
            try {
                probeAll();
            } catch (Throwable t) {
                System.err.println("探测轮失败（已隔离）: " + t);
            }
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }


    private static synchronized void probeAll() {
        if (useJvmti()) {
            probeAllJvmti();
        } else {
            probeAllInst();
        }
    }


    private static void probeAllJvmti() {
        Map<String, ClassLoader> injected = HookRegistry.injectedClasses();
        if (injected.isEmpty()) {
            return;
        }

        java.util.Set<String> watch = new java.util.HashSet<>(injected.keySet());
        watch.addAll(HookRegistry.guardClasses());
        List<String> names = new ArrayList<>(watch);
        java.util.Collections.sort(names);
        int shard = PROBE_CURSOR.getAndIncrement() % PROBE_SHARDS;
        List<String> shardNames = new ArrayList<>();
        for (int i = shard; i < names.size(); i += PROBE_SHARDS) {
            shardNames.add(names.get(i));
        }
        if (shardNames.isEmpty()) {
            return;
        }
        Map<String, Class<?>> observed = com.ryjs.core.JvmtiBridge.observedSnapshot();
        boolean zeroChannel = com.ryjs.core.JvmtiBridge.zeroChannelAvailable();
        HookTransformer.internalRetransform = true; // 我方主动 retransform：正常处理
        int ok = 0;
        int skipped = 0;
        try {

            List<Class<?>> ready = new ArrayList<>();
            List<String> misses = new ArrayList<>();
            for (String name : shardNames) {
                Class<?> c = observed.get(name);
                if (c == null || (!zeroChannel && !isModifiable(c))) {
                    misses.add(name);
                    continue;
                }
                if (!ancestryLoaded(c, observed)) {
                    misses.add(name);
                    continue;
                }
                ready.add(c);
            }
            for (Class<?> c : ready) {
                try {
                    retransform(c);
                    ok++;
                } catch (Throwable t) {
                    System.err.println("单类 retransform 失败（跳过，下轮再试）: " + c.getName() + " -> " + t);
                }
            }
            if (!misses.isEmpty()) {
                int[] r = com.ryjs.core.JvmtiBridge.probeRetransform(misses.toArray(new String[0]));
                ok += r[0];
                skipped += r[1];
            }
        } finally {
            HookTransformer.internalRetransform = false;
        }
        if (ok > 0) {
            System.out.println("probe 重注入: " + ok + " 个");
        }
        for (Map.Entry<String, ClassLoader> e : injected.entrySet()) {
            String className = e.getKey();
            Integer rounds = UNMARKED_ROUNDS.get(className);
            if (rounds == null || rounds < REDEFINE_THRESHOLD) {
                continue;
            }
            byte[] injectedBytes = LAST_INJECTED.get(className);
            if (injectedBytes == null) {
                continue;
            }

            Class<?> c = com.ryjs.core.JvmtiBridge.observedClass(className);
            int r;
            if (c != null) {
                r = com.ryjs.core.JvmtiBridge.redefineClass(c, injectedBytes);
            } else {
                r = com.ryjs.core.JvmtiBridge.redefineByName(className, injectedBytes);
            }
            if (r == 0) {
                UNMARKED_ROUNDS.remove(className);
                System.out.println("[ClassRestore] redefine 绕过链上还原者: " + className);
            } else {

                UNMARKED_ROUNDS.put(className, REDEFINE_COOLDOWN);
            }
        }
    }


    private static void probeAllInst() {
        Map<String, ClassLoader> injected = HookRegistry.injectedClasses();
        if (injected.isEmpty()) {
            return;
        }

        Map<String, Class<?>> loaded = new java.util.HashMap<>();

        Class<?>[] loadedArr = inst != null ? inst.getAllLoadedClasses() : new Class<?>[0];
        for (Class<?> c : loadedArr) {
            if (c != null && !c.isArray() && !c.isPrimitive()) {
                loaded.put(c.getName().replace('.', '/'), c);
            }
        }

        java.util.Set<String> watch = new java.util.HashSet<>(injected.keySet());
        watch.addAll(HookRegistry.guardClasses());
        List<String> names = new ArrayList<>(watch);
        java.util.Collections.sort(names);
        int shard = PROBE_CURSOR.getAndIncrement() % PROBE_SHARDS;
        List<Class<?>> classes = new ArrayList<>();
        int skipped = 0;
        for (int i = shard; i < names.size(); i += PROBE_SHARDS) {
            String className = names.get(i);
            Class<?> c = loaded.get(className);
            if (c == null || !isModifiable(c)) {
                skipped++;
                continue;
            }
            if (!ancestryLoaded(c, loaded)) {
                skipped++;
                continue;
            }
            classes.add(c);
        }
        if (classes.isEmpty()) {
            return;
        }
        HookTransformer.internalRetransform = true;
        int ok = 0;
        try {

            for (Class<?> c : classes) {
                try {
                    retransform(c);
                    ok++;
                } catch (Throwable t) {
                    System.err.println("单类 retransform 失败（跳过，下轮再试）: " + c.getName() + " -> " + t);
                }
            }
        } finally {
            HookTransformer.internalRetransform = false;
        }

        for (Map.Entry<String, ClassLoader> e : injected.entrySet()) {
            String className = e.getKey();
            Integer rounds = UNMARKED_ROUNDS.get(className);
            if (rounds == null || rounds < REDEFINE_THRESHOLD) {
                continue;
            }
            byte[] injectedBytes = LAST_INJECTED.get(className);
            if (injectedBytes == null) {
                continue;
            }
            try {
                Class<?> c = loaded.get(className); // 仅处理已加载类（不主动加载）
                if (c == null || !isModifiable(c)) {
                    UNMARKED_ROUNDS.put(className, REDEFINE_COOLDOWN);
                    continue;
                }
                if (!ancestryLoaded(c, loaded)) {
                    UNMARKED_ROUNDS.put(className, REDEFINE_COOLDOWN); // 父类链未就绪 → 冷却，下轮再试
                    continue;
                }
                redefine(c, injectedBytes);
                UNMARKED_ROUNDS.remove(className);
                System.out.println("redefine 绕过链上还原者: " + className);
            } catch (Throwable t) {

                UNMARKED_ROUNDS.put(className, REDEFINE_COOLDOWN);
            }
        }
    }


    static final class RestoreTransformer implements ClassFileTransformer {
        private static volatile int transformCalls = 0;

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {

            if (className == null || classfileBuffer == null
                    || !(HookRegistry.hasTarget(className)
                         || HookRegistry.isGuardClass(className)
                         || className.startsWith("com/ryjs/"))) {
                return null;
            }
            try {

                if (!isJarOriginalTarget(className) && containsOurHook(readNode(classfileBuffer))) {
                    return null;
                }

                byte[] original = null;
                com.ryjs.core.RyjsCore core = com.ryjs.coremod.Agent.AgentUtil.hiddenCore();
                if (core != null && core.isJarOriginalTarget(className)) {
                    original = core.restoreBaseline(className, loader);
                }
                if (original == null && isJarOriginalTarget(className)) {
                    original = originalBytes(className, loader);
                    if (original != null && "net/minecraftforge/server/command/EntityCommand$EntityListCommand".equals(className)) {
                        original = publicize(original, loader); // 仅 EntityListCommand：AT 差异 → 最大兼容形态
                    }
                }
                if (original == null) {
                    original = HookTransformer.initialBytes(className);
                }
                if (original == null) {
                    return null;
                }

                if (isJarOriginalTarget(className)) {
                    byte[] applied = HookTransformer.transform(loader, className, classBeingRedefined, protectionDomain, original);
                    byte[] target = (applied != original) ? applied : original;
                    boolean same;
                    if (core != null && className.startsWith("com/ryjs/")) {

                        same = !core.isSemanticallyModified(classfileBuffer, target);
                    } else {
                        same = java.util.Arrays.equals(classfileBuffer, target);
                    }
                    if (same) {
                        return null;
                    }
                    if (className.startsWith("com/ryjs/")) {
                        HookRegistry.recordGuardClass(className);
                    }
                    UNMARKED_ROUNDS.merge(className, 1, Integer::sum);
                    LAST_INJECTED.put(className, target);
                    logRestore("检测到外部修改，已还原为原版并重装", className, classfileBuffer, original);
                    return target;
                }

                boolean modified = (core != null) ? core.isModified(classfileBuffer, original)
                        : !java.util.Arrays.equals(classfileBuffer, original);
                if (!modified) {

                    if (HookRegistry.hasTarget(className) || HookRegistry.isGuardClass(className)) {
                        byte[] applied = HookTransformer.transform(loader, className, classBeingRedefined, protectionDomain, original);
                        if (applied != original) {
                            LAST_INJECTED.put(className, applied);
                            return applied;
                        }
                    }
                    return null;
                }

                UNMARKED_ROUNDS.merge(className, 1, Integer::sum);
                byte[] restored = HookTransformer.transform(loader, className, classBeingRedefined, protectionDomain, original);
                if (restored != original) {
                    LAST_INJECTED.put(className, restored);
                    logRestore("检测到外部修改，已还原为原版", className, classfileBuffer, original);
                    return restored;
                }
                logRestore("检测到外部修改，已还原为原版", className, classfileBuffer, original);
                return original;
            } catch (Throwable t) {
                System.err.println("transform失败: " + className + " -> " + t);
                return null;
            }
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
            org.objectweb.asm.ClassWriter writer = new LoaderAwareClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS, loader);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            return bytes; // publicize 失败 → 返回原字节（后续对比/还原降级）
        }
    }


    private static final java.util.Set<String> RESTORE_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void logRestore(String action, String className, byte[] input, byte[] baseline) {
        if (RESTORE_LOGGED.add(className)) {
            System.out.println("[ClassRestore] " + action + ": " + className + diffSummary(input, baseline));
        }
    }


    private static String diffSummary(byte[] input, byte[] baseline) {
        try {
            ClassNode in = readNode(input);
            ClassNode base = readNode(baseline);
            StringBuilder diff = new StringBuilder();
            int shown = 0;
            for (MethodNode m : in.methods) {
                MethodNode b = null;
                for (MethodNode x : base.methods) {
                    if (x.name.equals(m.name) && x.desc.equals(m.desc)) {
                        b = x;
                        break;
                    }
                }
                if (b == null) {
                    diff.append("+M:").append(m.name).append(m.desc).append(' ');
                } else if (m.instructions.size() != b.instructions.size()) {
                    diff.append("~M:").append(m.name).append(m.desc).append(' ');
                }
                if (++shown >= 5) {
                    break;
                }
            }
            return " [差异: " + diff + "| 输入=" + input.length + "B 基准=" + baseline.length + "B]";
        } catch (Throwable t) {
            return " [差异摘要失败: " + t + "| 输入=" + input.length + "B 基准=" + baseline.length + "B]";
        }
    }

    private static boolean ancestryLoaded(Class<?> c, Map<String, Class<?>> loaded) {
        Class<?> cur = c.getSuperclass();
        while (cur != null) {
            if (!loaded.containsKey(cur.getName().replace('.', '/'))) {
                return false;
            }
            cur = cur.getSuperclass();
        }
        for (Class<?> itf : c.getInterfaces()) {
            if (!loaded.containsKey(itf.getName().replace('.', '/'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsOurHook(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.startsWith("com/ryjs/reflection/hook/")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameSchema(ClassNode a, ClassNode b) {
        if (!a.name.equals(b.name)
                || !Objects.equals(a.superName, b.superName)
                || !a.interfaces.equals(b.interfaces)) {
            return false;
        }
        return memberKeys(a.fields).equals(memberKeys(b.fields))
                && memberKeys(a.methods).equals(memberKeys(b.methods));
    }

    private static Set<String> memberKeys(Collection<?> members) {
        Set<String> keys = new HashSet<>();
        for (Object member : members) {
            if (member instanceof FieldNode field) {
                keys.add("F:" + field.name + field.desc);
            } else if (member instanceof MethodNode method) {
                keys.add("M:" + method.name + method.desc);
            }
        }
        return keys;
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
}
