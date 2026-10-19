# OpenTV Launcher

专为 Android TV / 电视盒子设计的**纯离线**、开源电视启动器，内置**儿童防沉迷时长管理与 PIN 锁屏**。
主要面向低内存机型（如小米电视 4A）自用：不联网、不申请网络权限、不引入任何网络库。

---

## 核心特性

**桌面**

- AndroidX Leanback 横向多行布局，分类行 + 常驻"系统与控制"行
- 遥控器焦点反馈：卡片选中放大 1.08 倍 + 蓝色描边
- 长按卡片（OK 键长按）弹出操作菜单：**加入儿童白名单** / **隐藏应用**
- 顶部常驻时钟 + 儿童模式剩余时长（剩余 3 分钟变红提醒）
- 应用安装/卸载/升级后自动刷新，用户自定义（分类、隐藏、白名单）不会被覆盖

**设置（全部可用遥控器操作，不依赖软键盘）**

- 系统设置直达：系统设置、Wi-Fi、蓝牙、应用管理、显示、声音、默认桌面
- 分类管理：新增（预设名一键创建）、改名、上下调序、删除（应用自动迁回第一个分类）
- 隐藏应用清单 + 一键恢复显示
- 儿童模式：PIN 门禁、单次时长 15/30/45/60、每日上限 1~4 小时、重置今日计时、修改 PIN

**防沉迷（核心）**

| 机制 | 实现方式 |
|---|---|
| PIN 存储 | PBKDF2-HMAC-SHA256（1 万次迭代，实测这颗芯片约 0.6s）+ 16 字节随机盐，SHA-256 兜底 |
| 家长后台保护 | 进入家长设置必须输入 PIN；修改 PIN 需先验证旧 PIN |
| 防暴力破解 | 连续错误 5 次锁定 5 分钟 |
| 计时口径 | 每秒一次，仅在**屏幕点亮 + 儿童模式开启 + 未解锁**时计数；白名单应用（需授予"使用情况访问"）不计入 |
| 双上限 | 单次观看时长 + 每日累计上限，两者取先到者；跨天自动重置 |
| 锁屏 | 桌面 Activity 内的全屏遮罩（不依赖后台启动 Activity，规避 Android 10+ 的限制），拦截返回/菜单键，数字键直接输入 |
| 解锁 | 输对 PIN 后可选择「再加 30 分钟」或「今日不再限制」 |
| 数据 | 全本地 Room + DataStore，内存计时、5 秒节流落盘（不每秒写盘） |

### 已知局限（请务必了解）

1. **必须把本应用设为唯一默认桌面**，防沉迷才闭环。否则按 HOME 会回到原桌面，锁屏遮罩被绕过。
   推荐做法：先安装本应用 → 设为默认桌面 → 再 `pm disable-user` 停用原桌面。
2. 若孩子通过**遥控器"最近任务"键**切到别的应用，遮罩会被盖住。要做到绝对无法切走，需要悬浮窗权限（`SYSTEM_ALERT_WINDOW`）叠加覆盖层，或使用设备所有者/单应用模式（COSU），本版本未实现。
3. 孩子仍然可以在系统设置里「强行停止」或「卸载」本应用 —— 系统级防护需要设备管理员，属于后续版本。
4. 白名单"不计时"依赖系统「使用情况访问权限」，未授权时按亮屏时间统一计时。
5. **小米电视（PatchWall 固件）的系统设置主页是"弹窗型"Activity**，第三方应用拉起后会被系统收回
   （logcat: `SettingsPopup: showSettingsPopup mRootView == null`）。因此桌面的「系统设置」会进入本应用的
   设置页，由它提供可用的分类入口：Wi-Fi、蓝牙、图像/声音、默认桌面都能正常拉起；
   「全部系统设置」在本机上可能失败并给出提示。

---

## 构建与安装

### 方式一：GitHub Actions（推荐，不需要本地 Android SDK）

