package com.ryjs.agent.transformers;

import com.ryjs.agent.transformers.bypass.CleanMethodBypass;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;


public class CleanMethodClassFileTransformer implements ClassFileTransformer {


    private static volatile boolean firstErrorLogged = false;

    private static volatile boolean firstWorkLogged = false;

    private static final Set<String> INIT_METHOD_WHITELIST = new HashSet<>(
            Arrays.asList(
                    "<init>",
                    "<clinit>",
                    "init",
                    "initialize",
                    "setup",
                    "configure",
                    "register",
                    "registerAll",
                    "onRegister",
                    "addEntries",
                    "handleEvent",
                    "create",
                    "build",
                    "make",
                    "getRegistry",
                    "createRegistry",
                    "registerEvent",
                    "onLoad",
                    "onUnload",
                    "createAttributes",
                    "defineSynchedData",
                    "registerGoals",
                    "setRegistryName",
                    "load",
                    "save",
                    "parse",
                    "deserialize",
                    "serialize",
                    "read",
                    "write",
                    "commonSetup",
                    "clientSetup",
                    "serverSetup",
                    "onCommonSetup",
                    "onClientSetup",
                    "FMLCommonSetupEvent",
                    "FMLClientSetupEvent",
                    "FMLLoadCompleteEvent",
                    "preInit",
                    "postInit",
                    "earlyInit",
                    "lateInit",
                    "bootstrap",
                    "start",
                    "begin"
            )
    );

    private static final List<ProtectEntry> PROTECT_ENTRIES = new ArrayList<>();
    private static final Set<String> PROTECTED_CLASSES = new HashSet<>();
    private static volatile boolean configLoaded = false;

    public CleanMethodClassFileTransformer() {
        this.loadProtectConfig();
    }

    private synchronized void loadProtectConfig() {
        if (!configLoaded) {
            try {
                StringBuilder jsonContent = new StringBuilder();
                String line;
                String var15 = jsonContent.toString();
                int var16 = 0;
                StringBuilder currentEntry = new StringBuilder();
                boolean inString = false;
                boolean escape = false;

                for (int i = 0; i < var15.length(); i++) {
                    char c = var15.charAt(i);
                    if (escape) {
                        currentEntry.append(c);
                        escape = false;
                    } else if (c == '\\') {
                        currentEntry.append(c);
                        escape = true;
                    } else if (c == '"') {
                        inString = !inString;
                        currentEntry.append(c);
                    } else {
                        if (!inString) {
                            if (c != '{' && c != '[') {
                                if (c != '}' && c != ']') {
                                    if (c == ',' && var16 == 1 && !currentEntry.isEmpty()) {
                                        String entryContent = currentEntry.toString();
                                        this.parseProtectEntry(entryContent);
                                        currentEntry.setLength(0);
                                        continue;
                                    }
                                } else if (--var16 == 1 && !currentEntry.isEmpty()) {
                                    String entryContent = currentEntry.toString();
                                    this.parseProtectEntry(entryContent);
                                    currentEntry.setLength(0);
                                    continue;
                                }
                            } else {
                                if (var16 == 0 && !currentEntry.isEmpty()) {
                                    String key = this.extractJsonString(currentEntry.toString().trim());
                                    if (key != null && !key.isEmpty()) {
                                        currentEntry.setLength(0);
                                    }
                                }
                                var16++;
                            }
                        }
                        if (var16 >= 1) {
                            currentEntry.append(c);
                        }
                    }
                }

                if (!currentEntry.isEmpty()) {
                    this.parseProtectEntry(currentEntry.toString());
                }

                configLoaded = true;
                System.out.println("[CleanMethod] JSON保护条目加载完成，" + PROTECT_ENTRIES.size() + " 个方法级保护");
            } catch (Exception e) {
                System.err.println("[CleanMethod] 加载 protect_method.json 失败: " + e.getMessage());
                e.printStackTrace();
                configLoaded = true;
            }

            loadBypassProviders();
            System.out.println("[CleanMethod] 保护配置全部加载完成："
                    + PROTECT_ENTRIES.size() + " 个方法级保护，"
                    + PROTECTED_CLASSES.size() + " 个类级保护");
        }
    }


