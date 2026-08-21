package com.ryjs.coremod;


public final class ShouldAllretun {

    private static volatile boolean enabled = false;

    private ShouldAllretun() {}

    public static boolean shouldReturn() {
        return enabled;
    }

}
