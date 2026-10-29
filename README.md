# EasyCast 电视投屏接收端

给 Sony Android 8.0 电视（2019 款）用的 DLNA 投屏接收 App。在手机上打开抖音、B 站等 App，点「投屏」，选择电视上的设备，电视就会播放对应的直播或视频。

- 手机端不用开发：Android 和 iPhone 的抖音、B 站都自带 DLNA 投屏按钮。
- 电视端：后台常驻 + 开机自启，ExoPlayer 低延迟播放 FLV / HLS 直播流。
- 竖屏直播会按原始比例居中显示，左右留黑边，画面不变形。

## 功能

| 功能 | 说明 |
| --- | --- |
| DLNA 发现 | 组播 + 子网广播通知，回应搜索时发两次，定期重发 IGMP 加入报告 |
| 后台常驻 | 前台服务 `DlnaService`，界面关闭后设备仍在投屏列表里 |
| 开机自启 | `BootReceiver`；**安装后必须在电视上手动打开一次**才会生效 |
| 遥控器 | 确认键 / 播放暂停键：暂停和继续；返回键：停止播放 |
| 直播暂停 | 暂停超过 5 秒再继续，会重新拉流回到最新画面 |
| 主界面按钮 | 等待投屏时主界面底部有一排按钮，遥控器**左右键**切换，**确认键**执行；开始播放后按钮自动隐藏，停止后恢复 |
| 修改设备名 | 按钮「修改设备名称」，输入后按键盘确认键保存，立即生效 |
| Wi-Fi 重连 | 按钮「重新连接 Wi-Fi」，断开后重新连接，Wi-Fi 被关闭时会先打开 |
| 扫描并连接 Wi-Fi | 按钮「扫描连接 Wi-Fi」，列出附近热点（按信号强弱），已连过的网络标「已保存」，选中后可直接连接（系统不会向应用提供旧密码，所以无法显示出来，密码变了可选「输入新密码」）；其余网络选中后输入密码连接。首次使用要授予位置权限（Android 读取扫描结果的要求） |
| 系统 Wi-Fi 设置 | 按钮「系统 Wi-Fi 设置」，跳到电视自带的 Wi-Fi 设置页 |
| 界面日志 | 主界面中间是日志区，实时显示搜索、投屏地址、播放状态、错误和 Wi-Fi 操作（错误标红、警告标黄），不用再接 adb 看日志；底部「隐藏日志」可开关，开始播放后自动隐藏。隐藏日志后，这块区域改为显示最近 5 条投屏地址（带时间，保存在电视本地，重启后还在）；用遥控器**上下键**选中一条，**确认键**即可重新播放（直播地址里的令牌会过期，过期的地址会播放失败，需要回手机重新投屏） |
| 断网自恢复 | 网络断开后重新连上（含 IP 不变）会自动重建 DLNA 服务并重新广播；断网时界面显示提示 |

## 目录结构

```
easycast/
├─ app/src/main/java/com/easycast/tv/
│  ├─ DlnaRenderer.kt    UPnP/DLNA 核心：SSDP 发现、设备描述、SOAP 控制
│  ├─ DlnaService.kt     前台服务：持有播放器和 DlnaRenderer
│  ├─ MainActivity.kt    全屏播放界面、遥控器按键、改名对话框
│  └─ BootReceiver.kt    开机自启
├─ app/src/main/res/     图标（banner.png、ic_launcher.png）
├─ build.gradle.kts / settings.gradle.kts / gradle.properties
└─ local.properties      SDK 路径（本机文件，不要提交）
```

---

## 一、搭建开发环境（命令行，不装 Android Studio）

以下路径是本机实际用的路径，换位置时对应修改。

### 1. 下载并安装 JDK 17（Temurin）

Gradle 8.x 和 Android Gradle 插件 8.1 不支持过新的 JDK（比如 JDK 25，会报 `25.0.5` 之类的错误），**必须用 JDK 17**，它可以和其他版本的 JDK 并存。

1. 打开 https://adoptium.net/temurin/releases/?version=17 ，选 Windows x64 的 `.msi` 安装包。
2. 安装后默认位置：`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`（以实际版本号为准）。

### 2. 下载并放置命令行工具

1. 打开 https://developer.android.com/studio#command-line-tools-only ，下载 Windows 版 `commandlinetools-win-*_latest.zip`。
2. 解压后里面有一个 `cmdline-tools` 文件夹，把它**整理成下面这个结构**，`latest` 这一层不能少，否则 `sdkmanager` 会报错：

