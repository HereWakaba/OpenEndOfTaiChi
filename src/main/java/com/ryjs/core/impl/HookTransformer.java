package com.ryjs.core.impl;


import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;
import com.ryjs.hook.transformer.LoaderAwareClassWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class HookTransformer {
 public static volatile boolean DIAGNOSTIC = false;


   public static volatile boolean internalRetransform = false;

 private static final java.util.Map<String, byte[]> INITIAL_BYTES = new ConcurrentHashMap<>();

 private static final java.util.concurrent.atomic.AtomicBoolean MC_STAGE_TRIGGERED = new java.util.concurrent.atomic.AtomicBoolean();

   public static byte[] initialBytes(String internalName) {
      return INITIAL_BYTES.get(internalName);
   }

   private HookTransformer() {
   }

   public static byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
   if (classfileBuffer == null || loader == null || isSystemClass(className)) {
         return classfileBuffer;
      }
    if (className.startsWith("net/minecraft/") && MC_STAGE_TRIGGERED.compareAndSet(false, true)) {
         try {
            com.ryjs.coremod.Agent.AgentUtil.defineEncryptedBusiness();
         } catch (Throwable ignored) {
         }
      }

      if (HookRegistry.hasTarget(className) || HookRegistry.hasSubclassTargets()) {
         INITIAL_BYTES.putIfAbsent(className, classfileBuffer);
      }
      byte[] current = classfileBuffer;

      if ((HookRegistry.hasRedirects() || HookRegistry.hasRedirectExtends()) && isRedirectScope(className)) {
         current = applyInvokeRedirects(className, current, loader);
      }

      return injectHooks(className, current, loader, classBeingRedefined, protectionDomain);
   }

   private static byte[] injectHooks(String className, byte[] classfileBuffer, ClassLoader loader,
                                     Class<?> classBeingRedefined, ProtectionDomain protectionDomain) {
      if (HookRegistry.hasTarget(className) || HookRegistry.hasSubclassTargets()) {
         if ("net/minecraftforge/common/ForgeMod".equals(className)) {
            System.out.println("DIAG injectHooks hit ForgeMod loader=" + loader
                    + " redefining=" + (classBeingRedefined != null));
         }
         ClassNode classNode = new ClassNode(589824);
         new ClassReader(classfileBuffer).accept(classNode, 8);
         HookRegistry.recordClass(className);
         Set<String> ancestors = ancestors(loader, classNode);
         if (classBeingRedefined != null) {
            collectReflectionAncestors(classBeingRedefined, ancestors);
         }

         Collection<HookDefinition> definitions = HookRegistry.hooksFor(className, ancestors);
         if ("net/minecraftforge/common/ForgeMod".equals(className)) {
            System.out.println("DIAG ForgeMod definitions=" + definitions.size()
                    + " ancestors=" + ancestors.size());
         }
         if (definitions.isEmpty()) {
            return classfileBuffer;
         }

         Map<String, List<HookDefinition>> byMethod = new HashMap<>();

         for (HookDefinition definition : definitions) {
            for (String targetMethod : definition.targetMethods()) {
               byMethod.computeIfAbsent(methodKey(targetMethod, definition.targetDescriptor()), ignored -> new ArrayList<>()).add(definition);
            }
         }

         boolean changed = false;

         for (MethodNode method : classNode.methods) {
            List<HookDefinition> hooks = byMethod.get(methodKey(method.name, method.desc));
            if (hooks == null) {

               hooks = findCompatibleHooks(method, definitions, loader);
            }
            if (hooks != null) {
               hooks.forEach(HookRegistry::recordMatch);
               changed |= applyHooks(method, hooks);
            }
         }

         if (!changed) {
            if (DIAGNOSTIC) {
               Set<String> actualKeys = new HashSet<>();
               for (MethodNode method : classNode.methods) {
                  actualKeys.add(methodKey(method.name, method.desc));
               }

               List<String> unmatched = new ArrayList<>();
               for (HookDefinition definition : definitions) {
                  boolean present = false;
                  for (String targetMethod : definition.targetMethods()) {
                     if (actualKeys.contains(methodKey(targetMethod, definition.targetDescriptor()))) {
                        present = true;
                        break;
                     }
                  }

                  if (!present) {
                     unmatched.add(definition.targetMethods() + " " + definition.targetDescriptor() + " -> " + definition.callbackName());
                  }
               }

               if (!unmatched.isEmpty()) {
                  StringBuilder report = new StringBuilder("[HookTransformer][DIAG] 鐩爣绫?" + className + " 鏈?hook 绛惧悕涓嶇锛堟柟娉曞湪绫讳腑鏍规湰涓嶅瓨鍦級:");
                  for (String entry : unmatched) {
                     report.append("\n  鏈尮閰?").append(entry);
                  }
                  //cnmd编码炸了
                  report.append("\n  璇ョ被瀹為檯鏂规硶:");
                  for (MethodNode method : classNode.methods) {
                     report.append("\n    ").append(method.name).append(method.desc);
                  }

                  System.out.println(report);
               }
            }

            return classfileBuffer;
         }

         int writerFlags = requiresNewFrames(definitions) ? 3 : 1;
         ClassWriter writer = new LoaderAwareClassWriter(writerFlags, loader);
         classNode.accept(writer);
         byte[] transformed = writer.toByteArray();
         validateSchema(classfileBuffer, transformed, className);
         HookRegistry.recordInjected(className, loader);
         return transformed;
      } else {
         return classfileBuffer;
      }
   }


   private static boolean isRedirectScope(String internalName) {
      return internalName.startsWith("net/minecraft/")
         || internalName.startsWith("com/mojang/")
         || (internalName.startsWith("net/minecraftforge/") && !internalName.startsWith("net/minecraftforge/fml/"));
   }

   private static final java.util.Set<String> REDIRECT_EXTENDS_CHECKED = ConcurrentHashMap.newKeySet();

   private static byte[] applyInvokeRedirects(String internalName, byte[] bytes, ClassLoader loader) {
      resolveRedirectExtendsIfNeeded(internalName, bytes, loader);
      if (!containsAnyName(bytes, HookRegistry.redirectMethodNames())) {
         return bytes;
      }
      ClassNode node = new ClassNode(589824);
      new ClassReader(bytes).accept(node, 0);
      boolean changed = false;
      for (MethodNode method : node.methods) {
         for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) {
               continue;
            }
            int op = call.getOpcode();
            if (op != Opcodes.INVOKEVIRTUAL && op != Opcodes.INVOKEINTERFACE && op != Opcodes.INVOKESTATIC) {
               continue;
            }
            List<RedirectDefinition> defs = HookRegistry.redirectsFor(call.owner, call.name, call.desc);
            if (defs.isEmpty()) {
               continue;
            }
            RedirectDefinition def = pickRedirect(defs, op, call);
            if (def == null) {
               continue;
            }

            com.ryjs.hook.DiagLog.log("" + internalName + ": " + call.owner + "." + call.name
               + call.desc + " -> " + def.handlerOwner() + "." + def.handlerName());
            method.instructions.set(insn, new MethodInsnNode(
               Opcodes.INVOKESTATIC, def.handlerOwner(), def.handlerName(), def.handlerDescriptor(), false));
            changed = true;
         }
      }
      if (!changed) {
         return bytes;
      }
      ClassWriter writer = new LoaderAwareClassWriter(ClassWriter.COMPUTE_MAXS, loader);
      node.accept(writer);
      return writer.toByteArray();
   }

   private static RedirectDefinition pickRedirect(List<RedirectDefinition> defs, int opcode, MethodInsnNode call) {
      Type callType = Type.getMethodType(call.desc);
      Type[] callArgs = callType.getArgumentTypes();
      Type callReturn = callType.getReturnType();
      boolean callHasReceiver = opcode != Opcodes.INVOKESTATIC;
      for (RedirectDefinition def : defs) {
         Type handlerType = Type.getMethodType(def.handlerDescriptor());
         Type[] handlerArgs = handlerType.getArgumentTypes();
         boolean handlerHasReceiver = handlerArgs.length == callArgs.length + 1;
         if (handlerHasReceiver != callHasReceiver) {
            continue;
         }
         if (!handlerType.getReturnType().equals(callReturn)) {
            continue;
         }
         return def;
      }
      return null;
   }
   private static void resolveRedirectExtendsIfNeeded(String internalName, byte[] classBytes, ClassLoader loader) {
      if (!HookRegistry.hasRedirectExtends()) {
         return;
      }
      if (!REDIRECT_EXTENDS_CHECKED.add(internalName)) {
         return;
      }
      ClassReader reader = new ClassReader(classBytes);
      for (String iface : reader.getInterfaces()) {
         List<RedirectDefinition> defs = HookRegistry.redirectExtends().get(iface);
         if (defs != null && !defs.isEmpty()) {
            registerRedirectOverrides(internalName, classBytes, defs);
            return;
         }
      }
      String superName = reader.getSuperName();
      while (superName != null && !"java/lang/Object".equals(superName)) {
         List<RedirectDefinition> defs = HookRegistry.redirectExtends().get(superName);
         if (defs != null && !defs.isEmpty()) {
            registerRedirectOverrides(internalName, classBytes, defs);
            return;
         }
         byte[] superBytes = readClassResource(superName, loader);
         if (superBytes == null) {
            return;
         }
         superName = new ClassReader(superBytes).getSuperName();
      }
   }

   private static void registerRedirectOverrides(String internalName, byte[] classBytes, List<RedirectDefinition> extendDefs) {
      Set<String> declared = new HashSet<>();
      new ClassReader(classBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
         @Override
         public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            declared.add(name);
            return null;
         }
      }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      for (RedirectDefinition def : extendDefs) {
         if (declared.contains(def.targetMethod())) {
            HookRegistry.registerSubclassRedirect(internalName, def);
         }
      }
   }

   private static byte[] readClassResource(String internalName, ClassLoader loader) {
      try {
         java.io.InputStream in = (loader != null)
            ? loader.getResourceAsStream(internalName + ".class")
            : ClassLoader.getSystemResourceAsStream(internalName + ".class");
         if (in == null) {
            return null;
         }
         try (java.io.InputStream is = in) {
            return is.readAllBytes();
         }
      } catch (Throwable t) {
         return null;
      }
   }

   private static boolean containsAnyName(byte[] bytes, Set<String> names) {
      if (bytes == null || names.isEmpty()) {
         return false;
      }
      for (String name : names) {
         if (!name.isEmpty() && bytesContain(bytes, name)) {
            return true;
         }
      }
      return false;
   }

   private static boolean bytesContain(byte[] haystack, String needleStr) {
      byte[] needle = needleStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (needle.length == 0 || haystack.length < needle.length) {
         return false;
      }
      outer:
      for (int i = 0; i <= haystack.length - needle.length; i++) {
         for (int j = 0; j < needle.length; j++) {
            if (haystack[i + j] != needle[j]) {
               continue outer;
            }
         }
         return true;
      }
      return false;
   }

   private static final Map<String, Set<String>> COMPAT_ANCESTOR_CACHE = new ConcurrentHashMap<>();

   private static List<HookDefinition> findCompatibleHooks(MethodNode method, Collection<HookDefinition> definitions, ClassLoader loader) {
      List<HookDefinition> result = null;
      for (HookDefinition def : definitions) {
         if (!def.includeSubclasses() || !def.targetMethods().contains(method.name)) {
            continue;
         }
         if (descriptorCompatible(method.desc, def.targetDescriptor(), loader)) {
            if (result == null) {
               result = new ArrayList<>(2);
            }
            result.add(def);
         }
      }
      return result;
   }

   private static boolean descriptorCompatible(String actualDesc, String targetDesc, ClassLoader loader) {
      Type actual = Type.getMethodType(actualDesc);
      Type target = Type.getMethodType(targetDesc);
      Type[] aa = actual.getArgumentTypes();
      Type[] ta = target.getArgumentTypes();
      if (aa.length != ta.length) {
         return false;
      }
      for (int i = 0; i < aa.length; i++) {
         if (!argumentCompatible(aa[i], ta[i], loader)) {
            return false;
         }
      }
      return actual.getReturnType().equals(target.getReturnType());
   }

   private static boolean argumentCompatible(Type actual, Type target, ClassLoader loader) {
      if (actual.equals(target)) {
         return true;
      }
      if (actual.getSort() != Type.OBJECT || target.getSort() != Type.OBJECT) {
         return false;
      }

      return isSubclassOf(actual.getClassName(), target.getClassName(), loader);
   }

   private static boolean isSubclassOf(String child, String parent, ClassLoader loader) {
      if (child.equals(parent)) {
         return true;
      }
      Set<String> ancestors = COMPAT_ANCESTOR_CACHE.computeIfAbsent(child, c -> readSuperChain(c, loader));
      return ancestors.contains(parent);
   }

   private static Set<String> readSuperChain(String className, ClassLoader loader) {
      Set<String> chain = new HashSet<>();
      String current = className;
      while (current != null && !current.equals("java.lang.Object")) {
         String path = current.replace('.', '/') + ".class";
         InputStream in = (loader != null) ? loader.getResourceAsStream(path) : null;
         if (in == null) {
            in = ClassLoader.getSystemResourceAsStream(path);
         }
         if (in == null) {
            break;
         }
         try (InputStream is = in) {
            ClassReader reader = new ClassReader(is);
            String superName = reader.getSuperName();
            if (superName == null) {
               break;
            }
            current = superName.replace('/', '.');
            chain.add(current);
         } catch (Exception e) {
            break;
         }
      }
      return chain;
   }
   private static boolean isSystemClass(String className) {
      if (className == null) {
         return true;
      }

      String n = className.replace('.', '/');
      return n.startsWith("java/")
         || n.startsWith("javax/")
         || n.startsWith("jdk/")
         || n.startsWith("sun/")
         || n.startsWith("com/sun/")
         || n.startsWith("oracle/")
         || n.startsWith("org/objectweb/asm/")
         || n.startsWith("com/ryjs/");
   }

   private static boolean requiresNewFrames(Collection<HookDefinition> definitions) {
      for (HookDefinition definition : definitions) {
         if (definition.mode() == HookMode.GUARD || definition.cancellable()) {
            return true;
         }
      }

      return false;
   }

   private static void validateSchema(byte[] original, byte[] transformed, String requestedName) {
      ClassNode before = structuralNode(original);
      ClassNode after = structuralNode(transformed);
      if (!before.name.equals(after.name)
         || !before.name.equals(requestedName.replace('.', '/'))
         || !Objects.equals(before.superName, after.superName)
         || !before.interfaces.equals(after.interfaces)
         || !memberKeys(before.fields).equals(memberKeys(after.fields))
         || !memberKeys(before.methods).equals(memberKeys(after.methods))) {
         throw new IllegalStateException("Hook transformation changed class schema: " + requestedName);
      }
   }

   private static ClassNode structuralNode(byte[] bytes) {
      ClassNode node = new ClassNode(589824);
      new ClassReader(bytes).accept(node, 7);
      return node;
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

   private static boolean applyHooks(MethodNode method, List<HookDefinition> hooks) {
      if ((method.access & 1280) != 0) {
         return false;
      }

      HookDefinition terminal = null;

      for (HookDefinition hook : hooks) {
         if (hook.mode() == HookMode.OVERRIDE || hook.mode() == HookMode.CLEAR) {
            if (terminal != null) {
               throw new IllegalStateException("Multiple terminal hooks target " + method.name + method.desc);
            }

            terminal = hook;
         }
      }

      if (terminal != null) {
         method.instructions.clear();
         method.tryCatchBlocks.clear();
         method.localVariables = null;
         method.visibleLocalVariableAnnotations = null;
         method.invisibleLocalVariableAnnotations = null;
         if (terminal.mode() == HookMode.OVERRIDE) {
            method.instructions.add(loadArguments(method, terminal));
            method.instructions.add(callbackCall(terminal));
            method.instructions.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(172)));
         } else {
            method.instructions.add(defaultReturn(Type.getReturnType(method.desc)));
         }

         return true;
      } else {
         boolean changed = false;
         Set<String> insertedCallbacks = new HashSet<>();
         InsnList entry = new InsnList();

         for (HookDefinition hook : hooks) {
            String callbackKey = callbackKey(hook);
            if (hook.isHeadMode() && insertedCallbacks.add(callbackKey) && !containsCallback(method, hook)) {
               entry.add(loadArguments(method, hook));
               entry.add(callbackCall(hook));
               if (hook.cancellable()) {
                  entry.add(cancellableReturn(Type.getReturnType(method.desc)));
               }

               changed = true;
            }
         }

         method.instructions.insert(entry);
         Set<String> guardedCallbacks = new HashSet<>();
         InsnList guards = new InsnList();

         for (HookDefinition hook : hooks) {
            String callbackKey = callbackKey(hook);
            if (hook.mode() == HookMode.GUARD && guardedCallbacks.add(callbackKey) && !containsCallback(method, hook)) {
               LabelNode proceed = new LabelNode();
               guards.add(loadArguments(method, hook));
               guards.add(callbackCall(hook));
               guards.add(new JumpInsnNode(153, proceed));
               guards.add(defaultReturn(Type.getReturnType(method.desc)));
               guards.add(proceed);
               changed = true;
            }
         }

         method.instructions.insert(guards);
         Set<String> appendedCallbacks = new HashSet<>();
         List<HookDefinition> appendHooks = new ArrayList<>();

         for (HookDefinition hook : hooks) {
            String callbackKey = callbackKey(hook);
            if (hook.isReturnMode() && appendedCallbacks.add(callbackKey) && !hasDominantReturnCallback(method, hook)) {
               appendHooks.add(hook);
            }
         }

         AbstractInsnNode instruction = method.instructions.getFirst();

         while (instruction != null) {
            AbstractInsnNode next = instruction.getNext();
            if (isReturn(instruction.getOpcode()) && !appendHooks.isEmpty()) {
               InsnList append = new InsnList();
               Type returnType = Type.getReturnType(method.desc);
               int resultLocal = method.maxLocals;
               if (returnType.getSort() != 0) {
                  method.maxLocals = method.maxLocals + returnType.getSize();
                  append.add(new VarInsnNode(returnType.getOpcode(54), resultLocal));
               }

               for (HookDefinition hook : appendHooks) {
                  append.add(loadArguments(method, hook));
                  if (hook.includeResult()) {
                     append.add(new VarInsnNode(returnType.getOpcode(21), resultLocal));
                  }

                  append.add(callbackCall(hook));
                  if (hook.cancellable()) {
                     append.add(resolveReturnResult(returnType, resultLocal));
                  }
               }

               if (returnType.getSort() != 0) {
                  append.add(new VarInsnNode(returnType.getOpcode(21), resultLocal));
               }

               method.instructions.insertBefore(instruction, append);
               changed = true;
            }

            instruction = next;
         }

         return changed;
      }
   }

   private static InsnList loadArguments(MethodNode method, HookDefinition hook) {
      InsnList instructions = new InsnList();
      boolean instanceMethod = (method.access & 8) == 0;
      if (hook.includeThis()) {
         if (!instanceMethod) {
            throw new IllegalStateException("Cannot pass this for static method " + method.name + method.desc);
         }

         instructions.add(new VarInsnNode(25, 0));
      }

      int local = instanceMethod ? 1 : 0;

      for (Type argument : Type.getArgumentTypes(method.desc)) {
         instructions.add(new VarInsnNode(argument.getOpcode(21), local));
         local += argument.getSize();
      }

      return instructions;
   }

   private static MethodInsnNode callbackCall(HookDefinition hook) {
      return new MethodInsnNode(184, hook.callbackOwner(), hook.callbackName(), hook.callbackDescriptor(), false);
   }

   private static InsnList cancellableReturn(Type returnType) {
      InsnList instructions = new InsnList();
      LabelNode proceed = new LabelNode();
      instructions.add(new InsnNode(89));
      instructions.add(new MethodInsnNode(182, Type.getInternalName(HookResult.class), "isCancelled", "()Z", false));
      instructions.add(new JumpInsnNode(153, proceed));
      instructions.add(unboxResult(returnType));
      instructions.add(new InsnNode(returnType.getOpcode(172)));
      instructions.add(proceed);
      instructions.add(new InsnNode(87));
      return instructions;
   }

   private static InsnList resolveReturnResult(Type returnType, int resultLocal) {
      InsnList instructions = new InsnList();
      LabelNode unchanged = new LabelNode();
      LabelNode done = new LabelNode();
      instructions.add(new InsnNode(89));
      instructions.add(new MethodInsnNode(182, Type.getInternalName(HookResult.class), "isCancelled", "()Z", false));
      instructions.add(new JumpInsnNode(153, unchanged));
      instructions.add(unboxResult(returnType));
      if (returnType.getSort() != 0) {
         instructions.add(new VarInsnNode(returnType.getOpcode(54), resultLocal));
      }

      instructions.add(new JumpInsnNode(167, done));
      instructions.add(unchanged);
      instructions.add(new InsnNode(87));
      instructions.add(done);
      return instructions;
   }

   private static InsnList unboxResult(Type type) {
      InsnList instructions = new InsnList();
      instructions.add(new MethodInsnNode(182, Type.getInternalName(HookResult.class), "value", "()Ljava/lang/Object;", false));
      if (type.getSort() == 0) {
         instructions.add(new InsnNode(87));
         return instructions;
      } else if (type.getSort() != 10 && type.getSort() != 9) {
         Type boxed = boxedType(type);
         instructions.add(new TypeInsnNode(192, boxed.getInternalName()));
         instructions.add(new MethodInsnNode(182, boxed.getInternalName(), unboxMethod(type), "()" + type.getDescriptor(), false));
         return instructions;
      } else {
         instructions.add(new TypeInsnNode(192, type.getInternalName()));
         return instructions;
      }
   }

   private static Type boxedType(Type type) {
      return switch (type.getSort()) {
         case 1 -> Type.getType(Boolean.class);
         case 2 -> Type.getType(Character.class);
         case 3 -> Type.getType(Byte.class);
         case 4 -> Type.getType(Short.class);
         case 5 -> Type.getType(Integer.class);
         case 6 -> Type.getType(Float.class);
         case 7 -> Type.getType(Long.class);
         case 8 -> Type.getType(Double.class);
         default -> throw new IllegalArgumentException("Not primitive: " + type);
      };
   }

   private static String unboxMethod(Type type) {
      return switch (type.getSort()) {
         case 1 -> "booleanValue";
         case 2 -> "charValue";
         case 3 -> "byteValue";
         case 4 -> "shortValue";
         case 5 -> "intValue";
         case 6 -> "floatValue";
         case 7 -> "longValue";
         case 8 -> "doubleValue";
         default -> throw new IllegalArgumentException("Not primitive: " + type);
      };
   }

   private static Set<String> ancestors(ClassLoader loader, ClassNode node) {
      Set<String> ancestors = new HashSet<>();
      List<String> pending = new ArrayList<>();
      if (node.superName != null) {
         pending.add(node.superName);
      }

      pending.addAll(node.interfaces);
      ClassLoader effectiveLoader = loader != null ? loader : HookTransformer.class.getClassLoader();

      while (!pending.isEmpty()) {
         String name = pending.remove(pending.size() - 1);
         if (ancestors.add(name)) {
            try (InputStream input = effectiveLoader.getResourceAsStream(name + ".class")) {
               if (input != null) {
                  ClassReader reader = new ClassReader(input);
                  if (reader.getSuperName() != null) {
                     pending.add(reader.getSuperName());
                  }

                  pending.addAll(Arrays.asList(reader.getInterfaces()));
               }
            } catch (IOException var11) {
            }
         }
      }

      return ancestors;
   }

   private static void collectReflectionAncestors(Class<?> type, Set<String> ancestors) {
      if (type != null) {
         Class<?> parent = type.getSuperclass();
         if (parent != null && ancestors.add(parent.getName().replace('.', '/'))) {
            collectReflectionAncestors(parent, ancestors);
         }

         for (Class<?> contract : type.getInterfaces()) {
            if (ancestors.add(contract.getName().replace('.', '/'))) {
               collectReflectionAncestors(contract, ancestors);
            }
         }
      }
   }

   private static boolean containsCallback(MethodNode method, HookDefinition hook) {
      for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
         if (instruction instanceof MethodInsnNode call
            && call.getOpcode() == 184
            && call.owner.equals(hook.callbackOwner())
            && call.name.equals(hook.callbackName())
            && call.desc.equals(hook.callbackDescriptor())) {
            return true;
         }
      }

      return false;
   }

   private static boolean hasDominantReturnCallback(MethodNode method, HookDefinition hook) {
      boolean foundReturn = false;

      for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
         if (isReturn(instruction.getOpcode())) {
            foundReturn = true;
            if (!hasDominantReturnCallback(instruction, hook)) {
               return false;
            }
         }
      }

      return foundReturn;
   }

   private static boolean hasDominantReturnCallback(AbstractInsnNode returnInstruction, HookDefinition hook) {
      int inspected = 0;

      for (AbstractInsnNode instruction = returnInstruction.getPrevious(); instruction != null && inspected++ < 48; instruction = instruction.getPrevious()) {
         int opcode = instruction.getOpcode();
         if (isReturn(opcode) || opcode == 191) {
            return false;
         }

         if (instruction instanceof MethodInsnNode call) {
            if (call.getOpcode() == 184
               && call.owner.equals(hook.callbackOwner())
               && call.name.equals(hook.callbackName())
               && call.desc.equals(hook.callbackDescriptor())) {
               return true;
            }

            if ((
                  call.getOpcode() != 182
                     || !call.owner.equals(Type.getInternalName(HookResult.class))
                     || !call.name.equals("isCancelled") && !call.name.equals("value")
               )
               && !isPrimitiveUnboxCall(call)) {
               return false;
            }
         } else if (opcode >= 0
            && opcode != 0
            && opcode != 89
            && opcode != 87
            && opcode != 167
            && opcode != 153
            && opcode != 192
            && (opcode < 21 || opcode > 25)
            && (opcode < 54 || opcode > 58)) {
            return false;
         }
      }

      return false;
   }

   private static boolean isPrimitiveUnboxCall(MethodInsnNode call) {
      if (call.getOpcode() != 182) {
         return false;
      }

      return switch (call.owner) {
         case "java/lang/Boolean" -> call.name.equals("booleanValue") && call.desc.equals("()Z");
         case "java/lang/Byte" -> call.name.equals("byteValue") && call.desc.equals("()B");
         case "java/lang/Character" -> call.name.equals("charValue") && call.desc.equals("()C");
         case "java/lang/Short" -> call.name.equals("shortValue") && call.desc.equals("()S");
         case "java/lang/Integer" -> call.name.equals("intValue") && call.desc.equals("()I");
         case "java/lang/Float" -> call.name.equals("floatValue") && call.desc.equals("()F");
         case "java/lang/Long" -> call.name.equals("longValue") && call.desc.equals("()J");
         case "java/lang/Double" -> call.name.equals("doubleValue") && call.desc.equals("()D");
         default -> false;
      };
   }

   private static String callbackKey(HookDefinition hook) {
      return hook.callbackOwner() + "." + hook.callbackName() + hook.callbackDescriptor();
   }

   private static InsnList defaultReturn(Type returnType) {
      InsnList instructions = new InsnList();
      switch (returnType.getSort()) {
         case 0:
            instructions.add(new InsnNode(177));
            break;
         case 1:
         case 2:
         case 3:
         case 4:
         case 5:
         default:
            instructions.add(new InsnNode(3));
            instructions.add(new InsnNode(172));
            break;
         case 6:
            instructions.add(new InsnNode(11));
            instructions.add(new InsnNode(174));
            break;
         case 7:
            instructions.add(new InsnNode(9));
            instructions.add(new InsnNode(173));
            break;
         case 8:
            instructions.add(new InsnNode(14));
            instructions.add(new InsnNode(175));
            break;
         case 9:
         case 10:
            instructions.add(new InsnNode(1));
            instructions.add(new InsnNode(176));
      }

      return instructions;
   }

   private static boolean isReturn(int opcode) {
      return opcode >= 172 && opcode <= 177;
   }

   private static String methodKey(String name, String descriptor) {
      return name + descriptor;
   }
}


