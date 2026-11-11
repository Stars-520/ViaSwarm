package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 编译期 stub。运行时由 LSPosed 在模块 classloader 里提供真实实现，
 * 打包时这个类不会进 dex（只做 -cp）。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}