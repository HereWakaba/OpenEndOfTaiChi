package com.ryjs.agent;

import java.io.File;
import java.io.IOException;


public final class HighRiskGuard {

    private HighRiskGuard() {}


    public static void systemExit(int code) {
        if (TrustJudge.isCallerTrusted()) { Runtime.getRuntime().exit(code); }
    }
    public static void runtimeExit(Runtime r, int code) {
        if (TrustJudge.isCallerTrusted()) { r.exit(code); }
    }
    public static void runtimeHalt(Runtime r, int code) {
        if (TrustJudge.isCallerTrusted()) { r.halt(code); }
    }


    public static void systemLoad(String filename) {
        if (TrustJudge.isCallerTrusted()) { System.load(filename); }
    }
    public static void systemLoadLibrary(String libname) {
        if (TrustJudge.isCallerTrusted()) { System.loadLibrary(libname); }
    }
    public static void runtimeLoad(Runtime r, String filename) {
        if (TrustJudge.isCallerTrusted()) { r.load(filename); }
    }
    public static void runtimeLoadLibrary(Runtime r, String libname) {
        if (TrustJudge.isCallerTrusted()) { r.loadLibrary(libname); }
    }


    public static Process runtimeExec(Runtime r, String command) throws IOException {
        return TrustJudge.isCallerTrusted() ? r.exec(command) : null;
    }
    public static Process runtimeExecArr(Runtime r, String[] cmdarray) throws IOException {
        return TrustJudge.isCallerTrusted() ? r.exec(cmdarray) : null;
    }
    public static Process runtimeExecEnv(Runtime r, String command, String[] envp) throws IOException {
        return TrustJudge.isCallerTrusted() ? r.exec(command, envp) : null;
    }
    public static Process runtimeExecArrEnv(Runtime r, String[] cmdarray, String[] envp) throws IOException {
        return TrustJudge.isCallerTrusted() ? r.exec(cmdarray, envp) : null;
    }
    public static Process runtimeExecFull(Runtime r, String command, String[] envp, File dir) throws IOException {
        return TrustJudge.isCallerTrusted() ? r.exec(command, envp, dir) : null;
    }
    public static Process runtimeExecArrFull(Runtime r, String[] cmdarray, String[] envp, File dir) throws IOException {
        return TrustJudge.isCallerTrusted() ? r.exec(cmdarray, envp, dir) : null;
    }
    public static Process processBuilderStart(ProcessBuilder pb) throws IOException {
        return TrustJudge.isCallerTrusted() ? pb.start() : null;
    }
}
