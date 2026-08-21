package com.ryjs.core.impl;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.HookResult;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.objectweb.asm.Type;

public final class HookDefinition implements Comparable<HookDefinition> {
   private final String targetClass;
   private final String targetMethod;
   private final Set<String> targetMethods;
   private final String targetDescriptor;
   private final HookMode mode;
   private final boolean includeThis;
   private final boolean includeSubclasses;
   private final boolean includeResult;
   private final int order;
   private final String callbackOwner;
   private final String callbackName;
   private final String callbackDescriptor;

   private HookDefinition(AsmHook annotation, Method callback) {
      this.targetClass = normalizeClassName(annotation.targetClass());
      this.targetMethod = requireText(annotation.targetMethod(), "target method");
      this.targetMethods = Stream.concat(
            Stream.of(this.targetMethod), Arrays.stream(annotation.targetAliases()).map(alias -> requireText(alias, "target alias"))
         )
         .collect(Collectors.toUnmodifiableSet());
      this.targetDescriptor = requireMethodDescriptor(annotation.targetDescriptor());
      this.mode = Objects.requireNonNull(annotation.mode(), "mode");
      this.includeThis = annotation.includeThis();
      this.includeSubclasses = annotation.includeSubclasses();
      this.order = annotation.order();
      this.callbackOwner = Type.getInternalName(callback.getDeclaringClass());
      this.callbackName = callback.getName();
      this.callbackDescriptor = Type.getMethodDescriptor(callback);
      this.includeResult = this.detectResultArgument();
      this.validateCallback(callback);
   }

   public static HookDefinition from(AsmHook annotation, Method callback) {
      Objects.requireNonNull(annotation, "annotation");
      Objects.requireNonNull(callback, "callback");
      return new HookDefinition(annotation, callback);
   }

   public static HookDefinition override(
      String targetClass,
      String targetMethod,
      String targetDescriptor,
      boolean includeThis,
      String callbackOwner,
      String callbackName,
      String callbackDescriptor
   ) {
      return new HookDefinition(targetClass, targetMethod, targetDescriptor, includeThis, callbackOwner, callbackName, callbackDescriptor);
   }

   private HookDefinition(
      String targetClass,
      String targetMethod,
      String targetDescriptor,
      boolean includeThis,
      String callbackOwner,
      String callbackName,
      String callbackDescriptor
   ) {
      this.targetClass = normalizeClassName(targetClass);
      this.targetMethod = requireText(targetMethod, "target method");
      this.targetMethods = Set.of(this.targetMethod);
      this.targetDescriptor = requireMethodDescriptor(targetDescriptor);
      this.mode = HookMode.OVERRIDE;
      this.includeThis = includeThis;
      this.includeSubclasses = false;
      this.includeResult = false;
      this.order = 0;
      this.callbackOwner = normalizeClassName(callbackOwner);
      this.callbackName = requireText(callbackName, "callback method");
      this.callbackDescriptor = requireMethodDescriptor(callbackDescriptor);
      Type targetType = Type.getMethodType(this.targetDescriptor);
      Type callbackType = Type.getMethodType(this.callbackDescriptor);
      Type[] targetArguments = targetType.getArgumentTypes();
      Type[] callbackArguments = callbackType.getArgumentTypes();
      int offset = includeThis ? 1 : 0;
      if (callbackArguments.length != targetArguments.length + offset
         || includeThis && !callbackArguments[0].equals(Type.getObjectType(this.targetClass))
         || !Arrays.equals(targetArguments, Arrays.copyOfRange(callbackArguments, offset, callbackArguments.length))
         || !callbackType.getReturnType().equals(targetType.getReturnType())) {
         throw new IllegalArgumentException("Override callback must match target descriptor");
      }
   }

