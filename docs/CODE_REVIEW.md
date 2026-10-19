> **历史文档**：本文件记录的是 v1.1.0 之前的代码审查报告，其中列出的问题已在 v1.1.0~1.1.2 修复（见 develop.md），保留作为决策依据。

# OpenTV Launcher 代码审查报告

对照《编制需求.md》逐条核对，审查范围：`app/src/main/**`、`app/build.gradle.kts`、根 Gradle 配置、`.github/workflows/build-apk.yml`。
审查方式：静态走查（本机无 JDK/Android SDK，未实际编译运行）。

**结论**：工程骨架、构建链、离线约束都成立，能编出 APK；但**防沉迷模块（需求里最核心的安全模块）基本处于"演示级"，存在 4 个可 5 秒内绕过或直接失效的硬伤**，另有大量需求条目未实现。按当前状态不建议给儿童使用。

---

## 一、P0 阻断级问题（核心功能失效 / 可被绕过）

### P0-1 锁屏到点弹不出来（后台启动 Activity 被系统拦截）
`service/TimeTrackingService.kt:73-78`

```kotlin
val lockIntent = Intent(this, ScreenLockActivity::class.java)
    .addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)
startActivity(lockIntent)
```

- 从 Android 10（API 29）起，后台应用禁止启动 Activity；官方明确**"正在运行前台服务的应用，在启动 Activity 这件事上仍被视为后台"**。
- 唯一豁免途径之一是 `SYSTEM_ALERT_WINDOW` 已被用户授予，而本项目：
  - `AndroidManifest.xml:17` 声明了 `SYSTEM_ALERT_WINDOW`；
  - 但全项目**没有任何 `Settings.canDrawOverlays()` 检测、没有 `ACTION_MANAGE_OVERLAY_PERMISSION` 引导、也没有真正用 overlay 窗口实现锁屏**。
- 结果：targetSdk 34 的机器上，倒计时归零时 `startActivity` 被静默丢弃（或抛 `AndroidRuntimeException`），**锁屏根本不出现**。

**修复方向（三选一，推荐 a）**
- a) 锁屏不另起 Activity，改成在 `MainActivity` 内渲染全屏锁屏遮罩（配合全局 `locked` 状态 + `onResume` 强制回拉）；启动器天然在前台，不受 BAL 限制。
- b) 用 `WindowManager.addView(TYPE_APPLICATION_OVERLAY)` 覆盖层 + 正式申请悬浮窗权限。
- c) Device Owner / COSU kiosk（最可靠，但需要设备管理员授权）。

### P0-2 家长设置页没有任何 PIN 门禁 —— 儿童可 5 秒关闭防沉迷
`ui/parental/ParentalSettingsActivity.kt` 全文（1-65 行）

需求 5.1 明确："进入家长管理后台、修改时长限制、关闭儿童模式均必须输入 PIN 码。"
实际上：
- `SettingsActivity.kt:39-41` 直接 `startActivity(ParentalSettingsActivity)`，无校验；
- `ParentalSettingsActivity` 打开即渲染开关，`switchKidsMode` 一关就 `setKidsModeEnabled(false)`；
- `btnSavePin` 允许**直接覆盖 PIN**，不校验旧 PIN。

→ 孩子路径：遥控器 → 启动器设置 → 儿童模式设置 → 关开关 / 改 PIN，全程 0 成本。**这是最严重的漏洞。**

**修复**：`ParentalSettingsActivity` 增加 PIN 门禁（进入前弹 PIN 校验，失败 5 次锁 5 分钟）；改 PIN 必须先验证旧 PIN。

### P0-3 HOME 键 + 边沿触发，锁屏可被永久甩掉
`ui/lock/ScreenLockActivity.kt`、`service/TimeTrackingService.kt:60-68`、`AndroidManifest.xml:66-73`

