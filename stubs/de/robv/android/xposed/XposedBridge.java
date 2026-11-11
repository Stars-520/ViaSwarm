package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * 编译期 stub。只暴露模块用到的 log / hookMethod。
 */
public final class XposedBridge {

    public static void log(String text) {
        throw new RuntimeException("stub");
    }

    public static void log(Throwable t) {
        throw new RuntimeException("stub");
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new RuntimeException("stub");
    }
}