# 开发记录

## 技术栈

- Kotlin 1.9.22 + AGP 8.2.2 + Gradle 8.2，compileSdk/targetSdk 34，minSdk 23（Android 6.0，兼容小米电视 4A）
- AndroidX AppCompat + RecyclerView（桌面货架自绘）、Room + KSP（分类/应用数据）
- 网络：只用系统 `HttpURLConnection` 手写请求，不引第三方网络库（每日壁纸已下线，现在只对时用）
- release 沿用仓库内固定签名（`keystore/opentv.p12`），方便 CI 直出可安装包

## 版本 1.7.0 左上角换成联网监视器 + 电视设置秒开

这一版干了两件用户能直接看见的事：把左上那块「每日壁纸画框」换成实时联网监视器，
以及修掉「更多 → 电视设置」点了要十几秒才出画面的老毛病。

### 一、监视器：这台电视上到底能读到什么

目标很硬：Android 6.0.1 普通应用，没有 root，要按进程看实时速率。四条路全部实测过：

| 数据源 | 结果 | 结论 |
| --- | --- | --- |
| `/proc/net/tcp`、`tcp6` | 可读，含 `uid`、远端地址、`inode`、状态（`st=01` 即 ESTABLISHED） | ✅ 拿连接和归属进程 |
| `/proc/uid_stat/<uid>/tcp_rcv`、`tcp_snd` | 可读**任意 uid** 的累计字节 | ✅ 拿速率（差分） |
| `/proc/net/xt_qtaguid/stats` | 应用侧只吐出自己那 2 行（`qtaguid lines=2 uids=1`） | ❌ 沙箱限制 |
| `TrafficStats.getUidRxBytes(uid)` | 其它 uid 恒返回 0（Android 6 起按 uid 隔离） | ❌ |
| `NetworkStatsManager` | 需要 `USAGE_ACCESS_SETTINGS`，本机没有这个页面，无法授权 | ❌ |

于是 `NetTrafficMonitor` 的做法是：读两条 tcp 表拿「谁连着谁」，读 `/proc/uid_stat`
拿每个 uid 的累计收发，两次采样差分得到速率，再按 uid 的连接数把速率摊到具体连接上。

- 采样在 `HandlerThread("net-monitor")` 上每秒一次，结果回主线程；
- 文件名带 `uid_stat` 不存在时（部分新系统）退回 qtaguid，仍失败则只显示连接、速率显示 `—`；
- IPv6 地址里 `::ffff:a.b.c.d` 一律还原成点分十进制 —— 电视上隔三米看一堆冒号，根本认不出是哪台服务器。

### 二、界面：整块 Canvas 自绘，两个视图一个按钮

`NetMonitorView` 不用任何子 View，直接画：

- 头部：标题 + LIVE 指示灯 + 走势 sparkline + 进程数/连接数 + 右上角**「切换」按钮**；
- 视图 A「连接日志」：每 2 秒推一行，最多 7 行，滚动动画用 `ValueAnimator` 平移半行；
- 视图 B「进程排行」：按本秒速率排序，条形长度取 `sqrt(rate / max)`（不取线性，否则大流量一柱擎天，
  小流量全是 0 长度）；进程不满一屏补空槽，避免半屏留白。

遥控器上**按确定键等于按「切换」按钮**（`onKeyDown` 拦 CENTER/ENTER），因为电视上敲 1/2 这种需求不现实。
切换时有 toast 提示当前视图。

行合并的坑：长连接下载时每一轮采样都是同一个进程，直接 push 会把一屏刷成同一个包名。
现在同 `label + pkg` 的行**原地更新数值**、保留第一次出现的时间，读起来是「某进程从 xx:xx:xx 起一直在联网」。

另外 `formatRate(0)` 返回 `—` 而不是 `0 B/s`，且速率为 0 时不画条形 —— 一屏 0 B/s 看着像坏了。

### 三、时间戳必须走 TimeSync

