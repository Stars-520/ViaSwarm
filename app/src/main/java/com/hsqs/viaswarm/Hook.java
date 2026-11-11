package com.hsqs.viaswarm;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * ViaSwarm —— 把 Swarm Rebuild 注入 Via。
 *
 * 逆向结论（Via 7.2.1，versionCode 20260706，dex 单包 5.1MB，R8 混淆）：
 *
 *   t4.b   = Via 自己的 WebView 子类（extends WebView implements q4.a.c）
 *            重写了 loadUrl(String) / loadUrl(String, Map)，构造器带 Context
 *   p4.j   = 主浏览页的 WebViewClient（extends WebViewClient）
 *            onPageFinished(WebView, String) 里第一行就 (t4.b) webView 强转
 *            —— 说明它只挂在 t4.b 上，是干净的注入点
 *   a8.g$c = 广告拦截页的 WebViewClient，不处理正常页面，跳过
 *   kb.m7$b = 另一个 WebViewClient，只有 shouldOverrideUrlLoading
 *   s4.b.g(WebView, String) = Via 内部的 evaluateJavascript 包装，
 *            只被“暂停视频”等零散功能调用，不是页面加载流程
 *
 * 因此 hook 点只有一个：p4.j#onPageFinished。
 * 用 afterHookedMethod 拿参数里的 WebView，注入 assets 里的 swarm-core.js，
 * 再调用 window.__swarmRebuild.start({mode:"manual"}) 让它只探测不自动跑。
 *
 * 三个必须处理的坑：
 *   1. onPageFinished 会因重定向、iframe 多次触发 → WeakHashMap 去重
 *   2. evaluateJavascript 必须在 UI 线程 → 已经在了，但用 Handler 兜一层
 *   3. 页面还没解析完就注入会拿不到 DOM → onPageFinished 后再 delay 300ms
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "[ViaSwarm] ";

    /** Via 主 WebViewClient；混淆名，按版本可能变，见 resolveClientClass() */
    private static final String[] CLIENT_CANDIDATES = {
        "p4.j"
    };

    /** 已注入过的 WebView，弱引用避免泄漏 */
    private static final Set<WebView> INJECTED =
        Collections.newSetFromMap(new WeakHashMap<WebView, Boolean>());

    private static String sCore;   // assets 里读出的 swarm 核心，进程内缓存
    private static ClassLoader sHostLoader;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!"mark.via".equals(lpparam.packageName)) return;

        sHostLoader = lpparam.classLoader;
        log("attached to " + lpparam.packageName + " (" + lpparam.processName + ")");

        Class<?> client = null;
        for (String name : CLIENT_CANDIDATES) {
            try {
                client = XposedHelpers.findClass(name, lpparam.classLoader);
                log("found WebViewClient: " + name);
                break;
            } catch (Throwable t) {
                log("miss " + name + ": " + t.getClass().getSimpleName());
            }
        }
        if (client == null) {
            log("no WebViewClient matched — Via 版本可能变了，需要重新逆向");
            return;
        }

        hookOnPageFinished(client);
    }

    private void hookOnPageFinished(Class<?> client) {
        Method m = XposedHelpers.findMethodExactIfExists(
            client, "onPageFinished", WebView.class, String.class);
        if (m == null) {
            log("onPageFinished(WebView,String) 不存在，尝试遍历");
            for (Method cand : client.getDeclaredMethods()) {
                if (cand.getName().equals("onPageFinished") && cand.getParameterTypes().length == 2) {
                    m = cand;
                    break;
                }
            }
        }
        if (m == null) {
            log("拿不到 onPageFinished，放弃");
            return;
        }
        log("hooking " + m);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object a0 = param.args[0];
                    Object a1 = param.args[1];
                    if (!(a0 instanceof WebView)) return;
                    WebView wv = (WebView) a0;
                    String url = a1 instanceof String ? (String) a1 : null;

                    // 只处理 http(s)，跳过 about:blank / data: / file:
                    if (url != null && !url.startsWith("http")) {
                        log("skip non-http: " + shorten(url));
                        return;
                    }
                    synchronized (INJECTED) {
                        if (INJECTED.contains(wv)) {
                            log("already injected: " + shorten(url));
                            return;
                        }
                        INJECTED.add(wv);
                    }
                    log("inject target: " + shorten(url));
                    scheduleInject(wv, url);
                } catch (Throwable t) {
                    log("afterHook error: " + t);
                }
            }
        });
    }

    private void scheduleInject(final WebView wv, final String url) {
        final Context app = wv.getContext().getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    inject(wv, app);
                } catch (Throwable t) {
                    log("inject failed: " + t);
                }
            }
        }, 300);
    }

    private void inject(WebView wv, Context app) {
        String core = core(app);
        if (core == null || core.isEmpty()) {
            log("swarm 核心为空，放弃");
            return;
        }

        // 1) 先探测：页面里是否已经有 __swarmRebuild（避免重复注入）
        wv.evaluateJavascript(
            "(function(){return (window.__swarmRebuild ? 'yes' : 'no')})()",
            new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value != null && value.contains("yes")) {
                        log("page already has swarm, re-arm instead");
                        armReinject(wv);
                        return;
                    }
                    // 2) 注入核心（内部 IIFE，末尾 start() 已被注释掉，不会自己跑）
                    wv.evaluateJavascript(core, null);
                    // 3) 装一个浮动按钮，让用户点一下就开跑
                    wv.evaluateJavascript(BUTTON_JS, null);
                    log("core injected: " + shorten(urlOf(wv)));
                }
            });
    }

    /**
     * 第二次进同一页面（SPA 路由、前进后退）时，核心还在但 UI 已经拆了。
     * 只补按钮，不强杀已有实例。
     */
    private void armReinject(WebView wv) {
        wv.evaluateJavascript(
            "(function(){if(!document.getElementById('vs-arm')){" + BUTTON_BODY + "}return 'ok'})()",
            null);
    }

    private static String urlOf(WebView wv) {
        try { return wv.getUrl(); } catch (Throwable t) { return null; }
    }

    /** 从模块自己的 assets 读核心；宿主进程里不能用自己的 R，所以走 AssetManager 反射 */
    private static String core(Context app) {
        if (sCore != null) return sCore;
        InputStream in = null;
        try {
            // 模块 APK 路径在宿主进程里未必可直接 new File，用宿主包管理器拿模块自身
            Context modCtx = moduleContext(app);
            if (modCtx == null) {
                log("拿不到模块 Context");
                return null;
            }
            AssetManager am = modCtx.getAssets();
            in = am.open("swarm-core.js");
            ByteArrayOutputStream bos = new ByteArrayOutputStream(160 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            sCore = bos.toString("UTF-8");
            log("core loaded: " + sCore.length() + " chars");
            return sCore;
        } catch (Throwable t) {
            log("read assets failed: " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 在宿主进程里构造模块自己的 Context，用于读 assets。
     * createPackageContext 需要同一 uid 或者 MODULE 标志。
     */
    private static Context moduleContext(Context app) {
        try {
            return app.createPackageContext("com.hsqs.viaswarm",
                Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
        } catch (Throwable t) {
            log("createPackageContext: " + t);
            return null;
        }
    }

    // ---------------------------------------------------------------- 浮动按钮

    /**
     * 手机上不能按 Alt+Shift+S，装一个 56px 圆钮。
     * 用最高 z-index 的 fixed 容器，避开站点 CSS 影响；不抢 focus。
     */
    private static final String BUTTON_BODY =
        "var b=document.createElement('div');" +
        "b.id='vs-arm';" +
        "b.textContent='Swarm';" +
        "b.style.position='fixed';" +
        "b.style.right='14px';" +
        "b.style.bottom='calc(14px + env(safe-area-inset-bottom,0px))';" +
        "b.style.zIndex='2147483600';" +
        "b.style.minWidth='56px';" +
        "b.style.height='56px';" +
        "b.style.textAlign='center';" +
        "b.style.padding='0 18px';" +
        "b.style.borderRadius='28px';" +
        "b.style.background='#0D99FF';" +
        "b.style.color='#fff';" +
        "b.style.font='600 15px/56px Inter,system-ui,sans-serif';" +
        "b.style.boxShadow='0 8px 24px rgba(13,153,255,.4)';" +
        "b.style.webkitTapHighlightColor='transparent';" +
        "b.style.userSelect='none';" +
        "b.style.cursor='pointer';" +
        "b.style.transition='transform .15s ease,background .15s ease';" +
        "b.addEventListener('touchstart',function(){b.style.transform='scale(.94)'});" +
        "b.addEventListener('touchend',function(){b.style.transform=''});" +
        "b.addEventListener('click',function(){" +
        "  if(!window.__swarmRebuild){return}" +
        "  if(window.__swarmRebuild.app){window.__swarmRebuild.stop(false);b.textContent='Swarm';b.style.background='#0D99FF'}" +
        "  else{window.__swarmRebuild.start();b.textContent='Stop';b.style.background='#F24822'}" +
        "});" +
        "document.documentElement.appendChild(b);";

    private static final String BUTTON_JS =
        "(function(){if(document.getElementById('vs-arm'))return 'exists';" + BUTTON_BODY + "return 'ok'})();";

    private static void log(String s) {
        XposedBridge.log(TAG + s);
    }

    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() > 70 ? s.substring(0, 70) + "…" : s;
    }
}