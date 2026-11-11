# assets 目录说明

本目录是模块打包时会被塞进 APK 的静态资源。

| 文件 | 作用 |
|---|---|
| `xposed_init` | 单行，写入口类的全限定名：`io.github.stars520.viaswarm.Hook`。LSPosed 靠它找入口 |
| `swarm-core.js` | 鼠标集群动画（cursor swarm）的核心实现，约 149KB |

## swarm-core.js

这是注入进网页的那段 JavaScript。`Hook.java` 在页面加载完成后把它整个读出来扔进 WebView。

随仓库分发，克隆下来直接编译就能用。

### 它对外的接口契约

`Hook.java` 只依赖下面这些符号，改动核心时不能删：

```js
window.__swarmRebuild = {
  // 启动重建
  start(),

  // 停止重建。false 表示保留当前画面，不回滚到原始页面
  stop(keepFrame),

  // 切换。内部实现是 start / stop 二选一
  toggle(),

  // 当前运行实例，未运行时为 null / undefined。
  // 按钮用它判断当前是「可以启动」还是「正在运行所以该停止」
  get app(),

  // 调试用，注入框架不碰它
  debug: { captureModel, detectEnv, Sound }
}
```

### 必须是「不自动启动」

核心文件末尾原本有一行 `start();`，已经被注释掉了：

```js
// 不自动启动：android 上没有快捷键，改由页面按钮调用
// start();
```

**请不要把它改回来。** 浏览器扩展里靠快捷键触发所以能自动跑，但安卓上没有这个入口，保留它会变成每次打开网页都立刻清屏重建，页面完全没法正常看。

启动入口统一交给页面上那个绿色按钮。

### 调试用的最小占位

如果你换了核心、想先确认「注入链路本身有没有通」，可以把 `swarm-core.js` 临时换成这个假实现：

```js
(() => {
  const KEY = "__swarmRebuild";
  if (window[KEY]) { window[KEY].toggle(); return; }
  let app = null;
  window[KEY] = {
    start() {
      app = {};
      const d = document.createElement("div");
      d.id = "swarm-stub";
      d.textContent = "core 已注入（占位）";
      d.style.cssText =
        "position:fixed;left:12px;top:12px;z-index:2147483600;" +
        "background:#0D99FF;color:#fff;padding:10px 16px;" +
        "border-radius:10px;font:600 14px/1.4 system-ui,sans-serif";
      document.documentElement.appendChild(d);
    },
    stop() {
      app = null;
      const d = document.getElementById("swarm-stub");
      if (d) d.remove();
    },
    toggle() { app ? this.stop() : this.start(); },
    get app() { return app; },
    debug: {},
  };
})();
```

装上模块，点右下角绿色按钮，左上角出现蓝色小方块，就说明 Java 到 JS 这条链路是通的，问题只可能在核心实现本身。