- 锁屏 Activity 是 `singleInstance` 的独立任务；`MainActivity` 自身就是 `CATEGORY_HOME`。
- 按 HOME → 系统直接把启动器拉到前台，锁屏被压到后台；锁屏只拦了 `KEYCODE_BACK`（`ScreenLockActivity.kt:78-84`），**HOME 拦不住**（普通 Activity 也拦不住）。
- 更致命的是计时器是**边沿触发**：

```kotlin
if (remaining == 0) { triggerScreenLock() }   // 只在“归零那一秒”弹一次
```

`remaining` 到 0 后不再变化，锁屏一旦被 HOME 顶掉就**再也不会重新弹出**。孩子按一下 HOME 就彻底解锁。

**修复**：改成电平触发（`remaining <= 0` 时每次心跳都检查锁屏是否在最前，不在就重新拉起）；并让桌面在 `locked` 状态下 `onResume` 主动拉起锁屏；`onUserLeaveHint` 兜底。

### P0-4 解锁逻辑等价于"永久续命"，且"今日不再限制 / 每日上限"完全没做
`ui/lock/ScreenLockActivity.kt:58-76`、`data/repository/PreferencesRepository.kt:15-22`

- 输对 PIN 后直接 `updateRemainingSeconds(singleLimitMinutes * 60)` 再 `finish()`：
  - 需求要求"解锁时家长可选择增加 30 分钟或今日不再限制"，实现里**没有选择项**，一律续满一个单次时长；
  - `finish()` 后回到之前正在播的视频 App（因为锁屏是独立任务），孩子继续看；
  - 可以无限次重复解锁续命。
- **每日累计上限未实现**：`KEY_DAILY_LIMIT_MINUTES`、`KEY_LAST_TRACK_DATE` 声明后全项目零引用，没有跨天重置、没有当日累计。

### P0-5 计时不看前台应用 / 不看亮屏，白名单也照样扣时长
`service/TimeTrackingService.kt:45-71`、`ui/home/HomeFragment.kt:74-81`

- 服务每秒无条件 `remaining--`，不看当前前台是什么应用（没有 `UsageStatsManager` / `ActivityManager` 判定），不看屏幕是否点亮（无 `PowerManager.isInteractive`）。
  → 电视待机、儿童在看白名单里的网课，一样在扣时长。
- `HomeFragment` 只在"剩余 <= 0 时拦住启动"时看 `isWhitelistForKids`，**不影响计时**。需求要的是"白名单应用不计入倒计时"。
- 服务不是 `START_STICKY`，没有 `onTaskRemoved` 处理；被杀后计时直接归零，防沉迷形同虚设。

---

## 二、P1 需求条目缺失对照表

