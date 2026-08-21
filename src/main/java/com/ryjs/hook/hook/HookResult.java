package com.ryjs.hook.hook;

public final class HookResult<T> {
   private static final HookResult<?> PASS = new HookResult(false, null);
   private final boolean cancelled;
   private final T value;

   private HookResult(boolean cancelled, T value) {
      this.cancelled = cancelled;
      this.value = value;
   }

   public static <T> HookResult<T> pass() {
      return (HookResult<T>)PASS;
   }

   public static <T> HookResult<T> returnValue(T value) {
      return new HookResult<>(true, value);
   }

   public static HookResult<Void> cancel() {
      return returnValue(null);
   }

   public boolean isCancelled() {
      return this.cancelled;
   }

   public T value() {
      return this.value;
   }
}

