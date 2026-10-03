# 原型验证记录

验证日期：2026-10-03。当前版本：`0.3.1-prototype`，包名 `dev.tallybook.app`。

## 当前交付物

- APK：`artifacts/tallybook-0.3.1-debug.apk`，130,209 字节，versionCode 4。
- SHA-256：`ab78878897bd39f897a90ca36e35b9b393520bca82983e1186be77186250f897`。
- APK v2 签名验证通过；Android 8.0 / API 26 起可安装，compile/target SDK 35。
- 屏幕读取入口、无障碍服务、截图/OCR代码、中文模型与相关测试已移除。源文件扫描无残留；最终 APK 权限清单为空。
- vivo S30 已覆盖安装成功并冷启动，实机包信息确认为 versionCode 4 / 0.3.1。未卸载或清空账本，屏幕服务已从安装包移除。

## 已完成检查

| 检查 | 结果及范围 |
| --- | --- |
| Gradle | `scripts/build.ps1`：核心测试、应用APK、测试APK与lint成功 |
| JVM | 50项通过，0失败/错误/跳过 |
| Android集成 | `tallybook_api35` 模拟器26项通过，覆盖账本隔离、去重、预算目标持久化、受限Hook入口及启动 |
| Lint | 0错误、5警告 |
| 查询工具离线测试 | Python及Node虚构bridge共15项通过；覆盖分页、复合去重、凭据边界、转发归属与CSV转义 |
| 查询工具实机 | vivo S30 / Android16 / 微信8.0.77，当前登录账单页内两页各20条，合计40条唯一记录 |
| 本地导出 | JSON40条、CSV40条，未包含exportkey/csrf_token等请求鉴权字段；脚本自己的临时转发已移除 |

本次 Android 自动测试仅使用指定模拟器，未在手机或副屏平板运行清空账本的测试。真实账单和凭据不写入仓库。

## 接口验证边界

固定的 `nativeWXPayCgiTunnel` 列表查询及一次分页返回 `ret_code=0`，没有触发官方导出、人脸验证或账务修改。详细请求结构、复现方法和关闭调试步骤见 [接口研究](WECHAT_API_RESEARCH.md)。

带当前页面参数的独立Python HTTPS GET（无Cookie）返回HTTP200、业务码268511753、0记录。当前成立的是微信登录页面环境内的查询；尚未建立独立后台调用、会话续期、每日任务或完整历史账单同步。查询脚本尚未接入APK导入流程；APK仍只支持已适配的单笔JSON及CSV导出，不支持微信官方CSV/XLSX导入。

## 屏幕读取历史检查点

按用户要求，先提交并推送完整屏幕版本，再用后续提交移除。保留提交：[a02b758](https://github.com/Southwall-Tester/tallybook/commit/a02b758)。该版本为0.3.0，88项JVM、40项Android检查通过；本地真实截图样本识别5条、跳过3行。它未完成修复后手机服务到入库的实测，不应宣称真机屏幕采集已成功。历史APK在本地保留，完整验证细节可从该提交查看。

## 已有功能与未覆盖范围

此前完整UI流程已覆盖生活费、手动收支、消费试算、目标、重启恢复、删除及系统文件选择器CSV导出回读。本轮恢复原有界面与存储代码后通过26项集成检查，未重复整个旧UI脚本。微信Hook仍需兼容Xposed环境，尚无真实Hook采集成功证据。

当前不包含支付宝/银行采集、自动跨来源对账或完整退款核销。CI结果以对应提交的GitHub Actions为准，本页的数字首先是本地检查结果。

## 复现

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\test-emulator.ps1 -IntegrationOnly
python scripts/test_query_wechat_bills.py
```

测试脚本只允许 `tallybook_api35`，会重置其测试数据。`artifacts/instrumentation.txt`、JVM XML、lint报告和个人导出均是被Git忽略的本地产物。
