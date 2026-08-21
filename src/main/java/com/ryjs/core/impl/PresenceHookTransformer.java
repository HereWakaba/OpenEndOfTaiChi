package com.ryjs.core.impl;


import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;


public final class PresenceHookTransformer implements ClassFileTransformer {

   public static volatile boolean DIAGNOSTIC = false;

   @Override
   public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                           ProtectionDomain protectionDomain, byte[] classfileBuffer) {
      if (className == null || classfileBuffer == null) {
         return null;
      }
      try {
         byte[] out = HookTransformer.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
         if (out != classfileBuffer) {
            if (DIAGNOSTIC) {
               System.out.println("transformed " + className);
            }
            return out;
         }
         if (DIAGNOSTIC && HookRegistry.hasTarget(className)) {
            System.out.println("target seen but unchanged: " + className);
         }
         return null;
      } catch (Throwable t) {
         if (DIAGNOSTIC) {
            System.err.println("transform failed for " + className + ": " + t);
         }
         return null;
      }
   }
}
