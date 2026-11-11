# ViaSwarm

**把鼠标集群动画（Cursor Swarm）注入 Via 浏览器的 LSPosed 模块**

打开任意网页，右下角出现一个绿色按钮，点一下，一群多人协作光标涌进来，把你的页面拆成蓝图再逐格重画出来。

> 作者：**Stars.**

---

## 这是什么

Via 是个好浏览器，但它没法跑那些花里胡哨的页面特效。这个模块做的事很简单：在 Via 的 WebView 加载完页面之后，往里面注一段 JavaScript，然后给你一个按钮来控制它。

技术上，它是一个标准的 LSPosed 模块：

- 作用域只有一个：`mark.via`
- hook 点只有一个：`p4.j#onPageFinished`
- 不做任何网络请求，不写任何文件，不申请任何权限
- 全部代码就是「读一个 assets 里的 js 文件，然后丢进 WebView」

## 效果

核心实现是鼠标集群动画（cursor swarm），流程是：

1. 扫描当前页面，把每个元素的位置、尺寸、文字、边框、颜色、媒体都记成一份蓝图
2. 把页面清空
3. 一群虚拟光标从画面外飞进来，用十字准星画矩形、用打字机逐字写文本、用钢笔工具画线，把页面一块一块重建出来

你会看到的是一种「页面被一群人现场重新实现」的感觉。

## 环境要求

| 项 | 要求 |
|---|---|
| 设备 | 已 root，装了 **LSPosed**（Zygisk 或 Riru 都行） |
| 系统 | Android 7.0 及以上（minSdk 24） |
| 目标 | **Via 浏览器 7.2.1**（versionCode 20260706） |

Via 升级后如果失效，见下面「失效了怎么办」。

## 安装

**方法一：下载现成的**

去 [Releases](../../releases) 页面下载最新的 APK，装上去。

**方法二：自己编译**

```bash
git clone https://github.com/Stars-520/ViaSwarm.git
cd ViaSwarm
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/`。

核心脚本 `app/src/main/assets/swarm-core.js` 随仓库分发，直接编译就能得到完整功能的 APK。核心的接口契约与调试细节见 [docs/assets.md](docs/assets.md)。

## 启用

1. 安装 APK
2. 打开 LSPosed，进入「模块」页，勾选 **ViaSwarm**
3. 进模块详情，确认「作用域」里 **Via** 是勾上的
4. **强制停止 Via**（设置 → 应用 → Via → 强制停止）
5. 重新打开 Via

第 4 步不能省。模块只在进程启动时注入，不重启进程它不会生效。

## 使用

打开任意网页，等页面加载完，右下角会出现绿色的 **Swarm** 按钮：

- 点它 → 开始重建，按钮变红，文字变 **Stop**
- 再点 → 停止，按钮变回绿色

同一个页面重复点会来回切换，不会叠加。

## 已知限制

**只对 Via 自己的主浏览器页面生效。** 以下情况不会有按钮：

- Via 的广告拦截页、设置页等内部页面
- 页面里的 iframe（hook 点是主 WebView 的加载完成，iframe 加载不触发）
- SPA 站（B 站、推特这类）在站内切换路由不刷新页面，不会重新注入；手动刷新一下就有

**性能。** 重建过程要遍历全部 DOM 元素，元素特别多的页面（几千个节点）会卡。核心脚本内部有 `MAX_ITEMS = 4000` 之类的上限，超出会截断。

## 失效了怎么办

Via 每次更新都会重新跑 R8 混淆，`p4.j` 这个名字**一定会变**。症状是：模块装好了、也勾选了、Via 也重启了，但页面上没有按钮。

修法：

```bash
# 1. 把新版 Via 的 APK 拿出来反编译
# 2. 找到主 WebViewClient
grep -r "onPageFinished" --include="*.java" .
```

找那个 `onPageFinished(WebView webView, String url)` 里**第一行就做 `(t4.b) webView` 强转**的类，它的全限定名就是新的 hook 点。

然后改 `Hook.java`：

```java
private static final String[] CLIENT_CANDIDATES = {
    "p4.j"    // ← 改成新的类名
};
```

重新编译安装。

代码里 `CLIENT_CANDIDATES` 是个数组，你可以同时写多个候选名，模块会依次尝试，第一个找到的生效。这样能兼容几个不同的 Via 版本。

## 逆向记录

Via 7.2.1 的实际结构，给后来的人省点时间：

| 类 | 是什么 | 用不用 |
|---|---|---|
| `t4.b` | Via 的 WebView 子类，`extends WebView implements q4.a.c`，重写了 `loadUrl(String)` 和 `loadUrl(String, Map)` | 是注入目标容器 |
| `p4.j` | 主浏览页 WebViewClient，`extends WebViewClient` | ✅ **hook 点** |
| `a8.g$c` | 广告拦截页的 WebViewClient | ❌ 不处理正常页面 |
| `kb.m7$b` | 另一个 WebViewClient，只有 `shouldOverrideUrlLoading` | ❌ 不处理加载完成 |
| `s4.b.g(WebView, String)` | Via 内部对 `evaluateJavascript` 的包装 | ❌ 只被零散功能调用 |

**为什么选 `p4.j#onPageFinished` 而不是 `evaluateJavascript` 包装：** 后者只在「暂停视频」这类零散功能里被调，不是页面加载流程的一部分，等它触发可能永远等不到。

## 代码上的三个坑

写这类注入模块时踩过的，记下来：

**1. `onPageFinished` 会重复触发。** 页面有重定向、有 iframe、有 `pushState` 时都可能再触发一次。用 `WeakHashMap` 记录已注入的 WebView，重复的直接跳过。用弱引用是因为 WebView 会被回收，强引用会泄漏。

**2. 时机太早拿不到 DOM。** `onPageFinished` 触发时 DOM 不一定解析完，特别是图文混排的页面。注入前 `postDelayed` 300ms 是个便宜的保险。

**3. 不能让核心自动启动。** 核心脚本末尾通常有一行 `start();`。如果保留它，每次打开网页都会立刻清屏重建，页面完全没法正常看。必须注释掉，改成按钮触发。

## 文件结构

```
ViaSwarm/
├── app/
│   ├── build.gradle                    # 模块构建配置
│   └── src/main/
│       ├── AndroidManifest.xml         # LSPosed 需要的 4 个 meta-data
│       ├── assets/
│       │   ├── swarm-core.js           # 鼠标集群动画核心（149KB）
│       │   └── xposed_init             # 入口类名
│       ├── java/com/hsqs/viaswarm/
│       │   └── Hook.java               # 全部逻辑，278 行
│       └── res/values/
│           ├── strings.xml
│           └── xposed_scope.xml        # 作用域：mark.via
├── .github/workflows/build.yml          # GitHub Actions：自动出 APK
├── build.gradle
├── settings.gradle
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

## 自动打包

推到 `main` 分支，GitHub Actions 自动构建，APK 在 Actions 页面的 Artifacts 里。

打 tag 时额外发布 Release：

```bash
git tag v1.0
git push origin v1.0
```

APK 会出现在 Releases 页面，别人可以直接下载。

工作流是 `.github/workflows/build.yml`，走 JDK 17 + AGP 8.5.2 + Gradle 8.7。

## License

代码部分 MIT。

`swarm-core.js` 是鼠标集群动画的核心实现，随仓库分发。Via 浏览器本身开源，本仓库不复制 Via 的任何代码，只通过 LSPosed 在运行时 hook 已安装的 APK。

---

**Stars.**