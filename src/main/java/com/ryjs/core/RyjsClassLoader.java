package com.ryjs.core;

import java.io.InputStream;
import java.util.jar.JarFile;


public final class RyjsClassLoader extends ClassLoader {


    public static final String MANAGED_PREFIX = "com.ryjs.core.impl.";

    private static volatile RyjsClassLoader INSTANCE;

    private final String jarPath;

    private RyjsClassLoader(String jarPath, ClassLoader parent) {
        super(parent);
        this.jarPath = stripDecorations(jarPath);
    }


    private static String stripDecorations(String path) {
        if (path == null) {
            return null;
        }
        int bang = path.indexOf("!/");
        if (bang != -1) {
            path = path.substring(0, bang);
        }
        int hash = path.indexOf('#');
        if (hash != -1) {
            path = path.substring(0, hash);
        }
        return path;
    }

    public static RyjsClassLoader instance(String jarPath) {
        RyjsClassLoader cur = INSTANCE;
        if (cur != null) {
            return cur;
        }
        synchronized (RyjsClassLoader.class) {
            if (INSTANCE == null) {
                INSTANCE = new RyjsClassLoader(jarPath, RyjsClassLoader.class.getClassLoader());
            }
            return INSTANCE;
        }
    }


    public String jarPath() {
        return jarPath;
    }

    public byte[] readClassBytes(String className) {
        byte[] raw = readRawBytes(className);
        if (raw == null) {
            return null;
        }
        try {
            byte[] dec = NativeDecrypt.tryDecrypt(raw);
            if (dec != null) {
                return dec;
            }
            return decrypt(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    private byte[] readRawBytes(String className) {
        if (jarPath == null) {
            return null;
        }
        String base = className.replace('.', '/');
        try (JarFile jar = new JarFile(jarPath)) {
            for (String suffix : new String[] { ".mcmod", ".class" }) {
                java.util.jar.JarEntry entry = jar.getJarEntry(base + suffix);
                if (entry == null) {
                    continue;
                }
                try (InputStream is = jar.getInputStream(entry)) {
                    return is.readAllBytes();
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }


    private static final int MAGIC = 0x52594A53;

    private static final int KEY_SALT = 0x6A5C4E31;

    private byte[] decrypt(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return bytes;
        }
        int magic = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        if (magic != MAGIC) {
            return bytes;
        }
        int len = ((bytes[4] & 0xFF) << 24) | ((bytes[5] & 0xFF) << 16) | ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        if (len < 0 || len > bytes.length - 8) {
            return null;
        }
        byte[] out = new byte[len];
        int acc = KEY_SALT ^ len;
        for (int i = 0; i < len; i++) {
            acc = acc * 33 ^ (i + len);
            out[i] = (byte) (bytes[8 + i] ^ (byte) acc);
        }
        return out;
    }

    public Class<?> defineClass(String className, byte[] bytes) {
        return defineClass(className, bytes, 0, bytes.length);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(MANAGED_PREFIX)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                c = findClass(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = readClassBytes(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name + " (not found in " + jarPath + ")");
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}
