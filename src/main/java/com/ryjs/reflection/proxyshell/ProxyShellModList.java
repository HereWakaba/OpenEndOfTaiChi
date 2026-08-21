package com.ryjs.reflection.proxyshell;

import com.ryjs.proxyshell.ProxyShellSupport;
import com.ryjs.reflection.Reflection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.common.ForgeI18n;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.internal.BrandingControl;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.ForgeFeature;
import net.minecraftforge.resource.PathPackResources;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import sun.misc.Unsafe;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ProxyShellModList {

    private ProxyShellModList() {}

    private static volatile boolean entriesInstalled = false;
    private static final List<Shell> SHELLS = new ArrayList<>();

    private static final class Shell {
        final ProxyShellSupport.ModMeta meta;
        final Path jar;
        ModFileInfo fakeOwningFileForLogo;
        Object fakeModFile;

        Shell(ProxyShellSupport.ModMeta meta, Path jar) {
            this.meta = meta;
            this.jar = jar;
        }
    }


    public static synchronized void installEntries(Class<?> selfClass) {
        if (entriesInstalled) {
            return;
        }
        entriesInstalled = true;
        if (!ProxyShellSupport.enabled()) {
            return;
        }
        try {
            ModList ml = ModList.get();
            if (ml == null) {
                return;
            }
            IModFileInfo self = ml.getModFileById(Reflection.MODID);
            if (!(self instanceof ModFileInfo)) {
                return;
            }
            ModFileInfo realOwning = (ModFileInfo) self;
            List<IModInfo> live = ml.getMods();
            int n = 0;
            for (Path jar : ProxyShellSupport.listBlockedJars(selfClass)) {
                ProxyShellSupport.ModMeta meta = ProxyShellSupport.readModMeta(jar);
                if (meta == null) {
                    continue;
                }
                boolean dup = false;
                for (IModInfo mi : live) {
                    if (meta.modId.equals(mi.getModId())) {
                        dup = true;
                        break;
                    }
                }
                if (dup) {
                    continue;
                }
                SHELLS.add(new Shell(meta, jar));
                live.add(new ShellModInfo(meta, realOwning));
                n++;
            }
            ProxyShellSupport.log("已伪造 " + n + " 个 mods 列表条目");
        } catch (Throwable t) {
            ProxyShellSupport.log("伪造 mods 列表条目失败: " + t);
        }
    }

    public static synchronized void installLogos() {
        if (!ProxyShellSupport.enabled() || SHELLS.isEmpty()) {
            return;
        }
        try {
            Map<Object, Object> fileById = modListFileById();
            Map<Object, Object> packs = resourcePacks();
            if (fileById == null || packs == null) {
                return;
            }
            for (Shell sh : SHELLS) {
                if (sh.meta.logoFile == null) {
                    continue;
                }
                if (sh.fakeModFile == null) {
                    sh.fakeModFile = allocate(ModFile.class);
                    sh.fakeOwningFileForLogo = (ModFileInfo) allocate(ModFileInfo.class);
                    setField(ModFileInfo.class, sh.fakeOwningFileForLogo, "modFile", sh.fakeModFile);
                }
                fileById.put(sh.meta.modId, sh.fakeOwningFileForLogo);
                packs.put(sh.fakeModFile, logoPack(sh));
            }
        } catch (Throwable t) {
            ProxyShellSupport.log("注入 mods 列表 logo 失败: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    public static synchronized void installBranding() {
        if (!ProxyShellSupport.enabled() || SHELLS.isEmpty()) {
            return;
        }
        try {
            ModList ml = ModList.get();
            if (ml == null) {
                return;
            }
            BrandingControl.forEachLine(true, false, (i, s) -> {});
            Field fb = BrandingControl.class.getDeclaredField("brandings");
            fb.setAccessible(true);
            List<String> cur = (List<String>) fb.get(null);
            if (cur == null) {
                return;
            }
            int real = ml.size();
            String oldLine = ForgeI18n.parseMessage("fml.menu.loadingmods", real);
            String newLine = ForgeI18n.parseMessage("fml.menu.loadingmods", real + SHELLS.size());
            List<String> rebuilt = new ArrayList<>();
            boolean replaced = false;
            for (String s : cur) {
                if (!replaced && oldLine.equals(s)) {
                    rebuilt.add(newLine);
                    replaced = true;
                } else {
                    rebuilt.add(s);
                }
            }
            if (!replaced) {
                return;
            }
            fb.set(null, rebuilt);
            Field fn = BrandingControl.class.getDeclaredField("brandingsNoMC");
            fn.setAccessible(true);
            fn.set(null, rebuilt.subList(1, rebuilt.size()));
        } catch (Throwable t) {
            ProxyShellSupport.log("修正主界面 mod 计数失败: " + t);
        }
    }

    private static PathPackResources logoPack(Shell sh) {
        final Path jar = sh.jar;
        return new PathPackResources("reflection_shell_logo_" + sh.meta.modId, true, jar) {
            @Override
            public IoSupplier<InputStream> getRootResource(String... paths) {
                byte[] png = readZip(jar, String.join("/", paths));
                return png == null ? null : () -> new ByteArrayInputStream(png);
            }
        };
    }

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static Object allocate(Class<?> c) throws Exception {
        return unsafe().allocateInstance(c);
    }

    private static void setField(Class<?> owner, Object inst, String name, Object val) throws Exception {
        Unsafe u = unsafe();
        Field f = owner.getDeclaredField(name);
        u.putObject(inst, u.objectFieldOffset(f), val);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> modListFileById() throws Exception {
        ModList ml = ModList.get();
        if (ml == null) {
            return null;
        }
        Field f = ModList.class.getDeclaredField("fileById");
        f.setAccessible(true);
        return (Map<Object, Object>) f.get(ml);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> resourcePacks() throws Exception {
        Field f = net.minecraftforge.resource.ResourcePackLoader.class.getDeclaredField("modResourcePacks");
        f.setAccessible(true);
        return (Map<Object, Object>) f.get(null);
    }

    private static byte[] readZip(Path jar, String entry) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry(entry);
            return e == null ? null : zf.getInputStream(e).readAllBytes();
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class ShellModInfo implements IModInfo {
        private final ProxyShellSupport.ModMeta meta;
        private final ModFileInfo owningFile;
        private final ArtifactVersion version;
        private final IConfigurable emptyConfig = new IConfigurable() {
            @Override
            public <T> Optional<T> getConfigElement(String... key) {
                return Optional.empty();
            }

            @Override
            public List<? extends IConfigurable> getConfigList(String... key) {
                return Collections.emptyList();
            }
        };

        ShellModInfo(ProxyShellSupport.ModMeta meta, ModFileInfo owningFile) {
            this.meta = meta;
            this.owningFile = owningFile;
            ArtifactVersion v;
            try {
                v = new DefaultArtifactVersion(meta.version);
            } catch (Throwable t) {
                v = new DefaultArtifactVersion("1");
            }
            this.version = v;
        }

        @Override
        public IModFileInfo getOwningFile() {
            return owningFile;
        }

        @Override
        public String getModId() {
            return meta.modId;
        }

        @Override
        public String getNamespace() {
            return meta.modId;
        }

        @Override
        public ArtifactVersion getVersion() {
            return version;
        }

        @Override
        public List<? extends ModVersion> getDependencies() {
            return Collections.emptyList();
        }

        @Override
        public String getDisplayName() {
            return meta.displayName;
        }

        @Override
        public String getDescription() {
            return meta.description;
        }

        @Override
        public Optional<String> getLogoFile() {
            return meta.logoFile == null ? Optional.empty() : Optional.of(meta.logoFile);
        }

        @Override
        public boolean getLogoBlur() {
            return true;
        }

        @Override
        public Optional<URL> getUpdateURL() {
            return Optional.empty();
        }

        @Override
        public Optional<URL> getModURL() {
            return Optional.empty();
        }

        @Override
        public IConfigurable getConfig() {
            return emptyConfig;
        }

        @Override
        public List<? extends ForgeFeature.Bound> getForgeFeatures() {
            return Collections.emptyList();
        }

        @Override
        public Map<String, Object> getModProperties() {
            return Collections.emptyMap();
        }
    }
}