列表里的行时间一开始用 `SimpleDateFormat().format(Date())`，结果和左上角大钟差 37 分钟 ——
因为系统时钟本身就慢（见 1.6.4）。凡是界面显示时间，一律 `TimeSync.now()`，这个坑这版又踩了一次。

### 四、壁纸功能整套下线

删除：`WallpaperRepository.kt`、`WallpaperSettingsDialog.kt`、`dialog_wallpaper.xml`，
以及 `bg_frame`、`bg_frame_scrim`、`bg_thumb`、`ic_kind_*` 等 8 个 drawable（删前 grep 确认零引用）。
`BackdropView` 从「Bitmap 放大糊化」改成**纯渐变**（竖向薄荷底 + 斜向微光 + 左上径向光斑），零 IO、零大图内存。

### 五、机器人让开监视器：给它们一块舞台

机器人是一层全屏自绘、盖在所有控件之上，飘到监视器上就把速率数字糊住了。
第一版想用「禁区矩形 + 找点重试」，但没用 —— 因为动作编排（打招呼站屏幕中间、双人舞绕屏幕中心转）
写死了绝对位置，`pickTarget` 只管自由巡航。

最终办法是把**坐标系本身**缩进一块空地（`DesktopPetView.stageRect`）：

- 逻辑尺寸用舞台的宽高，动作编排全是百分比，自动跟着缩；
- `onDraw` 里整体 `canvas.translate(舞台左上角)`；
- 舞台只有屏幕四分之一大，按舞台算缩放会把机器人缩成小不点，所以加了 `fixedScale` 由外部指定倍率。

舞台取监视器右下、内存卡之下、「更多」磁贴之上的那块空地（屏幕比例 0.672–0.912 × 0.360–0.765）。

> 踩坑：新属性一开始叫 `stage`，和动作状态机里的 `private var stage: Int`（动作内部阶段标记）撞名，
> 编译器报 6 处 `Overload resolution ambiguity`，改名 `stageRect` 才对。

### 六、「电视设置」为什么慢：根因不在我们这边

`com.xiaomi.mitv.settings/.entry.MainActivity`（设置主页）自己有问题：

```
MainActivity.onCreate → SettingsPopup → MainView → SettingsUtil.isEthDeviceUp
  反复读 /sys/class/net/eth1/carrier（文件不存在）
ConfigurationManager.getService() 在主线程每 500ms 轮询一次，连续 getService() 0..5, service = null
07:44:08 启动，07:44:11 还在轮询
```

外部没法优化别人家的主线程。所以在 `AllAppsActivity.launchApp()` 里拦下 `com.xiaomi.mitv.settings`
这个包，直接跳本应用的 `SettingsActivity`（秒开，且 Wi-Fi / 蓝牙 / 应用 / 显示 / 声音 / 桌面 /
账号 / 关于 / 系统主页 都能分类直达，每个入口都是细分 Activity，实测 420–560ms）。

设置页另外补了三个按钮：账号与安全（`.entry.SecurityActivity`）、关于本机（`.entry.AboutActivity`）、
系统设置主页（`.entry.MainActivity`，点之前先 toast「可能要十几秒」，watchdog 放宽到 14 秒）。

> 注意：小米账号的 Activity 类名在 `com.xiaomi.mitv.account.*`，**包名**才是 `com.xiaomi.account`。
> 写成 `"$pkg.mitv.account.*"` 会拼出不存在的类，显式组件直接抛异常。

### 七、其它小修

- 分辨率/padding 变化时机器人位置重算（`onSizeChanged` 只置 `initialized = false`，真正初始化放 `onDraw`）；
- 监视器右上角的「切换」按钮用 `btnRect` 记录位置，纯视觉，不做点击热区 —— 电视上没人拿手指戳；
- 清掉调试期日志（`perUid=…`、`qtaguid lines=…`、`监视器禁区`），只留真正的告警。


## 版本 1.6.4 时钟校对 + 开机时长

### 一、左上时间为什么不对

不是格式化写错，也不是时区错，是**电视自己的系统时钟就慢**：

