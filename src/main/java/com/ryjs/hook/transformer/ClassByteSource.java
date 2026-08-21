package com.ryjs.hook.transformer;

import java.io.IOException;
import java.io.InputStream;

final class ClassByteSource {
   private ClassByteSource() {
   }

   static byte[] read(ClassLoader loader, String internalName) {
      ClassLoader effectiveLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();

      try (InputStream input = effectiveLoader.getResourceAsStream(internalName + ".class")) {
         return input == null ? null : input.readAllBytes();
      } catch (IOException | RuntimeException ignored) {
         return null;
      }
   }
}

