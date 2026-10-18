# 与 tv-vibehack 的对比与改进建议

对比对象：`chinalazi/tv-vibehack`（fork 自 `Gathin23/tv-vibehack`，MIT，单 Activity + RecyclerView + RecyclerView 动画，Java）
我方：`open-tv-launcher`（Kotlin + Leanback + Room + DataStore + 防沉迷）
目标设备：小米电视 4A（Android TV，PatchWall 国行 ROM，低内存机型）

---

## 0. 一句话结论

**两个项目解决的不是同一个问题**：tv-vibehack 解决"去广告 + 好看的桌面"，我们解决"好看 + 家长管控"。
但在**"如何真正在你那台 4A 上把桌面装上去、占住 Home 键、并让遥控器好用"**这件事上，它比我们做得多、做得细；
而**"防沉迷"这块它完全没有**，这部分我们是对的，只是实现还不牢。

真正值得马上抄的，是它的**部署链路**、**信号源菜单**、**焦点高亮手感**和**小米踩坑记录**——不是它的技术栈。

---

## 1. 最该抄的 5 件事（按对你那台机器的收益排序）

### ① 部署链路：把"编译出 APK"补成"装到电视并占住 Home 键"

它有一整套：`tools/tv.py`（纯 Python ADB 客户端，**不需要装 adb 或 Android SDK**）→ `b64push.py` 分块推 APK → 一条 batch 跑完：

```bash
pm install -r /data/local/tmp/tvhome.apk
cmd package set-home-activity --user 0 com.gathin.tvhome
pm disable-user --user 0 com.google.android.tvlauncher   # 国行是 com.mitv.tvhome
```

我们目前只有 GitHub Actions 出 APK，**中间断在"怎么装进电视"**：得自己找 U 盘/电视助手，还得在小米弹的"选择桌面"里点，而 PatchWall 经常会抢回去。

**为什么对你特别重要**：这直接决定上一轮 P0-3 的"按 HOME 绕过锁屏"能不能根治。
一旦我们成为**唯一** HOME（原桌面被 `disable-user` 停用），按 HOME 必然回到我们自己的 Activity → 锁屏遮罩画在自己界面里就不会被甩掉，也不需要 `SYSTEM_ALERT_WINDOW`、不需要对抗后台启动限制。

**建议**：新增 `deploy/deploy.py`（照抄 `tools/tv.py` 的 adb_shell 用法，Windows 可直接跑，符合你不想装 SDK 的偏好），参数化：
`--install` / `--set-home` / `--disable-stock <pkg>` / `--restore <pkg>`，并把国行 PatchWall 包名（`com.mitv.tvhome` / `com.mitv.tvhome.atv`）写进默认值。

### ② 信号源（HDMI/AV）切换 —— 电视桌面上最高频的操作，我们完全没有

它用 `TvInputManager` 枚举 `isPassthroughInput()` 的输入，点一下切过去：

```java
TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
for (TvInputInfo info : tim.getTvInputList()) {
    if (!info.isPassthroughInput()) continue;
    // 切换
    startActivity(new Intent(Intent.ACTION_VIEW)
        .setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.getId()),
                        "vnd.android.cursor.item/channel"));
}
```

Kotlin 版 ≈ 25 行，加上 `info.getType() == TvInputInfo.TYPE_HDMI` 排序、`getInputState()` 显示"已连接/未连接"、末尾再去 `getLeanbackLaunchIntentForPackage(liveTvPkg)` 加一个"电视直播"入口。

**我们的差距**：需求里只写了 APP 和分类，没写信号源。但对一台电视来说，"我要看有线/HDMI2 的盒子"比"打开某个 App"更常用。
小米国行 ROM 的 LiveTV 包名不一定是 `com.mediatek.wwtv.tvcenter`（那是 MTK 方案），**先 `pm list packages | grep -i -E 'tvhome|livetv|wwtv|tuner'` 确认**，再写进 config。

### ③ 焦点高亮的手感：它一个玻璃块弹跳 > 我们 0 个 selector

它没有给每个卡片画 focus 边框，而是**一整块半透明玻璃（GlassView）在所有磁贴之间用弹簧动画飞行**：