```
电视 date  -> Wed Oct 21 16:52:51 CST 2026   (epoch 1792572771)
电脑 date  -> Wed Oct 21 17:30:03  +0800     (epoch 1792575003)
差 37 分 12 秒
```

- `getprop persist.sys.timezone` = `Asia/Shanghai`，时区正确；
- `settings get global auto_time` = 1、`auto_time_zone` = 1，「自动确定时间」是开着的；
- 但时间就是慢了 37 分钟 —— 说明这台电视的 NTP 对时其实没成功（大概率连不上小米的对时服务器），
  开关只是"允许自动"，不代表同步成功。

界面时钟原来用 `TextClock`，它直接显示系统时间，所以跟着一起错。

### 二、处理：应用内网络校时（不改系统时间）

应用没有 `SET_TIME` 权限（signature 级），改不了系统时钟，所以改成**自己算偏移**：

| 项 | 实现 |
|---|---|
| 文件 | `TimeSync.kt` |
| 取时 | HEAD 请求 `https://www.bing.com`（依次退到 `cn.bing.com`、`www.baidu.com`），读响应头 `Date` |
| 精度 | `Date` 只有秒级，取「请求前后本地时间中点」去对，抵消一半往返延迟 |
| 偏移 | `offset = 服务器时间 - 系统时间`，存 `SharedPreferences`，6 小时内不重复请求 |
| 使用 | 界面一律用 `TimeSync.now()` = 系统时间 + offset；校时失败 offset=0，退化成系统时间 |
| 时机 | 启动时读缓存（界面立刻正确）→ 后台异步 sync → 成功后刷新一次 |

实测 logcat：`TimeSync: synced via https://www.bing.com, offset=2227s`（37 分 07 秒，与手工比对一致）。
重启应用后走缓存，不再发请求，时间仍然正确。

**注意**：这只修了桌面显示。系统本身还是慢 37 分钟，别的应用看到的时间依旧不准；
要彻底修得去「设置 → 系统 → 日期和时间」手动校准一次。

### 三、新增：日期旁边的开机时长

| 项 | 实现 |
|---|---|
| 位置 | 日期胶囊右边，8dp 间距，同一行的 `LinearLayout` |
| 样式 | `bg_uptime_pill`：55% 白 + 淡边框，比日期胶囊淡一档，读作次要信息 |
| 取数 | `SystemClock.elapsedRealtime()`（含待机），电视常年不断电，按「从开机算起」比「本次使用时长」直观 |
| 格式 | `<1 小时` → `32分钟`；`<1 天` → `1小时09分`；`≥1 天` → `2天3小时` |
| 刷新 | 跟时钟共用一个 1 秒 ticker，文本没变就不 setText |

### 四、时钟刷新改造

- `TextClock` → 普通 `TextView`：因为要叠加校时偏移，`TextClock` 只能跟系统时钟走。
- `refreshStatus()` 拆成 `refreshMemory()` + `updateClock()`：
  原来 5 秒一次，现在 ticker 改成 **1 秒**（分钟跳变不延迟），内存查询挪到每 5 个 tick 才做一次。

### 五、验证（MiTV4-ANSM0）

| 项 | 结果 |
|---|---|
| 版本 | `versionCode=26 / versionName=1.6.4` |
| 时钟 | 电视系统 16:59 时，桌面显示 17:35/17:36/17:38，与本机逐分钟一致 |
| 跳分 | 等 62 秒后从 17:36 跳到 17:38，无延迟 |
| 重启 | 冷启动走缓存 offset，不发请求也正确 |
| 开机时长 | 1小时02分 → 1小时03分 → 1小时09分，走字正常 |
| 日志 | `TimeSync: synced via https://www.bing.com, offset=2227s`，无异常 |

## 版本 1.6.3 双机器人 + 动作环状菜单

1.6.2 的桌面只有一只机器人自己飞。这一版让它有了一个伴，并且把「演什么」交给用户：
右下角「更多」上面多了一个机器人按钮，点开是一个环状动作菜单。

### 一、桌面机器人：从一只变成两只

