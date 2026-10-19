# 开发记录

## 技术栈

- Kotlin 1.9.22 + AGP 8.2.2 + Gradle 8.2，compileSdk/targetSdk 34，minSdk 23（Android 6.0，兼容小米电视 4A）
- AndroidX Leanback（桌面）、Room + KSP（分类/应用数据）、DataStore（家长控制状态）
- 无网络权限、无网络库；release 沿用 debug 签名，方便 CI 直出可安装包

## 版本 1.1.0（本轮重写）

### 防沉迷（核心修复）

| 旧实现 | 新实现 |
|---|---|
| 后台 Service `startActivity` 拉起锁屏 Activity（Android 10+ 被 BAL 拦截，弹不出来） | 锁屏改为桌面 Activity 内的全屏遮罩，随状态显示 |
| 锁屏是 `singleInstance` 独立任务，按 HOME 即可绕过，且只在"归零那一秒"边沿触发 | 状态驱动 + `onResume` 重新评估，`clearTaskOnLaunch` / `excludeFromRecents` |
| 家长设置页无 PIN 门禁，可直接关闭儿童模式 | 进入即要求 PIN；改 PIN 需先验旧 PIN |
| 解锁一律"续满单次时长"，无选择；每日上限字段声明未实现 | 解锁后可选「+30 分钟 / 今日不再限制」；每日上限与跨天重置完整实现 |
| 每秒写一次 DataStore | 内存 StateFlow 计时，5 秒节流落盘 |
| PIN 单轮 SHA-256 | PBKDF2-HMAC-SHA256 + SecureRandom 盐（迭代数 1 万，老版本 12 万会在电视上耗 7 秒，校验成功后自动迁移）；错误 5 次锁 5 分钟 |
| 计时不看亮屏 / 白名单照样扣时长 | 亮屏 + 前台白名单应用判定（UsageStats，可选授权） |
| 后台服务不处理被杀后恢复 | `START_STICKY`，开机广播恢复计时 |

### 桌面与数据

- `HomeFragment` 改为 `combine(分类Flow, 应用Flow)` 单一渲染，装/卸载即时刷新，不再用 `first()` 拉一次
- `PACKAGE_REMOVED` 判断 `EXTRA_REPLACING`，应用升级不再丢失用户配置（分类/隐藏/白名单）
- 扫描时一次读取全表做差集，并清理已卸载/组件失效的残留项
- 卡片焦点：放大 1.08 + 描边 selector；图标加 LruCache
- 长按卡片：加入白名单 / 隐藏应用
- Room 初始化竞态修复（默认分类改为按需创建，不再依赖 `INSTANCE` 的时序）

### 设置

- 系统设置补齐：显示 / 声音 / 默认桌面
- 分类管理：新增（预设名）、改名、调序、删除（应用迁移）、隐藏应用恢复
- 防沉迷设置全部改为遥控器九宫格 / 按钮，规避小米固件软键盘不可用的问题

### 工程

- 移除未使用的 `material`、`constraintlayout`、`leanback-preference` 依赖
- 开启 `buildConfig`（版本号展示）
- `allowBackup=false`（避免 PIN 哈希被备份导出）
- 新增 `deploy/deploy.py`（纯 Python ADB，无需 adb/SDK）
- 新增 `tools/verify_static.py`（无 SDK 环境下的静态自检）
- 删除 `ui/lock/ScreenLockActivity`（改为桌面内遮罩）

## 版本 1.4.x（全新桌面视觉）

- 首页从 Leanback 多行改为单栏居中布局：`activity_main` 用顶栏 + 居中 stage，
  卡片为自绘 `AppTileView`（品牌色渐变 + 白描边聚焦），不再依赖 `RowsSupportFragment`。
- 新增 `AmbientBackdropView`：两个径向渐变绘制氛围光（跟随聚焦应用主色，420ms 过渡），
  以及 `bg_vignette`（中心透明、边缘压暗）。不用 RenderEffect（API 31+），API 23 可用。
- 新增 `AppPalette`：把图标画到 16×16 位图，按饱和度加权取主色，`LruCache` 缓存；灰白图标走中性色。
- 新增 `AllAppsActivity`（6 列 `VerticalGridView`）+ `AppTilePresenter` + `AppMenu`（自绘玻璃菜单）。
- 锁屏改为玻璃卡片：`PinPad` 换成圆点 + 玻璃键位（`setInputEnabled` 防止成功后重复提交），
  主按钮用 `bg_btn_primary_selector`（白色填充 + 白色外圈表示焦点）。
