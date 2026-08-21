package com.ryjs.core.impl;

import com.ryjs.hook.hook.AsmHook;
import com.ryjs.hook.hook.AsmHooks;
import com.ryjs.hook.hook.HookMode;
import com.ryjs.hook.hook.InvokeRedirect;
import com.ryjs.hook.hook.InvokeRedirects;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;


public final class HookScanner {

   private static final int API = Opcodes.ASM9;
   private static final String ASM_HOOK_DESC = "Lcom/ryjs/hook/hook/AsmHook;";
   private static final String ASM_HOOKS_DESC = "Lcom/ryjs/hook/hook/AsmHooks;";
   private static final String REDIRECT_DESC = "Lcom/ryjs/hook/hook/InvokeRedirect;";
   private static final String REDIRECTS_DESC = "Lcom/ryjs/hook/hook/InvokeRedirects;";

   private HookScanner() {
   }


   public record ScanResult(List<HookDefinition> hooks, List<RedirectDefinition> redirects) {
   }


   public static ScanResult scanAll(byte[] classBytes) {
      List<HookDefinition> definitions = new ArrayList<>();
      List<RedirectDefinition> redirects = new ArrayList<>();
      ClassReader reader = new ClassReader(classBytes);
      String owner = reader.getClassName();
      reader.accept(
         new ScanningClassVisitor(owner, definitions, redirects),
         ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
      );
      return new ScanResult(definitions, redirects);
   }

   public static List<HookDefinition> scan(byte[] classBytes) {
      return scanAll(classBytes).hooks();
   }

   private static final class ScanningClassVisitor extends ClassVisitor {
      private final String owner;
      private final List<HookDefinition> sink;
      private final List<RedirectDefinition> redirectSink;

      ScanningClassVisitor(String owner, List<HookDefinition> sink, List<RedirectDefinition> redirectSink) {
         super(API);
         this.owner = owner;
         this.sink = sink;
         this.redirectSink = redirectSink;
      }

      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
         return new ScanningMethodVisitor(this.owner, access, name, descriptor, this.sink, this.redirectSink);
      }
   }

   private static final class ScanningMethodVisitor extends MethodVisitor {
      private final String owner;
      private final int access;
      private final String name;
      private final String descriptor;
      private final List<HookDefinition> sink;
      private final List<RedirectDefinition> redirectSink;
      private final List<ParsedHook> parsed = new ArrayList<>();
      private final List<ParsedRedirect> parsedRedirects = new ArrayList<>();

      ScanningMethodVisitor(String owner, int access, String name, String descriptor, List<HookDefinition> sink,
                            List<RedirectDefinition> redirectSink) {
         super(API);
         this.owner = owner;
         this.access = access;
         this.name = name;
         this.descriptor = descriptor;
         this.sink = sink;
         this.redirectSink = redirectSink;
      }

      @Override
      public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
         if (ASM_HOOK_DESC.equals(annotationDescriptor)) {
            return new HookAnnotationVisitor(this.parsed::add);
         } else if (ASM_HOOKS_DESC.equals(annotationDescriptor)) {
            return new AnnotationVisitor(API) {
               @Override
               public AnnotationVisitor visitArray(String arrayName) {
                  if ("value".equals(arrayName)) {
                     return new AnnotationVisitor(API) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String elementName, String elementDescriptor) {
                           return new HookAnnotationVisitor(ScanningMethodVisitor.this.parsed::add);
                        }
                     };
                  }
                  return null;
               }
            };
         } else if (REDIRECT_DESC.equals(annotationDescriptor)) {
            return new RedirectAnnotationVisitor(this.parsedRedirects::add);
         } else if (REDIRECTS_DESC.equals(annotationDescriptor)) {
            return new AnnotationVisitor(API) {
               @Override
               public AnnotationVisitor visitArray(String arrayName) {
                  if ("value".equals(arrayName)) {
                     return new AnnotationVisitor(API) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String elementName, String elementDescriptor) {
                           return new RedirectAnnotationVisitor(ScanningMethodVisitor.this.parsedRedirects::add);
                        }
                     };
                  }
                  return null;
               }
            };
         }
         return null;
      }

      @Override
      public void visitEnd() {
         for (ParsedHook hook : this.parsed) {
            this.sink.add(HookDefinition.parsed(
               hook.targetClass,
               hook.targetMethod,
               hook.aliases.toArray(new String[0]),
               hook.targetDescriptor,
               hook.mode,
               hook.includeThis,
               hook.includeSubclasses,
               hook.order,
               this.owner,
               this.name,
               this.descriptor,
               this.access
            ));
         }
         for (ParsedRedirect redirect : this.parsedRedirects) {
            this.redirectSink.add(RedirectDefinition.parsed(
               redirect.targetClass,
               redirect.method,
               redirect.desc,
               redirect.mixinExtends,
               this.owner,
               this.name,
               this.descriptor
            ));
         }
      }
   }

   private static final class HookAnnotationVisitor extends AnnotationVisitor {
      private final Consumer<ParsedHook> sink;
      private final ParsedHook hook = new ParsedHook();

      HookAnnotationVisitor(Consumer<ParsedHook> sink) {
         super(API);
         this.sink = sink;
      }

      @Override
      public void visit(String name, Object value) {
         switch (name) {
            case "targetClass" -> this.hook.targetClass = (String) value;
            case "targetMethod" -> this.hook.targetMethod = (String) value;
            case "targetDescriptor" -> this.hook.targetDescriptor = (String) value;
            case "includeThis" -> this.hook.includeThis = Boolean.TRUE.equals(value);
            case "includeSubclasses" -> this.hook.includeSubclasses = Boolean.TRUE.equals(value);
            case "order" -> this.hook.order = value instanceof Integer intValue ? intValue : 0;
            default -> {
            }
         }
      }

      @Override
      public void visitEnum(String name, String descriptor, String value) {
         if ("mode".equals(name)) {
            this.hook.mode = HookMode.valueOf(value);
         }
      }

      @Override
      public AnnotationVisitor visitArray(String name) {
         if ("targetAliases".equals(name)) {
            return new AnnotationVisitor(API) {
               @Override
               public void visit(String ignored, Object value) {
                  if (value instanceof String alias) {
                     HookAnnotationVisitor.this.hook.aliases.add(alias);
                  }
               }
            };
         }
         return null;
      }

      @Override
      public void visitEnd() {
         this.sink.accept(this.hook);
      }
   }

   private static final class ParsedHook {
      String targetClass;
      String targetMethod;
      String targetDescriptor;
      HookMode mode;
      boolean includeThis;
      boolean includeSubclasses;
      int order;
      final List<String> aliases = new ArrayList<>();
   }

   private static final class RedirectAnnotationVisitor extends AnnotationVisitor {
      private final Consumer<ParsedRedirect> sink;
      private final ParsedRedirect redirect = new ParsedRedirect();

      RedirectAnnotationVisitor(Consumer<ParsedRedirect> sink) {
         super(API);
         this.sink = sink;
      }

      @Override
      public void visit(String name, Object value) {
         switch (name) {
            case "targetClass" -> this.redirect.targetClass = (String) value;
            case "method" -> this.redirect.method = (String) value;
            case "desc" -> this.redirect.desc = value == null ? "" : (String) value;
            case "mixinExtends" -> this.redirect.mixinExtends = Boolean.TRUE.equals(value);
            default -> {
            }
         }
      }

      @Override
      public void visitEnd() {
         this.sink.accept(this.redirect);
      }
   }

   private static final class ParsedRedirect {
      String targetClass;
      String method;
      String desc = "";
      boolean mixinExtends;
   }
}