| # | 需求条目 | 状态 | 证据 |
|---|---|---|---|
| 1.1 | 第一行为常用/固定应用行 | 未实现 | `HomeFragment.kt:49-53` 第一行是"系统与控制"快捷行 |
| 1.2 | 焦点放大 1.0 → 1.08、边框/阴影高亮 | 未实现 | `res/drawable/` 只有 `ic_launcher_banner.xml`，无 focus selector；`ListRowPresenter` 未设 zoom |
| 1.3 | 长按 OK 弹操作菜单（打开/移至分类/隐藏/置顶） | 未实现 | 无 `onItemViewLongClickedListener` |
| 1.4 | 顶部时钟 + 日期 + 剩余时长 | 部分 | `activity_main.xml:36-42` 只有 TextClock，无日期 |
| 2.1 | 安装/卸载广播动态刷新 | 部分 | 广播会写库，但 UI 不刷新（见 P2-1）；升级会误删（见 P2-2） |
| 2.2 | 分类 增/改/删/排序 | 仅"增" | `CategoryManagerActivity.kt:38-52` 只有新增 + 只读列表 |
| 2.3 | 一个应用归入多个分类 | 未实现 | `AppItemEntity.kt:23` 单一 `categoryId`，需改成关联表 |
| 2.4 | 隐藏应用 / 一键恢复 | 未实现 | `isHidden` 无任何写入口；`getHiddenAppsFlow` 无人调用 |
| 2.5 | 手动置顶 / 首字母 / 启动频率排序 | 未实现 | `sortWeight` 恒为 0，无启动频率统计 |
| 3.1 | 系统设置、Wi-Fi、蓝牙、应用管理直达 | 已实现 | `SettingsActivity.kt:25-36` |
| 3.2 | 显示 / 声音设置直达 | 未实现 | 缺 `ACTION_DISPLAY_SETTINGS` / `ACTION_SOUND_SETTINGS` |
| 3.3 | 单次时长 15/30/45/60/自定义 | 部分 | `ParentalSettingsActivity.kt:40-42` 只有 30/45/60，无 15、无自定义 |
| 3.4 | 每日累计上限 + 重置周期 | 未实现 | 见 P0-4 |
| 3.5 | 白名单应用管理 UI | 未实现 | `isWhitelistForKids` 无写入口，`getKidWhitelistApps()` 无人调用 |
| 3.6 | 应用可见性控制（隐藏清单/一键恢复） | 未实现 | — |
| 3.7 | 卡片行间距 / 卡片大小偏好 | 未实现 | — |
| 3.8 | 默认桌面检测引导、版本号、开源声明 | 未实现 | `HomeFragment` 无相关项 |
| 4 | Room + DataStore 持久化 | 已实现 | 但 `CategoryEntity.isChildVisible` 未使用 |
| 5.1 | PIN 4~6 位 + SHA-256 加盐 | 部分 | 硬编码 4 位（`ScreenLockActivity.kt:35`、`ParentalSettingsActivity.kt:46`） |
| 5.2 | 进入家长后台需 PIN | 未实现 | 见 P0-2 |
| 5.3 | 3 分钟倒计时提醒 | 未实现 | — |
| 5.4 | 超时锁屏 + 按键拦截 | 部分/失效 | 见 P0-1 / P0-3；MENU 键未拦 |
| 5.5 | 解锁可选"加 30 分钟 / 今日不再限制" | 未实现 | 见 P0-4 |
| 5.6 | 错误 5 次锁 5 分钟 | 未实现 | 无失败计数 |
| 4.1 | MVVM + ViewModel | 未实现 | 全部 Activity/Fragment 直接调 DAO/DataStore，无 ViewModel、无状态层 |

---

## 三、P2 逻辑与实现缺陷

### P2-1 安装/卸载后桌面不刷新（违反"无需重启"）
`HomeFragment.kt:43-67`

外层只 `collect` 了 **categories** 一个 Flow，每个分类下的应用列表用 `first()` **一次性**取：

```kotlin
app.database.launcherDao().getAllCategoriesFlow().collectLatest { categories ->
    ...
    val apps = app.database.launcherDao().getAppsByCategoryIdFlow(cat.id).first()  // 一次性
```

→ 装/卸应用后 `PackageChangeReceiver` 更新了数据库，但只有分类表变化才会重建行，界面不会动。

同时该写法还有三个副作用：`rowsAdapter.clear()` 全量重建导致**每次刷新焦点丢失**；`collectLatest` 在 N 次 DB 查询中途被新事件取消，可能渲染出半截数据；每次刷新都 `new AppCardPresenter()`。
**修复**：用 `combine(categoriesFlow, appsFlow)` 或 `flatMapLatest` 组合成 `UiState`，在 ViewModel 里做，一次渲染。

### P2-2 应用升级会丢掉用户自定义（分类/隐藏/白名单全没）
`PackageChangeReceiver.kt:22-29`

应用更新时系统会发 `PACKAGE_REMOVED`（带 `EXTRA_REPLACING=true`），当前代码不判断就直接 `deleteAppsByPackage()`，随后 `PACKAGE_ADDED` 重新插入默认分类。
→ 每次 App 升级，用户给它设置的分类、隐藏、白名单**全部被重置**。

```kotlin
// 修复：先排除“正在替换”
if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
```

### P2-3 每秒写一次 DataStore（IO 放大 + 闪存损耗）
`TimeTrackingService.kt:62`、`PreferencesRepository.kt:59-61`