| 项 | 实现 |
|---|---|
| 文件 | `DesktopPetView.kt` 整体重写：内部拆成 `Robot` 对象 + 动作状态机 |
| 小蓝 | 原造型完全不动（青色 `#00F0FF`、单天线、胶囊眼、圆形核心） |
| 小玫 | 玫粉 `#FF5C9D`、侧马尾 + 发光发梢、睫毛 / 微笑 / 腮红、心形核心、悬浮裙摆、耳罩 + 耳环、暖白机身 |
| 复用 | 两只共用同一份绘制与物理代码，只是 `Skin` 配色和几个装饰图层不同，所以天然同风格 |
| 缩放 | 仍然是 `min(w,h)/720*0.85`，小玫额外 ×0.96，看起来比男生娇小一点 |

### 二、动作（默认自动轮播）

`PetAction` 枚举：AUTO / GREET / HIGHFIVE / DANCE / CHASE / GIFT / HEART / LEAN / QUARREL / HUG / FREE。

- **AUTO**：默认状态，按 `autoSequence` 一个个演过去，每个动作按 `durationMs` 自动切下一个，演完 HUG 回到 GREET。
- 在菜单里单独选一个动作：只演一次，结束后自动回到 AUTO。
- **FREE**：两只各自漂浮漫游，太近会互相让开。
- 遥控器按 OK 进应用时，两只一起眨眼歪头（`onUserAction`），不打断当前动作。

### 三、右下角机器人按钮 + 环状菜单

| 项 | 实现 |
|---|---|
| 按钮 | `btn_robot_cfg`，48dp 圆钮（`bg_icon_btn`），位置在「更多」磁贴正上方，`layout_marginBottom=10dp` |
| 图标 | `ic_robot_cfg.xml`：机器人脑袋 + 右下角齿轮，两个形状不重叠，单色也能认出来；默认绿图标，聚焦变白（`@color/icon_on_circle`） |
| 提示 | 聚焦时左侧浮出「机器人设置」气泡（复用 `showTip(placeLeftOf = true)`，按钮贴着屏幕右下角，气泡放左边才不掉出屏幕） |
| 菜单 | `RobotWheelDialog`（全屏透明窗）+ `RobotWheelView`（纯 Canvas 画） |
| 交互 | 选中项永远停在 12 点方向，按左右键整圈滚动、OK 确定、返回关闭；不依赖任何子 View，所以没有焦点搜索问题 |
| 动画 | 选中位置用浮点 `pos` 平滑追 `target`，条目按 `i - pos` 算角度，文字始终正立；打开时有 0.9→1 的缩放弹入 |

### 四、焦点链路（新的）

```
货架第一行 → （右）更多磁贴 → （上）机器人按钮 → （上）一键清理
                              ← （下）机器人按钮 ← （下）更多
```
- `btnClean.nextFocusDownId = btn_robot_cfg`
- `btn_home_more` 的 `nextFocusUpId = btn_robot_cfg`（原来是 btn_clean）
- 机器人按钮按左回货架（`restoreFocus()`），和右列其它按钮一致

### 五、性能

- 这块 View 是全屏的，60fps 重绘在小米 4A 上会把 GPU 顶满（logcat 里 `RenderInspector: QueueBuffer time out` 一秒一条）。
- 改成 `postInvalidateDelayed(22)` 限到 ~45fps，告警从每秒一条降到几乎没有，肉眼看不出差别。
- 发光一律用「三圈递减透明度」手绘，不用 `setShadowLayer`（老 GPU 上走软件路径）。
- 画笔 / RectF / Path 全部复用；粒子用固定池（死掉的槽位复用），上限 110 个。

### 六、踩到的坑

1. **`Path.quadraticTo` 是 API 30 才有的**，minSdk 23 编译不过，得用 `quadTo`。
2. **弹窗遮罩 0.59 的黑色压不住亮壁纸**，节点上的小字看不清；加到 0.89，并且在弹窗期间把宠物 View 的 `alpha` 设 0，
   否则两只机器人会从遮罩后面透出来，正好糊在菜单标题上。