```java
SpringForce().setStiffness(260f).setDampingRatio(0.58f)   // 位置
// 磁贴本身：focus 时 scale 1.10，OvershootInterpolator(1.6f)，260ms
```

外加：顶部大字号应用名 + 从 banner 采样主色的环境光晕、入场错峰动画（`LayoutAnimationController` delay 0.06）、圆角 `setClipToOutline`。

**我们的现状**：`res/drawable/` 里只有一个 banner 矢量图，**没有任何 focus selector**，`ListRowPresenter` 也没设 zoom —— 遥控器选中哪张卡几乎看不出来，这在电视上是致命的（4A 的遥控器没有鼠标）。
需求里写的"Scale 1.0 → 1.08 + 边框高亮"目前是 0 分。

**建议（不改架构的最小修法）**：`AppCardPresenter` 里给 `ImageCardView` 加 `OnFocusChangeListener` 做 1.08 缩放（`animate().scaleX/Y().setDuration(260).setInterpolator(OvershootInterpolator(1.4f))`）+ 一个 `state_focused` 的 2dp 高亮 border drawable。
**更好**：抄它的 GlassView 方案，比 Leanback 默认好看一个量级。

### ④ 小米的坑：默认输入法是坏的 → 我们的 EditText 很可能弹不出键盘

它 README 里明确记录（针对小米）：

> No on-screen keyboard after switching launchers: the default input method pointed at a Baidu IME that isn't installed. Fix with `ime set com.google.android.inputmethod.latin/...LatinIME`

**这对我们是硬伤**：我们的设置页有两个 `EditText`——
- `activity_parental_settings.xml:92` 输入 PIN；
- `activity_category_manager.xml:32` 输入分类名。

如果在 4A 上默认 IME 指向未安装的百度输入法，**这两个输入框点了没反应，家长设置页直接不可用**。

**建议**：
1. PIN 输入全部改成 D-pad 九宫格（锁屏页已经是了，设置页也照抄一份，复用同一个自定义 View）；
2. 分类名改成"预设名列表 + 内置 D-pad 字母键盘"，或者先给 6 个默认分类，重命名走预设；
3. 兜底：提供 `config.xml` 式的静态配置（见 ⑤），改一下重装即可，不必依赖屏幕输入；
4. README 里记一条：装完后若键盘异常，用 `ime set` 修（或 `pm enable com.baidu.input`）。

### ⑤ 自用场景下，"编译期配置"其实比"运行时 UI"更省事

它把 dock 顺序、隐藏应用、LiveTV 包名、列数全写进 `res/values/config.xml`，改完重编：

```xml
<string-array name="dock_apps">...</string-array>
<string-array name="hidden_apps">...</string-array>
<string name="live_tv_package">com.mediatek.wwtv.tvcenter</string>
<integer name="columns">4</integer>
```

我们做了 Room + 分类管理 UI，但**分类管理只有"新增"**（不能改名/删除/调序/归类），隐藏应用的 `isHidden` **没有任何入口能置 true** —— 等于"有数据库没 UI"。
在你只服务一台电视的前提下，**先补一个 `config.xml` 兜底 + 首启时读它初始化 DB**，收益/成本比远高于先做完整的分类管理 UI。

---

## 2. 它做得不如我们的地方（别照抄）