   public static HookDefinition parsed(
      String targetClass,
      String targetMethod,
      String[] targetAliases,
      String targetDescriptor,
      HookMode mode,
      boolean includeThis,
      boolean includeSubclasses,
      int order,
      String callbackOwner,
      String callbackName,
      String callbackDescriptor,
      int callbackAccess
   ) {
      return new HookDefinition(
         targetClass, targetMethod, targetAliases, targetDescriptor, mode,
         includeThis, includeSubclasses, order, callbackOwner, callbackName, callbackDescriptor, callbackAccess
      );
   }

   private HookDefinition(
      String targetClass,
      String targetMethod,
      String[] targetAliases,
      String targetDescriptor,
      HookMode mode,
      boolean includeThis,
      boolean includeSubclasses,
      int order,
      String callbackOwner,
      String callbackName,
      String callbackDescriptor,
      int callbackAccess
   ) {
      this.targetClass = normalizeClassName(targetClass);
      this.targetMethod = requireText(targetMethod, "target method");
      this.targetMethods = Stream.concat(
            Stream.of(this.targetMethod),
            Arrays.stream(targetAliases == null ? new String[0] : targetAliases).map(alias -> requireText(alias, "target alias"))
         )
         .collect(Collectors.toUnmodifiableSet());
      this.targetDescriptor = requireMethodDescriptor(targetDescriptor);
      this.mode = Objects.requireNonNull(mode, "mode");
      this.includeThis = includeThis;
      this.includeSubclasses = includeSubclasses;
      this.order = order;
      this.callbackOwner = normalizeClassName(callbackOwner);
      this.callbackName = requireText(callbackName, "callback method");
      this.callbackDescriptor = requireMethodDescriptor(callbackDescriptor);
      this.includeResult = this.detectResultArgument();
      this.validateParsedCallback(callbackAccess);
   }

   private void validateCallback(Method callback) {
      if (!Modifier.isPublic(callback.getModifiers()) || !Modifier.isStatic(callback.getModifiers())) {
         throw new IllegalArgumentException("ASM hook callback must be public static: " + callback);
      }

      if (!Modifier.isPublic(callback.getDeclaringClass().getModifiers())) {
         throw new IllegalArgumentException("ASM hook callback class must be public: " + callback.getDeclaringClass());
      }

      this.validateDescriptors(String.valueOf(callback));
   }

   private void validateParsedCallback(int callbackAccess) {
      if (!Modifier.isPublic(callbackAccess) || !Modifier.isStatic(callbackAccess)) {
         throw new IllegalArgumentException("ASM hook callback must be public static: " + this.callbackOwner + "." + this.callbackName);
      }

      this.validateDescriptors(this.callbackOwner + "." + this.callbackName + this.callbackDescriptor);
   }