推送代码后，Actions 里的 `Build OpenTV Launcher APK` 会自动编译，在 Run 详情底部下载 `OpenTVLauncher-Release-APK`。

### 方式二：本地构建

需要 JDK 17 + Android SDK（platform 34、build-tools 34）。

```bash
./gradlew :app:assembleRelease
```

### 装到电视（不依赖 adb 二进制）

```bash
pip install -r deploy/requirements.txt
python deploy/deploy.py --host 192.168.1.50 --probe
python deploy/deploy.py --host 192.168.1.50 --install app-release.apk --set-home
python deploy/deploy.py --host 192.168.1.50 --list-stock
python deploy/deploy.py --host 192.168.1.50 --disable-stock com.mitv.tvhome
```

恢复原桌面：

```bash
python deploy/deploy.py --host 192.168.1.50 --restore-stock com.mitv.tvhome
```

> 电视需开启「开发者选项 → USB 调试」，并与电脑处于同一 Wi-Fi。首次连接时电视上会弹「允许 USB 调试」，记得允许。

### 升级说明

- 从 1.0.x / 1.1.1 升级到 1.1.2 时，本地数据库结构有调整（删掉了两个从未使用的字段），
  升级会**重建分类表**：分类回到默认的「常用应用」，隐藏/白名单标记需要重新设置一次，
  已安装应用会在下次启动时自动重新扫描。

### 兼容性

- 支持 **Android 6.0（API 23）及以上**，小米电视 4A 等停留在 Android 6.0 的国产机型可直接安装。
- 最低版本已按 API 23 复查：`View.setForeground` / `getSystemService(Class)` 恰好是 API 23；前台服务类型、通知渠道、`ACTIVITY_RESUMED` 等均有版本判断；PIN 哈希在 API < 26 自动退回 `PBKDF2WithHmacSHA1`。
- Android 6.0 上通知无需运行时授权；Android 13+ 首次启动会弹一次通知权限（用于前台服务提示）。

### 小米电视 4A 特别注意

- 原桌面包名通常是 `com.mitv.tvhome`（PatchWall）或 `com.mitv.tvhome.atv`，先用 `--list-stock` 确认。
- **先装新桌面再停用原桌面**，否则按 Home 可能落到工厂测试应用。
- 部分小米固件的默认输入法指向未安装的百度输入法，导致**软键盘完全无法弹出**。
  因此本版本的 PIN、分类名全部改成遥控器可选操作，不依赖 IME。
- 如需临时修输入法：`adb shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`

---

## 工程结构

```
app/src/main/
├── AndroidManifest.xml               # 无 INTERNET 权限
├── java/com/openlauncher/tv/
│   ├── app/                          # Application（数据库 / 仓库单例）
│   ├── data/local/                   # Room：分类、应用项、DAO
│   ├── data/parental/                # 防沉迷状态机（PIN、每日额度、跨天重置）
│   ├── data/repository/              # 应用扫描与同步
│   ├── receiver/                     # 安装/卸载/开机广播
│   ├── service/                      # 计时前台服务 + 前台应用识别
│   ├── ui/common/PinPad.kt           # 遥控器九宫格（不依赖输入法）
│   ├── ui/home/                      # 桌面、卡片、焦点反馈
│   ├── ui/parental/                  # 家长控制（PIN 门禁）
│   └── ui/settings/                  # 系统设置入口、分类与应用管理
└── res/
deploy/deploy.py                      # 纯 Python ADB 部署脚本
tools/verify_static.py                # 无 SDK 环境下的静态自检
docs/                                 # 需求 / 审查 / 对比等设计文档
```

## 自检

没有本地 Android SDK 时，可以先跑静态检查（资源引用、ViewBinding id、括号平衡、import 是否存在）：

```bash
python tools/verify_static.py
```

## 协议

MIT。使用到的 AndroidX / Leanback 组件遵循 Apache-2.0。
本项目仅供自用与学习，请自行承担刷机与改动系统桌面的风险。
