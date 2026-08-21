package com.ryjs.agent;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;


public final class FullFilterRestarter {

    private static volatile boolean attempted = false;

    private FullFilterRestarter() {}

    public static void maybeRestart() {
        if (attempted) return;
        attempted = true;
        try {
            System.out.println("[FullFilter] 检查完整过滤重启: fullFilterCoremod=" + DefenseConfig.fullFilterCoremod());
            if (!DefenseConfig.fullFilterCoremod()) {
                return;
            }
            String jvmArgs = String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments());
            String mainCmd = System.getProperty("sun.java.command", "");
            if (jvmArgs.contains("-javaagent") || mainCmd.contains("-javaagent")) {
                System.out.println("[FullFilter] 已带 -javaagent（过滤启动）——不重启");
                return;
            }
            String jar = jarPath();
            if (jar == null || jar.isEmpty()) {
                System.out.println("[FullFilter] 自身 jar 路径获取失败——跳过重启");
                return;
            }
            String javaBin = System.getProperty("java.home") + File.separator + "bin"
                    + File.separator + "javaw.exe";
            if (!new File(javaBin).exists()) {
                javaBin = System.getProperty("java.home") + File.separator + "bin"
                        + File.separator + "java.exe";
            }
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(javaBin);
            cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
            cmd.add("-javaagent:" + jar);

            cmd.add("-Dreflection.filterRestarted=true");
            String cp = System.getProperty("java.class.path", "");
            if (!cp.isEmpty()) {
                cmd.add("-cp");
                cmd.add(cp);
            }
            if (!mainCmd.isEmpty()) {

                for (String part : mainCmd.split("\\s+")) {
                    if (!part.isEmpty()) cmd.add(part);
                }
            }

            int totalLen = 0;
            for (String a : cmd) totalLen += a.length() + 3;
            if (totalLen > 32000) {
                System.out.println("[FullFilter] 命令行过长（" + totalLen + " 字符）——放弃自动重启，请手动带 -javaagent");
                return;
            }


            java.io.File logFile = new java.io.File(System.getProperty("user.dir", "."), "reflection_fullfilter_new.log");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(logFile);

            System.out.println("[FullFilter] 新进程命令:\n" + String.join(" ", cmd));
            Process p = pb.start();
            System.out.println("[FullFilter] 已重启（带 premain 完整过滤）: -javaagent:" + jar);
            try {
                Thread.sleep(4000L);
            } catch (InterruptedException ignored) {
            }

            try {
                System.out.println("[FullFilter] 新进程已退出，退出码=" + p.exitValue());
            } catch (Throwable t) {
                System.out.println("[FullFilter] 新进程运行中（存活）");
            }
            try {
                if (logFile.exists() && logFile.length() > 0) {
                    String out = java.nio.file.Files.readString(logFile.toPath());
                    if (out.length() > 2500) out = out.substring(0, 2500) + "...(截断)";
                    System.out.println("[FullFilter] 新进程输出:\n" + out);
                }
            } catch (Throwable ignored) {
            }

            Runtime.getRuntime().halt(0);
        } catch (Throwable t) {
            System.err.println("[FullFilter] 重启失败（忽略——按原样继续启动）: " + t);
        }
    }


    private static String jarPath() {
        try {
            java.net.URL url = FullFilterRestarter.class.getResource("/com/ryjs/agent/FullFilterRestarter.class");
            if (url == null) return null;
            String path = url.toString();
            if (path.startsWith("union:")) {
                path = path.substring(6);
            } else if (path.startsWith("jar:file:")) {
                path = path.substring(9);
            } else if (path.startsWith("file:")) {
                path = path.substring(5);
            }
            try {
                path = java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
            int bang = path.indexOf("!/");
            if (bang != -1) {
                path = path.substring(0, bang);
            }
            int hash = path.indexOf('#');
            if (hash != -1) {
                path = path.substring(0, hash);
            }

            if (path.length() > 2 && path.charAt(0) == '/'
                    && Character.isLetter(path.charAt(1)) && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            return path.replace('/', '\\'); // Windows 标准反斜杠
        } catch (Throwable t) {
            return null;
        }
    }
}
