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

## 模拟器验证

影响界面、存储或 Android 集成时，使用项目的可丢弃模拟器：

```powershell
.\scripts\start-emulator.ps1
# 等待 emulator-5554 的 sys.boot_completed 返回 1 后再执行。
. .\.tools\env.ps1
adb -s emulator-5554 shell getprop sys.boot_completed
.\scripts\test-emulator.ps1
```

测试脚本校验 AVD 名称 `tallybook_api35`，只在该模拟器重置测试账本，使用虚构数据检查真实/演示隔离、交易处理、采集权限以及界面和 CSV 导出。UI 检查还需要 Python 3。完成后可释放模拟器资源：

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
