import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Build tiny IWP probe jars for the ordering ladder experiments.
 * Each jar: module-info (module NAME) + one no-op ImmediateWindowProvider whose
 * static block prints "[PROBE NAME] static init <ms>".
 *
 * Usage: java ProbeBuilder <fmlloader.jar> <outDir> <name1> [name2] ...
 * Output: <outDir>/probe-<name>.jar
 */
public class ProbeBuilder {

    private static final String PROBE_SRC =
        "package %NAME%;\n" +
        "public class P implements net.minecraftforge.fml.loading.ImmediateWindowProvider {\n" +
        "    static {\n" +
        "        String name = \"%NAME%\";\n" +
        "        String mode = System.getProperty(\"probe.mode\", \"halt\");\n" +
        "        String logPath = System.getProperty(\"probe.winner.log\", \"E:\\\\Minecraft\\\\Reflection\\\\probe_winner.log\");\n" +
        "        try {\n" +
        "            java.io.FileOutputStream out = new java.io.FileOutputStream(logPath, true);\n" +
        "            try {\n" +
        "                out.write((name + \" \" + System.currentTimeMillis() + \"\\n\").getBytes(java.nio.charset.StandardCharsets.UTF_8));\n" +
        "            } finally { out.close(); }\n" +
        "        } catch (Throwable ignored) {}\n" +
        "        System.out.println(\"[PROBE \" + name + \"] static init \" + System.currentTimeMillis());\n" +
        "        if (\"halt\".equals(mode)) {\n" +
        "            Runtime.getRuntime().halt(0);\n" +
        "        } else if (\"chain\".equals(mode)) {\n" +
        "            // 自我重启链：读自身完整命令行 → 写入 bat → start 子进程 MC → 自杀\n" +
        "            String diag = \"\";\n" +
        "            try {\n" +
        "                String cmd = ProcessHandle.current().info().commandLine().orElse(null);\n" +
        "                if (cmd == null || cmd.isEmpty()) {\n" +
        "                    diag = \"relaunch=FAIL commandLine unavailable\";\n" +
        "                } else {\n" +
        "                    java.nio.file.Path bat = java.nio.file.Paths.get(System.getProperty(\"probe.relaunch.bat\", \"E:\\\\Minecraft\\\\Reflection\\\\relaunch.bat\"));\n" +
        "                    java.nio.file.Files.write(bat, (\"@echo off\\r\\ncd /d \" + System.getProperty(\"user.dir\") + \"\\r\\n\" + cmd + \"\\r\\n\").getBytes(java.nio.charset.StandardCharsets.UTF_8));\n" +
        "                    Process p = new ProcessBuilder(\"cmd\", \"/c\", \"start\", \"\", \"\\\"\" + bat + \"\\\"\").start();\n" +
        "                    diag = \"relaunch=OK pid=\" + p.pid();\n" +
        "                }\n" +
        "            } catch (Throwable t) {\n" +
        "                diag = \"relaunch=EX \" + t;\n" +
        "            }\n" +
        "            try {\n" +
        "                java.io.FileOutputStream dout = new java.io.FileOutputStream(logPath, true);\n" +
        "                try { dout.write((\"[\" + name + \"] \" + diag + \"\\n\").getBytes(java.nio.charset.StandardCharsets.UTF_8)); } finally { dout.close(); }\n" +
        "            } catch (Throwable ignored) {}\n" +
        "            Runtime.getRuntime().halt(0);\n" +
        "        }\n" +
        "        // mode=log：只记录不退出，游戏正常继续，JVMTI agent 记完整顺序（树化实验用）\n" +
        "    }\n" +
        "    public String name() { return \"\"; }\n" +
        "    public Runnable initialize(String[] arguments) { return null; }\n" +
        "    public void updateFramebufferSize(java.util.function.IntConsumer width, java.util.function.IntConsumer height) {}\n" +
        "    public long setupMinecraftWindow(java.util.function.IntSupplier width, java.util.function.IntSupplier height, java.util.function.Supplier<String> title, java.util.function.LongSupplier monitor) { return 0; }\n" +
        "    public boolean positionWindow(java.util.Optional<Object> monitor, java.util.function.IntConsumer widthSetter, java.util.function.IntConsumer heightSetter, java.util.function.IntConsumer xSetter, java.util.function.IntConsumer ySetter) { return false; }\n" +
        "    public <T> java.util.function.Supplier<T> loadingOverlay(java.util.function.Supplier<?> mc, java.util.function.Supplier<?> ri, java.util.function.Consumer<java.util.Optional<Throwable>> ex, boolean fade) { return null; }\n" +
        "    public void updateModuleReads(java.lang.ModuleLayer layer) {}\n" +
        "    public void periodicTick() {}\n" +
        "    public String getGLVersion() { return \"\"; }\n" +
        "}\n";

    private static final String MODULE_SRC =
        "module %NAME% {\n" +
        "    requires static fmlloader;\n" +
        "    provides net.minecraftforge.fml.loading.ImmediateWindowProvider with %NAME%.P;\n" +
        "}\n";

    private ProbeBuilder() {}

    private static void write(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
    }

    private static int run(List<String> cmd, String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            System.err.println("[" + label + "] failed rc=" + rc + "\n" + out);
        }
        return rc;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java ProbeBuilder <fmlloader.jar> <outDir> <name1> [name2] ...");
            System.exit(1);
        }
        File fmlloader = new File(args[0]);
        if (!fmlloader.isFile()) {
            System.err.println("fmlloader jar not found: " + args[0]);
            System.exit(1);
        }
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);
        String javac = System.getProperty("java.home") + File.separator + "bin" + File.separator + "javac.exe";
        String jar = System.getProperty("java.home") + File.separator + "bin" + File.separator + "jar.exe";

        for (int i = 2; i < args.length; i++) {
            String modName = args[i];
            Path work = Files.createTempDirectory("probe-" + modName + "-");
            Path src = work.resolve("src");
            Path cls = work.resolve("classes");
            Files.createDirectories(src.resolve(modName));
            write(src, "module-info.java", MODULE_SRC.replace("%NAME%", modName));
            write(src.resolve(modName), "P.java", PROBE_SRC.replace("%NAME%", modName));

            // fmlloader is an automatic module: put it on the module path
            List<String> compile = new ArrayList<>(List.of(
                javac, "-encoding", "UTF-8", "-d", cls.toString(),
                "--module-path", fmlloader.getAbsolutePath(),
                src.resolve("module-info.java").toString(),
                src.resolve(modName).resolve("P.java").toString()));
            if (run(compile, "javac " + modName) != 0) {
                System.exit(1);
            }

            // case-insensitive FS safety: prefix an index so aa/Aa/AA/aA and P/p
            // do not collide into the same file (module name lives in module-info)
            Path jarOut = outDir.resolve(String.format("probe-%02d-%s.jar", i - 1, modName));
            List<String> pack = new ArrayList<>(List.of(
                jar, "--create", "--file", jarOut.toString(), "-C", cls.toString(), "."));
            if (run(pack, "jar " + modName) != 0) {
                System.exit(1);
            }
            System.out.println("[ProbeBuilder] built " + jarOut.toAbsolutePath());
        }
    }
}
