# 开发与推送流程

仓库：<https://github.com/Southwall-Tester/tallybook>。按一个完整功能或修复形成一次可验证的提交，验证后推送。用户已授权持续开发中的常规推送；不需要另设按时间执行的任务，也不推送未完成或测试失败的检查点。

## 本地环境

Windows PowerShell 在仓库根目录运行：

```powershell
# 首次准备 JDK 21、Android SDK 35 和项目内工具；已有 .tools 时无需重装。
.\scripts\bootstrap.ps1

# 核心测试、debug APK、Android 测试 APK、lint；导出版本化 APK 与 SHA256。
.\scripts\build.ps1
```

`build.ps1` 使用仓库中的 Gradle 8.11.1 wrapper。产物版本读取 Android 生成的 `output-metadata.json`，例如 `0.2.0-prototype` 导出为 `artifacts/tallybook-0.2.0-debug.apk`。新版本不会覆盖旧版本；同一版本重新构建时，若内容变化，旧文件按 SHA256 保存在 `artifacts/history/`。这些本机构建产物不作为 Git 源码提交。

已有 JDK 21 和 Android SDK 的环境，可设置 `JAVA_HOME`、`ANDROID_HOME` 后直接执行：

```bash
bash ./gradlew :core:test :app:assembleDebug :app:lintDebug --console=plain --no-daemon
```

Windows 直接调用 wrapper 前，可运行 `. .\.tools\env.ps1`，再用 ` .\gradlew.bat` 替代上面的 `bash ./gradlew`。不要把本机 SDK 的绝对路径写进提交。

## 真机开发

