# 原型验证记录

验证日期：2026-10-03。对象：`0.1.0-prototype`，包名 `dev.tallybook.app`。

## 交付物

- APK：[tallybook-0.1.0-debug.apk](../artifacts/tallybook-0.1.0-debug.apk)，74,122 字节，调试签名。
- SHA-256：`4455cde3ab5a25a7e9bd4bd4a9241968b357505ec1b63aa661f02fb1e56c9773`。
- Android 8.0（API 26）起可安装；编译和目标 SDK 为 35。实际运行验证使用 Android 15 / API 35 模拟器，尚未覆盖所有安卓版本或厂商系统。
- `apksigner verify --verbose` 成功：APK v2 签名有效。

## 已完成的检查

| 检查 | 结果与范围 |
| --- | --- |
| Gradle 构建 | `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 成功 |
| JVM 解析测试 | 28 项全部通过；覆盖金额、方向、日期、状态、稳定 ID、回调包装以及无效输入拒绝 |
| Android 集成测试 | 16 项全部通过；使用平台 JSON 实现，覆盖数据库去重、状态更新、演示隔离、采集时限、接收校验与界面启动 |
| 外部调用检查 | 模拟器 shell UID 调用采集 Provider 被拒绝；Provider 不提供账本查询 |
| 界面与 CSV | 使用系统文件选择器实际保存 CSV，并回读核对 4 笔虚构交易、UTF-8 BOM、交易单号文本格式及收支合计 |
| 静态检查 | 0 个错误、5 个警告；其中 1 个为导出的 Provider，4 个为中文界面文案的国际化提示 |

Provider 为微信进程写入本地记录而导出；代码逐次核验 Binder 调用方 UID，只允许本应用或安装的微信包，采集关闭时拒绝交易写入。当前检查验证了外部 shell 被拒绝和应用内流程，**未验证真实微信进程与模块环境之间的通信**。

CSV 样例全部为虚构数据。确认支出 60.80 元、确认收入 120.00 元；退款样例保留在文件中并标记待核对，不计入确认收支。文件见 [demo-export.csv](../artifacts/demo-export.csv)。

证据文件：

- [Android 集成检查](../artifacts/instrumentation.txt)
- [外部调用拒绝记录](../artifacts/provider-access-check.txt)
- [界面检查结果](../artifacts/ui-smoke.json)
- [真实账本初始页](../artifacts/01-real-empty.png)、[虚构演示账本](../artifacts/02-demo-ledger.png)、[采集页](../artifacts/03-capture.png)、[设置页](../artifacts/04-settings.png)
- 详细 JVM 报告：`core/build/reports/tests/test/index.html`
- 详细 Lint 报告：`app/build/reports/lint-results-debug.html`

## 尚未验证或实现

- 没有连接用户手机，也没有登录微信、读取真实账单或进行真实支付。
- Hook 基于开源项目公开的 `evaluateJavascript` 切入点和**单笔账单详情**格式。模拟器测试使用虚构输入，不能证明当前微信版本兼容。
- 采集需要可运行 Xposed 模块的环境。普通未 Root 手机只安装 APK，可体验账本、导入支持的 JSON 和导出 CSV，不能因此获得读取微信进程的能力。
- 尚无独立微信后台查询 API、完整账单列表分页、每日定时拉取、支付宝或银行采集。
- JSON 导入仅支持已适配的单笔详情或本应用规范交易格式，不是官方账单 CSV/XLSX 导入。
- 未测试真实手机的后台保活、厂商权限限制、多微信账号、跨平台去重或完整退款核销。

## 复现

工具链保存在本项目 `.tools`：JDK 21、Gradle 8.11.1、Android SDK 35。脚本只设置当前进程环境，不改全局 PATH。首次下载需要网络。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\start-emulator.ps1 -Install
```

模拟器以隐藏窗口方式运行。等待启动完成后：

```powershell
. .\.tools\env.ps1
adb -s emulator-5554 shell getprop sys.boot_completed
# 上一条输出为 1 后执行；需已安装 Python 3。
powershell -ExecutionPolicy Bypass -File .\scripts\test-emulator.ps1
```

测试脚本仅允许本项目的 `tallybook_api35` 模拟器，并重置其中小账本的测试数据；不要改成真机运行。真实微信兼容性验证步骤见 [README](../README.md)。
