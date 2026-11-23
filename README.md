# WP-09 热成像

面向登陆舰 WP-09 USB-C 直连的 Android 应用。安装包使用独立包名，可与原应用共存。支持 Android 7.0 及以上的 ARM64 手机，连接设备需要 USB Host（OTG）。

在 [GitHub Releases](https://github.com/PeterPtroc/WP-09/releases/latest) 下载 `WP-09-v*-arm64.apk` 安装。手机无需安装英睿应用。如果手机上装过本项目的调试版，先备份要保留的数据，再卸载调试版并安装正式版。后续使用本项目正式签名的版本可以覆盖升级。

## 使用和校准

1. 退出正在使用热像仪的应用，插入 WP-09。打开本应用，按系统提示授予相机和 USB 访问权限。
2. 打开“测温”并点按画面。逐像素模式支持任意选点、全图平均温度和右侧摄氏色带。灰度预览模式用同一帧的最低、中心和最高读数按亮度插值，选点估算值标为“约”。空间较窄时，色带贴在画面右缘。横屏时热像以 4:3 比例显示，读数和操作键移到右侧，系统栏可从屏幕边缘滑出。
3. 先按被测表面的发射率调整 `ε`。设备稳定后，对准已知温度的参考面并填写参考温度。灰度预览模式用画面中心校准，逐像素模式用选定区域校准。单点校准修正偏差，两点校准同时修正偏差和斜率。
4. 用第三个已知温度对照误差，并尽量保持参考面材质、距离和环境条件一致。更改发射率会清除上一次现场校准。
5. 拍照保存 PNG，录像保存无声 MP4。Android 10 及以上可在相册的 WP09 目录查看。旧系统保存在应用的外部存储目录。

只有收到可验证的辐射测温数据时，界面才会显示摄氏值。如果状态显示“图像已连接 · 无温度数据”，画面仍支持切换色彩和拍录。现场校准依赖可用的温度流。官方 WP-09 规格列出了 USB-C、256×192 和激光指示，未给出绝对测温精度。[英睿 WP-09 产品规格](https://inreii.com/wp09/)

已用一帧 `XWild_WP09_C`（固件 `V0.1.2`）的实机 USB 数据回放验证三点解码。亮度受场景对比度和饱和影响，改变取景范围可能改变估算读数。全图平均温度需要逐像素测温数据。

## 实机取样

如果接入后无法出图或测温，把 WP-09 朝向桌面或墙面，长按右上角连接状态，选择“导出 ZIP”。文件含 USB 状态和可读取的一帧原始热像。发送该文件可用于分辨连接问题、普通预览帧和可解码的辐射测温帧。

## 构建与验证

使用 Android SDK 35、NDK 27.1.12297006、CMake 3.22.1 和 JDK 17 或 21。Gradle 直接打包 `app/src/main/jniLibs/arm64-v8a/` 下的四个库，构建无需原 APK。

`libusb100.so`、`libuvc.so`、`libjpeg-turbo1500.so` 和 `libthermometry.so` 取自英睿 `INREII-release-87.apk`，用于连接设备、读取热图和解码测温数据。原文件的 SHA-256 为 `a26dc91e7f80ab4628edf5701b19caafaf3f1a7d2a8cbc64bd37ba8ed9efa558`。[英睿官网下载页](https://inreii.com/service/infiray-app/)

```sh
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

模拟器测试使用合成帧，覆盖测温数据识别、温度统计、触摸选点、发射率、校准以及照片和录像保存。USB 实际取流、激光开关、固件测温能力和精度需要用 WP-09 验证。

## 自动发布

推送 `main` 或向 `main` 提交 Pull Request 后，GitHub Actions 运行 Android Lint、编译测试 APK 并上传调试 APK。更新 `app/build.gradle` 中的 `versionCode` 和 `versionName`，将版本提交推送到 `main`，再推送与 `versionName` 一致的 tag。比如 `versionName` 为 `0.7` 时：

```sh
git tag v0.7
git push origin v0.7
```

工作流会校验 tag 和 APK 版本，签名后把 ARM64 安装包及 `SHA256SUMS.txt` 发到 GitHub Releases。发布依赖两个 GitHub Actions Secrets，`WP09_SIGNING_KEYSTORE` 为 Base64 编码的 PKCS#12 密钥库，密钥别名为 `wp09`，`WP09_SIGNING_PASSWORD` 为密钥库密码。保留签名密钥备份，以便后续版本能覆盖升级。