3. **拥抱的手臂画成了"吊床"**：端点画到对方身后，会在外侧戳出一截白条；改成端点落在对方机身内侧、只露出两人中间那段才像搂住。
4. **小米电视的「安装确认」对话框会卡住 adb install**：`adb install` 一直不返回，`pm install` 也一样。
   屏幕上其实是 MIUI 的「您正在从其它设备安装应用」确认框，勾上「应用到全部」按确定之后，后续安装才能一路过。
5. **`dumpsys package` 有时返回旧版本号**：安装完成后立刻查可能是上一次的缓存结果，用 `ls -l /data/app/.../base.apk` 的文件大小对一下最稳。

### 七、真机验证（MiTV4-ANSM0 / Android 6.0.1）

| 项 | 结果 |
|---|---|
| 覆盖安装 | `versionCode=25 / versionName=1.6.3`，base.apk 2976392 字节 |
| 冷启动 | `am start -S -W` 632ms 起来，两只机器人在问候，无 crash |
| 机器人按钮 | 焦点从「更多」按上即到，圆钮变绿、左侧出气泡 |
| 环状菜单 | 11 个节点，左右键整圈滚动、选中项始终在 12 点、`1/11` 计数正确 |
| 选动作 | 选「送光花」→ 男生掌心凝花飞到她胸口，她变心形眼并说「好漂亮！」 |
| 返回键 | 关闭菜单并恢复机器人显示，焦点回到机器人按钮 |
| 演完 | 单次动作结束后自动回到「自动轮播」 |
| 性能 | logcat 掉帧告警基本消失，无 FATAL |

## 版本 1.6.2（1.6 线的正式基线，后续小改动都在 1.6.x 上做）

上一版 1.6.1 只把右上是三个入口改成小图标、去掉了存储卡片，前面几轮定下来的视觉和交互还没落地。
这一版把它们一次补齐，并把「换壁纸」从画框上的刷新按钮换成可配置的壁纸设置面板。

### 一、画框右上角：刷新按钮 → 齿轮（壁纸设置）

| 项 | 实现 |
|---|---|
| 控件 | `btn_wallpaper_settings`，画框内右上角 36dp 圆形按钮，`@drawable/bg_gear_btn`（默认白底绿齿轮，聚焦绿底白齿轮 + 3dp 白圈） |
| 提示气泡 | 聚焦时在按钮**左侧**浮出「壁纸设置」气泡；与右上三个图标的提示共用根布局上的一个 `tv_focus_tip`，位置由代码用「相对根布局」的坐标计算 |
| 去掉的东西 | 原来的 `bing` 徽标、`btn_wallpaper_refresh` 刷新圆钮全部删除，手动换图改到面板里的「立即换一张」 |

### 二、壁纸设置面板（`WallpaperSettingsDialog`）

`Dialog(Theme = DialogSheet)`：透明全屏窗口 + 布局里画的 `#8C0A1A16` 遮罩，卡片 520dp 居中、圆角 26dp。

| 分区 | 内容 |
|---|---|
| 图片种类 | 每日风景 / 世界风光 / 摄影 / 动漫 / 纯色 |
| 图片来源 | 第一行 Bing 中文·日本·美国·欧洲，第二行 safebooru 动漫 / 纯色 / 自定义 |
| 接口地址 | 只读展示当前源的真实接口（带绿点），右侧「编辑」可改成自定义图片直链 |
| 预览 | 4 张缩略图（当前正在用的那张打绿框），按确认立刻换成这一张 |
| 动作 | 立即换一张（随机换）、完成 |

- **种类和来源是联动的**：点种类跳到它对应的默认来源，点来源把种类高亮切过去，两个列表永远指同一件事，不会出现"选了动漫但来源还是 Bing"。
- 选择结果写进 `SharedPreferences("wallpaper")`，开机即用；重启后仍是上次选的源（实测切到 safebooru 后重启，壁纸仍是动漫图）。

### 三、多图源（`WallpaperRepository.Source`）

