# 原型验证记录

验证日期：2026-10-03。当前开发版本：`0.4.0-prototype`，包名 `dev.tallybook.app`。

## 当前交付物

- APK：`artifacts/tallybook-0.4.0-debug.apk`，256,341 字节，versionCode 5。
- SHA-256：`c0c3ec79933383acfa71b60012cb1982bbf66d18e41f99ed0dffc069372ef6ba`。
- APK 签名验证通过；Android 8.0 / API 26 起可安装，compile/target SDK 35；最终 APK 权限清单为空。
- 新增钱钱练习、私有 USB 查询请求与待核对入账。历史 APK 均保留，未恢复屏幕读取。
- vivo S30 首次覆盖安装被系统取消；用户要求重新发起后，`adb install -r` 返回 `Success`。随后冷启动返回 `Status: ok`，实机包信息确认为 versionCode 5 / `0.4.0-prototype`。未卸载或清空手机数据。该结果证明安装与启动成功，不代表新增微信查询到入库的真机闭环已通过。

## 已完成检查

| 检查 | 结果及范围 |
| --- | --- |
| Gradle | `scripts/build.ps1`：核心测试、应用 APK、测试 APK 与 lint 成功 |
| JVM | 84 项通过，0 失败/错误/跳过；新增 24 项钱钱模型与 JSON 测试、10 项查询解析测试 |
| 钱钱计算 | 5,151 种整数百分比分配组合及边界金额守恒；精确 72 小时、跨夏令时/闰日、主题循环、正负假设复利；长期 JSON 历史与严格损坏检查 |
| Android 集成 | `tallybook_api35` 模拟器 74 项通过；其中 29 项钱钱持久化/隔离/更新删除/损坏保护、19 项私有查询协议及确认/去重检查 |
| 旧功能 UI | 生活费、手动收支、消费试算不入账、存钱目标、重启恢复、手动删除、真实/演示隔离、系统文件选择器 CSV 导出回读与旧 Hook 开关均通过 |
| Lint | 0 错误、10 警告；涉及现有受调用方限制的导出 Provider、同步持久化提示及中文字符串本地化提示 |
| 查询工具离线 | 23 项 Python/Node 虚构测试通过；含分页、复合去重、凭据边界、CSV、私有 stdin 投递、取消/换请求/过期后继续监听 |
| 钱钱定向 UI | `scripts/book-ui-smoke.py` 的 9 组检查全部通过：愿望/重点/选图取消保留输入、旋转日记草稿、分配计划转已执行、记录行动结果并完成、星期准则、每周回顾与越界日期恢复、正负年率试算、来源隔离、重启恢复 |
| GitHub CI | 开发检查点 `961280d` 的 [push 检查](https://github.com/Southwall-Tester/tallybook/actions/runs/37115780895) 和 [PR 检查](https://github.com/Southwall-Tester/tallybook/actions/runs/37115783129) 均成功；OCR 保存标签的构建亦成功 |

Android 自动测试仅使用指定的可丢弃模拟器，未在手机或副屏平板运行重置数据的测试。个人账单、凭据、原书全文和隐私截图不进入 Git。

## 钱钱功能与验证边界

已实现九个页面：今天、愿望与梦想、三用途分配、成功日记、72 小时行动、每日准则、每周复盘、方法库、学习试算。六类记录单独按真实/演示来源持久化；保存操作后台串行执行，失败保留原记录和输入。模拟器存储检查与定向点按流程已通过。三张虚构数据截图已查看，表单、方法库和负年率结果正常显示，长页面可滚动。

梦想图片使用系统文件选择器取得只读 URI，不请求整个相册权限。真实图片选择、持久授权及重启打开尚未完成验证。分配记录与手填梦想进度不改动真实账本；复利和 72 法则均为用户输入假设的教学试算，不记作收入。

43 个方法入口是简短转述与操作导向，不代表 43 个专用子系统全部完成。完整资产/债务管理、收入项目、账户用途余额、投资组合模拟和所有方法的专用流程仍是设计范围，见 [产品设计](BOOK_PRODUCT_PLAN.md)。工程检查不证明用户的储蓄、学习或行为效果。

## 微信接口验证边界

此前已在 vivo S30 / Android 16 / 微信 8.0.77 的当前登录账单页，用固定只读原生查询及分页取得两页各 20 条、共 40 条唯一记录，并导出本地 JSON/CSV。未触发官方导出、人脸验证或账务修改；导出不包含请求鉴权字段，工具创建的转发已移除。详细结构和边界见 [接口研究](WECHAT_API_RESEARCH.md)。

0.4.0 的手机请求、私有结果验证、批量入库、逐笔确认及去重已通过模拟器与虚构协议检查，**微信实际查询 → 电脑回传 → 手机新版本入库尚未完成真机验证**。以前的 40 条电脑导出不能算作本次手机入账成功。

带当前页面参数的独立 Python HTTPS GET（无 Cookie）此前返回 HTTP 200、业务码 268511753、0 记录。目前成立的是微信登录页面环境内的查询。尚未建立独立后台调用、会话续期、每日任务或完整历史账单同步。当前亦不支持微信官方 CSV/XLSX 导入、支付宝/银行采集、自动跨来源对账或完整退款核销。旧 Hook 仍需兼容 Xposed 环境，尚无真机采集成功证据。

## 屏幕读取历史检查点

按用户要求，先提交并推送完整屏幕版本，再用后续提交移除：

- 保存版 [a02b758](https://github.com/Southwall-Tester/tallybook/commit/a02b758)，另有已推送标签 [screen-ocr-v0.3.0](https://github.com/Southwall-Tester/tallybook/tree/screen-ocr-v0.3.0)。
- 删除版 [4df9827](https://github.com/Southwall-Tester/tallybook/commit/4df9827)，没有改写保存版历史。

历史 0.3.0 有 88 项 JVM、40 项 Android 检查通过；本地真实截图样本识别 5 条、跳过 3 行。该版本未完成修复后手机服务到入库的实测，不宣称真机屏幕采集成功。0.3.1 删除版已在手机成功安装启动。

## 复现

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\test-emulator.ps1
python -X utf8 scripts/book-ui-smoke.py
python -X utf8 scripts/test_query_wechat_bills.py
```

UI 脚本串行运行。测试工具只允许 `tallybook_api35`；集成测试会重置其测试数据，不影响预览 AVD 或手机。`artifacts/` 测试报告、JVM XML、lint、APK 与个人导出均是被 Git 忽略的本地产物。CI 结果以具体提交的 GitHub Actions 为准，本文数字首先是本地检查结果。
