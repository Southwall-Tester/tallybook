# 微信个人账单查询接口调查

调查日期：2026-10-03。目标是在本人已登录的微信中复用“查看账单”的查询请求，减少手动导出操作。

**已在 S30 的已登录微信账单页面内，主动调用查询桥接并连续取得两页共 40 条不重复记录。** 没有翻动页面列表、修改页面 store，也没有要求刷脸。独立 HTTP 客户端用相同会话参数请求仍失败；脱离微信的后台获取、会话自动续期和每日同步尚未打通。

## 已观察到的设备和接口状态

实验设备为 vivo S30（V2464A），Android 16 / API 36，微信 8.0.77。副屏平板不参与实验。本次初始只读检查记录：微信包标志未包含 `DEBUGGABLE`，`run-as` 访问被拒绝，当时未发现标准 WebView 调试 socket。随后开启微信自身的浏览器调试，`/proc/net/unix` 出现对应微信进程的 `@webview_devtools_remote_<pid>`，通过 ADB 转发后 `/json/list` 实际列出标题为“账单”的 H5 调试目标，主机及路径为 `tenpay.wechatpay.cn/userroll/readtemplate`，URL 参数名为 `exportkey`、`t`。早期实验中的 1294 是电脑本机转发端口，不是微信在手机上固定监听的端口。本页不记录完整查询串、进程号或调试目标标识。

