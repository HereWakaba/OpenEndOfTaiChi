package com.ryjs.reflection.death;


public class DeathOverlay extends McWindowOverlay {

    private static DeathOverlay instance;

    private DeathOverlay() {
        super();
    }


    public static void open() {
        if (instance != null && instance.isVisible()) {
            return;
        }
        if (instance != null) {
            instance.close();
            instance = null;
        }
        instance = new DeathOverlay();
        instance.preInit();
        instance.show();
    }

    public static boolean isOverlayVisible() {
        return instance != null && instance.isVisible();
    }

    public static void forceClose() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }
}
