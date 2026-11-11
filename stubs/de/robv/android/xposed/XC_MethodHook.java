package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * 编译期 stub。只需要 afterHookedMethod / beforeHookedMethod 可被子类重写。
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { throw new RuntimeException("stub"); }
        public void setResult(Object result) { throw new RuntimeException("stub"); }
        public Throwable getThrowable() { throw new RuntimeException("stub"); }
        public boolean hasThrowable() { throw new RuntimeException("stub"); }
        public void setThrowable(Throwable t) { throw new RuntimeException("stub"); }
    }

    public static class Unhook {
        public void unhook() { throw new RuntimeException("stub"); }
    }
}