    private void loadBypassProviders() {
        try {
            ServiceLoader<CleanMethodBypass> loader =
                    ServiceLoader.load(CleanMethodBypass.class,
                            CleanMethodClassFileTransformer.class.getClassLoader());

            for (CleanMethodBypass provider : loader) {
                String tag = provider.getClass().getSimpleName();

                if (provider.protectedClasses() != null) {
                    for (String cls : provider.protectedClasses()) {
                        PROTECTED_CLASSES.add(cls.replace('/', '.'));
                        System.out.println("[CleanMethod] [Bypass:" + tag + "] 类保护: " + cls);
                    }
                }

                if (provider.protectedMethods() != null) {
                    for (String entry : provider.protectedMethods()) {
                        int lastDot = entry.lastIndexOf('.');
                        if (lastDot <= 0) continue;
                        String cls = entry.substring(0, lastDot).replace('/', '.');
                        String method = entry.substring(lastDot + 1);
                        ProtectEntry pe = new ProtectEntry();
                        pe.type = "method";
                        pe.className = cls;
                        pe.method = method;
                        PROTECT_ENTRIES.add(pe);
                        System.out.println("[CleanMethod] [Bypass:" + tag + "] 方法保护: " + cls + "." + method);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[CleanMethod] 加载 Bypass 保护清单失败: " + t.getMessage());
        }
    }

    private void parseProtectEntry(String entryJson) {
        try {
            ProtectEntry entry = new ProtectEntry();
            entry.type = this.extractJsonValue(entryJson, "type");
            entry.className = this.extractJsonValue(entryJson, "class");
            entry.method = this.extractJsonValue(entryJson, "method");
            if (entry.className != null) {
                entry.className = entry.className.replace('/', '.');
                String argsSection = this.extractJsonObject(entryJson, "args");
                if (argsSection != null) {
                    entry.args = this.parseSimpleMap(argsSection);
                }

                if ("class".equals(entry.type)) {
                    PROTECTED_CLASSES.add(entry.className);
                } else if ("method".equals(entry.type) && entry.method != null) {
                    PROTECT_ENTRIES.add(entry);
                }
            }
        } catch (Exception e) {
            System.err.println("[CleanMethod] 解析保护条目失败: " + entryJson);
            e.printStackTrace();
        }
    }

    private String extractJsonValue(String json, String key) {
        int start = json.indexOf('"' + key + '"');
        if (start == -1) {
            return null;
        } else {
            start = json.indexOf(58, start) + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
                start++;
            }
            if (start >= json.length()) {
                return null;
            } else {
                char quoteChar = json.charAt(start);
                if (quoteChar != '"') {
                    return null;
                } else {
                    start++;
                    StringBuilder value = new StringBuilder();
                    boolean escape = false;
                    for (int i = start; i < json.length(); i++) {
                        char c = json.charAt(i);
                        if (escape) {
                            value.append(c);
                            escape = false;
                        } else if (c == '\\') {
                            escape = true;
                        } else {
                            if (c == '"') {
                                return value.toString();
                            }
                            value.append(c);
                        }
                    }
                    return null;
                }
            }
        }
    }

    private String extractJsonObject(String json, String key) {
        String searchKey = '"' + key + '"';
        int start = json.indexOf(searchKey);
        if (start == -1) {
            return null;
        } else {
            start = json.indexOf(58, start) + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
                start++;
            }
            if (start >= json.length()) {
                return null;
            } else {
                char openChar = json.charAt(start);
                char closeChar = (char) (openChar == '{' ? 125 : (openChar == '[' ? 93 : 0));
                if (closeChar == 0) {
                    return null;
                } else {
                    start++;
                    int depth = 1;
                    StringBuilder content = new StringBuilder();
                    boolean inString = false;
                    boolean escape = false;
                    for (int i = start; i < json.length(); i++) {
                        char c = json.charAt(i);
                        if (escape) {
                            content.append(c);
                            escape = false;
                        } else if (c == '\\') {
                            content.append(c);
                            escape = true;
                        } else if (c == '"') {
                            inString = !inString;
                            content.append(c);
                        } else {
                            if (!inString) {
                                if (c == openChar) {
                                    depth++;
                                } else if (c == closeChar) {
                                    if (--depth == 0) {
                                        return content.toString();
                                    }
                                }
                            }
                            content.append(c);
                        }
                    }
                    return null;
                }
            }
        }
    }

    private Map<String, String> parseSimpleMap(String json) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = json.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":");
            if (kv.length == 2) {
                String key = this.extractJsonString(kv[0].trim());
                String value = this.extractJsonString(kv[1].trim());
                if (key != null && value != null) {
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    private String extractJsonString(String str) {
        str = str.trim();
        return str.startsWith("\"") && str.endsWith("\"") ? str.substring(1, str.length() - 1) : str;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        final String standardClassName = className != null ? className.replace('/', '.') : null;
        if (standardClassName == null || standardClassName.isEmpty()) {
            return classfileBuffer;
        } else if (standardClassName.startsWith("com.ryjs")) {
            return classfileBuffer;
        } else if (com.ryjs.agent.CompatWhitelist.isWhitelistedClass(standardClassName)) {
            return classfileBuffer; // 兼容白名单：良性 mod 不清方法（名单为数据文件）
        } else if (!isModJarByProtectionDomain(protectionDomain)) {
            return classfileBuffer;
        } else if (PROTECTED_CLASSES.contains(standardClassName)) {
            return classfileBuffer;
        } else {
            if (!firstWorkLogged && !standardClassName.startsWith("reflectionpreloadprobe")) {
                firstWorkLogged = true;
                System.out.println("[CleanMethod] pipeline active — 开始处理 /mods/ 类");
            }
            try {
                ClassReader reader = new ClassReader(classfileBuffer);

                // ★ 预读：一次性识别反射密集型 static 方法（独立 try-catch，失败不影响主变换）
                final Set<String> reflectionHeavyKeys = new HashSet<>();
                try {
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            if ((access & Opcodes.ACC_STATIC) == 0) return null;
                            if (isInitMethod(name)) return null;
                            if (isProtectedMethod(standardClassName, name, descriptor)) return null;
                            MethodNode node = new MethodNode(access, name, descriptor, signature, exceptions) {
                                @Override
                                public void visitEnd() {
                                    super.visitEnd();
                                    if (isReflectionHeavyMethod(this)) {
                                        reflectionHeavyKeys.add(name + descriptor);
                                    }
                                }
                            };
                            return node;
                        }
                    }, ClassReader.EXPAND_FRAMES);
                } catch (Exception e) {
                    // 预读失败不影响主变换，只是跳过反射敏感方法检测
                }

                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                final int[] stats = new int[7]; // [6] = 反射敏感方法跳过数
                ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                        if (!isStatic) {
                            stats[5]++;
                            return mv;
                        } else if (CleanMethodClassFileTransformer.this.isProtectedMethod(standardClassName, name, descriptor)) {
                            stats[3]++;
                            return mv;
                        } else if (CleanMethodClassFileTransformer.this.isInitMethod(name)) {
                            stats[3]++;
                            return mv;
                        } else if ((access & Opcodes.ACC_SYNTHETIC) != 0 && name.startsWith("lambda$")) {
                            // 编译器合成的 lambda 体（如 Util.make(new EnumMap<>(), m -> { m.put(...); }) 的初始化 lambda）：
                            // 清空会破坏合法初始化（EnumMap 变空 → get() 返 null → NPE）；lambda 内若有危险调用另有 AntiExit/AllReturn 兜底，故保留。
                            stats[3]++;
                            return mv;
                        } else if (reflectionHeavyKeys.contains(name + descriptor)) {
                            stats[6]++;
                            return mv;
                        } else {
                            Type returnType = Type.getReturnType(descriptor);
                            int sort = returnType.getSort();
                            if (sort == Type.VOID) {
                                stats[0]++;
                                return new VoidMethodCleaner(mv, access, name, descriptor, standardClassName);
                            } else if (sort == Type.FLOAT) {
                                stats[1]++;
                                return new FloatMethodCleaner(mv, access, name, descriptor, standardClassName);
                            } else if (sort == Type.BOOLEAN) {
                                stats[2]++;
                                return new BooleanMethodCleaner(mv, access, name, descriptor, standardClassName);
                            } else {
                                stats[4]++;
                                return mv;
                            }
                        }
                    }

                    @Override
                    public void visitEnd() {
                        super.visitEnd();
                        int modified = stats[0] + stats[1] + stats[2];
                        if ((modified > 0 || stats[3] > 0 || stats[6] > 0)
                                && !standardClassName.startsWith("reflectionpreloadprobe")) {
                            StringBuilder msg = new StringBuilder();
                            msg.append(String.format("[CleanMethod] %s: ", standardClassName));
                            msg.append(String.format("清空 %d void + %d float + %d bool",
                                    stats[0], stats[1], stats[2]));
                            if (stats[3] > 0) msg.append(String.format(", 保留 %d 初始化方法", stats[3]));
                            if (stats[6] > 0) msg.append(String.format(", 跳过 %d 反射敏感方法", stats[6]));
                            System.out.println(msg.toString());
                        }
                    }
                };
                reader.accept(visitor, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Throwable e) {
                // 绝不静默：首次异常必吼（含堆栈，括 NoClassDefFoundError 等 Error），后续抑制成单行。
                if (!firstErrorLogged) {
                    firstErrorLogged = true;
                    System.err.println("[CleanMethod] 首次 transform 异常 @ " + standardClassName
                            + "（后续同类异常将被抑制）：");
                    e.printStackTrace();
                } else {
                    System.err.println("[CleanMethod] transform 异常 @ " + standardClassName
                            + ": [" + e.getClass().getSimpleName() + "] " + e.getMessage());
                }
                return classfileBuffer;
            }
        }
    }

    /**
     * 预加载全部内部类（具名 Cleaner/SafeClassWriter + transform 内的匿名 visitor）——
     * 必须在 CoexCleaner 摘除本模块前调用。否则模块被摘后首次 transform 懒加载会
     * NoClassDefFoundError，逃逸给 JVM TransformerManager 后被静默丢弃→永久失效。
     */
    public static void preload() {
        // 1) 具名内部类：直接触碰即加载
        Class<?>[] touch = {
                VoidMethodCleaner.class, FloatMethodCleaner.class, BooleanMethodCleaner.class,
                SafeClassWriter.class, ProtectEntry.class
        };
        if (touch.length != 5) {
            throw new IllegalStateException("unreachable");
        }
        // 2) 匿名 visitor：用一个合成 /mods/ 探针类跑通 transform 的完整访问链（含预读+主 visitor）
        try {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "reflectionpreloadprobe/CleanMethodProbe",
                    null, "java/lang/Object", null);
            emitProbeMethod(cw, "aaa", "()V", -1, Opcodes.RETURN);
            emitProbeMethod(cw, "bbb", "()F", Opcodes.FCONST_0, Opcodes.FRETURN);
            emitProbeMethod(cw, "ccc", "()Z", Opcodes.ICONST_0, Opcodes.IRETURN);
            cw.visitEnd();
            java.security.CodeSource cs = new java.security.CodeSource(
                    new java.net.URL("file:/mods/reflectionpreloadprobe.jar"),
                    (java.security.cert.Certificate[]) null);
            ProtectionDomain pd = new ProtectionDomain(cs, null);
            new CleanMethodClassFileTransformer().transform(
                    CleanMethodClassFileTransformer.class.getClassLoader(),
                    "reflectionpreloadprobe/CleanMethodProbe", null, pd, cw.toByteArray());
            System.out.println("[CleanMethod] preload 完成（内部类已加载）");
        } catch (Throwable t) {
            System.err.println("[CleanMethod] preload 失败: " + t);
        }
    }

    /** preload 用：向探针类写入一个 static 方法（constOp<0 表示无需压常量，如 void）。 */
    private static void emitProbeMethod(ClassWriter cw, String name, String desc, int constOp, int retOp) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        if (constOp >= 0) {
            mv.visitInsn(constOp);
        }
        mv.visitInsn(retOp);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
    }

    private boolean isProtectedMethod(String className, String methodName, String descriptor) {
        for (ProtectEntry entry : PROTECT_ENTRIES) {
            if (className.equals(entry.className) && methodName.equals(entry.method)) {
                if (entry.args == null || entry.args.isEmpty()) {
                    return true;
                }
                Type[] argTypes = Type.getArgumentTypes(descriptor);
                if (argTypes.length == entry.args.size()) {
                    boolean argsMatch = true;
                    int i = 0;
                    Iterator<String> var9 = entry.args.values().iterator();
                    while (true) {
                        if (var9.hasNext()) {
                            String expectedType = var9.next();
                            String actualType = argTypes[i].getClassName();
                            if (actualType.equals(expectedType)
                                    || actualType.replace('.', '/').equals(expectedType.replace('.', '/'))) {
                                i++;
                                continue;
                            }
                            argsMatch = false;
                        }
                        if (argsMatch) {
                            return true;
                        }
                        break;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInitMethod(String methodName) {
        if (INIT_METHOD_WHITELIST.contains(methodName)) {
            return true;
        } else {
            String lowerName = methodName.toLowerCase();
            if (!lowerName.contains("init")
                    && !lowerName.contains("setup")
                    && !lowerName.contains("register")
                    && !lowerName.contains("create")
                    && !lowerName.contains("build")
                    && !lowerName.contains("make")
                    && !lowerName.contains("load")
                    && !lowerName.contains("bootstrap")
                    && !lowerName.contains("config")
                    && !lowerName.contains("deserialize")
                    && !lowerName.contains("serialize")
                    && !lowerName.contains("parse")
                    && !lowerName.contains("event")) {
                return methodName.startsWith("on")
                        && (methodName.contains("Setup") || methodName.contains("Load")
                        || methodName.contains("Register") || methodName.contains("Event"));
            }
            return true;
        }
    }

    /**
     * 启发式检测：方法是否大量使用反射/Unsafe/VarHandle/Instrumentation API。
     * 这类方法通常是 mod 的关键基础设施代码（如 ITransformationService 的辅助方法），
     * 清空会导致 mod 无法正常加载。不硬编码任何特定 mod 信息。
     */
    private boolean isReflectionHeavyMethod(MethodNode methodNode) {
        int suspiciousCount = 0;
        for (int i = 0; i < methodNode.instructions.size(); i++) {
            AbstractInsnNode insn = methodNode.instructions.get(i);
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode mi) {
                if (mi.name.equals("forName") && mi.owner.equals("java/lang/Class")) suspiciousCount++;
                if (mi.name.equals("invoke") && mi.owner.equals("java/lang/reflect/Method")) suspiciousCount++;
                if (mi.name.equals("get") && mi.owner.startsWith("java/lang/invoke/VarHandle")) suspiciousCount++;
                if (mi.name.equals("set") && mi.owner.startsWith("java/lang/invoke/VarHandle")) suspiciousCount++;
                if (mi.name.equals("attach") && mi.owner.contains("VirtualMachine")) suspiciousCount++;
                if (mi.name.equals("removeTransformer") && mi.owner.contains("Instrumentation")) suspiciousCount++;
                if (mi.name.equals("addTransformer") && mi.owner.contains("Instrumentation")) suspiciousCount++;
                if (mi.owner.equals("java/lang/Thread") &&
                        (mi.name.equals("start") || mi.name.equals("suspend") || mi.name.equals("stop"))) suspiciousCount++;
            } else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode fi) {
                if (fi.owner.contains("sun/misc/Unsafe") || fi.owner.contains("jdk/internal/misc/Unsafe")) suspiciousCount++;
                if (fi.owner.startsWith("java/lang/invoke/VarHandle")) suspiciousCount++;
            } else if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof String s) {
                if (s.contains("sun.misc") || s.contains("jdk.internal") || s.contains("sun.instrument")) suspiciousCount++;
            }
        }
        return suspiciousCount >= 2;
    }

    /**
     * 判断类的来源是否是 /mods/ 目录下的 mod jar。
     */
    private static boolean isModJarByProtectionDomain(ProtectionDomain pd) {
        if (pd != null && pd.getCodeSource() != null) {
            String rawUrl = pd.getCodeSource().getLocation().toString();
            String url = rawUrl.toLowerCase();
            String normalizedUrl = url.replace('\\', '/');
            if (!normalizedUrl.contains("/mods/")) {
                return false;
            } else if (normalizedUrl.contains("/libraries/")) {
                return false;
            } else {
                String[] forgeCoreLibs = new String[]{
                        "forge-",
                        "fmlcore",
                        "javafmllanguage",
                        "lowcodelanguage",
                        "mclanguage",
                        "modlauncher",
                        "bootstraplauncher",
                        "securejarhandler",
                        "coremods",
                        "accesstransformers",
                        "fabric-loader",
                        "fabric-api"
                };
                for (String lib : forgeCoreLibs) {
                    if (normalizedUrl.contains(lib)) {
                        return false;
                    }
                }
                return true;
            }
        } else {
            return false;
        }
    }

    // ==================== 内部 Cleaner 类 ====================

    private static class BooleanMethodCleaner extends AdviceAdapter {
        BooleanMethodCleaner(MethodVisitor mv, int access, String name, String descriptor, String className) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            this.mv.visitInsn(Opcodes.ICONST_0);  // false
            this.mv.visitInsn(Opcodes.IRETURN);
            this.mv.visitEnd();
        }

        @Override
        public void visitCode() {
            this.onMethodEnter();
        }

        @Override
        public void visitInsn(int opcode) {}

        @Override
        public void visitIntInsn(int opcode, int operand) {}

        @Override
        public void visitVarInsn(int opcode, int varIndex) {}

        @Override
        public void visitTypeInsn(int opcode, String type) {}

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {}

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {}

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                             Object... bootstrapMethodArguments) {}

        @Override
        public void visitJumpInsn(int opcode, Label label) {}

        @Override
        public void visitLabel(Label label) {}

        @Override
        public void visitLdcInsn(Object value) {}

        @Override
        public void visitIincInsn(int varIndex, int increment) {}

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {}

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {}

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {}

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {}

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {}

        @Override
        public void visitLineNumber(int line, Label start) {}
    }

    private static class FloatMethodCleaner extends AdviceAdapter {
        FloatMethodCleaner(MethodVisitor mv, int access, String name, String descriptor, String className) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            this.mv.visitInsn(Opcodes.FCONST_0);  // 0.0f
            this.mv.visitInsn(Opcodes.FRETURN);
            this.mv.visitEnd();
        }

        @Override
        public void visitCode() {
            this.onMethodEnter();
        }

        @Override
        public void visitInsn(int opcode) {}

        @Override
        public void visitIntInsn(int opcode, int operand) {}

        @Override
        public void visitVarInsn(int opcode, int varIndex) {}

        @Override
        public void visitTypeInsn(int opcode, String type) {}

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {}

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {}

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                             Object... bootstrapMethodArguments) {}

        @Override
        public void visitJumpInsn(int opcode, Label label) {}

        @Override
        public void visitLabel(Label label) {}

        @Override
        public void visitLdcInsn(Object value) {}

        @Override
        public void visitIincInsn(int varIndex, int increment) {}

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {}

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {}

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {}

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {}

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {}

        @Override
        public void visitLineNumber(int line, Label start) {}
    }

    private static class VoidMethodCleaner extends AdviceAdapter {
        VoidMethodCleaner(MethodVisitor mv, int access, String name, String descriptor, String className) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            this.mv.visitInsn(Opcodes.RETURN);
            this.mv.visitEnd();
        }

        @Override
        public void visitCode() {
            this.onMethodEnter();
        }

        @Override
        public void visitInsn(int opcode) {}

        @Override
        public void visitIntInsn(int opcode, int operand) {}

        @Override
        public void visitVarInsn(int opcode, int varIndex) {}

        @Override
        public void visitTypeInsn(int opcode, String type) {}

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {}

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {}

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                             Object... bootstrapMethodArguments) {}

        @Override
        public void visitJumpInsn(int opcode, Label label) {}

        @Override
        public void visitLabel(Label label) {}

        @Override
        public void visitLdcInsn(Object value) {}

        @Override
        public void visitIincInsn(int varIndex, int increment) {}

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {}

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {}

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {}

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {}

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {}

        @Override
        public void visitLineNumber(int line, Label start) {}
    }

    /**
     * 安全的 ClassWriter，避免 COMPUTE_FRAMES 时触发 getCommonSuperClass 加载类。
     * Forge 多模块类加载器环境下，默认实现会抛 TypeNotPresentException。
     */
    private static class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Exception e) {
                return "java/lang/Object";
            }
        }
    }

    public static class ProtectEntry {
        public String type;
        public String className;
        public String method;
        public Map<String, String> args = new HashMap<>();
    }
}
