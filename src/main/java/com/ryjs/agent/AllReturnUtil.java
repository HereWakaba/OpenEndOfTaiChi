package com.ryjs.agent;

public class AllReturnUtil {
    private static volatile boolean AR = false;
    
    public static void set(boolean a) {
        AR = a;
    }
    
    public static boolean shouldAR() {
        return AR;
    }
}