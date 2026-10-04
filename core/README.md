# 账本与生活费规划核心

这是纯 Java 17 模块，无 Android 依赖。它提供本地单笔文件解析、手动交易、生活费预算、梦想与行动练习、钱罐/项目/资产盘点、月度回顾和离线学习计算，不负责访问微信、拉取列表或进行登录验证。微信详情结构参考来自 [AutoAccounting 的 WebViewHooker](https://github.com/AutoAccountingOrg/AutoAccounting/blob/master/app/src/main/java/net/ankio/auto/xposed/hooks/wechat/hooks/WebViewHooker.kt)，实现和测试数据独立编写。没有保存上游注释中的真实账户、交易号或签名。

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
- `Transaction.fromJson(String)` 只读取本模块的规范 JSON，格式不匹配抛出 `IllegalArgumentException`。它验证 `schemaVersion: 1`、`provider: "wechat"` 或 `"manual"`、完整且无多余字段、金额与时间范围、待核对标记、ID 一致性；旧 `wechat_query` schema 仍可读取以保留历史记录。
- 如需要构造记录，公开构造函数参数顺序为 `tradeId, counterparty, status, paymentMethod, description, amountMinor, occurredAt, reviewRequired, reviewReason`，ID 由构造函数生成。

## 手动记账

```java
Transaction record = Transaction.manual(
        UUID.randomUUID().toString(), "食堂午餐", "餐饮", "", -1250, occurredAt);
```

- 参数依次是 `entryId, title, category, note, signedAmount, occurredAt`。`provider` 为实例字段；手动交易的 `provider == Transaction.MANUAL_PROVIDER`，`status == Transaction.MANUAL_STATUS`（“手动记录”），`paymentMethod` 保存分类，`description` 保存备注。
- `entryId` 是 1 至 128 位字母、数字、下划线或短横线，通常使用 UUID。标题、分类非空；标题最多 1024 字符、分类 512 字符、备注 2048 字符。文本不得有首尾空格或控制字符。金额非零，时间与金额范围沿用微信规范记录的限制。
- 手动 ID 为 `manual:` 加 SHA-256，只由 provider 和 entryId 决定；同一 entryId 修改金额、名称、日期后仍是同一条记录。微信原有 ID 算法、JSON 字段和 schema 版本保持不变，现有微信账目无需迁移。
- 用户明确输入的手动收支不经过微信的“转账”等文字推断，也不标为待核对。手动记录与微信记录分属不同 ID 空间；同一笔消费如果既手记又导入，会统计两次。调用方应提示用户避免重复记账，并提供手动记录删除入口。

## 生活费预算

```java
BudgetPlan plan = new BudgetPlan(startDate, endInclusive,
        openingMinor, fixedReserveMinor, savingsReserveMinor);
BudgetEngine.Snapshot summary = BudgetEngine.summarize(
        plan, deduplicatedTransactions, today, ZoneId.systemDefault());
```

所有金额都是人民币分。计划日期包含首尾两天，长度为 1 至 366 天；三个计划金额均非负，单个金额不超过 `Transaction.MAX_ABS_AMOUNT_MINOR`。交易列表应为当前所选账本中按 ID 去重、已应用最新状态更新的记录；计算器不会再次去重。

只统计计划日期内、且交易日期不晚于 `today` 的记录。交易日期按传入的 `ZoneId` 从 Unix 毫秒转换；微信原始日期在解析时固定按上海时区解释，两者是不同步骤。待核对记录只增加 `pendingCount`，不进入收入或支出，包括已标为待核对的退款。

```text
remainingMinor = openingMinor + incomeMinor - expenseMinor
                 - fixedReserveMinor - savingsReserveMinor
dailyMinor = max(0, remainingMinor) / daysLeft
```

`incomeMinor`、`expenseMinor` 均是非负总额；`remainingMinor` 可为负数。`daysLeft` 包含今天，`dailyMinor` 向下取整到分。`today` 在计划期外时，`active == false`，`daysLeft` 与 `dailyMinor` 为 0；已发生的周期内收支仍保留在汇总中。所有加减采用 checked 运算，超出 long 范围抛 `ArithmeticException`，调用方应显示无法汇总而不是继续使用错误金额。

预算的使用假设必须展示给用户：

1. 期初金额是开始日交易记入前的可用金额；已包含的钱不能再次作为本期收入加入。
2. 固定预留是尚未支付的固定开销。支付并记为支出后，应相应调减预留，避免重复扣减。
3. 储蓄预留是本期从可花金额中留出的部分，存钱目标的已存金额单独维护，不会自动改动预算。自己的账户间转移不能再次记为新增收入；若已把存钱转出记成支出，也应核对预留以避免重复扣减。
4. 结果是已记录数据上的估算，不是银行余额；未采集的交易、待核对记录和跨来源重复记录会影响结果。

## 存钱目标

```java
SavingsGoal goal = new SavingsGoal(name, targetMinor, savedMinor, targetDate);
long remaining = goal.remainingMinor();
int percent = goal.progressPercent();
long dailyNeed = goal.dailyNeedMinor(today);
```

名称为 1 至 128 字符且无首尾空格或控制字符；目标金额必须大于 0，已存金额非负，两者均不超过单笔金额上限。已存金额可以超过目标。

`remainingMinor()` 最低为 0；`progressPercent()` 向下取整数并限制在 0 至 100；`dailyNeedMinor(today)` 包含今天与目标日，向上取整到分。目标已过期而尚未完成时返回全部剩余金额，调用方应明确显示“已过期”，不要误称为正常每日计划。完成或超额完成时，剩余金额和每日所需均为 0。

目标进度不会生成交易、发起转账或调整预算，也不会凭空预测目标完成日期。

## 已支持与明确拒绝的内容

支持完整 `javascript:WeixinJSBridge._handleMessageFromWeixin({...})` 回传（可带末尾分号）、其中的桥接 JSON，以及解出的单笔详情 JSON。桥接必须满足 `__json_message.__params.err_msg == "nativeWXPayCgiTunnel:ok"`，`respbuf` 为 JSON 字符串。详情含 `ret_code` 时必须为整数 0。

单笔详情需要 `header.nickname`、带正负号的 `header.fee` 和 `preview` 行。只按已识别的标签读字段，不推断未知列表或其他页面的 schema。金额最多两位小数，禁止浮点误差、零金额及无方向金额；缺失时间、多个不同时间或多个冲突单号均拒绝。范围限制为 2000 年至 2100 年前，绝对金额不超过 999999999999.99 元。

退款、失败、待支付、未知/缺失状态以及转账、充值、提现、还款等资金转移线索会标为待核对。`reviewRequired` 不会替用户判定最终收支或退款抵扣。

两个 JSON 入口都限制 256 KiB 和 32 层嵌套，拒绝重复字段名、单引号、裸字段名、NaN、注释、尾逗号及附加脚本。不会执行 JavaScript，也不在规范记录中保留 URL、跳转参数、签名或原始响应。

## 样例与验证

`src/main/resources/demo-wechat*.json` 是四笔明确标注为全虚构的演示记录：早餐支出、书店支出、闲置物品售出收入、退款待核对。`src/test/resources` 中的独立夹具也全部为虚构数据。

运行 `gradlew.bat :core:test`。测试覆盖解析严格性、旧 ID 与 JSON 兼容、整数金额守恒、预算与目标、真实日期边界、钱罐不可重复分配/核销及负余额保护、项目预期与实收隔离、JSON 损坏保护、月度复盘和假设学习计算。当前数量和实际结果见 [验证记录](../docs/VALIDATION.md)。

`MoneyFinance` 只建模用途和事实，项目实际流水与账本的原子写入由 Android 的 `MoneyFinanceStore` 负责。`LearningMath` 的追加、通胀、分散风险和消费债务演算不生成交易。当前模块不访问微信、银行或市场行情。
