package com.ryjs.core.impl;

import java.util.Objects;
import org.objectweb.asm.Type;


public final class RedirectDefinition {

   private final String targetOwner;
   private final String targetMethod;
   private final String targetDescriptor;
   private final boolean mixinExtends;
   private final String handlerOwner;
   private final String handlerName;
   private final String handlerDescriptor;

   private RedirectDefinition(
      String targetOwner,
      String targetMethod,
      String targetDescriptor,
      boolean mixinExtends,
      String handlerOwner,
      String handlerName,
      String handlerDescriptor
   ) {
      this.targetOwner = targetOwner;
      this.targetMethod = targetMethod;
      this.targetDescriptor = targetDescriptor;
      this.mixinExtends = mixinExtends;
      this.handlerOwner = handlerOwner;
      this.handlerName = handlerName;
      this.handlerDescriptor = handlerDescriptor;
   }


   public static RedirectDefinition parsed(
      String targetClass,
      String targetMethod,
      String targetDescriptor,
      boolean mixinExtends,
      String handlerOwner,
      String handlerName,
      String handlerDescriptor
   ) {
      String owner = normalize(targetClass, "target class");
      String method = requireText(targetMethod, "target method");
      String desc = targetDescriptor == null ? "" : targetDescriptor.trim();
      if (!desc.isEmpty()) {
         try {
            Type.getMethodType(desc);
         } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid redirect target descriptor: " + desc, e);
         }
      }
      String hOwner = normalize(handlerOwner, "handler owner");
      String hName = requireText(handlerName, "handler method");
      String hDesc = requireText(handlerDescriptor, "handler descriptor");
      try {
         Type.getMethodType(hDesc);
      } catch (IllegalArgumentException e) {
         throw new IllegalArgumentException("Invalid handler descriptor: " + hDesc, e);
      }
      return new RedirectDefinition(owner, method, desc, mixinExtends, hOwner, hName, hDesc);
   }


   public String matchKey() {
      return this.targetOwner + "/" + this.targetMethod + "/" + this.targetDescriptor;
   }

   public String targetOwner() {
      return this.targetOwner;
   }

   public String targetMethod() {
      return this.targetMethod;
   }

   public String targetDescriptor() {
      return this.targetDescriptor;
   }

   public boolean mixinExtends() {
      return this.mixinExtends;
   }

   public String handlerOwner() {
      return this.handlerOwner;
   }

   public String handlerName() {
      return this.handlerName;
   }

   public String handlerDescriptor() {
      return this.handlerDescriptor;
   }


   public boolean handlerHasReceiver() {
      return Type.getArgumentTypes(this.handlerDescriptor).length
         == Type.getArgumentTypes(this.targetDescriptorOrAny()).length + 1;
   }


   private String targetDescriptorOrAny() {
      return this.targetDescriptor.isEmpty() ? "()V" : this.targetDescriptor;
   }

   private static String normalize(String value, String label) {
      return requireText(value, label).replace('.', '/');
   }

   private static String requireText(String value, String label) {
      if (value != null && !value.trim().isEmpty()) {
         return value.trim();
      }
      throw new IllegalArgumentException(label + " cannot be empty");
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      }
      if (!(o instanceof RedirectDefinition that)) {
         return false;
      }
      return this.mixinExtends == that.mixinExtends
         && this.targetOwner.equals(that.targetOwner)
         && this.targetMethod.equals(that.targetMethod)
         && this.targetDescriptor.equals(that.targetDescriptor)
         && this.handlerOwner.equals(that.handlerOwner)
         && this.handlerName.equals(that.handlerName)
         && this.handlerDescriptor.equals(that.handlerDescriptor);
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.targetOwner, this.targetMethod, this.targetDescriptor,
         this.mixinExtends, this.handlerOwner, this.handlerName, this.handlerDescriptor);
   }
}
