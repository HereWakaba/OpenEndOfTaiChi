package com.ryjs.reflection.client.render;


public final class TaiChiRenderBridge {

    private TaiChiRenderBridge() {}


    static native boolean nativeBind();


    static native void nativeSetOverlayVisible(boolean visible);


    static native void nativePushFrame(int[] pixels, int width, int height, int winX, int winY);


    static native void nativeSetDefenseFlags(int flags);


    public static native void nativeForceRedraw();


    public static native void nativeSetForceRedraw(boolean enabled);

    public static native void nativeSetFullRedraw(boolean enabled);

    public static native void nativeGlAttack();

    public static native void nativeDeathMouseEject(int on);

    public static native void nativeSetDeathGl(int on);

    public static native void nativeDeathGlFrame(int[] pixels, int width, int height);

    public static native void nativeEarlyFrame(int[] pixels, int width, int height);

    public static native void nativeEarlyFrameOff();

    public static native void nativeEarlyBar(int on);
}
