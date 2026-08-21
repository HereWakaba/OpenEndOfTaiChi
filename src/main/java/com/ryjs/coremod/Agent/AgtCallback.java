package com.ryjs.coremod.Agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;


public class AgtCallback {

    public static void agentmain(String agentArgs, Instrumentation inst) {
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (clazz.getName().equals("com.ryjs.coremod.Agent.AgentUtil")) {
                try {
                    Field f = clazz.getDeclaredField("INST");
                    f.setAccessible(true);
                    f.set(null, inst);
                } catch (Throwable t) {
                    System.out.print("callbackerr: ");
                    t.printStackTrace();
                }
                return;
            }
        }
    }
}
