package com.ryjs.proxyshell;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 闸②：阻止非白名单 /mods/ mod 被 Forge 当 @Mod 发现。
 *
 * <p><b>主落点</b>（{@link #pruneCandidateMods}）：在 {@code ReflectionCoremod.beginScanning}（fml 已跑完 beginModScan、
 * candidateMods 就绪，completeScan 尚未跑）时，反射 {@code FMLLoader.modValidator} 的私有 {@code candidateMods} 列表，
 * 把拦截目标移除。因为 {@code ModValidator.getModResources} 依据 {@code LoadingModList.getModFiles()} 构建 GAME 模块层，
 * 且 LoadingModList 由 {@code ModSorter.sort(candidateMods)} 生成——从 candidateMods 摘掉即同时挡住 mod 列表与 GAME 层，
 * 该 mod 的任何类都不进 JVM。</p>
 *
 * <p><b>兜底落点</b>（{@link #pruneLoadingModList}）：在 {@code completeScan} 时对已建好的 {@code LoadingModList}
 * 再摘一次（modFiles / sortedList / fileById），即便 beginScanning 时序不利也至少阻止 ModContainer 创建 / ModMain 构造。</p>
 *
 * <p>私有字段读取用 Unsafe（跨模块，避免 InaccessibleObjectException）；对象方法调用一律走已导出的
 * forgespi 接口（IModFile/IModFileInfo/IModInfo），不碰 fml 内部非导出类型。</p>
 */
public final class ProxyShellBlocker {

    private ProxyShellBlocker() {}

    private static final Unsafe UNSAFE = getUnsafe();
    private static volatile boolean candidatePruned = false;
    private static volatile boolean loadingListPruned = false;

    /**
     * 预加载本包全部类——<b>必须在 {@code CoexistenceCleaner.cleanAll} 把 reflection 模块从 SERVICE 层摘除之前调用</b>。
     *
     * <p>原因：闸②的落点 {@code ReflectionCoremod.beginScanning/completeScan} 由 ModLauncher 在
     * {@code runScanningTransformationServices} 阶段调用，远晚于 cleanAll；届时 SERVICE 层的 ModuleClassLoader
     * 已看不到我们的模块，首次加载本类会 NoClassDefFoundError 且不被 catch，直接崩游戏。</p>
     */
    public static void preload() {
        try {
            // 触发 ProxyShellSupport 与本类（含 UNSAFE 静态初始化）的类加载
            ProxyShellSupport.enabled();
            Class.forName("com.ryjs.proxyshell.ProxyShellSupport");
            Class.forName("com.ryjs.proxyshell.ProxyShellBlocker");
            System.out.println("[ProxyShell] preload 完成（ProxyShellSupport/ProxyShellBlocker 已加载，UNSAFE="
                    + (UNSAFE != null) + "）");
        } catch (Throwable t) {
            System.err.println("[ProxyShell] preload 失败（闸②将无法生效）: " + t);
        }
    }

    /** 主落点：从 ModValidator.candidateMods 摘除拦截目标。beginScanning 调用。 */
    public static void pruneCandidateMods(Class<?> selfClass) {
        if (!ProxyShellSupport.enabled() || candidatePruned || UNSAFE == null) {
            return;
        }
        try {
            Object modValidator = staticGet(FMLLoader.class, "modValidator");
            if (modValidator == null) {
                ProxyShellSupport.log("beginScanning: modValidator 尚为 null（时序过早），跳过主摘除，靠兜底");
                return;
            }
            Object cmObj = instanceGet(modValidator, "candidateMods");
            if (!(cmObj instanceof List<?>)) {
                ProxyShellSupport.log("candidateMods 取不到或非 List，跳过");
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> candidateMods = (List<Object>) cmObj;
            Path selfJar = ProxyShellSupport.resolveSelfJar(selfClass);
            ProxyShellSupport.log("beginScanning 探针：candidateMods.size=" + candidateMods.size() + "（fml 是否先行的判据）");
            int[] removed = {0};
            candidateMods.removeIf(o -> {
                Path jar = jarOf(o);
                boolean block = jar != null && ProxyShellSupport.isBlockTargetJar(jar, selfJar);
                if (block) {
                    removed[0]++;
                    ProxyShellSupport.log("拦发现(candidateMods 移除): " + jar.getFileName());
                }
                return block;
            });
            candidatePruned = true;
            ProxyShellSupport.log("candidateMods 已移除 " + removed[0] + " 个，剩余 " + candidateMods.size());
        } catch (Throwable t) {
            ProxyShellSupport.log("pruneCandidateMods 异常（不影响启动，靠兜底）: " + t);
        }
    }

    /** 兜底：对已建好的 LoadingModList 再摘一次（modFiles / sortedList / fileById）。completeScan 调用。 */
    public static void pruneLoadingModList(Class<?> selfClass) {
        if (!ProxyShellSupport.enabled() || loadingListPruned || UNSAFE == null) {
            return;
        }
        try {
            LoadingModList lml = LoadingModList.get();
            if (lml == null) {
                ProxyShellSupport.log("completeScan: LoadingModList 尚为 null，跳过兜底");
                return;
            }
            Path selfJar = ProxyShellSupport.resolveSelfJar(selfClass);
            int[] removed = {0};

            Object modFiles = instanceGet(lml, "modFiles");
            if (modFiles instanceof List<?>) {
                ((List<Object>) modFiles).removeIf(o -> dropByJar(o, selfJar, removed));
            }
            Object sortedList = instanceGet(lml, "sortedList");
            if (sortedList instanceof List<?>) {
                ((List<Object>) sortedList).removeIf(o -> dropByJar(o, selfJar, null));
            }
            Object fileById = instanceGet(lml, "fileById");
            if (fileById instanceof Map<?, ?>) {
                ((Map<Object, Object>) fileById).values().removeIf(o -> dropByJar(o, selfJar, null));
            }
            loadingListPruned = true;
            ProxyShellSupport.log("LoadingModList 兜底移除 " + removed[0] + " 个 modFile（阻止 ModContainer/ModMain 构造）");
        } catch (Throwable t) {
            ProxyShellSupport.log("pruneLoadingModList 异常（不影响启动）: " + t);
        }
    }

    private static boolean dropByJar(Object o, Path selfJar, int[] counter) {
        Path jar = jarOf(o);
        boolean block = jar != null && ProxyShellSupport.isBlockTargetJar(jar, selfJar);
        if (block && counter != null) {
            counter[0]++;
            ProxyShellSupport.log("拦发现(LoadingModList 移除): " + jar.getFileName());
        }
        return block;
    }

    /** 经已导出的 forgespi 接口取对象所属 jar 路径（IModFile→SecureJar；IModFileInfo→getFile；IModInfo→getOwningFile）。 */
    private static Path jarOf(Object o) {
        try {
            if (o instanceof IModFile f) {
                return f.getSecureJar().getPrimaryPath();
            }
            if (o instanceof IModFileInfo fi) {
                return jarOf(fi.getFile());
            }
            if (o instanceof IModInfo mi) {
                return jarOf(mi.getOwningFile());
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    // ===== Unsafe 字段读取（跨模块） =====

    private static Object staticGet(Class<?> owner, String fieldName) {
        try {
            Field f = owner.getDeclaredField(fieldName);
            Object base = UNSAFE.staticFieldBase(f);
            long off = UNSAFE.staticFieldOffset(f);
            return UNSAFE.getObject(base, off);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object instanceGet(Object target, String fieldName) {
        try {
            Field f = findField(target.getClass(), fieldName);
            if (f == null) {
                return null;
            }
            long off = UNSAFE.objectFieldOffset(f);
            return UNSAFE.getObject(target, off);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
            }
        }
        return null;
    }

    private static Unsafe getUnsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