- 设置页：左侧导航 + 右侧 `item_settings_row` 信息卡（图标 / 标题 / 副标题 / 右侧状态值 / 箭头）。
- 移除 `HomeFragment`、`AppCardPresenter`、`ActionItemPresenter`、`card_foreground`、`bg_panel`。
- 修复编译问题：`VerticalGridView.adapter` 需要显式转成 `RecyclerView.Adapter`（Java 裸类型）。

## 版本 1.2.x / 1.3.0（真机联调，小米电视 4A / Android 6.0）

- **锁屏**：从 Activity 内遮罩改为独立 `LockScreenActivity`，由计时服务在进入锁定态时拉起，
  可直接覆盖正在播放的视频（SmartTube 实测）；桌面 `onResume` 兜底，按 HOME 回来会再次弹出。
  `LockScreenLauncher` 带 800ms 节流，避免重复 startActivity。
- **PIN 性能**：`verifyPin` 的 PBKDF2 之前在**主线程**执行，实测 6990ms（logcat），输完最后一位整屏卡死。
  改为 `Dispatchers.Default` + 迭代 120000 → 10000，实测 580ms；老 PIN 校验成功后自动迁移参数。
- **系统设置**：`SystemSettingsLauncher` 按「小米自定义 action → 标准 action → 显式组件」顺序兜底。
  小米电视没有实现 `android.settings.SETTINGS`（ActivityNotFound），需要 `com.xiaomi.mitv.settings.SETTINGS`。
  Wi-Fi / 蓝牙 / 图像声音 子页面均可正常拉起；设置主页是弹窗型 Activity，第三方拉起会被系统收回，
  因此桌面「系统设置」改为进入应用内设置页。
- **签名**：仓库内固定 `keystore/opentv.p12`（release 与 debug 同签名），修复了之前每次 CI 生成新 debug key
  导致旧版本无法覆盖安装的问题。
- **调试支持**：debug 包内置 `com.openlauncher.tv.DEBUG` 广播（lock / reset / pin / sysset），并随 Release 发布。

## 版本 1.1.2（代码清理）

- 删除 7 个从未调用的 DAO 方法（`getCategory` / `getCategoryCount` / `getAppsByCategoryIdFlow` / `findApp` / `insertOrUpdateApp` / `updateApp` / `getKidWhitelistApps`）。
- `CategoryEntity` 删除两个从未被读取的字段（`isChildVisible` / `isSystemDefault`），数据库版本 1 → 2，采用 `fallbackToDestructiveMigration()`（分类会被重建）。
- 删除未使用的 `PinPad.setTitle()`、`AppIconCache.clear()`。
- 移除未使用的依赖 `lifecycle-viewmodel-ktx`（本工程无 ViewModel）。
- 布局里删除 3 个没有代码引用的 id（`main_frame` / `tv_title` / `tv_lock_title`）。
- 设计文档（需求 / 审查 / 对比）收进 `docs/`；`.gitignore` 增加 `deploy/.adbkey/`（adb 私钥）与 `*.log`。

## 版本 1.1.1

- `minSdk` 24 → 23：小米电视 4A（Android 6.0 / API 23）此前报 `INSTALL_FAILED_OLDER_SDK` 装不上。
- 复查全部 API 调用：`View.setForeground`、`Context.getSystemService(Class)` 均为 API 23；`startForeground(type)`、`NotificationChannel`、`ACTIVITY_RESUMED`、`FOREGROUND_SERVICE_TYPE_*` 都有版本判断；PBKDF2 在 API < 26 自动退回 `PBKDF2WithHmacSHA1`。

## 历史版本

- 1.0.0：初始工程骨架、Leanback 多行、Room、DataStore、CI 出包（约 4.92 MB）。

## 尚未实现（后续）

- 应用卡片手动排序 / 按启动频率排序（`sortWeight` 字段已预留）
- 一个应用归属多个分类（需要关联表）
- HDMI / 信号源切换（`TvInputManager`）、Live TV 直达
- 悬浮窗级锁屏覆盖层（彻底防"最近任务"切走）、设备所有者 kiosk 模式
- 单元测试（`ParentalState` 的跨天重置、额度计算适合先补）