| 点 | tv-vibehack | 我们 | 判断 |
|---|---|---|---|
| 应用扫描范围 | **只查 `CATEGORY_LEANBACK_LAUNCHER`** | 同时查 LEANBACK + LAUNCHER | 国行电视很多 App 只有 `CATEGORY_LAUNCHER`，它这套在纯国行 ROM 上会扫出一片空。**我们的做法是对的** |
| 隐藏应用 | 只能改 config 重编 | 有字段无 UI | 半斤八两，但我们至少结构上支持运行时改 |
| 配置持久化 | 无（全在资源里） | Room + DataStore | 我们更适合做产品化；但它更适合"一台机器自用" |
| 权限模型 | 有 `ACCESS_FINE_LOCATION`（为了读 SSID），靠 adb 手工 grant | 零网络、零运行时权限申请 | 我们更干净；但代价是顶部不能显示 Wi-Fi 名称 |
| 回桌面性能 | `onResume` 里全量 `reload()` + `notifyDataSetChanged` + 主线程加载全部 banner/icon | 同样在主线程加载图标（`AppCardPresenter.kt:32-38`） | **两边都会在 1GB 机器上卡一下**，别学它；我们该做 icon 缓存 + 增量更新 |
| 防沉迷 | 无 | 有（但锁不牢） | 我们的核心价值，必须做扎实 |
| targetSdk | 29（旧规则，少踩新限制） | 34（规范，但踩了 FGS/BAL 的坑） | 不用降到 29，但要知道**你那台是 Android 10 = API 29，BAL 限制从 Android 10 就开始生效**，所以锁屏问题在你这台机器上一定会出现 |

---

## 3. 内存/体积：4A 是低内存设备，我们的技术栈偏重

它的依赖只有两个：

```groovy
implementation 'androidx.recyclerview:recyclerview:1.3.2'
implementation 'androidx.dynamicanimation:dynamicanimation:1.0.0'
```

我们：`leanback + appcompat + material + constraintlayout + room(runtime/ktx/compiler) + datastore + coroutines`。
粗估 APK 4~6MB vs 它约 1MB，dex 方法数也差一个量级；再加上 `Application.onCreate` 里**冷启动就启动全量扫描 + Room 打开 + DataStore 首次读盘**。

在 1GB/2GB 的 4A 上，表现为"按 Home 回桌面要等一下才开始跟手"。

**可落地的瘦身（不改功能）**：
1. `Application.onCreate` 的 `scanAndSyncInstalledApps()` 推迟到 `HomeFragment` 首帧之后（`view.post {}` 或 `Choreographer.postFrameCallback`），冷启动至少快 300~500ms；
2. 去掉 `material` 和 `constraintlayout`（当前 layout 全是 LinearLayout/FrameLayout，没用到 Material 组件）；
3. 图标用 `LruCache<ComponentName, Drawable>` 缓存 + 后台线程预取，别在 `onBindViewHolder` 里同步 `getApplicationIcon`；
4. 如果愿意再狠一点：分类/隐藏这类小数据改用 `SharedPreferences` JSON，能省掉 Room + KSP 全部体积（但会失去 Flow 响应式，取舍看你）。

---

## 4. 代码级可抄的具体片段

### 4.1 桌面 Activity 的更"占得住"的声明（`AndroidManifest.xml`）

```xml
<activity
    android:name=".ui.home.MainActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:stateNotNeeded="true"
    android:clearTaskOnLaunch="true"     <!-- 新增：每次回桌面清栈，残留界面全没 -->
    android:excludeFromRecents="true"    <!-- 新增：不进最近任务，减少切走入口 -->
    android:screenOrientation="landscape" <!-- 新增：TV 固定横屏 -->
    android:configChanges="orientation|screenSize|keyboard|keyboardHidden|uiMode">
```

`clearTaskOnLaunch` + `excludeFromRecents` 对防沉迷有直接价值：孩子不能从"最近任务"里切回刚才的视频。

### 4.2 启动应用时的正确 flags

我们目前只加了 `FLAG_ACTIVITY_NEW_TASK`，它多一个：

```kotlin
Intent().apply {
    component = ComponentName(pkg, cls)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
}
```

`RESET_TASK_IF_NEEDED` 能避免某些 App 被拉起到"上次退出时的中间页"。

### 4.3 长按 OK 的兜底功能（顺手就能加）

它对长按的定义是"打开系统应用详情"（`ACTION_APPLICATION_DETAILS_SETTINGS`）——一个不花钱但很实用的默认行为。
我们的需求要"打开/移至分类/隐藏/置顶"菜单，菜单没做之前，先接这个当兜底比现在"完全没反应"好。

### 4.4 一个很值得抄的工程习惯：把踩坑写进仓库

