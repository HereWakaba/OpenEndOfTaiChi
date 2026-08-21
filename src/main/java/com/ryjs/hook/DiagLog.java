package com.ryjs.hook;


public final class DiagLog {

   private static final java.io.PrintWriter WRITER = open();

   private DiagLog() {
   }

   private static java.io.PrintWriter open() {
      try {
         return new java.io.PrintWriter(new java.io.FileWriter("reflection_diag.log", true), true);
      } catch (Throwable t) {
         return null;
      }
   }

   public static void log(String msg) {
      System.out.println(msg);
      if (WRITER != null) {
         WRITER.println("[diag] " + msg);
         WRITER.flush();
      }
   }
}
