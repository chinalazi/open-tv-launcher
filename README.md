# OpenTV Launcher

专为 Android TV / 电视盒子设计的**纯离线**、开源电视启动器，界面为 **Apple TV 风格**。
主要面向低内存机型（如小米电视 4A）自用：不联网、不申请网络权限、不引入任何网络库。

---

## 核心特性

**桌面（Apple TV 风格）**

- 纯黑背景 + **氛围光**：焦点落在哪块磁贴上，整块背景就泛出该应用图标的主色
- **顶部大字**：聚焦应用以大号标题显示，下方带分类名；左上角是细体时钟 + 日期
- **圆角品牌色磁贴**：每块磁贴用应用主色调做渐变，聚焦时放大 1.08 倍、抬升、描白边
- **一行一个分类的货架**：可用遥控器上下切换分类、左右浏览磁贴，长度超出时自动横向滚动
- 长按磁贴（OK 键长按）：**移动到其他分类** / **在桌面隐藏**
- 应用安装/卸载/升级后自动刷新，分类与隐藏设置不会被覆盖；数据刷新后焦点自动回到原来那块磁贴

**设置（全部可用遥控器操作，不依赖软键盘）**

- 系统设置直达：系统设置、Wi-Fi、蓝牙、应用管理、显示、声音、默认桌面
- 分类管理：新增（预设名一键创建）、改名、上下调序、删除（应用自动迁回第一个分类）
- 隐藏应用清单 + 一键恢复显示

> 1.5.0 起已**整体移除儿童防沉迷时长管理与 PIN 锁屏**（`ParentalRepository` / 计时前台服务 / 锁屏 Activity / PIN 键盘全部删除），
> 相关权限（`FOREGROUND_SERVICE`、`POST_NOTIFICATIONS`、`PACKAGE_USAGE_STATS`）也一并去掉，APK 更小、更省内存。

### 已知局限

**小米电视（PatchWall 固件）的系统设置主页是"弹窗型"Activity**，第三方应用拉起后会被系统收回
（logcat: `SettingsPopup: showSettingsPopup mRootView == null`）。因此桌面的「设置」进入本应用的
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

- 从 1.3.1 / 1.4.x 升级到 1.5.0：数据库版本 2 → 3，会删除已废弃的儿童白名单字段，
  **分类与隐藏设置保留**；若迁移异常则自动重建空表（应用会在下次启动时重新扫描，分类回到「常用应用」）。
- 本版本 APK 仍使用仓库内的固定签名（`keystore/opentv.p12`），可直接覆盖安装。

### 兼容性

- 支持 **Android 6.0（API 23）及以上**，小米电视 4A 等停留在 Android 6.0 的国产机型可直接安装。
- 磁贴尺寸按屏幕宽度自适应（<960dp / 960dp / ≥1280dp 三档），1080p 与 4K 电视观感一致。

### 小米电视 4A 特别注意

- 原桌面包名通常是 `com.mitv.tvhome`（PatchWall）或 `com.mitv.tvhome.atv`，先用 `--list-stock` 确认。
- **先装新桌面再停用原桌面**，否则按 Home 可能落到工厂测试应用。
- 部分小米固件的默认输入法指向未安装的百度输入法，导致**软键盘完全无法弹出**，
  因此分类名全部改成遥控器可选操作，不依赖 IME。

---

## 工程结构

```
app/src/main/
├── AndroidManifest.xml               # 无 INTERNET 权限
├── java/com/openlauncher/tv/
│   ├── app/                          # Application（数据库 / 仓库单例）
│   ├── data/local/                   # Room：分类、应用项、DAO、迁移
│   ├── data/repository/              # 应用扫描与同步
│   ├── receiver/                     # 安装/卸载/开机广播
│   ├── ui/home/                      # 桌面：货架、磁贴、氛围光、主色提取
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

MIT。使用到的 AndroidX 组件遵循 Apache-2.0。
本项目仅供自用与学习，请自行承担刷机与改动系统桌面的风险。
