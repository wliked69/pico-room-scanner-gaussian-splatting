# PICO Room Scanner & Gaussian Splatting

PICO 头显房间采集 → Windows 本地高斯泼溅重建与网格导出。

本项目提供两个应用的源码：

| 目录 | 用途 |
| --- | --- |
| `headset/` | RoomSplat：拍摄、房间三角网格覆盖提示、暂停/重置、设备端打包 |
| `desktop/` | Windows：头显扫描文件管理、导入照片、相机求解、高斯训练、PLY与近似碰撞OBJ导出 |
| `tools/` | 获取第三方引擎、构建Windows程序、传输/准备文件的辅助脚本 |

## 构建头显应用

使用Android Studio打开`headset/`。需要JDK21、Android SDK35、对应PICO OS6运行环境；扫描端使用NDK28.2.13676358与CMake3.22.1。SDK与依赖版本见项目Gradle配置，不必升级。

在对应项目目录运行：

```powershell
.\gradlew.bat --no-daemon assembleDebug testDebugUnitTest
```

APK输出到`app/build/outputs/apk/debug/app-debug.apk`。自行配置本机Android SDK路径；签名私钥及`local.properties`不在仓库中。重新生成的调试签名可能与既有安装不同，请先保存重要扫描，不要通过卸载绕过签名冲突。

RoomSplat包名`com.example.roomsplat`。相机与空间数据权限应通过头显系统提示正常授权，不依赖调试权限旁路。

## Windows建模端

建议Python3.12，安装依赖：

```powershell
python -m pip install -r desktop/requirements-build.txt -r desktop/requirements-mesh.txt
python tools/install_engines.py
cd desktop
python launch.py
```

引擎下载脚本从官方发行版获取COLMAP与Brush，依赖网络和对应发行包仍可用；引擎二进制未提交。程序内可指定COLMAP和Brush可执行文件路径。若使用打包的Python COLMAP worker，需要安装`pycolmap`并通过`tools/build_windows.ps1`构建。Windows GPU训练走Brush的WebGPU/Vulkan后端；相机特征匹配和位姿求解使用CPU。AMD RX9070XT曾完成本地GPU训练验证，NVIDIA兼容逻辑有测试，未在NVIDIA实卡上验收。需要支持引擎功能的显卡驱动。

USB连接头显并允许调试，打开“头显数据 · 导入 / 管理”，可查看/导入已打包扫描或未打包照片。删除操作请核对设备和文件，按软件提示备份或确认；不会自动同步到GitHub。

每次重建在扫描项目旁生成：

```text
reconstruction-日期时间/
  models/*.ply
  models/collision-日期时间-…/collision.obj
  report.json
  pipeline.log
  training.log
```

“打开项目文件夹”可定位输出。“查看模型”打开高斯模型；OBJ可以导入Unity并配置静态MeshCollider。

## 数据与依赖

本仓库按源码白名单重新整理，未带入原工作目录Git历史。照片、扫描ZIP、PLY/OBJ、真实墙面几何夹具、深度快照、日志、验证目录、打包程序、个人设置、设备序列号及签名私钥均未提交。内置`box.usdz`是程序用的通用盒子资源，不是扫描模型。

真实数据资源依赖的四个测试文件未公开；内联真实深度样例已替换成合成数值，保留同类回归逻辑。新增`.gitignore`用于避免未来误提交常见数据输出；每次推送仍应检查暂存列表。

第三方依赖保留各自授权与使用要求：

- [PICO Spatial SDK](https://developer.picoxr.com/)
- [COLMAP](https://github.com/colmap/colmap) / [pycolmap](https://github.com/colmap/pycolmap)
- [Brush](https://github.com/ArthurBrussee/brush)

本仓库暂未选择额外开源许可证。