因此，初始没有标准 socket 不能推导为无法调试；本次已找到并实际连接微信提供的 CDP 入口。USB 调试本身仍不授予其他应用访问微信私有数据的权限，参见 [Android 应用沙箱](https://source.android.com/docs/security/app-sandbox)。成功复用的是当前已登录页面及微信原生桥接上下文，不是已获得可持续使用的独立登录机制。

| 本次实机验证 | 结果 | 能说明什么 |
| --- | --- | --- |
| 当前账单页 `WeixinJSBridge.invoke('nativeWXPayCgiTunnel', …)`，固定列表命令和每页 20 条 | 回调 `nativeWXPayCgiTunnel:ok`，`ret_code=0`，第一页 20 条 | 当前微信会话内主动查询成立 |
| 使用第一页响应的四个游标及最后记录的时间戳查询第二页 | 再得 20 条新记录，合计 40 条不重复记录 | 当前会话内连续两页查询成立；未验证全部历史或长期定时同步 |
| Python `urllib` 请求当前主机 `/userroll/userrolllist`，携带同样 `exportkey`、`csrf_token`、UA、Referer，不携带 Cookie | HTTP 200，`ret_code=268511753`，记录数 0 | 这组普通 HTTPS 请求条件没有成功；不能据此彻底否定其他完整鉴权条件下的 HTTP 调用 |

对历史个人查询地址 `https://wx.tenpay.com/userroll/userrolllist` 发起不带 Cookie、登录票据或其他个人凭据的 GET，得到 HTTP 200、JSON 错误 `ret_code=268511752`、`ret_msg=会话过期，请重新打开`。旧模板入口 `/userroll/readtemplate?t=userroll/index_tmpl&cid=1474` 同样返回会话过期页面。这里只验证了地址仍响应且要求会话，没有取得账单，也没有证明旧参数仍适用于当前版本。

[2018 年开发者的第一手记录（第 61、68 条回复）](https://www.v2ex.com/t/499756?p=1) 描述过用该地址读取个人账单，分页涉及 `last_bill_id`、`last_bill_type`、`last_create_time`、`last_trans_id`，并指出 `exportkey` 有有效期、自动获取尚未解决。该记录仅作为历史线索。

[微信支付官方交易账单 API](https://pay.wechatpay.cn/doc/v3/merchant/4013071227) 是商户接口，要求商户签名；[官方 FAQ](https://pay.wechatpay.cn/doc/v3/merchant/4013071254) 明确其交易账单对应用户支付给商户的收款订单。它不能替代普通用户跨商户的个人消费账单查询。“查看时不刷脸”也不代表请求没有登录态、页面上下文或服务端鉴权。

## 微信 8.0.77 APK 的请求链路

检查文件：`.tools/wechat-inspection/wechat-8.0.77-base.apk`，278,623,416 字节。

```text
SHA-256: 18714afe662d2ca58e8e9389ad727c9ff562ed8f3711a93c904819bb753215d2
```

使用 [jadx 官方 1.5.6 发布版](https://github.com/skylot/jadx/releases/tag/v1.5.6)，仅在本机提取相关 DEX、选择性反编译，单线程、768 MiB Java 堆；没有上传 APK 或反编译内容。

| 位置 | 静态确认的行为 | 验证边界 |
| --- | --- | --- |
| `classes6.dex`，`com.tencent.mm.plugin.webview.ui.tools.newjsapi.u2` | H5 的 `nativeWXPayCgiTunnel`，编号 455；读取 `cgi_id`、`cgi_url`、`reqbuf`、`cmd`。要求 ID 大于 0、请求体和命令非空；路径需匹配 `/cgi-bin/mmpay-bin/tunnel_…` 或其子目录下的 `tunnel_…` | 通用桥接；下节结合当前页面 bundle 与实机确定列表命令 |
| `classes15.dex`，`li0.m.Ti` 与 `y85.w36` | 将桥接参数及当前页面 URL、页面上下文、场景等包装为含 `BaseRequest` 的 protobuf 请求，交给微信网络层 | 原生层补充的完整鉴权机制尚未独立复现，不能只复制 URL 到普通 HTTP 客户端 |
| `classes6.dex`，`…newjsapi.t2` | 收到成功响应后取 `y85.x36.d`，以 `respbuf` 回给页面 | 与本次实际观察到的 JSON 列表回调一致 |
| `classes11.dex`，`FrLifeController.startWebViewUIPage` | 将含 `userroll/readtemplate` 的 URL 识别为支付账单 H5 入口 | 该入口本次已在手机上发现 |
| `classes11.dex`，`zs3.k` / `MallOrderRecordListUI.H6` | 另有原生 `NetScenePatchQueryUserRoll`，请求 `Limit`、`Offset`、`Extbuf`；命令号 105，继承的 URI 为 `/cgi-bin/micromsg-bin/tenpay`、网络类型 385；响应解析 `UserRollList` 和分页信息 | 可能是保留的旧界面，未证明本机当前账单页使用它，也未请求服务器验证 |

原生命令 105 的父类链路还涉及 `WCPaySign` 和客户端证书签名分支，经微信自己的网络层发送。另见的 `payuqueryuserroll` 属于 PayU 路线，不能当作国内个人账单接口。

[AutoAccounting 固定提交中的 WebViewHooker](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/xposed/hooks/wechat/hooks/WebViewHooker.kt#L31) 观察 `evaluateJavascript` 中的 `nativeWXPayCgiTunnel:ok`、解析 `respbuf`。该文件提供回包采集的第一手实现，未实现独立 HTTP 登录、会话续期或列表批量查询。

## 当前账单 H5 的列表请求

只分析账单页引用的公共外链 `chunk-common.b2841c07.js`，没有读取或保存个人会话的内联脚本。公共 bundle 的 `dd32` 模块按 `svrData.change_secure_tunnel_flag` 的低位选择列表桥接；实机该值为 3，列表和详情位均开启。列表使用以下固定参数，本轮只调用列表：

```text
桥接名：nativeWXPayCgiTunnel
cmd：userrolllist
cgi_id：5004
cgi_url：/cgi-bin/mmpay-bin/tunnel_userrollsecuretunnel
首请求 reqbuf：count=20，sort_type=1，classify_type=0，加当前会话参数
```

`e5ec` 模块从当前页面 URL 取得 `exportkey`，合并 `svrData.extra_params`；本次实际会话需要其中的 `csrf_token`。这些值只在已登录页面内使用，不写入导出文件或日志。APK 原生桥接还会加入当前页面及微信网络上下文，因此“这些查询参数已知”不等于“独立 HTTP 鉴权已解决”。公共模块包含 HTTP 降级分支，但本次开发脚本固定走已验证的桥接，不自动尝试其他通道。

成功响应的结构为 `ret_code`、`record`、`statistic`、`total`、四个游标、`is_over`、`cold_data_limit`、`banner`。下一页保持 `count=20`、`sort_type=1`、`classify_type=0`，加入响应中的 `last_bill_id`、`last_bill_type`、`last_trans_id`、`last_create_time`，以及本页最后一条记录的 `timestamp` 作为 `start_time`。按 `(bill_id, trans_id, timestamp)` 去重，不截断交易标识。

已观察记录字段：`bill_id`、`trans_id`、`title`、`timestamp`、`fee`、`fee_type`、`fee_attr`、`current_state`、`current_state_type`、`bill_type`、`icon_url`、`out_trade_no`。`timestamp` 为秒；本次人民币记录的 `fee` 为整数分，页面展示函数使用 `fee / 100`。`fee_attr` 使用 `positive`、`negtive`（原始拼写）、`neutral`；其中 `neutral` 不能擅自推断成收入或支出。退款、转账、理财等进入记账汇总的规则仍应另外验证，不能只凭金额正负导入。

## 可复现的电脑端查询脚本

[scripts/query-wechat-bills.py](../scripts/query-wechat-bills.py) 将上述实验证据做成只读开发工具。需要 Python 3、ADB 和 [websocket-client](https://websocket-client.readthedocs.io/en/latest/examples.html)；本机既有环境为 websocket-client 1.8.0。

```powershell
. .\.tools\env.ps1
python -m pip install websocket-client==1.8.0
python scripts/query-wechat-bills.py --serial PHONE_SERIAL --pages 2

# 纯虚构离线测试；JS 桥接测试需要本机 Node.js，不访问手机。
python -m unittest discover -s scripts -p test_query_wechat_bills.py -v
```

必须显式指定实验手机，脚本拒绝模拟器及报告为平板的设备。它从微信进程及 `/proc/net/unix` 找到调试 socket，仅创建自己的 `tcp:0` 临时转发，退出时只删除仍属于本次的映射，不影响已有转发。它不安装应用、不切换页面、不自动开关调试。ADB 的临时端口分配和转发命令见 [官方 ADB 手册](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md)。

只选择 HTTPS 下 `tenpay.wechatpay.cn` 或 `wx.tenpay.com` 的 `/userroll/readtemplate` 页面；多个候选时停止，要求只保留一个账单页。CDP 在当前页面闭包内完成查询和游标推进，不访问页面 store，也不返回会话值或游标。默认两页、每页 20 条，`--pages` 范围 1–10；没有新增记录、重复游标或已结束时停止。单次桥接设短超时；服务端错误或结构变化不导出半成品。

控制台仅打印页码、返回码、条数和固定提示，不打印账单内容、目标 URL、凭据或异常正文。导出文件写入已被 Git 忽略的 `.tools/wechat-query/`，使用带时间和随机后缀的新文件名保留旧结果。`--output` 可显式选择另一处本机目录，拒绝网络共享和映射网络盘；这些文件含个人账单，应保持在个人本机目录并排除版本控制。

JSON 保留上述已知账单字段的值和类型；不导出其他响应字段或未知字段，`icon_url` 去除查询串和片段。CSV 另加 `signed_fee_minor`：仅人民币整数分且 `positive`/`negtive` 时给出带符号值，其他情况留空。CSV 对可能成为电子表格公式的文本加前导单引号，原值以 JSON 为准；在表格软件中导入长交易编号时应选择文本列，避免精度损失。此工具当前只导出到电脑，还没有接入手机记账应用或实现手机独立后台运行。

脚本的 15 个离线测试已通过，覆盖分页、复合去重、凭据隔离、错误响应、CSV 金额边界与转发清理。随后该脚本在上述 S30 上实跑退出码为 0：两页均 `ret_code=0`、`count=20`、`added=20`。本机只检查导出元数据，确认 JSON 和 CSV 各 40 条、复合标识唯一 40 条、未包含鉴权字段。脚本的临时转发已清理，原有本机 TCP 1294 转发保留。该结果与先前直接 CDP 实验一致；长期运行和会话续期仍未验证。

## 已验证的浏览器调试入口与后续检查

APK 静态代码注册了 `https://debugxweb.qq.com/?inspector=true`，参数别名为 `enable_remote_debug`：

```text
d36.d1 命令注册/授权检查
→ s26.y 微信浏览器 URL 拦截及确认框
→ d36.z1 写入 bEnableRemoteDebug
→ com.tencent.xweb.a3.s 开启内核 remote-debugging / 系统 WebView 调试
```

普通模式下存在调试授权检查和 24 小时授权时间判断。`s26.y.f()` 中的确认框为“权限请求 / 是否打开微信浏览器调试能力？”，点击确定记录本地授权；该方法中没有刷脸或支付密码操作。另一个默认实现 `s26.e` 会直接拒绝授权。上述代码解释静态机制；本次 S30 已实际启用调试开关，并通过转发发现账单 H5 调试目标，不再仅是代码线索。其他设备、微信版本和内核不据此保证可用。

最小可逆验证流程及当前进度：

1. **已完成开关验证**：只在实验手机的微信内启用上述调试入口，微信提供自己的授权确认流程。
2. **已连接账单目标**：固定该手机的 ADB 连接，将电脑临时端口转发到微信进程的 devtools socket，再通过 `/json/list` 选择账单页。参见 [Chrome 官方 WebView 调试说明](https://developer.chrome.com/docs/devtools/remote-debugging/webviews)；开启 USB 调试和开启 WebView 调试是不同条件。
3. **已验证当前会话查询和两页分页**：保持已登录账单页，直接调用固定的只读列表桥接；没有更新页面 store 或手动翻页。只记录脱敏的路径、参数名、结构和条数，不把会话票据、完整 URL、个人账单或截图写入 Git、日志或共享文档。
4. **尚待验证长期运行**：开发脚本已完成两页实机复测；后续分别验证页面关闭、微信重启、会话过期时的行为。当前成功不能推出“脱离微信独立后台调用”或“可每天自动续期”。
5. **结束时恢复，尚待检查**：在微信内部打开 `https://debugxweb.qq.com/?inspector=false`，检查调试目标是否关闭；只撤销本次为该手机建立的 ADB 转发。关闭参数的处理已静态确认，实际恢复效果仍须检查。

本轮不调用支付、删除记录或其他账务修改接口。没有改包、Root 操作、安装代理证书或读取微信私有存储；仅在当前页面中使用其已有会话参数。

## 本机证据位置

以下均位于已忽略的 `.tools/wechat-inspection/`，不随仓库发布：

- `query-strings.json`、`request-xrefs.json`：字符串与方法定位结果；定位线索再经选定类反编译核对。
- `selected/com.tencent.mm.plugin.webview.ui.tools.newjsapi.u2.java`、`selected/li0.m.java`、`selected/y85.w36.java`、`selected/com.tencent.mm.plugin.webview.ui.tools.newjsapi.t2.java`：H5 桥接请求与回包。
- `selected/zs3.k.java`、`selected/com.tencent.mm.plugin.order.ui.MallOrderRecordListUI.java`、`selected/com.tencent.mm.wallet_core.tenpay.model.o.java`、`selected/com.tencent.mm.wallet_core.model.z0.java`：原生命令 105 及网络封装。
- `selected/d36.d1.java`、`selected/d36.d1.simple.java`、`selected/d36.z1.java`、`selected/s26.y.java`、`selected/s26.e.java`、`selected/com.tencent.xweb.a3.java`：调试命令、授权与内核开关。简单反编译模式用于交叉核对控制流；反编译结果本身仍需运行验证。
- `page-script-11.js`：当前账单页引用的公共外链 bundle；`selected/public-module-dd32.js`、`selected/public-module-e5ec.js`、`selected/public-module-a5f2.js` 为列表请求、会话合并和根状态相关模块的本地定位摘取。

本页不包含真实账单金额、交易对方、设备序列号、账号信息或登录票据。