```
D:\Android\cmdline-tools\latest\bin\sdkmanager.bat
```

### 3. 安装 SDK 组件

在 PowerShell 里，先切到 JDK 17，再安装（`sdkmanager` 同样需要 JDK 17）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:ANDROID_HOME = "D:\Android"
java -version
```

`java -version` 应显示 17。然后接受协议并安装：

```powershell
D:\Android\cmdline-tools\latest\bin\sdkmanager.bat --licenses
```

```powershell
D:\Android\cmdline-tools\latest\bin\sdkmanager.bat "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

下载慢的话需要给 PowerShell 配代理。装好后 `adb.exe` 在 `D:\Android\platform-tools\`。

### 4. 安装 Gradle 并生成 wrapper

工程里如果没有 `gradlew.bat`，需要先装一次 Gradle 来生成：

1. 打开 https://gradle.org/releases/ ，下载 **binary-only** 的 zip（本机用的是 8.14.6），解压到 `D:\gradle-8.14.6`。
2. 在工程目录里生成 wrapper（还是在已切到 JDK 17 的窗口里）：

```powershell
cd easycast
D:\gradle-8.14.6\bin\gradle.bat wrapper --gradle-version 8.14.6
```

之后只用 `.\gradlew.bat`，不再需要单独装的 Gradle。

### 5. 配置 SDK 路径

在工程根目录创建 `local.properties`，内容就一行（这是**文件内容**，不是命令）：

```
sdk.dir=D\:\\Android
```

---

## 二、编译 APK

在已切到 JDK 17 的 PowerShell 窗口里：

```powershell
cd easycast
.\gradlew.bat assembleDebug
```

成功后 APK 在：

```
app\build\outputs\apk\debug\app-debug.apk
```

第一次编译要下载依赖，约几分钟，之后几秒。

---

## 三、用 adb 安装到 Sony 电视

### 1. 打开电视的调试功能

1. **设置 → 设备偏好设置 → 关于 → 版本号**，连按遥控器确认键 7 次，提示已进入开发者模式。
2. 回到 **设备偏好设置 → 开发者选项**，打开 **USB 调试**。
3. 在 **设置 → 网络** 里查看电视的 IP，比如 `192.168.5.57`。电脑和电视必须在能互相访问的网络里。

### 2. 连接并安装

```powershell
D:\Android\platform-tools\adb.exe connect 192.168.5.57:5555
```

电视上会弹出"允许 USB 调试"，勾选"始终允许"并确认。然后安装：

```powershell
cd easycast
D:\Android\platform-tools\adb.exe install -r .\app\build\outputs\apk\debug\app-debug.apk
```
看到 `Success` 即完成。IP 换成电视的实际地址。

> **只能用 adb 安装**：实测在这台 Sony 电视（Android 8.0）上，把 APK 拷到 U 盘再用电视的文件管理器安装会报错，装不上，请一律用 adb 安装。debug 版和 release 版的签名不同，互相替换前要先 `adb uninstall com.easycast.tv`。

### 3. 首次使用

1. 在电视的应用列表里打开 **简易投屏**，**必须手动打开一次**，之后才会开机自启。
2. 按返回键退出界面，设备仍在后台，投屏列表里能搜到。
3. 手机连**同一个网络**，在抖音直播间或 B 站视频里点「投屏」，选择本设备。
4. 电视设置里如果有「自启动管理」或「后台限制」，把 EasyCast 加入白名单，避免被系统杀掉。

### 4. adb 常用操作

```powershell
D:\Android\platform-tools\adb.exe devices
D:\Android\platform-tools\adb.exe disconnect
D:\Android\platform-tools\adb.exe kill-server
```

连接卡死时，先 `disconnect` 或 `kill-server`，再重新 `connect`。状态含义：`device` 正常；`unauthorized` 要在电视上点允许；`offline` 要断开重连。

### 5. 看日志排查

```powershell
D:\Android\platform-tools\adb.exe logcat -c
D:\Android\platform-tools\adb.exe logcat -d -v time -s EM
```

日志里每条都有时间，记录了每次搜索（`M-SEARCH`）、取设备描述（`HTTP GET /desc.xml`）和控制命令（`SOAP`）。用来判断是网络没送到、手机没来搜，还是设备描述有问题。

---

## 四、路由器设置（端口转发）

### 网络结构

本机的网络是：光猫（192.168.1.1）下面接一台交换机（192.168.1.5）和一台华硕路由器（WAN 口 192.168.1.3）。

- pc1（开发电脑）连交换机，IP 是 192.168.1.4
- 电视机连华硕路由，IP 是 192.168.5.57
- 光猫没有管理权限，不能加静态路由，所以**华硕必须保持 NAT 开启**，否则电视机会上不了网。

### 目标

保持华硕 NAT 开启的前提下，让 pc1 能用 adb 访问电视机（安装和调试）。只把需要的端口（adb 的 5555）从华硕的 WAN 口转发给电视机，其他入站流量仍然被华硕挡住。

### 设置步骤

1. 浏览器打开华硕后台 `http://192.168.5.1` 并登录。
2. 进入 **外部网络(WAN) → 端口转发（虚拟服务器）**，打开 **启用端口转发**。
3. 添加一条规则，点 **＋** 后应用：

