package com.ryjs.core.impl;


public final class CoreProbe {
    private CoreProbe() {
    }

    public String describe() {
        return "impl=" + getClass().getName() + " loader=" + getClass().getClassLoader().getClass().getName();
    }
}
