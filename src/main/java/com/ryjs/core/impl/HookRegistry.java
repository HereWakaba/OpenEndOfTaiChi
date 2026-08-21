package com.ryjs.core.impl;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.HookResult;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class HookRegistry {
   private static final Map<String, CopyOnWriteArrayList<HookDefinition>> HOOKS = new ConcurrentHashMap<>();
   private static final Set<HookDefinition> MATCHED = ConcurrentHashMap.newKeySet();
   private static final Set<String> SEEN_CLASSES = ConcurrentHashMap.newKeySet();

   private static final Map<String, CopyOnWriteArrayList<RedirectDefinition>> REDIRECTS = new ConcurrentHashMap<>();

   private static final Map<String, CopyOnWriteArrayList<RedirectDefinition>> REDIRECT_EXTENDS = new ConcurrentHashMap<>();
   private static final Set<String> GUARD_CLASSES = ConcurrentHashMap.newKeySet();

   private static final Map<String, ClassLoader> INJECTED_CLASSES = new ConcurrentHashMap<>();
   private static volatile boolean subclassTargets;

   private HookRegistry() {
   }

   public static List<HookDefinition> register(Class<?> callbackContainer) {
      Objects.requireNonNull(callbackContainer, "callbackContainer");
      List<HookDefinition> added = new ArrayList<>();

      for (Method method : callbackContainer.getDeclaredMethods()) {
         for (AsmHook annotation : method.getAnnotationsByType(AsmHook.class)) {
            HookDefinition definition = HookDefinition.from(annotation, method);
            HOOKS.computeIfAbsent(definition.targetClass(), ignored -> new CopyOnWriteArrayList<>()).add(definition);
            HOOKS.get(definition.targetClass()).sort(null);
            subclassTargets = subclassTargets | definition.includeSubclasses();
            added.add(definition);
         }
      }

      return Collections.unmodifiableList(added);
   }

   public static void register(HookDefinition definition) {
      Objects.requireNonNull(definition, "definition");
      HOOKS.computeIfAbsent(definition.targetClass(), ignored -> new CopyOnWriteArrayList<>()).add(definition);
      HOOKS.get(definition.targetClass()).sort(null);
      subclassTargets = subclassTargets | definition.includeSubclasses();
   }


   public static List<HookDefinition> register(byte[] callbackClassBytes) {
      Objects.requireNonNull(callbackClassBytes, "callbackClassBytes");
      HookScanner.ScanResult result = HookScanner.scanAll(callbackClassBytes);
      List<HookDefinition> added = new ArrayList<>();
      for (HookDefinition definition : result.hooks()) {
         register(definition);
         added.add(definition);
      }
      for (RedirectDefinition redirect : result.redirects()) {
         registerRedirect(redirect);
      }
      return Collections.unmodifiableList(added);
   }

   public static Collection<HookDefinition> hooksFor(String className) {
      if (className == null) {
         return Collections.emptyList();
      }

      List<HookDefinition> definitions = HOOKS.get(className.replace('.', '/'));
      return definitions == null ? Collections.emptyList() : definitions;
   }

   public static Collection<HookDefinition> hooksFor(String className, Collection<String> ancestors) {
      List<HookDefinition> result = new ArrayList<>(hooksFor(className));

      for (String ancestor : ancestors) {
         for (HookDefinition definition : hooksFor(ancestor)) {
            if (definition.includeSubclasses()) {
               result.add(definition);
            }
         }
      }

      result.sort(null);
      return Collections.unmodifiableList(result);
   }

   public static boolean hasTarget(String className) {
      return className != null && HOOKS.containsKey(className.replace('.', '/'));
   }


   public static void registerRedirect(RedirectDefinition definition) {
      Objects.requireNonNull(definition, "redirect definition");
      REDIRECTS.computeIfAbsent(definition.matchKey(), ignored -> new CopyOnWriteArrayList<>()).add(definition);
      if (definition.mixinExtends()) {
         REDIRECT_EXTENDS.computeIfAbsent(definition.targetOwner(), ignored -> new CopyOnWriteArrayList<>()).add(definition);
      }
   }

   public static void registerSubclassRedirect(String subclassOwner, RedirectDefinition definition) {
      String key = subclassOwner + "/" + definition.targetMethod() + "/" + definition.targetDescriptor();
      REDIRECTS.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(definition);
   }

   public static boolean hasRedirects() {
      return !REDIRECTS.isEmpty();
   }

   public static int redirectCount() {
      int n = 0;
      for (List<RedirectDefinition> defs : REDIRECTS.values()) {
         n += defs.size();
      }
      return n;
   }

   public static boolean hasRedirectExtends() {
      return !REDIRECT_EXTENDS.isEmpty();
   }

   public static List<RedirectDefinition> redirectsFor(String owner, String name, String desc) {
      if (REDIRECTS.isEmpty() || owner == null || name == null) {
         return Collections.emptyList();
      }
      List<RedirectDefinition> exact = REDIRECTS.get(owner + "/" + name + "/" + (desc == null ? "" : desc));
      List<RedirectDefinition> wildcard = REDIRECTS.get(owner + "/" + name + "/");
      if ((exact == null || exact.isEmpty()) && (wildcard == null || wildcard.isEmpty())) {
         return Collections.emptyList();
      }
      List<RedirectDefinition> result = new ArrayList<>();
      if (exact != null) {
         result.addAll(exact);
      }
      if (wildcard != null) {
         result.addAll(wildcard);
      }
      return Collections.unmodifiableList(result);
   }


   public static Map<String, CopyOnWriteArrayList<RedirectDefinition>> redirectExtends() {
      return REDIRECT_EXTENDS;
   }


   public static Set<String> redirectMethodNames() {
      if (REDIRECTS.isEmpty() && REDIRECT_EXTENDS.isEmpty()) {
         return Collections.emptySet();
      }
      Set<String> names = ConcurrentHashMap.newKeySet();
      for (List<RedirectDefinition> defs : REDIRECTS.values()) {
         for (RedirectDefinition d : defs) {
            names.add(d.targetMethod());
         }
      }
      for (List<RedirectDefinition> defs : REDIRECT_EXTENDS.values()) {
         for (RedirectDefinition d : defs) {
            names.add(d.targetMethod());
         }
      }
      return names;
   }


   public static void recordGuardClass(String internalName) {
      if (internalName != null) {
         GUARD_CLASSES.add(internalName);
      }
   }

   public static boolean isGuardClass(String internalName) {
      return internalName != null && GUARD_CLASSES.contains(internalName);
   }

   public static Set<String> guardClasses() {
      return Set.copyOf(GUARD_CLASSES);
   }

   public static boolean hasSubclassTargets() {
      return subclassTargets;
   }

   static void recordClass(String className) {
      if (className != null) {
         SEEN_CLASSES.add(className.replace('.', '/'));
      }
   }

   static void recordMatch(HookDefinition definition) {
      MATCHED.add(definition);
   }


   static void recordInjected(String className, ClassLoader loader) {
      if (className != null) {
         INJECTED_CLASSES.putIfAbsent(className.replace('.', '/'), loader);
      }
   }


   public static Map<String, ClassLoader> injectedClasses() {
      return Map.copyOf(INJECTED_CLASSES);
   }

   public static HookAudit audit() {
      List<String> missingTargets = new ArrayList<>();

      for (Entry<String, CopyOnWriteArrayList<HookDefinition>> entry : HOOKS.entrySet()) {
         for (HookDefinition definition : entry.getValue()) {
            if (!MATCHED.contains(definition)) {
               missingTargets.add(
                  definition.targetClass()
                     + "."
                     + definition.targetMethod()
                     + definition.targetDescriptor()
                     + " -> "
                     + definition.callbackOwner().replace('/', '.')
                     + "."
                     + definition.callbackName()
                     + definition.callbackDescriptor()
               );
            }
         }
      }

      Collections.sort(missingTargets);
      List<String> unseenClasses = HOOKS.keySet().stream().filter(target -> !SEEN_CLASSES.contains(target)).sorted().toList();
      return new HookAudit(HOOKS.values().stream().mapToInt(Collection::size).sum(), MATCHED.size(), unseenClasses, missingTargets);
   }

   public static Set<String> targets() {
      return Set.copyOf(HOOKS.keySet());
   }

   public static void clear() {
      HOOKS.clear();
      MATCHED.clear();
      SEEN_CLASSES.clear();
      INJECTED_CLASSES.clear();
      REDIRECTS.clear();
      REDIRECT_EXTENDS.clear();
      GUARD_CLASSES.clear();
      subclassTargets = false;
   }

   public record HookAudit(int registeredHooks, int matchedHooks, List<String> unseenClasses, List<String> missingTargets) {
      public boolean complete() {
         return this.missingTargets.isEmpty();
      }

      public String diagnostic() {
         return this.complete()
            ? "All " + this.registeredHooks + " registered hooks matched"
            : "Matched " + this.matchedHooks + "/" + this.registeredHooks + " hooks; missing targets: " + String.join(", ", this.missingTargets);
      }
   }
}