| 源 | 接口 | 说明 |
|---|---|---|
| Bing 中文 / 日本 / 美国 / 欧洲 | `HPImageArchive.aspx?...&n=N&mkt=zh-CN\|ja-JP\|en-US\|en-GB` | 缩略图把 `_1920x1080.jpg` 换成 `_400x240.jpg`（Bing 没有 `_480x270`，会 404） |
| safebooru 动漫 | `index.php?page=dapi&s=post&q=index&json=1&limit=60&tags=scenery` | 客户端按 `width/height ≥ 1.6` 筛横图，缩略图用返回的 `preview_url` |
| 纯色 | 本地 Canvas 画 1920×1080 绿色渐变 | 不联网，断网也能换 |
| 自定义 | 用户填的图片直链 | 填完自动切到这个源 |

**Picsum 被拿掉了**：它的图片会 302 到 `fastly.picsum.photos`，这台电视（Android 6.0.1）的 CA 库不认识那张证书，
实测报 `SSLHandshakeException: Trust anchor for certification path not found`，缩略图和原图都下不来。
换成同一套 Bing 接口的「欧洲」分区，稳定且零成本。

双缓冲逻辑没变：`current.jpg` 秒开上屏，`pending.jpg` 后台静默下载，下次启动/回到桌面才切换。

### 四、界面按方案 1（薄荷晨光）重排

| 区域 | 变化 |
|---|---|
| 背景 | `BackdropView` 从"压暗"改成"洗白 + 淡绿光晕"：白色渐变 0x4D→0x8C + 左上角径向绿光，兜底色改成薄荷浅绿 |
| 时间 | 从画框内挪到画框上方：40sp 细体时间 + 白底深绿的日期胶囊「2026年10月21日 · 星期三」 |
| 画框 | 位置下移，宽度收到 610dp、高度 262dp，成为独立画框 |
| 右上三图标 | 改成 56dp 圆钮，默认半透明白、聚焦实心绿；聚焦时下方浮出名称气泡 |
| 内存卡 | 半透明白卡 + 深绿文字，环形进度改绿，一键清理改成绿底白字胶囊，卡片与上方三个圆钮右边对齐 |
| 右下 | 去掉「设置」磁贴（设置入口收进「更多」），只留「更多」，与左侧「常用应用」同一水平线 |
| 货架标题 / 磁贴文字 | 加深色投影，保证洗白后的背景上仍然清晰 |

### 五、焦点链路（电视上最容易出问题的地方）

- 货架上按「上」→ 齿轮：`TileAdapter` 新增 `upNeighborId`，第一行所有磁贴都指向齿轮。
  光设 `nextFocusUpId` 还不够——RecyclerView 的焦点搜索第一次会失败（表现为按两下才上去），
  所以在磁贴上额外挂了 `setOnKeyListener`，`DPAD_UP` 直接 `findViewById(...).requestFocus()`，按一下就上去。
- 齿轮按「下」→ 回货架、按「右」→ 网络连接：用 `OnKeyListener` 显式接。
- 面板内部：动态生成的 chip / 缩略图拿不到稳定的 id，改成建完统一 `wireFocus()` 手工串行——
  种类行 → 来源行 A → 来源行 B →（预览图 / 编辑）→ 立即换一张 → 完成，每一格都写死 `nextFocusXxxId`。
- 焦点可见性：chip 和缩略图都补了 `state_selected + state_focused` 的组合样式（选中且聚焦 = 绿底白圈），
  否则"已选中"的格子拿到焦点时看不出变化。

### 六、踩到的坑

1. `Source` 和 `Kind` 两个枚举互相引用（`Source(label, kind)` / `Kind(label, defaultSource)`），
   JVM 类初始化顺序会让后初始化的那个枚举字段读到 `null`，真机上点一下就 `NullPointerException` 崩掉。
   改成两边都用 `get()` 计算属性，延迟求值。
2. `am force-stop` 对默认桌面无效（系统立刻把它拉起来），测试时必须用 `am start -S -W` 才能真正换上新包。
3. 弹窗卡片内容超过 540dp 屏高会被裁掉，压缩了内边距、chip 高度和缩略图尺寸后才稳。

