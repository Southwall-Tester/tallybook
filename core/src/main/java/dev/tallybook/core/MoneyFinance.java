package dev.tallybook.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Purpose envelopes and personal project records. An envelope movement is never an income. */
public final class MoneyFinance {
    public static final int SCHEMA_VERSION = 1;
    // Kept below Android's usual CursorWindow row limit: a valid write must remain readable.
    public static final int MAX_STATE_BYTES = 1024 * 1024;
    public static final int MAX_RECORDS = 10000;
    public static final long MAX_AMOUNT_MINOR = Transaction.MAX_ABS_AMOUNT_MINOR;
    private MoneyFinance() { }

    public static final class PotEntry {
        public final String id, kind, transactionId, fromPot, toPot, note;
        public final long amountMinor, gooseMinor, dreamMinor, dailyMinor;
        public final LocalDate date;
        public PotEntry(String id, String kind, String transactionId, String fromPot, String toPot,
                        long amountMinor, long gooseMinor, long dreamMinor, long dailyMinor, LocalDate date, String note) {
            this.id = key(id); this.kind = text(kind, 20, false); this.date = day(date);
            this.note = text(note, 1000, true); this.transactionId = text(transactionId, 100, true);
            this.fromPot = text(fromPot, 20, true); this.toPot = text(toPot, 20, true);
            this.amountMinor = amount(amountMinor, false); this.gooseMinor = amount(gooseMinor, true);
            this.dreamMinor = amount(dreamMinor, true); this.dailyMinor = amount(dailyMinor, true);
            boolean allocated = "allocation".equals(kind);
            if (allocated) {
                if (!fromPot.isEmpty() || !toPot.matches("dream_[0-9]") || !validTransactionId(transactionId)
                        || sum(sum(gooseMinor, dreamMinor), dailyMinor) != amountMinor) throw invalid("收入分配记录无效");
            } else {
                if (gooseMinor != 0 || dreamMinor != 0 || dailyMinor != 0) throw invalid("非分配记录不能包含分配金额");
                if ("opening".equals(kind)) {
                    pot(toPot); if (!fromPot.isEmpty() || !transactionId.isEmpty()) throw invalid("期初资金不能冒充收入");
                } else if ("transfer".equals(kind)) {
                    pot(fromPot); pot(toPot);
                    if (fromPot.equals(toPot) || !transactionId.isEmpty()) throw invalid("请选择两个不同用途的钱罐");
                } else if ("spend".equals(kind)) {
                    pot(fromPot); if (!toPot.isEmpty() || !validTransactionId(transactionId)) throw invalid("支出核销记录无效");
                } else throw invalid("未知钱罐操作");
            }
        }
    }

    public static final class Project {
        public final String id, title, need, offer, resources, note;
        public final long expectedMinor;
        public final LocalDate dueDate;
        public final boolean archived;
        public Project(String id, String title, String need, String offer, String resources,
                       long expectedMinor, LocalDate dueDate, String note, boolean archived) {
            this.id = key(id); this.title = text(title, 512, false); this.need = text(need, 2000, true);
            this.offer = text(offer, 2000, true); this.resources = text(resources, 2000, true);
            this.expectedMinor = amount(expectedMinor, true); this.dueDate = optionalDay(dueDate);
            this.note = text(note, 2000, true); this.archived = archived;
        }
    }

    public static final class ProjectFlow {
        public final String id, projectId, kind, note;
        public final long amountMinor;
        public final LocalDate date;
        public ProjectFlow(String id, String projectId, String kind, long amountMinor, LocalDate date, String note) {
            this.id = key(id); this.projectId = key(projectId);
            if (!"income".equals(kind) && !"cost".equals(kind)) throw invalid("请选择实际收入或实际成本");
            this.kind = kind; this.amountMinor = amount(amountMinor, false); this.date = day(date);
            this.note = text(note, 1000, true);
        }
        public String ledgerEntryId() { return "finance_flow_" + id; }
    }

    public static final class Item {
        public final String id, kind, title, note;
        public final long amountMinor, minPaymentMinor;
        public final LocalDate dueDate;
        public Item(String id, String kind, String title, long amountMinor, LocalDate dueDate, long minPaymentMinor, String note) {
            this.id = key(id); this.title = text(title, 512, false);
            if (!"cash".equals(kind) && !"asset".equals(kind) && !"debt".equals(kind)) throw invalid("未知资产或债务类型");
            this.kind = kind; this.amountMinor = amount(amountMinor, true); this.minPaymentMinor = amount(minPaymentMinor, true);
            this.dueDate = optionalDay(dueDate); this.note = text(note, 2000, true);
            if (!"debt".equals(kind) && (dueDate != null || minPaymentMinor != 0)) throw invalid("只有债务需要填写还款资料");
            if (minPaymentMinor > amountMinor) throw invalid("本期需还金额不能超过当前债务余额");
        }
    }

