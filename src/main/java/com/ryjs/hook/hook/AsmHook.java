package com.ryjs.hook.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


//很显然，这个是我从秘密4.1中翻出来的，感谢秘密作者4.1未验证
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(AsmHooks.class)
public @interface AsmHook {
   String targetClass();

   String targetMethod();

   String[] targetAliases() default {};

   String targetDescriptor();

   HookMode mode();

   boolean includeThis() default false;

   boolean includeSubclasses() default false;

   int order() default 0;
}

