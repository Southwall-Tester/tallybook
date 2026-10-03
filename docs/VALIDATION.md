# 原型验证记录

验证日期：2026-10-03。对象：`0.2.0-prototype`，包名 `dev.tallybook.app`。

## 交付物

- 本地 APK：`artifacts/tallybook-0.2.0-debug.apk`，129,865 字节，调试签名；生成文件不纳入 Git。
- SHA-256：`2af057ab5fb5b770aa386a39b1ec777a790e6df2bf491050a20f4beb3f119280`。
- Android 8.0（API 26）起可安装；编译和目标 SDK 为 35。实际运行验证使用 Android 15 / API 35 模拟器，尚未覆盖所有安卓版本或厂商系统。
- `apksigner verify --verbose` 成功：APK v2 签名有效。

## 已完成的检查

| 检查 | 结果与范围 |
| --- | --- |
| Gradle 构建 | `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 成功 |
| JVM 核心测试 | 50 项全部通过；28 项微信解析、6 项手动交易、9 项预算、7 项目标测试，覆盖金额/日期边界、稳定 ID、状态、时区与溢出 |
| Android 集成测试 | 26 项全部通过；覆盖平台 JSON、数据库去重、手动记录删除限制、采集校验、预算/目标持久化与真实/演示隔离 |
| 外部调用检查 | 模拟器 shell UID 调用采集 Provider 被拒绝；Provider 不提供账本查询 |
| 界面与 CSV | 完整操作预算、手动支出、消费试算、目标保存、重启恢复、手动删除、采集开关；通过系统文件选择器导出真实/演示 CSV 并回读核对 |
| 静态检查 | 0 个错误、5 个警告；其中 1 个为导出的 Provider，4 个为中文界面文案的国际化提示 |

Provider 为微信进程写入本地记录而导出；代码逐次核验 Binder 调用方 UID，只允许本应用或安装的微信包，采集关闭时拒绝交易写入。当前检查验证了外部 shell 被拒绝和应用内流程，**未验证真实微信进程与模块环境之间的通信**。

界面样例：期初 2,000 元、固定预留 300 元、储蓄预留 200 元，可用 1,500 元；手动记支出 25.50 元后，可用 1,474.50 元，30 天每日参考 49.15 元。试算消费 100 元显示 1,374.50 元，不写入账本。目标 1,000 元、已存 250 元显示 25%，重启后保持。

CSV 样例全部为虚构数据。手动记录导出金额为 -25.50 元；演示确认支出 60.80 元、确认收入 120.00 元。退款样例标记待核对，不计入确认收支。演示回读文件在本地 `artifacts/demo-export.csv`。

完整界面流程在本次功能构建上通过。随后补充保存/删除/清空后的页面刷新通知，并修复旋转后恢复已保存草稿的问题。最终 APK 重新构建，通过 26 项集成检查及 `ui-refresh-smoke.py` 的保存→旋转→确认只有一笔→删除定向回归。未对所有写入与旋转并发时序进行穷尽验证。

电脑预览：`scripts/preview.ps1` 已实际创建并启动可见的 `tallybook_preview:5556` 窗口、安装 APK 并打开 Activity。测试设备为另一个 AVD，预览数据不会被测试清空。

证据文件（`artifacts/` 与 `build/` 为本地产物，未上传 Git；CI 报告从对应 Actions 运行下载）：

- Android 集成检查：`artifacts/instrumentation.txt`
- 外部调用拒绝记录：`artifacts/provider-access-check.txt`
- 界面检查结果：`artifacts/ui-smoke.json`
- 最终构建旋转定向检查：`artifacts/refresh-check.json`
- 虚构数据截图：[生活费首页](screenshots/budget-home.png)、[目标页](screenshots/savings-goal.png)
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

版本历史：0.1.0 验证了 28 项核心测试及 16 项安卓集成检查，原始验证文档保存在 Git 历史，本地旧 APK 仍保留。
