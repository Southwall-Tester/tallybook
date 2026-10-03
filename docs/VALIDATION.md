# 原型验证记录

验证日期：2026-10-03。对象：`0.3.0-prototype`，包名 `dev.tallybook.app`。本页区分构建、模拟器检查、真实图片样本与微信当前登录页面内的接口研究。

## 交付物

- 本地 APK：`artifacts/tallybook-0.3.0-debug.apk`，47,154,774 字节，调试签名；生成文件不纳入 Git。
- SHA-256：`23020e19f1537938028697a6a144771d7e44fd73a3fd8b87194a3c3223ac8b68`。
- 应用最低 Android 8.0 / API 26，编译和目标 SDK 为 35；屏幕读取仅支持 Android 14 / API 34 及以上。
- 自动检查只使用 `emulator-5554 / tallybook_api35`（Android 15 / API 35）。真实手机为 vivo S30（V2464A）、Android 16 / API 36，微信 `8.0.77`、`versionCode=3160`、target SDK 34；未操作副屏平板。
- 最新 APK 经 `apksigner verify --verbose` 检查，v2 签名有效；旧版本 APK 保留在本地。

## 已完成的检查

| 检查 | 结果与范围 |
| --- | --- |
| Gradle 构建 | `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 成功 |
| JVM 核心测试 | 88 项，0 失败、0 错误、0 跳过；28 项详情解析、25 项列表解析、13 项屏幕交易、6 项手动交易、9 项预算、7 项目标 |
| Android 集成测试 | 最新 APK 上 40 项全部通过；覆盖平台 JSON、来源限制、屏幕记录去重/待核对/确认/删除、真实与演示隔离、授权及预算和目标持久化 |
| 本机中文 OCR | 捆绑 `com.google.mlkit:text-recognition-chinese:16.0.1`，在没有 `INTERNET` 权限的 APK 中识别合成中文图片，解析出 2 条待核对记录 |
| 新读取页定向 UI | 从设置进入读取页，核对停止状态与首次权限说明；两个权限入口均打开系统设置；返回后读取仍关闭；核对识别限制说明及返回真实账本入口 |
| 真实图片样本 OCR | 模拟器处理一张本地微信列表截图，使用服务相同的 `OcrLines` 与核心解析器，结果为 `RESULT OK rows=5 skipped=3` |
| 外部调用 | 本轮旧功能检查中 shell UID 调用 Provider 被拒绝；40 项集成检查覆盖禁止账本查询和拒绝屏幕来源写入 Hook 桥 |
| Lint | 最新 XML 为 0 错误、10 警告：2 个 `UnusedAttribute`、2 个 `UseRequiresApi`、1 个 `ExportedContentProvider`、5 个 `SetTextI18n` |

新读取页 UI 检查没有启用模拟器无障碍服务或打开微信。仅导航到权限设置不会开启读取；未设置授权字段时按默认关闭处理。确认、重复 OCR 不覆盖已确认状态、删除及来源隔离已通过虚构数据的存储集成检查；本次定向 UI 检查没有逐一点击屏幕记录的确认和删除按钮。

Provider 为微信进程写入而导出，逐次核验 Binder UID，且 Hook 授权关闭时拒绝交易写入。已有检查仍不能证明真实微信进程与 Xposed 模块之间的兼容性。

## 图片样本与手机服务

该手机的微信列表在 UIAutomator 中仅有空根节点，但 ADB 截图可取得图像，因此应用采用指定微信窗口截图加本机 OCR。它只核验包名与窗口 ID，不依赖账单控件文字。

早期解析漏掉年月标题尾部下拉符号，以及被 ML Kit 合为一行的名称与金额。最新修复按 Element 的实际边界分离右侧带符号金额，不猜坐标，不把 O 猜成 0。样本中 5 条完整记录被识别，3 行因没有明确收支符号或显示不完整等条件跳过；结果仅覆盖这张图，不能推算全量准确率或覆盖率。

屏幕记录先存为待核对，按名称、带方向金额和分钟去重。相似真实交易可能合并，识别文字变化可能产生重复；不能与手记或详情来源自动合并。年份来自可见年月标题或相邻帧可靠重叠记录，不使用手机当前年份补猜。

最后追加的回归将新 OCR 名称中两个汉字之间的水平空白统一去掉，使同一中文名称因 OCR 多出空格时仍保持相同去重标识，并支持相邻帧的重叠匹配。拉丁字母或数字边界的空格保留，金额与时间不变，既有已保存记录的名称和 ID 不重写。对应新增 5 项核心测试已通过；最终 APK 再次安装到测试模拟器，真实图片诊断仍为 5 条有效、3 行跳过。

S30 已完成应用部署、启动和初始权限流程检查；**修复版服务的截图→识别→账本新增链路尚待手机再次验证**。模拟器处理真实图片成功，不代表手机服务已自动读取成功。前台限定、30 分钟超时、停止撤销和图片释放已实现；授权开关与期限有集成检查，持续半小时、厂商后台回收及所有切换/旋转并发情况没有穷尽实测。

真实图片、OCR 原文、登录态和调试数据仅留本地，不成为仓库测试夹具或公开证据。本页只记录数量、状态码和验证边界。

## 微信当前页面内的查询研究

另一路研究在本人已登录的微信 H5 环境中，通过 CDP 检查并主动调用页面查询桥：首批 20 条，下一批再取得 20 条新的结构化记录，共 40 条；返回 `nativeWXPayCgiTunnel:ok`、`ret_code=0`。这证明**当前登录页面环境内的查询和一次分页成立**，没有触发导出或刷脸。

将当前页面的 `exportkey`、`csrf_token`、User-Agent 和 Referer 用于独立 Python HTTPS GET、未携带 Cookie 的试验，HTTP 为 200，但业务返回 `retcode=268511753`，记录为 0。HTTP 成功不能当作账单查询成功；这一试验也不足以证明所有独立调用路径均不可行。

这一路研究未接入当前 APK 的导入流程，尚未建立脱离微信环境的独立后台查询、凭据续期或每日任务。参数值与原始响应不入 Git，内部查询桥不作为公开个人账单 API。更多过程见 [微信接口研究](WECHAT_API_RESEARCH.md)。

## 既有功能和开发流程回归

本轮较早的功能构建已通过完整旧 UI 流程：预算、手动支出、消费试算、目标保存、重启恢复、手动删除、Hook 开关，以及系统文件选择器导出真实/演示 CSV 后回读。`artifacts/ui-smoke.json` 记录通过。金额拆行修复版通过读取页定向 UI；最后的中文空格归一化 APK 再次通过 40 项 Android 检查和真实图片诊断，没有重跑整套旧 UI 或不受该解析改动影响的读取页导航。

旧 UI 样例均为虚构数据：期初 2,000 元，固定预留 300 元，储蓄预留 200 元，可用 1,500 元；记支出 25.50 元后为 1,474.50 元，30 天每日参考 49.15 元。试算 100 元后显示 1,374.50 元，不写账本。目标 1,000 元、已存 250 元显示 25%，重启后保持。演示 CSV 确认支出 60.80 元、确认收入 120.00 元，退款样例待核对。

0.2.0 保存→旋转→确认只有一笔→删除的定向回归已通过，见 `artifacts/refresh-check.json`。电脑预览验证使用独立 `tallybook_preview:5556`；自动测试不会清空预览设备或手机。

USB 开发脚本已验证 PowerShell 5.1 语法、枚举与未选目标/目标缺失/选择模拟器时的拒绝分支；S30 已完成固定序列号后的安装和启动。序列号仅本地保存，未对手机执行清账本的测试；`-Watch` 长期连续运行未测。本页报告本地结果，CI 应以相应 GitHub Actions 运行为准。

## 证据文件

`artifacts/`、`.tools/` 与构建报告为本地产物，不上传 Git。公开截图只含虚构数据。

- Android 检查：`artifacts/instrumentation.txt`
- 外部调用拒绝：`artifacts/provider-access-check.txt`
- 旧完整 UI：`artifacts/ui-smoke.json`
- 新读取页 UI：`artifacts/screen-reader-ui.json`、`artifacts/screen-reader-ui.png`
- 历史旋转回归：`artifacts/refresh-check.json`
- 虚构公开截图：[生活费首页](screenshots/budget-home.png)、[目标页](screenshots/savings-goal.png)
- JVM：`core/build/test-results/test/TEST-*.xml`、`core/build/reports/tests/test/index.html`
- Lint：`app/build/reports/lint-results-debug.xml`、`app/build/reports/lint-results-debug.html`

## 尚未验证或实现

- 最新 OCR 修复版在手机上的自动读取和实际新增，以及更多微信版本、设备和布局。
- Hook 仍需兼容 Xposed 环境，与无需 Root 的屏幕功能独立授权；模拟器不能证明微信 Hook 兼容。
- 脱离微信的后台 API、无人值守的完整历史分页和每日同步；支付宝或银行采集。
- 微信官方 CSV/XLSX 导入；现有 JSON 只支持适配的单笔详情或本应用规范交易格式。
- 多微信账号、跨来源去重、完整退款核销、后台保活及所有权限和旋转并发时序。

## 复现

工具链位于 `.tools`：JDK 21、Gradle 8.11.1、Android SDK 35。脚本仅设置当前进程环境。首次下载工具和依赖需要网络，APK 捆绑 OCR 无需运行时下载模型。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\start-emulator.ps1 -Install
. .\.tools\env.ps1
adb -s emulator-5554 shell getprop sys.boot_completed
# 输出为 1 后：
powershell -ExecutionPolicy Bypass -File .\scripts\test-emulator.ps1 -IntegrationOnly
```

去掉 `-IntegrationOnly` 会额外执行旧完整 UI。测试只允许 `tallybook_api35`，会重置其测试账本，不应改为真机运行。读取页定向脚本为本地 `.tools/screen-ui-check.py`。图片诊断仅在本地样本已提供时使用 instrumentation 的 `-e ocrFixture true` 分支，不给公开 CI 提供个人图片。

版本历史：0.1.0 为 28 项核心测试与 16 项 Android 检查；0.2.0 为 50 项核心测试与 26 项 Android 检查。历史验证记录见 Git 历史。
