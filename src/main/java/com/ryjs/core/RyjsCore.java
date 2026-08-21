package com.ryjs.core;


public interface RyjsCore {

    boolean isHidden();

    String describe();

    boolean isMemoryEntity(Object entity);

    boolean isJarOriginalTarget(String className);

    byte[] restoreBaseline(String className, ClassLoader loader);

    boolean isModified(byte[] current, byte[] baseline);

    boolean isSemanticallyModified(byte[] current, byte[] baseline);
}
