package com.ryjs.reflection.util;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

public final class Int2ObjectMapUtil<V> {
    private static final List<String> blackField = List.of("values", "keys", "entries", "map");
    private transient int[] key;
    private transient V[] value;
    private transient int mask;
    private transient boolean containsNullKey;
    private transient int first = -1;
    private transient int last = -1;
    private transient long[] link;
    private transient int n;
    private transient int maxFill;
    private transient int minN;
    private int size;
    private float f;
    private final Int2ObjectLinkedOpenHashMap<V> map;

    private Int2ObjectMapUtil(Int2ObjectLinkedOpenHashMap<V> map) {
        this.map = map;
        try {
            Field[] fields = Int2ObjectMapUtil.class.getDeclaredFields();
            AccessibleObject.setAccessible((AccessibleObject[])fields, (boolean)true);
            for (Field field : fields) {
                if (Modifier.isStatic((int)field.getModifiers()) || blackField.contains(field.getName())) continue;
                Field field1 = Int2ObjectLinkedOpenHashMap.class.getDeclaredField(field.getName());
                field1.setAccessible(true);
                field.set(this, field1.get(this.map));
            }
        }
        catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public static <K> Int2ObjectMapUtil<K> getInstance(Int2ObjectLinkedOpenHashMap<K> map) {
        return new Int2ObjectMapUtil<K>(map);
    }

    public Int2ObjectMapUtil<V> remove(int k) {
        if (k == 0) {
            if (this.containsNullKey) {
                return this.removeNullEntry();
            }
            return this;
        }
        int[] key = this.key;
        int pos = HashCommon.mix((int)k) & this.mask;
        int curr = key[pos];
        if (curr == 0) {
            return this;
        }
        if (k == curr) {
            return this.removeEntry(pos);
        }
        do {
            if ((curr = key[pos = pos + 1 & this.mask]) != 0) continue;
            return this;
        } while (k != curr);
        return this.removeEntry(pos);
    }

    public Int2ObjectLinkedOpenHashMap<V> synchronize() {
        try {
            Field[] fields = Int2ObjectLinkedOpenHashMap.class.getDeclaredFields();
            AccessibleObject.setAccessible((AccessibleObject[])fields, (boolean)true);
            for (Field field : fields) {
                if (Modifier.isStatic((int)field.getModifiers()) || blackField.contains(field.getName())) continue;
                Field field1 = Int2ObjectMapUtil.class.getDeclaredField(field.getName());
                field1.setAccessible(true);
                field.set(this.map, field1.get(this));
            }
        }
        catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        return this.map;
    }

    private Int2ObjectMapUtil<V> removeNullEntry() {
        this.containsNullKey = false;
        this.value[this.n] = null;
        --this.size;
        this.fixPointers(this.n);
        if (this.n > this.minN && this.size < this.maxFill / 4 && this.n > 16) {
            this.rehash(this.n / 2);
        }
        return this;
    }

    private void rehash(int newN) {
        int[] key = this.key;
        V[] value = this.value;
        int mask = newN - 1;
        int[] newKey = new int[newN + 1];
        @SuppressWarnings("unchecked")
        V[] newValue = (V[]) new Object[newN + 1];
        int i = this.first;
        int prev = -1;
        int newPrev = -1;
        long[] link = this.link;
        long[] newLink = new long[newN + 1];
        this.first = -1;
        int j = this.size;
        while (j-- != 0) {
            int pos;
            if (key[i] == 0) {
                pos = newN;
            } else {
                pos = HashCommon.mix((int)key[i]) & mask;
                while (newKey[pos] != 0) {
                    pos = pos + 1 & mask;
                }
            }
            newKey[pos] = key[i];
            newValue[pos] = value[i];
            if (prev != -1) {
                int n = newPrev;
                newLink[n] = newLink[n] ^ (newLink[newPrev] ^ (long)pos & 0xFFFFFFFFL) & 0xFFFFFFFFL;
                int n2 = pos;
                newLink[n2] = newLink[n2] ^ (newLink[pos] ^ ((long)newPrev & 0xFFFFFFFFL) << 32) & 0xFFFFFFFF00000000L;
                newPrev = pos;
            } else {
                newPrev = this.first = pos;
                newLink[pos] = -1L;
            }
            int t = i;
            i = (int)link[i];
            prev = t;
        }
        this.link = newLink;
        this.last = newPrev;
        if (newPrev != -1) {
            int n = newPrev;
            newLink[n] = newLink[n] | 0xFFFFFFFFL;
        }
        this.n = newN;
        this.mask = mask;
        this.maxFill = HashCommon.maxFill((int)this.n, (float)this.f);
        this.key = newKey;
        this.value = newValue;
    }

    private void fixPointers(int i) {
        if (this.size == 0) {
            this.last = -1;
            this.first = -1;
            return;
        }
        if (this.first == i) {
            this.first = (int)this.link[i];
            if (0 <= this.first) {
                int n = this.first;
                this.link[n] = this.link[n] | 0xFFFFFFFF00000000L;
            }
            return;
        }
        if (this.last == i) {
            this.last = (int)(this.link[i] >>> 32);
            if (0 <= this.last) {
                int n = this.last;
                this.link[n] = this.link[n] | 0xFFFFFFFFL;
            }
            return;
        }
        long linki = this.link[i];
        int prev = (int)(linki >>> 32);
        int next = (int)linki;
        int n = prev;
        this.link[n] = this.link[n] ^ (this.link[prev] ^ linki & 0xFFFFFFFFL) & 0xFFFFFFFFL;
        int n2 = next;
        this.link[n2] = this.link[n2] ^ (this.link[next] ^ linki & 0xFFFFFFFF00000000L) & 0xFFFFFFFF00000000L;
    }

    private Int2ObjectMapUtil<V> removeEntry(int pos) {
        this.value[pos] = null;
        --this.size;
        this.fixPointers(pos);
        this.shiftKeys(pos);
        if (this.n > this.minN && this.size < this.maxFill / 4 && this.n > 16) {
            this.rehash(this.n / 2);
        }
        return this;
    }

    private void shiftKeys(int pos) {
        int[] key = this.key;
        while (true) {
            int curr;
            int last = pos;
            pos = last + 1 & this.mask;
            while (true) {
                if ((curr = key[pos]) == 0) {
                    key[last] = 0;
                    this.value[last] = null;
                    return;
                }
                int slot = HashCommon.mix((int)curr) & this.mask;
                if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos) break;
                pos = pos + 1 & this.mask;
            }
            key[last] = curr;
            this.value[last] = this.value[pos];
            this.fixPointers(pos, last);
        }
    }

