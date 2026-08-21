package com.ryjs.hook.transformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

public final class LoaderAwareClassWriter extends ClassWriter {
   private final BytecodeHierarchy hierarchy;

   public LoaderAwareClassWriter(ClassReader reader, int flags, ClassLoader loader) {
      super(reader, flags);
      this.hierarchy = new BytecodeHierarchy(loader);
   }

   public LoaderAwareClassWriter(int flags, ClassLoader loader) {
      super(flags);
      this.hierarchy = new BytecodeHierarchy(loader);
   }

   protected String getCommonSuperClass(String type1, String type2) {
      return this.hierarchy.commonSuperClass(type1, type2);
   }
}