DataStore 每次 `edit` 都是"整文件重写 + fsync"。1Hz 写一年 ≈ 3000 万次落盘，电视盒子 eMMC 会被明显磨损，还会和 UI 读抢锁。
**修复**：把剩余秒数放内存（或 `MutableStateFlow`），每 30~60s 或关键节点（暂停/退出/归零）落盘一次。

### P2-4 `verifyPin` 用 `edit {}` 做只读校验
`PreferencesRepository.kt:72-83`

```kotlin
context.dataStore.edit { preferences -> ... isValid = ... }   // 为了读，却触发写事务
```

- 每次校验都会产生一次写；并发校验有概率抛 `IllegalStateException`；
- 校验失败计数、限流也无法在这里落地。

**修复**：`context.dataStore.data.first()` 只读；同时加入失败次数/锁定截止时间（对应需求 5.6）。

### P2-5 PIN 强度不足 + 可被 adb 备份导出
- 单轮 `SHA-256(pin:salt)`，4 位 PIN 只有 1 万种组合，拿 `.db`/备份文件可瞬间穷举。
- salt 用 `System.currentTimeMillis()`（可预测），应用 `allowBackup="true"`（`AndroidManifest.xml:23`）→ 家长 PIN 可被备份带走。
**修复**：`PBKDF2WithHmacSHA256`（≥10 万次迭代）或 `EncryptedSharedPreferences`；salt 用 `SecureRandom`；`allowBackup="false"`。

### P2-6 数据库初始化存在竞态
`data/local/AppDatabase.kt:42-49`、`app/LauncherApplication.kt:20-26`

- `onCreate` 回调里写 `INSTANCE?.let { ... }`，若首次打开早于 `INSTANCE = instance` 赋值，初始化被静默跳过（默认分类永远不建）；
- `Application.onCreate` 同时启动扫描，可能读到空分类表 → `defaultCategoryId` 落到硬编码 `1L`（`AppRepository.kt:52`）。
**修复**：无条件 `INSTANCE!!`；或把默认分类放进迁移/首次初始化流程，并让扫描"无分类先建分类"。

### P2-7 删除分类会产生"孤儿应用"
`LauncherDao.deleteCategory()` 无级联处理，删除后该分类下 `AppItemEntity.categoryId` 悬空，而桌面是"按分类渲染" → 这些应用在桌面彻底消失且无法找回。
**修复**：`@Transaction` 内把该分类下应用迁回默认分类，或加外键 `ON DELETE SET DEFAULT`。

### P2-8 其他实现细节
| 位置 | 问题 |
|---|---|
| `HomeFragment.kt:105-109` | id==3「儿童模式管理」跳的是 `SettingsActivity`，应跳 `ParentalSettingsActivity` |
| `MainActivity.kt:45-56` | `val isKids` 声明未使用；剩余时长无条件 `VISIBLE`，儿童模式关闭时也显示"剩余时长" |
| `MainActivity.kt:53` | `String.format` 未指定 `Locale` |
| `AppRepository.kt:60` | 循环内逐个 `findApp()` 查询，数百应用会明显变慢，应一次取全表建 Map |
| `AppRepository.kt:48` | 用硬编码类名字符串过滤自身，应 `MainActivity::class.java.name` |
| `AppCardPresenter.kt:32-38` | 主线程同步 `getApplicationIcon`/`loadLabel`，多行渲染卡顿，应缓存 + 后台预取 |
| `ActionItemPresenter.kt:15-17`、`AppCardPresenter.kt:18` | `width = 240`、`height = 110`、`setMainImageDimensions(260,160)` 是 **px**，不同密度电视上尺寸错乱，必须 dp→px |
| `ScreenLockActivity.kt:78-84` | 只拦 BACK；MENU 未拦；未 `FLAG_KEEP_SCREEN_ON`、未沉浸式、未屏蔽 Recents |
| `ParentalSettingsActivity.kt:28-38` | 关闭儿童模式同样不校验 PIN；重新开启时不重置剩余配额（上次为 0 则一开就锁死） |
| `values/strings.xml` | 只有 `app_name`，全部文案硬编码在 layout/代码里（无法国际化，lint 全量 HardcodedText） |
| 全局 | 无任何 focus selector drawable，遥控器选中态基本靠系统默认，与"边框高亮"差距大 |

