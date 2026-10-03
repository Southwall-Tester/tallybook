# 微信账单详情解析核心

这是纯 Java 17 模块，无 Android 依赖。它解析已经取得的单笔详情文本，不负责访问微信、拉取列表或进行登录验证。结构参考来自 [AutoAccounting 的 WebViewHooker](https://github.com/AutoAccountingOrg/AutoAccounting/blob/master/app/src/main/java/net/ankio/auto/xposed/hooks/wechat/hooks/WebViewHooker.kt)，实现和测试数据独立编写。没有保存上游注释中的真实账户、交易号或签名。

## 调用约定

```java
ParseResult result = WechatBillParser.parse(text);
if (result.isSuccess()) {
    Transaction record = result.transaction;
    String normalized = record.toJson();
    Transaction restored = Transaction.fromJson(normalized);
}
```

- `ParseResult` 的 `transaction`、`code`、`message` 是 public final 字段。解析失败时 `transaction == null`；错误说明不带原始账单内容。
- `amountMinor` 是有符号的人民币分：负数为支出方向，正数为收入方向。不能仅据方向判断是否真实收入或消费，`reviewRequired` 记录必须排除在确认汇总之外。
- `occurredAt` 是 Unix 毫秒。原始秒/毫秒时间戳须有 `is_timestamp: true`。支持完整的 `yyyy-MM-dd HH:mm:ss` 和 `yyyy年MM月dd日 HH:mm:ss` 文本，明确按 `Asia/Shanghai` 解释，不使用手机当前时区或采集时间。
- `id` 是确定性的 `wechat:` 加 SHA-256。存在交易单号时仅以 provider 和该单号识别交易，状态更新不会产生新 ID；数据库应更新原记录，防止退款后继续累计原支出。缺少单号时对交易对象、方式、说明、金额、交易时间计算指纹，并强制待核对。此指纹仍可能合并真实的重复消费，不能视为完整去重证据。
- `Transaction.fromJson(String)` 只读取本模块的规范 JSON，格式不匹配抛出 `IllegalArgumentException`。它验证 `schemaVersion: 1`、`provider: "wechat"`、完整且无多余字段、金额与时间范围、待核对标记、ID 一致性。
- 如需要构造记录，公开构造函数参数顺序为 `tradeId, counterparty, status, paymentMethod, description, amountMinor, occurredAt, reviewRequired, reviewReason`，ID 由构造函数生成。

## 已支持与明确拒绝的内容

支持完整 `javascript:WeixinJSBridge._handleMessageFromWeixin({...})` 回传（可带末尾分号）、其中的桥接 JSON，以及解出的单笔详情 JSON。桥接必须满足 `__json_message.__params.err_msg == "nativeWXPayCgiTunnel:ok"`，`respbuf` 为 JSON 字符串。详情含 `ret_code` 时必须为整数 0。

单笔详情需要 `header.nickname`、带正负号的 `header.fee` 和 `preview` 行。只按已识别的标签读字段，不推断未知列表或其他页面的 schema。金额最多两位小数，禁止浮点误差、零金额及无方向金额；缺失时间、多个不同时间或多个冲突单号均拒绝。范围限制为 2000 年至 2100 年前，绝对金额不超过 999999999999.99 元。

退款、失败、待支付、未知/缺失状态以及转账、充值、提现、还款等资金转移线索会标为待核对。`reviewRequired` 不会替用户判定最终收支或退款抵扣。

两个 JSON 入口都限制 256 KiB 和 32 层嵌套，拒绝重复字段名、单引号、裸字段名、NaN、注释、尾逗号及附加脚本。不会执行 JavaScript，也不在规范记录中保留 URL、跳转参数、签名或原始响应。

## 样例与验证

`src/main/resources/demo-wechat*.json` 是四笔明确标注为全虚构的演示记录：早餐支出、书店支出、闲置物品售出收入、退款待核对。`src/test/resources` 中的独立夹具也全部为虚构数据。

运行 `gradlew.bat :core:test`。当前 28 项 JUnit 测试覆盖桥接转义、不同输入形式、身份去重、状态更新、精确金额、收入支出、退款/失败/待核对、无单号指纹、日期与时间歧义、未知结构、异常/超大/深嵌套 JSON，以及规范记录边界校验。

测试只验证本地解析与数据边界；尚不能证明当前微信版本会走该回传路径、真实手机能采集成功，或能补齐历史账单。
