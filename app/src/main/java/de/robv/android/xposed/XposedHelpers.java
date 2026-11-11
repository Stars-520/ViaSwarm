package de.robv.android.xposed;

import java.lang.reflect.Method;

/**
 * 编译期 stub。只暴露模块用到的 findClass / findMethodExactIfExists。
 */
public final class XposedHelpers {

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new RuntimeException("stub");
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        throw new RuntimeException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new RuntimeException("stub");
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new RuntimeException("stub");
    }
}