它 README 有 "Gotchas we hit" 段落 + `examples/<机型>/debloat.txt` 分机型清单 + `PROMPT.md`（把整个流程写成可复用提示词）。
我们只有 `develop.md`，而且**里面描述的一半功能代码里并不存在**（隐藏应用恢复、白名单管理、3 分钟提醒、手动排序）——文档比代码乐观，下次改的时候会被自己骗。

**建议**：`develop.md` 改成"已实现 / 未实现"两栏，或直接以 `CODE_REVIEW.md` 为准；新增 `examples/xiaomi-mi-tv-4a.md` 记录：
- PatchWall 包名、可停用清单（参考它那份，但要**先 `pm list packages` 核对你这台的国行包名**）；
- `ime set` 修键盘；
- 链式 `pm` 命令会中断，要一条一条发；
- **先装新桌面再停旧桌面**，否则 Home 键会落到工厂测试 App（它明确踩过）。

---

## 5. 优先级行动清单

| 优先级 | 事项 | 预计改动 | 收益 |
|---|---|---|---|
| P0 | 锁屏载体改造 + `clearTaskOnLaunch`/`excludeFromRecents` + 唯一 HOME 方案 | 中 | 防沉迷真正生效（替代上一轮的 P0-1/P0-3 修法） |
| P0 | 家长页 PIN 门禁（上轮 P0-2） | 小 | 堵住 5 秒绕过 |
| P1 | 部署脚本 `deploy.py`（install + set-home + disable-stock + restore） | 小 | 能真正装到电视并占住 Home 键，不用 SDK/U 盘 |
| P1 | PIN 输入改九宫格、消除对软键盘的依赖 | 小 | 4A 上设置页可用（IME 坏的坑） |
| P1 | 焦点缩放 1.08 + focus border（或 GlassView 方案） | 小~中 | 遥控器可用性，需求里明确要求 |
| P2 | 信号源/HDMI 切换 + LiveTV 入口 | 中 | 电视最高频操作 |
| P2 | `config.xml` 兜底 + 分类管理补齐改名/删除/归类/隐藏 | 中 | 现在"有库无 UI" |
| P3 | 冷启动优化（延后扫描、图标缓存、去无用依赖） | 小 | 1GB 机器回桌面跟手 |
| P3 | 文档纠偏 + `examples/xiaomi-mi-tv-4a.md` | 小 | 可维护性 |

---

## 6. 附：它的实现要点速查（供抄的时候对位置）

| 能力 | 它的实现 | 文件 |
|---|---|---|
| 安装到电视 | `pm install -r` + `set-home-activity` + `disable-user` | `launcher/build-and-install.sh` |
| 无 adb 二进制也能连电视 | `adb_shell` + `PythonRSASigner` | `tools/tv.py` |
| 大文件推送 | base64 分块 + md5 校验 | `tools/b64push.py` |
| 应用扫描 | `queryIntentActivities(CATEGORY_LEANBACK_LAUNCHER)`，过滤自己 + hidden 列表 | `AppAdapter.java:86-113` |
| 焦点弹簧高亮 | `SpringAnimation(TRANSLATION_X/Y)`，stiffness 260 / damping 0.58 | `MainActivity.java:138-142` |
| 磁贴缩放 | 1.10 + Overshoot(1.6f) + 名字淡入 | `AppAdapter.java:258-271` |
| banner 取色环境光 | 12×12 缩略图取高饱和色，写 HSV 调亮 | `AppAdapter.java:184-224` |
| 圆角磁贴 | `ViewOutlineProvider` + `setClipToOutline(true)` | `AppAdapter.java:243-249` |
| 信号源菜单 | `TvInputManager` + `buildChannelUriForPassthroughInput` | `MainActivity.java:392-483` |
| D-pad 确定性导航 | 自己算 position±COLUMNS，顶栏两端吞掉方向键 | `MainActivity.java:152-161, 306-320` |
| 记住上次焦点 | `lastFocused` + `onResume` 里 `focusTile()` | `MainActivity.java:183-188` |
| 小米去广告清单 | PatchWall/遥测/预装/推荐服务（全部 disable 不卸载） | `examples/xiaomi-mi-tv-4a/debloat.txt` |