    public static final class State {
        public final List<PotEntry> entries;
        public final List<Project> projects;
        public final List<ProjectFlow> flows;
        public final List<Item> items;
        public final Map<String, Long> balances;
        public final long cashMinor, assetMinor, debtMinor, netWorthMinor;
        public State(List<PotEntry> entries, List<Project> projects, List<ProjectFlow> flows, List<Item> items) {
            this.entries = copy(entries, e -> e.id); this.projects = copy(projects, p -> p.id);
            this.flows = copy(flows, f -> f.id); this.items = copy(items, i -> i.id);
            Set<String> projectIds = new HashSet<>();
            for (Project project : this.projects) projectIds.add(project.id);
            Map<String, Long> income = new LinkedHashMap<>(), costs = new LinkedHashMap<>();
            for (ProjectFlow flow : this.flows) {
                if (!projectIds.contains(flow.projectId)) throw invalid("项目流水缺少所属项目");
                Map<String, Long> totals = "income".equals(flow.kind) ? income : costs;
                totals.put(flow.projectId, sum(totals.getOrDefault(flow.projectId, 0L), flow.amountMinor));
            }
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("goose", 0L); values.put("daily", 0L);
            for (int i = 0; i < 10; i++) values.put("dream_" + i, 0L);
            Set<String> usedTransactions = new HashSet<>();
            for (PotEntry entry : this.entries) {
                if (!entry.transactionId.isEmpty() && !usedTransactions.add(entry.transactionId)) throw invalid("这笔账单已经用于钱罐，不能重复使用");
                if ("opening".equals(entry.kind)) credit(values, entry.toPot, entry.amountMinor);
                else if ("allocation".equals(entry.kind)) {
                    credit(values, "goose", entry.gooseMinor); credit(values, "daily", entry.dailyMinor);
                    credit(values, entry.toPot, entry.dreamMinor);
                } else if ("transfer".equals(entry.kind)) {
                    debit(values, entry.fromPot, entry.amountMinor); credit(values, entry.toPot, entry.amountMinor);
                } else debit(values, entry.fromPot, entry.amountMinor);
            }
            balances = Collections.unmodifiableMap(values);
            long cash = 0, asset = 0, debt = 0;
            for (Item item : this.items) {
                if ("cash".equals(item.kind)) cash = sum(cash, item.amountMinor);
                else if ("asset".equals(item.kind)) asset = sum(asset, item.amountMinor);
                else debt = sum(debt, item.amountMinor);
            }
            cashMinor = cash; assetMinor = asset; debtMinor = debt;
            netWorthMinor = sum(cash, asset) - debt;
        }
        public long balance(String pot) { return balances.get(MoneyFinance.pot(pot)); }
        public long projectIncome(String id) { return projectTotal(id, "income"); }
        public long projectCost(String id) { return projectTotal(id, "cost"); }
        public long projectNet(String id) { return projectIncome(id) - projectCost(id); }
        private long projectTotal(String id, String kind) {
            key(id); long total = 0;
            for (ProjectFlow flow : flows) if (flow.projectId.equals(id) && flow.kind.equals(kind)) total = sum(total, flow.amountMinor);
            return total;
        }
        public boolean usesTransaction(String id) {
            for (PotEntry e : entries) if (e.transactionId.equals(id)) return true;
            return false;
        }
        public static State empty() { return new State(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList()); }
        public String toJson() { return MoneyFinanceJson.write(this); }
        public static State fromJson(String json) { return MoneyFinanceJson.read(json); }
    }

    public static String key(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) throw invalid("记录编号无效");
        return id;
    }
    public static String pot(String value) {
        if (!"goose".equals(value) && !"daily".equals(value) && (value == null || !value.matches("dream_[0-9]"))) throw invalid("请选择有效钱罐");
        return value;
    }
    private static boolean validTransactionId(String value) { return value.matches("(manual|wechat|wechat_query):[a-f0-9]{64}"); }
    private static long amount(long value, boolean allowZero) {
        if (value < (allowZero ? 0 : 1) || value > MAX_AMOUNT_MINOR) throw invalid("金额超出范围，请使用大于零的有效金额");
        return value;
    }
    private static long sum(long first, long second) {
        try { return amount(Math.addExact(first, second), true); }
        catch (ArithmeticException failure) { throw invalid("金额合计超出范围"); }
    }
    private static void credit(Map<String,Long> balances, String pot, long value) { balances.put(pot, sum(balances.get(pot), value)); }
    private static void debit(Map<String,Long> balances, String pot, long value) {
        long result = balances.get(pot) - value;
        if (result < 0) throw invalid("钱罐余额不足；如在撤销，请先撤销依赖这笔资金的后续操作");
        balances.put(pot, result);
    }
    private static LocalDate optionalDay(LocalDate date) { return date == null ? null : day(date); }
    public static LocalDate day(LocalDate date) {
        if (date == null || date.isBefore(LocalDate.of(2000, 1, 1)) || !date.isBefore(LocalDate.of(2100, 1, 1))) throw invalid("日期须在2000至2099年之间");
        return date;
    }
    private static String text(String value, int max, boolean blank) {
        if (value == null || value.length() > max || (!blank && value.trim().isEmpty())) throw invalid("文字为空或过长");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)) && value.charAt(i) != '\n' && value.charAt(i) != '\r' && value.charAt(i) != '\t') throw invalid("文字包含无效控制字符");
        return value;
    }
    private static <T> List<T> copy(List<T> list, Function<T,String> id) {
        if (list == null || list.size() > MAX_RECORDS) throw invalid("记录数量超出范围");
        List<T> result = new ArrayList<>(list); Set<String> ids = new HashSet<>();
        for (T item : result) if (item == null || !ids.add(id.apply(item))) throw invalid("记录为空或编号重复");
        return Collections.unmodifiableList(result);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