### 七、真机验证结果（小米电视 4A / Android 6.0.1 / 192.168.31.142）

| 场景 | 结果 |
|---|---|
| 齿轮聚焦 | 变绿 + 左侧「壁纸设置」气泡 ✓ |
| 打开面板 | 520dp 卡片居中，初始焦点在第一格「每日风景」✓ |
| 切换种类/来源 | 接口地址与 4 张预览图同步刷新 ✓ |
| 点预览图 | 下载原图 → 立即换壁纸 → 自动关闭面板 ✓ |
| 立即换一张 | 实测下载 1242KB 并上屏 ✓ |
| 编辑接口地址 | 弹出自定义地址输入框 + 系统键盘 ✓ |
| 返回 / 完成 | 关闭面板，焦点回到齿轮 ✓ |
| 重启后 | 保持上次选的源（safebooru 动漫）✓ |
| 崩溃 | 无（旧版枚举 NPE 已修） |

产物：`dist/OpenTVLauncher-1.6.2-release.apk`（versionCode 24 / versionName 1.6.2），已覆盖安装并跑通。

## 版本 1.5.0（以 1.3.1 为基线重做）

1.4.x 的桌面在真机上问题较多，因此把 `main` 回退到 1.3.1（`ffc4247`），在其之上重新做两件事：

### 一、桌面改为 Apple TV 风格

| 元素 | 实现 |
|---|---|
| 背景 | 纯黑 + `AmbientGlowView` 径向氛围光，颜色随焦点应用图标主色渐变（`ArgbEvaluator`，420ms） |
| 主色 | `AppPalette` 把图标画到 36×36 位图后按饱和度加权取平均色，HSV 归一到 `s∈[0.45,0.72] v∈[0.52,0.80]`，失败退回稳定色板 |
| 顶部 | 细体 `TextClock` + 日期；下面是聚焦应用的大字标题（40sp）与分类名，切换时淡入上浮 |
| 磁贴 | `AppTileView`：圆角品牌色渐变方块 + 图标 + 下方应用名；聚焦放大 1.08、`translationZ` 抬升、白色 2dp 描边 |
| 货架 | `ShelfRowView` = 标题 + 横向 RecyclerView（RecyclerView 自身不参与焦点，焦点只在磁贴间流动） |
| 自适应 | 磁贴边长按 `screenWidthDp` 三档：<960dp→84、≥960dp→96、≥1280dp→112，圆角/图标随之等比缩放 |
| 焦点记忆 | 数据刷新后按 `app:<id>` 找回原来那块磁贴；从应用返回桌面时若焦点丢失则自动复位 |
| 交互 | 长按磁贴 → 移动到其他分类 / 隐藏；遥控器 MENU 键 → 设置；「设置」磁贴固定在第一行末尾 |

技术选择：去掉 `androidx.leanback`（`RowsSupportFragment` 的默认样式与焦点模型不好改），
改用 AppCompat + RecyclerView 自绘，APK 更小，样式完全可控。

### 二、移除儿童防沉迷 / PIN 锁屏

删除 `data/parental/ParentalRepository.kt`、`service/TimeTrackingService.kt`、`service/ForegroundApp.kt`、
`ui/lock/LockScreen*.kt`、`ui/parental/ParentalSettingsActivity.kt`、`ui/common/PinPad.kt`、
`activity_lock.xml`、`activity_parental_settings.xml`、`pin_key.xml`，以及 Manifest 里的
`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `POST_NOTIFICATIONS` / `PACKAGE_USAGE_STATS` 权限。
`AppItemEntity.isWhitelistForKids` 一并移除，数据库 2 → 3，`MIGRATION_2_3` 重建 `app_items` 表（保留分类与隐藏标记）。


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
- 桌面主题切换（深/浅色、列数）；壁纸侧已在 1.6.2 支持多来源与自定义地址
- 壁纸本地缓存目录清理策略（目前只保留 `current.jpg` / `pending.jpg` 两个文件，暂不需要）
- 单元测试（应用扫描与分类迁移逻辑适合先补）
