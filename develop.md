# OpenTV Launcher 开发与编制记录 (develop.md)

本项目记录了专为 Android TV / 电视机顶盒设计的纯离线、开源启动器（OpenTV Launcher）的完整开发过程。

---

## 阶段一：架构设计与工程脚手架搭建

### 1. 技术栈选型
- **构建系统**: Gradle 8.2 + Android Gradle Plugin 8.2.2 + Kotlin 1.9.22
- **UI 框架**: AndroidX Leanback 1.0.0 + Material Components + ViewBinding
- **持久化层**: Room 2.6.1 + Jetpack DataStore Preferences
- **协程调度**: Kotlin Coroutines (1.7.3) + Flow 响应式数据流
- **零网络安全策略**: 移除了所有网络权限声明（零 `android.permission.INTERNET`），不包含任何 HTTP/网络库依赖。

### 2. 核心模块与职责划分
1. **`app`**: Application 初始化与全局上下文服务。
2. **`data.local`**:
   - `CategoryEntity`：分类定义（系统默认/自定义分类、显示权重、儿童可见性）。
   - `AppItemEntity`：应用属性映射（包名、Activity、所属分类、自定义标题、白名单标记、隐藏状态）。
   - `AppDatabase`：Room 数据库实例与线程池调度。
3. **`receiver`**:
   - `PackageChangeReceiver`：监听应用安装、替换、卸载广播，实现桌面应用列表自动热刷新。
4. **`service`**:
   - `TimeTrackingService`：前台/后台运行的防沉迷观看时长监控服务，维持单次/每日倒计时与锁屏触发。
5. **`ui.home`**:
   - `MainActivity` & `HomeFragment`：基于 Leanback 构建 TV 桌面、横向卡片行（`ArrayObjectAdapter` + `ListRow`）、焦点平滑缩放与遥控器交互。
6. **`ui.settings`**:
   - `SettingsActivity`：设置总控抽屉，细分为**系统设置**直达与**启动器自身设置**。
   - `CategoryManagerActivity`：分类管理与隐藏应用恢复。
7. **`ui.parental`**:
   - `ParentalSettingsActivity`：家长控制中心（PIN 码设置、单次/每日观看时长限制、白名单 App 管理）。
8. **`ui.lock`**:
   - `ScreenLockActivity`：防沉迷超时全屏锁屏阻断界面，拦截返回/菜单键，大号 D-Pad 数字密码键盘输入解锁。
9. **`.github/workflows`**:
   - `build-apk.yml`：配置 GitHub Actions 自动化编译工作流，推送代码自动构建 Release APK 并发布 Artifact。

---

## 阶段二：实施日志（Git Commits 追踪）

- `47e6204`: 初始项目架构搭建、纯离线 TV Launcher、Leanback 响应式横向卡片行、Room 数据库、DataStore、防沉迷锁屏与 GitHub Actions 配置。
- `527107d`: 补齐官方 Gradle 8.2 wrapper 二进制（`gradle-wrapper.jar`），并为 release 配置 debug 签名以便即开即用。
- `c528f9e`: 修复 `settings.gradle.kts` 中的 Kotlin DSL 语法规范（`include(":app")`）。
- `2f9dc4a`: 优化 Gradle 8.2 API 兼容性（`layout.buildDirectory`）、添加 Lint 防御配置以及 CI 构建诊断报告与日志产物上传。