普通 Android 8.0 及以上手机即可通过 USB 开发，不需要 Root。首次在实验手机上开启「开发者选项 → USB 调试」，使用支持数据传输的线连接电脑，解锁手机并允许此电脑的调试授权。具体设置入口因品牌不同而异，参见 [Android 真机运行说明](https://developer.android.com/studio/run/device)。

```powershell
# 只列出 ADB 设备，不安装应用。
.\scripts\run-device.ps1 -List

# 将 PHONE_SERIAL 替换为已确认的实验手机序列号。
# 检查设备并记住选择，暂不安装；序列号只保存在 .tools/phone-target.json。
.\scripts\run-device.ps1 -Serial PHONE_SERIAL -Check -Remember

# 增量构建、覆盖安装并启动；也可双击根目录「手机运行小账本.cmd」。
.\scripts\run-device.ps1

# 可选：保存源码后自动构建、更新并重启小账本；Ctrl+C 结束。
.\scripts\run-device.ps1 -Watch
```

脚本要求显式选择或已记住的设备，全程固定序列号，不会自动选择其他设备。它拒绝模拟器和识别为平板、电视、手表、车载系统的设备；设备类型依赖厂商报告，首次仍须确认选中的是实验手机。设备断开或调试授权失效时停止。副屏平板保持原样，脚本不操作 spacedesk、驱动或 ADB server。

`-Watch` 监控应用和核心模块的主源码、资源与构建配置，文件稳定后才构建。构建失败不会部署旧 APK，修改源码并保存后可重试；安装失败不会卸载应用或清空数据。正常更新使用 `adb install -r`，兼容签名下保留应用数据；启动会重启小账本，编辑中的未保存内容可能丢失。

这条流程省去手动打包、传文件和点安装，但内部仍会构建、覆盖安装 APK 并重启应用。若使用 Android Studio，可通过 [Apply Changes](https://developer.android.com/studio/run#apply-changes) 应用部分方法体和资源改动；需要 debug 构建和 Android 8.0 及以上，清单、字段、方法签名等变动仍可能需要完整 Run。当前项目采用原生 Java UI，脚本没有通用热重载能力。

若列表为空，先确认实验手机上的 USB 调试、数据线和连接模式；若显示 `unauthorized`，在手机上接受调试授权。多个安卓设备同时连接时不要按列表顺序猜测。Windows 的厂商驱动问题需按手机型号单独排查，不要为调试手机停用正在使用的副屏平板。

## 模拟器验证

电脑交互预览直接双击根目录 `预览小账本.cmd`，或运行 `scripts/preview.ps1`。它打开可见的 `tallybook_preview`（端口 5556），保留试用数据。修改代码后使用 `scripts/preview.ps1 -Rebuild` 更新 APK。

影响界面、存储或 Android 集成时，使用项目的可丢弃模拟器：

```powershell
.\scripts\start-emulator.ps1
# 等待 emulator-5554 的 sys.boot_completed 返回 1 后再执行。
. .\.tools\env.ps1
adb -s emulator-5554 shell getprop sys.boot_completed
.\scripts\test-emulator.ps1
```

测试脚本校验 AVD 名称 `tallybook_api35`，只在该模拟器重置测试账本，使用虚构数据检查真实/演示隔离、交易处理、采集权限以及界面和 CSV 导出。UI 检查还需要 Python 3。完成后可释放模拟器资源：

若仅改动 Android 集成逻辑，可用 `scripts/test-emulator.ps1 -IntegrationOnly` 只运行集成和调用权限检查；完整界面流程仍使用默认命令。预览设备与测试设备相互隔离。

保存与旋转的定向回归：先运行上述 `-IntegrationOnly` 重置测试数据，再运行 `python scripts/ui-refresh-smoke.py`。它在一次保存后切换屏幕方向，确认只出现一笔记录、已保存草稿不再弹出，并能删除该记录。

钱钱练习的定向 UI 检查使用 `python -X utf8 scripts/book-ui-smoke.py`，在上述集成检查后运行。只操作 `tallybook_api35` 的虚构演示练习，覆盖愿望、选图取消、旋转草稿、分配、行动、准则、周复盘、正负年率试算、重启及真实/演示隔离。它与其他 UI 脚本必须串行运行；结果保存在忽略的 `artifacts/book-ui-smoke.json`。成功取消选图不代表实际图片权限持久化已验证。

微信 USB 电脑助手使用 `scripts/sync-wechat.ps1` 或根目录 `微信接口同步.cmd`。只使用 `.tools/phone-target.json` 中明确选择的手机，不会自行挑选设备；手机端必须先创建五分钟查询请求。虚构协议检查运行 `python -X utf8 scripts/test_query_wechat_bills.py`，真实查询步骤和范围见 [接口研究记录](WECHAT_API_RESEARCH.md)。

```powershell
adb -s emulator-5554 emu avd name
# 仅在上一条确认名称为 tallybook_api35 时执行。
adb -s emulator-5554 emu kill
```

模拟器、解析样例和 CI 不能证明当前微信版本或真实手机采集可用。实际结果记录在 [VALIDATION.md](VALIDATION.md)，没有运行过的检查标记为未验证。

## 提交与分支

初期小功能可在 `main` 上按以下顺序完成；复杂功能、多人并行或需要评审时，先执行 `git switch -c feature/<short-name>`，推送该分支并开 PR。

1. 完成一个范围清楚的功能，补充真正验证行为的测试及使用说明。
2. 执行本地构建和适用的模拟器检查，修复失败项。
3. 检查 `git status --short`、`git diff --check`、`git diff`，确认没有账单、隐私截图、密钥或构建工具。
4. 用明确文件路径暂存，检查暂存差异，再原子提交。
5. 推送并查看 GitHub Actions；只有实际成功后才称为 CI 通过。

```powershell
git status --short
git diff --check
git diff
# 替换为本次实际修改的文件；不要无差别加入用户放入目录的文件。
git add <file1> <file2>
git diff --cached --stat
git diff --cached
git commit -m "feat: describe the completed feature"
git push origin main
```

发布新的可安装版本时同步增加 `app/build.gradle` 中的 `versionCode` 和 `versionName`。debug APK 用于开发验证，CI APK 的签名由该运行环境的 debug keystore 决定；本机构建与 CI 构建不保证可直接相互覆盖安装。

## GitHub Actions

[android.yml](../.github/workflows/android.yml) 在 push、pull request 或手动触发时运行核心测试、debug 构建和 lint。运行成功后可在 [Actions 页面](https://github.com/Southwall-Tester/tallybook/actions) 下载对应提交的 APK，检查报告也会保存为 artifact（30 天）。当前工作流不启动云模拟器，不执行手机操作。

工作流只有 `contents: read` 权限，checkout 不保留凭据，不发布到应用商店。Actions 固定完整提交 SHA，wrapper 固定 Gradle 8.11.1，并使用[官方分发 SHA256](https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256)校验下载。升级时从各官方仓库发布标签重新确认完整提交，而不是只改注释中的版本号：

- [actions/checkout](https://github.com/actions/checkout/releases)
- [actions/setup-java](https://github.com/actions/setup-java/releases)
- [gradle/actions](https://github.com/gradle/actions/releases)
- [actions/upload-artifact](https://github.com/actions/upload-artifact/releases)

建立工作流本身不代表 CI 已成功；以具体提交的 Actions 结果为准。
