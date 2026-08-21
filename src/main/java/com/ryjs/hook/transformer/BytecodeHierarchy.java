package com.ryjs.hook.transformer;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;

public final class BytecodeHierarchy {
   private final ClassLoader loader;
   private final ConcurrentHashMap<String, ClassInfo> classes = new ConcurrentHashMap<>();

   public BytecodeHierarchy(ClassLoader loader) {
      this.loader = loader;
   }

   public String commonSuperClass(String first, String second) {
      if (first.equals(second)) {
         return first;
      }

      if (this.isAssignableFrom(first, second)) {
         return first;
      }

      if (this.isAssignableFrom(second, first)) {
         return second;
      }

      ClassInfo firstInfo = this.info(first);
      ClassInfo secondInfo = this.info(second);
      if (firstInfo != null && secondInfo != null && !firstInfo.isInterface() && !secondInfo.isInterface()) {
         String current = firstInfo.superName();

         while (current != null) {
            if (this.isAssignableFrom(current, second)) {
               return current;
            }

            ClassInfo currentInfo = this.info(current);
            current = currentInfo == null ? null : currentInfo.superName();
         }

         return "java/lang/Object";
      } else {
         return "java/lang/Object";
      }
   }

   private boolean isAssignableFrom(String target, String candidate) {
      if (!target.equals(candidate) && !"java/lang/Object".equals(target)) {
         Set<String> visited = new HashSet<>();
         ArrayDeque<String> pending = new ArrayDeque<>();
         pending.add(candidate);

         while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (visited.add(name)) {
               ClassInfo current = this.info(name);
               if (current != null) {
                  if (target.equals(current.superName())) {
                     return true;
                  }

                  if (current.superName() != null) {
                     pending.addLast(current.superName());
                  }

                  for (String interfaceName : current.interfaces()) {
                     if (target.equals(interfaceName)) {
                        return true;
                     }

                     pending.addLast(interfaceName);
                  }
               }
            }
         }

         return false;
      } else {
         return true;
      }
   }

   private ClassInfo info(String internalName) {
      return this.classes.computeIfAbsent(internalName, name -> {
         byte[] bytes = ClassByteSource.read(this.loader, name);
         if (bytes == null) {
            return ClassInfo.MISSING;
         }

         ClassReader reader = new ClassReader(bytes);
         return ClassInfo.present(reader.getSuperName(), reader.getInterfaces(), (reader.getAccess() & 512) != 0);
      }).present() ? this.classes.get(internalName) : null;
   }

   private record ClassInfo(String superName, String[] interfaces, boolean isInterface, boolean present) {
      private static final ClassInfo MISSING = new ClassInfo(null, new String[0], false, false);

      private static ClassInfo present(String superName, String[] interfaces, boolean isInterface) {
         return new ClassInfo(superName, (String[])interfaces.clone(), isInterface, true);
      }
   }
}