---

## 四、P3 工程 / 构建 / CI

| 项 | 说明 |
|---|---|
| `AndroidManifest.xml:72` `android:showOnLockScreen` | 已废弃属性（API 26 起 deprecated，推荐 `showWhenLocked` + `turnScreenOn`）；TV 无锁屏场景下更无意义，建议删除或换成新属性 |
| `AndroidManifest.xml:17` `SYSTEM_ALERT_WINDOW` | 声明但完全未使用（见 P0-1）；要么真做 overlay 并引导授权，要么删掉 |
| 缺 `POST_NOTIFICATIONS` | Android 13+ 未声明/未申请，前台服务通知被静默，用户看不到"监护中" |
| `AndroidManifest.xml:87-100` receiver | 收到 `BOOT_COMPLETED` 只做扫描，不拉起 `TimeTrackingService`；建议开机即恢复计时 |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | 只有 v26 目录，且内容是**普通 vector 而非 `<adaptive-icon>`**；`minSdk=24` → API 24/25 上取不到 `@mipmap/ic_launcher`，图标空白/异常。需补 `mipmap-anydpi` 或 `drawable` 兜底 |
| `build.gradle.kts:24` release 用 debug 签名 | 自用可安装，但以后换正式签名必须卸载重装（数据全丢）；建议 CI 用 secrets 生成 keystore |
| `build.gradle.kts:45-48` `lint { abortOnError = false }` | 会永久掩盖真实错误，建议仅对 debug 关闭 |
| `build.gradle.kts:23` `isMinifyEnabled=false` | `proguard-rules.pro` 形同废纸（非阻断） |
| `HomeFragment` 架构 | 无 ViewModel（需求明确 MVVM），无锁屏/计时的单一状态源——这正是 P0-3/P0-4 的根因 |
| 测试 | 无任何单元/仪器测试；防沉迷这类状态机强烈建议补 `TimeTracking` 的纯逻辑单测 |
| 构建链自身 | AGP 8.2.2 + Gradle 8.2 + Kotlin 1.9.22 + KSP 1.9.22-1.0.17 组合正确；`gradle-wrapper.jar` 63KB 完整、`gradlew` 为 LF，CI 脚本可跑 |

---

## 五、做得对的部分

- 清单**确实没有 `INTERNET`**，依赖里也没有任何网络库，离线约束成立 ✅
- Room（KSP）+ DataStore + Coroutines/Flow 的选型与需求一致 ✅
- `CATEGORY_HOME` + `CATEGORY_LEANBACK_LAUNCHER` + `singleTask` + `stateNotNeeded` 的启动器声明是对的 ✅
- `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 声明完整 ✅
- 安装/卸载广播、`PACKAGE_REPLACED` 都注册了 ✅
- CI 有错误注解提取、日志与 APK 双 artifact ✅

---

## 六、建议修复顺序

1. **P0-2 家长页 PIN 门禁**（改动最小、收益最大，先堵住"5 秒绕过"）
2. **P0-3 锁屏回拉 + 电平触发**（把"锁一次就没了"补成"锁住就出不去"）
3. **P0-1 锁屏载体改造**（改到 MainActivity 遮罩或 overlay，解决 BAL 拦截）
4. **P0-4 每日上限 + 解锁选项**（补 `KEY_LAST_TRACK_DATE`/日累计，加"加 30 分钟/今日不限制"）
5. **P0-5 计时口径**（前台应用 + 亮屏 + 白名单豁免；内存计时、定期落盘、START_STICKY）
6. **P2-1/P2-2 刷新与升级丢配置**（体验硬伤）
7. 再补 P1 里缺失的 UI（分类增删改、隐藏、长按菜单、focus selector、焦点缩放）