| 项目 | 填写 |
| --- | --- |
| 服务名称 | adb |
| 外部端口 | 5555 |
| 内部 IP 地址 | 192.168.5.57（电视机） |
| 内部端口 | 5555 |
| 协议 | TCP |

4. 电视机的 IP 要固定，否则 IP 变了转发就失效。在华硕后台 **局域网(LAN) → DHCP 服务器** 里启用手动指定 IP，把电视机的 MAC 地址绑定到 `192.168.5.57`。
5. 在 pc1 上，用华硕的 WAN 口地址和转发的端口连接电视机：

```powershell
D:\Android\platform-tools\adb.exe connect 192.168.1.3:5555
```

之后安装 APK 的命令不变，只是连接地址不同（`devices` 里显示的是 `192.168.1.3:5555`）：

```powershell
D:\Android\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

### 注意

- 端口转发只放行 5555，比把整台电视机暴露出去的 DMZ 安全得多。用完后可以把规则关掉或删除。
- 转发的是 TCP 端口，所以 `ping 192.168.1.3` 是不通的（`ping` 用的是 ICMP），用 `adb connect` 来验证。
- 反方向不受影响：NAT 开着时，电视机主动访问 pc1（`ping 192.168.1.4`）本来就是通的。
- 如果想让 pc1 直接访问 `192.168.5.57`，需要关闭华硕 NAT，并在光猫上加静态路由（目的 `192.168.5.0/24`，下一跳 `192.168.1.3`）。光猫没权限就做不到，只能用上面的端口转发。
- 投屏本身不需要转发端口：手机连华硕 Wi-Fi，和电视机在同一个网段，直接互相访问。

### 投屏对网络的要求

**手机和电视必须在同一个网段**。DLNA 的发现靠局域网组播和广播，不能跨网段。本机的电视机连在华硕后面（192.168.5.x），所以手机要连**华硕的 Wi-Fi**，和电视机处于同一个网段；手机连在光猫或交换机那一侧（192.168.1.x）是搜不到电视机的。

---

## 五、常见问题

| 现象 | 处理 |
| --- | --- |
| `gradle wrapper` 报 `25.0.5` 之类的错误 | 默认 JDK 版本太新，切到 JDK 17 再执行（见「一、1」） |
| `sdkmanager` 找不到或报错 | 检查目录结构是否是 `cmdline-tools\latest\bin`，并且已切到 JDK 17 |
| 编译找不到 SDK | 检查 `local.properties` 的 `sdk.dir`，或设置环境变量 `ANDROID_HOME` |
| 投屏列表里找不到设备 | 确认手机和电视同一网段、路由器没开「AP 隔离」、电视上已手动打开过一次 App |
| 设备出现得慢（几秒到十几秒） | 电视尽量用网线；关闭路由器或交换机的 IGMP 监听/组播优化；抓日志看第一条 `M-SEARCH` 的时间 |
| 能搜到但播放失败 | 看 logcat，把 `SOAP SetAVTransportURI` 和报错发出来 |
| 竖屏直播画面变形 | 已处理为按比例居中显示，黑边是正常的 |
| 开机后设备不在线 | 确认安装后手动打开过一次，并把 App 加入电视的自启动或后台白名单 |
| 改名后手机还显示旧名字 | 退出投屏搜索再进一次 |

## 局限

- 只实现了 DLNA。乐播、当贝的私有协议、AirPlay、Chromecast、Miracast 不在支持范围内，所以发现速度达不到乐播那种「秒出」，投屏入口也只限抖音、B 站这类自带 DLNA 投屏的 App。
- 不同版本的抖音、B 站对 DLNA 的兼容情况可能不同，遇到搜不到或播放失败，请按上面的方法抓日志反馈。