   private void validateDescriptors(String callbackLabel) {
      if (this.targetMethod.equals("<clinit>")) {
         throw new IllegalArgumentException("Static initializer hooks are not supported: " + this.targetClass + "." + this.targetMethod);
      }
      boolean constructor = this.targetMethod.equals("<init>");
      if (constructor) {
         if (this.includeThis) {
            throw new IllegalArgumentException("Constructor hooks cannot include this (uninitialized this before super()): " + callbackLabel);
         }
         if (this.mode != HookMode.HEAD && this.mode != HookMode.RETURN) {
            throw new IllegalArgumentException("Constructor hooks support only HEAD/RETURN modes: " + callbackLabel);
         }
      }
      Type targetType = Type.getMethodType(this.targetDescriptor);
         Type callbackType = Type.getMethodType(this.callbackDescriptor);
         Type[] targetArguments = targetType.getArgumentTypes();
         Type[] callbackArguments = callbackType.getArgumentTypes();
         if (this.mode != HookMode.CLEAR) {
            int offset = this.includeThis ? 1 : 0;
            int baseArguments = targetArguments.length + offset;
            boolean validResultArgument = this.isReturnMode()
               && targetType.getReturnType().getSort() != 0
               && callbackArguments.length == baseArguments + 1
               && callbackArguments[callbackArguments.length - 1].equals(targetType.getReturnType());
            if ((callbackArguments.length == baseArguments || validResultArgument)
               && (!this.includeThis || callbackArguments[0].equals(Type.getObjectType(this.targetClass)))
               && Arrays.equals(targetArguments, Arrays.copyOfRange(callbackArguments, offset, offset + targetArguments.length))) {
               Type callbackReturn = callbackType.getReturnType();
               Type targetReturn = targetType.getReturnType();
               if (this.mode == HookMode.OVERRIDE && !callbackReturn.equals(targetReturn)) {
                  throw new IllegalArgumentException("OVERRIDE hook return type must match target: " + callbackLabel);
               }

               if ((this.isHeadMode() || this.isReturnMode()) && callbackReturn.getSort() != 0 && !callbackReturn.equals(Type.getType(HookResult.class))) {
                  throw new IllegalArgumentException(this.mode + " hook must return void or HookResult: " + callbackLabel);
               }

               if (this.mode == HookMode.GUARD && callbackReturn.getSort() != 1) {
                  throw new IllegalArgumentException("GUARD hook requires a boolean callback: " + callbackLabel);
               }
            } else {
               throw new IllegalArgumentException("Hook arguments must match target arguments and optional return value: " + callbackLabel);
            }
         } else if (callbackArguments.length != 0 || callbackType.getReturnType().getSort() != 0) {
            throw new IllegalArgumentException("CLEAR marker callback must have signature ()V: " + callbackLabel);
         }
   }

   private static String normalizeClassName(String name) {
      return requireText(name, "target class").replace('.', '/');
   }

   private static String requireText(String value, String label) {
      if (value != null && !value.trim().isEmpty()) {
         return value.trim();
      } else {
         throw new IllegalArgumentException(label + " cannot be empty");
      }
   }

   private static String requireMethodDescriptor(String descriptor) {
      String value = requireText(descriptor, "target descriptor");

      try {
         Type.getMethodType(value);
         return value;
      } catch (IllegalArgumentException exception) {
         throw new IllegalArgumentException("Invalid target method descriptor: " + value, exception);
      }
   }

   public String targetClass() {
      return this.targetClass;
   }

   public String targetMethod() {
      return this.targetMethod;
   }

   public Set<String> targetMethods() {
      return this.targetMethods;
   }

   public String targetDescriptor() {
      return this.targetDescriptor;
   }

   public HookMode mode() {
      return this.mode;
   }

   public boolean includeThis() {
      return this.includeThis;
   }

   public boolean includeSubclasses() {
      return this.includeSubclasses;
   }

   public boolean cancellable() {
      return Type.getReturnType(this.callbackDescriptor).equals(Type.getType(HookResult.class));
   }

   public boolean includeResult() {
      return this.includeResult;
   }

   private boolean detectResultArgument() {
      Type targetType = Type.getMethodType(this.targetDescriptor);
      Type callbackType = Type.getMethodType(this.callbackDescriptor);
      return this.isReturnMode()
         && targetType.getReturnType().getSort() != 0
         && callbackType.getArgumentTypes().length == targetType.getArgumentTypes().length + (this.includeThis ? 2 : 1);
   }

   public boolean isHeadMode() {
      return this.mode == HookMode.HEAD || this.mode == HookMode.INSERT;
   }

   public boolean isReturnMode() {
      return this.mode == HookMode.RETURN || this.mode == HookMode.APPEND;
   }

   public String callbackOwner() {
      return this.callbackOwner;
   }

   public String callbackName() {
      return this.callbackName;
   }

   public String callbackDescriptor() {
      return this.callbackDescriptor;
   }

   public int compareTo(HookDefinition other) {
      int byOrder = Integer.compare(this.order, other.order);
      if (byOrder != 0) {
         return byOrder;
      }

      int byOwner = this.callbackOwner.compareTo(other.callbackOwner);
      return byOwner != 0 ? byOwner : this.callbackName.compareTo(other.callbackName);
   }
}

