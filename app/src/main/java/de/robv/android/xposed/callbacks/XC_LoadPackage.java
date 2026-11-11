package de.robv.android.xposed.callbacks;

/**
 * 编译期 stub。只要字段名与方法签名对上即可，运行时用 LSPosed 的真实类。
 */
public class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public Object appInfo;
        public boolean isFirstApplication;
    }
}