    private void fixPointers(int s, int d) {
        if (this.size == 1) {
            this.first = this.last = d;
            this.link[d] = -1L;
            return;
        }
        if (this.first == s) {
            this.first = d;
            int n = (int)this.link[s];
            this.link[n] = this.link[n] ^ (this.link[(int)this.link[s]] ^ ((long)d & 0xFFFFFFFFL) << 32) & 0xFFFFFFFF00000000L;
            this.link[d] = this.link[s];
            return;
        }
        if (this.last == s) {
            this.last = d;
            int n = (int)(this.link[s] >>> 32);
            this.link[n] = this.link[n] ^ (this.link[(int)(this.link[s] >>> 32)] ^ (long)d & 0xFFFFFFFFL) & 0xFFFFFFFFL;
            this.link[d] = this.link[s];
            return;
        }
        long links = this.link[s];
        int prev = (int)(links >>> 32);
        int next = (int)links;
        int n = prev;
        this.link[n] = this.link[n] ^ (this.link[prev] ^ (long)d & 0xFFFFFFFFL) & 0xFFFFFFFFL;
        int n2 = next;
        this.link[n2] = this.link[n2] ^ (this.link[next] ^ ((long)d & 0xFFFFFFFFL) << 32) & 0xFFFFFFFF00000000L;
        this.link[d] = links;
    }
}
