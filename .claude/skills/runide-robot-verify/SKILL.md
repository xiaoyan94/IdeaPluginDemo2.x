---
name: runide-robot-verify
description: runIde 沙箱 UI 自动化验证：起带 robot 服务的沙箱、Rhino JS 驱动插件 Swing UI（含模态弹窗逐个应答）、盘上核验产物、日志断言归因。触发词："沙箱验证"、"runIde 验证"、"robot 驱动"、"UI 自动化验证"、"沙箱点不动"。
---

# runIde 沙箱 robot 驱动验证

## 何时用

插件 UI 改动（生成器/对话框/表单类，如 codegen 阶段 3 各项）需要真实沙箱端到端验证且不想手工点 UI。golden/单测覆盖渲染，本流程覆盖「UI 交互 → 参数收集 → 产物落盘 → 日志断言」全链路。

## 步骤

### 1. 起沙箱

1. 杀残留：按**命令行**定位（`Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 匹配 CommandLine 含 `robot-server.port=8082`；按进程名查会漏，TaskStop 只杀 gradle 管道）
2. `JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2025.2.2/jbr" ./gradlew runIdeForUiTests --console=plain -q`（首次需在线解析 robot-server-plugin，之后可 --offline）
3. 轮询 `curl http://localhost:8082/` 至 200（约 40s；license 弹框期 UI 冻结会瞬断 000，按进程存活判定而非端口）
4. license agent 写法与协议端点见 memory `runide-robot-ui-driving`（**agent 行必须与真实 IDEA vmoptions 逐字一致、不带 `=jetbrains` 后缀**，否则沙箱 ~60s exit 7 且日志无 ERROR）
5. UiTests 沙箱用独立 config（`config_runIdeForUiTests/`，无 recentProjects 恒 Welcome）；要自动打开既有项目用 plain `runIde`（主 config 支持翻 recentProjects 的 opened 标记），robot 驱动只能配 UiTests 沙箱

### 2. JS 驱动通道

python 助手 POST `http://localhost:8082/js/execute`，body `{"script": "<js>", "runInEdt": true}`：

- **JS 返回值不回传响应 message——探针要结果必须 `throw '字符串'`**（响应 exception.details 可见）
- 端点**无 `/rpc` 前缀**（`/xpath/component` 查组件坐标、`/js/execute` 跑 JS、`GET /` 全组件 dump）
- 组件 dump 的 `visible_text`/`accessiblename` 属性可先 `curl /` 定位再写 xpath

### 3. 打开插件 UI（classloader 反射）

插件类不在 Rhino 默认 classloader——借插件自己的 action 拿 classloader：

```js
var am = Packages.com.intellij.openapi.actionSystem.ActionManager.getInstance();
var action = am.getAction('<插件 action id>');
var loader = action.getClass().getClassLoader();
var Cls = java.lang.Class.forName('<插件 UI 类 FQCN>', true, loader);
var project = Packages.com.intellij.openapi.project.ProjectManager.getInstance().openProjects[0];
var module = Packages.com.intellij.openapi.module.ModuleManager.getInstance(project).findModuleByName('<模块名>');
var P = java.lang.Class.forName('com.intellij.openapi.project.Project', true, loader);
var M = java.lang.Class.forName('com.intellij.openapi.module.Module', true, loader);
var ui = Cls.getConstructor(P, M).newInstance(project, module);
Cls.getMethod('show').invoke(ui);
```

（等价真实 action 调用；`JavaAdapter` 构造在 Rhino 里不可用，别走动态代理 DataContext 弯路。）

### 4. 模态弹窗防死锁（核心纪律）

- **任何会弹模态框的按钮一律 `SwingUtilities.invokeLater(function(){ btn.doClick(); })` 后立即返回**——同步 doClick 会卡死在模态泵里
- 下一个 robot 调用里处理弹窗：walk `java.awt.Window.getWindows()`（`instanceof java.awt.Dialog` + `getTitle()` 匹配）→ 找 `JTextComponent` `setText` → `getRootPane().getDefaultButton()` 仍 invokeLater 点击
- runInEdt 的 JS 会在模态泵内执行，逐弹窗一个调用推进；输入框按标题+当前值区分（如 GridName 空文本 vs 页面名称预填）
- walk 递归：`instanceof java.awt.Container` + `getComponentCount()/getComponent(i)`

### 5. 核验与收尾

- 产物：目标模块 `svn status` 列新增 + 逐项内容核对（对照口径）；新增产物会被 IDEA svn 自动 add，清理时先 revert 撤 add 再删（显式路径）
- 日志：`build/idea-sandbox/IU-2024.3.5/log_runIdeForUiTests/idea.log` grep `Slow operations|AssertionFailedError|ERROR`，断言**按堆栈归因**（插件自身 vs 既有分支；已知 `detectEasyExcel2`（P1-6 EDT 分支）非新回归）
- 生成模块若为 MES 项目：`mvn compile -P central,dev` 验产物可编译
- 收尾：杀沙箱进程（按命令行定位）再发版构建，防 prepareSandbox 文件锁
