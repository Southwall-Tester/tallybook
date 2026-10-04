package dev.tallybook.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.tallybook.core.MoneyCoach;
import dev.tallybook.core.MoneyFinance;
import dev.tallybook.core.Transaction;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** One SQLite transaction covers both purpose envelopes/project state and actual ledger rows. */
public final class MoneyFinanceStore implements AutoCloseable {
    private final LedgerStore ledger;
    private final String source;
    public MoneyFinanceStore(Context context, String source) {
        if (!LedgerStore.WECHAT.equals(source) && !LedgerStore.DEMO.equals(source)) throw new IllegalArgumentException("未知资金账本来源");
        this.source = source; ledger = new LedgerStore(context);
    }
    @Override public void close() { ledger.close(); }
    public MoneyFinance.State load() {
        SQLiteDatabase db = ledger.getReadableDatabase(); db.beginTransaction();
        try { MoneyFinance.State state = readState(db, source); verifyLinks(db, source, state); db.setTransactionSuccessful(); return state; }
        finally { db.endTransaction(); }
    }
    public List<Transaction> listEligibleIncome() { return eligible(true); }
    public List<Transaction> listEligibleExpenses() { return eligible(false); }
    private List<Transaction> eligible(boolean income) {
        SQLiteDatabase db = ledger.getReadableDatabase(); db.beginTransaction();
        try {
            MoneyFinance.State state = readState(db, source); verifyLinks(db, source, state);
            List<Transaction> result = new ArrayList<>();
            try (Cursor c = db.query("transactions", new String[]{"payload"}, "source = ?", new String[]{source}, null, null, "occurred_at DESC, transaction_id ASC")) {
                while (c.moveToNext()) {
                    Transaction tx = Transaction.fromJson(c.getString(0));
                    if (!tx.reviewRequired && !transactionDay(tx).isAfter(LocalDate.now())
                            && ((tx.amountMinor > 0) == income) && !state.usesTransaction(tx.id)) result.add(tx);
                }
            }
            db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }
    public void addOpening(String id, String pot, long amount, LocalDate date, String note) {
        actualDate(date);
        MoneyFinance.PotEntry entry = new MoneyFinance.PotEntry(id,"opening","","",pot,amount,0,0,0,date,note);
        mutate((db,draft) -> addEntry(draft,entry));
    }
    public void allocateIncome(String id, String transactionId, int goose, int dream, int daily, int dreamSlot, LocalDate date, String note) {
        actualDate(date);
        MoneyCoach.allocate(0,goose,dream,daily);
        if (dreamSlot < 0 || dreamSlot > 9) throw new IllegalArgumentException("请选择有效梦想钱罐");
        mutate((db,draft) -> {
            Transaction tx = requiredTransaction(db,source,transactionId);
            actualDate(transactionDay(tx));
            if (tx.reviewRequired || tx.amountMinor <= 0) throw new IllegalArgumentException("只能分配本账本已确认的正收入");
            MoneyCoach.Allocation split = MoneyCoach.allocate(tx.amountMinor,goose,dream,daily);
            addEntry(draft,new MoneyFinance.PotEntry(id,"allocation",tx.id,"","dream_" + dreamSlot,
                    tx.amountMinor,split.gooseMinor,split.dreamMinor,split.dailyMinor,date,note));
        });
    }
    public void transfer(String id, String fromPot, String toPot, long amount, LocalDate date, String note) {
        actualDate(date);
        MoneyFinance.PotEntry entry = new MoneyFinance.PotEntry(id,"transfer","",fromPot,toPot,amount,0,0,0,date,note);
        mutate((db,draft) -> addEntry(draft,entry));
    }
    public void coverExpense(String id, String transactionId, String pot, LocalDate date, String note) {
        actualDate(date);
        mutate((db,draft) -> {
            Transaction tx = requiredTransaction(db,source,transactionId);
            actualDate(transactionDay(tx));
            if (tx.reviewRequired || tx.amountMinor >= 0) throw new IllegalArgumentException("只能核销本账本已确认的支出");
            addEntry(draft,new MoneyFinance.PotEntry(id,"spend",tx.id,pot,"",-tx.amountMinor,0,0,0,date,note));
        });
    }
    public void deletePotEntry(String id) {
        MoneyFinance.key(id); mutate((db,draft) -> draft.entries.removeIf(entry -> entry.id.equals(id)));
    }
    public void saveProject(MoneyFinance.Project project) {
        require(project); mutate((db,draft) -> upsert(draft.projects,project,p -> p.id));
    }
    public void deleteProject(String id) {
        MoneyFinance.key(id);
        mutate((db,draft) -> {
            for (MoneyFinance.ProjectFlow flow : draft.flows) if (flow.projectId.equals(id)) throw new IllegalArgumentException("已有实际流水的项目请归档，以保留账目依据");
            draft.projects.removeIf(project -> project.id.equals(id));
        });
    }
    public void saveFlow(MoneyFinance.ProjectFlow flow) {
        require(flow);
        actualDate(flow.date);
        mutate((db,draft) -> {
            MoneyFinance.Project project = null;
            for (MoneyFinance.Project p : draft.projects) if (p.id.equals(flow.projectId)) project = p;
            if (project == null) throw new IllegalArgumentException("项目已不存在，请返回项目列表");
            if (project.archived) throw new IllegalArgumentException("请先恢复项目，再记录或修改实际流水");
            MoneyFinance.ProjectFlow previous = null;
            for (MoneyFinance.ProjectFlow f : draft.flows) if (f.id.equals(flow.id)) previous = f;
            if (previous != null && !previous.projectId.equals(flow.projectId)) throw new IllegalArgumentException("流水不能改到其他项目");
            Transaction tx = flowTransaction(flow,project);
            if (draft.freeze().usesTransaction(tx.id)) throw new IllegalArgumentException("这笔项目流水已用于钱罐，请先撤销钱罐关联再修改");
            Transaction existing = findTransaction(db,source,tx.id);
            if (previous == null && existing != null) throw new IllegalArgumentException("流水编号已存在，请返回列表检查，避免重复入账");
            upsert(draft.flows,flow,f -> f.id);
            writeTransaction(db,source,tx);
        });
    }
    public void deleteFlow(String id) {
        MoneyFinance.key(id);
        mutate((db,draft) -> {
            MoneyFinance.ProjectFlow target = null;
            for (MoneyFinance.ProjectFlow flow : draft.flows) if (flow.id.equals(id)) target = flow;
            if (target == null) return;
            String txId = flowTransactionId(target);
            if (draft.freeze().usesTransaction(txId)) throw new IllegalArgumentException("这笔项目流水已用于钱罐，请先撤销钱罐关联再删除");
            db.delete("transactions","source = ? AND transaction_id = ?",new String[]{source,txId});
            draft.flows.removeIf(flow -> flow.id.equals(id));
        });
    }
    public void saveItem(MoneyFinance.Item item) { require(item); mutate((db,draft) -> upsert(draft.items,item,i -> i.id)); }
    public void deleteItem(String id) { MoneyFinance.key(id); mutate((db,draft) -> draft.items.removeIf(item -> item.id.equals(id))); }

    /** Explicit full reset only; ordinary ledger clear never calls this. Source isolation is preserved. */
    public void clearAll() {
        SQLiteDatabase db = ledger.getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("finance_states","source = ?",new String[]{source});
            db.delete("transactions","source = ?",new String[]{source});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private void mutate(Update update) {
        SQLiteDatabase db = ledger.getWritableDatabase(); db.beginTransaction();
        try {
            MoneyFinance.State previous = readState(db,source); verifyLinks(db,source,previous);
            Draft draft = new Draft(previous); update.apply(db,draft);
            MoneyFinance.State next = draft.freeze(); verifyLinks(db,source,next);
            ContentValues values = new ContentValues(); values.put("source",source); values.put("payload",next.toJson());
            if (db.insertWithOnConflict("finance_states",null,values,SQLiteDatabase.CONFLICT_REPLACE) == -1)
                throw new IllegalStateException("未能保存资金练习，原有记录保持不变");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    static MoneyFinance.State readState(SQLiteDatabase db,String source) {
        try (Cursor c = db.query("finance_states",new String[]{"payload"},"source = ?",new String[]{source},null,null,null)) {
            if (!c.moveToFirst()) return MoneyFinance.State.empty();
            try { return MoneyFinance.State.fromJson(c.getString(0)); }
            catch (RuntimeException failure) { throw new IllegalStateException("资金练习暂时无法读取，原始数据已保留，请勿清空或覆盖",failure); }
        }
    }
    static void requireLedgerMutationAllowed(SQLiteDatabase db,String source,String id) {
        MoneyFinance.State state = readState(db,source);
        if (state.usesTransaction(id)) throw new IllegalArgumentException("这笔账单已用于钱罐，请先撤销对应资金操作");
        for (MoneyFinance.ProjectFlow flow : state.flows) if (flowTransactionId(flow).equals(id)) throw new IllegalArgumentException("这笔账单来自收入项目，请到项目中修改或删除实际流水");
    }
    private static void verifyLinks(SQLiteDatabase db,String source,MoneyFinance.State state) {
        for (MoneyFinance.PotEntry entry : state.entries) {
            if (entry.transactionId.isEmpty()) continue;
            Transaction tx = requiredTransaction(db,source,entry.transactionId);
            long signed = "allocation".equals(entry.kind) ? entry.amountMinor : -entry.amountMinor;
            if (tx.reviewRequired || tx.amountMinor != signed) throw new IllegalStateException("钱罐关联的账单已变化，原始数据已保留，请先核对");
        }
        for (MoneyFinance.ProjectFlow flow : state.flows) {
            Transaction tx = requiredTransaction(db,source,flowTransactionId(flow));
            if (!Transaction.MANUAL_PROVIDER.equals(tx.provider) || tx.reviewRequired || !flow.ledgerEntryId().equals(tx.tradeId)
                    || tx.amountMinor != signedFlow(flow) || tx.occurredAt != flowTime(flow.date)) throw new IllegalStateException("项目与实际账本暂不一致，原始数据已保留，请勿覆盖");
        }
    }
    private static Transaction requiredTransaction(SQLiteDatabase db,String source,String id) {
        Transaction tx = findTransaction(db,source,id);
        if (tx == null) throw new IllegalArgumentException("本账本中未找到关联账单，请返回列表重新选择");
        return tx;
    }
    private static Transaction findTransaction(SQLiteDatabase db,String source,String id) {
        if (id == null) throw new IllegalArgumentException("缺少账单编号");
        try (Cursor c = db.query("transactions",new String[]{"payload"},"source = ? AND transaction_id = ?",new String[]{source,id},null,null,null)) {
            if (!c.moveToFirst()) return null;
            Transaction tx = Transaction.fromJson(c.getString(0));
            if (!tx.id.equals(id)) throw new IllegalStateException("账单编号与内容不一致，原始数据已保留");
            return tx;
        }
    }
    private static Transaction flowTransaction(MoneyFinance.ProjectFlow flow,MoneyFinance.Project project) {
        return Transaction.manual(flow.ledgerEntryId(),flat(project.title),"income".equals(flow.kind) ? "项目收入" : "项目成本",flat(flow.note),signedFlow(flow),flowTime(flow.date));
    }
    private static String flowTransactionId(MoneyFinance.ProjectFlow flow) {
        return Transaction.manual(flow.ledgerEntryId(),"项目流水","项目","",signedFlow(flow),flowTime(flow.date)).id;
    }
    private static long signedFlow(MoneyFinance.ProjectFlow flow) { return "income".equals(flow.kind) ? flow.amountMinor : -flow.amountMinor; }
    private static LocalDate transactionDay(Transaction tx) { return Instant.ofEpochMilli(tx.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate(); }
    private static void actualDate(LocalDate date) {
        MoneyFinance.day(date);
        if (date.isAfter(LocalDate.now())) throw new IllegalArgumentException("实际资金操作不能使用未来日期；预计收入请写在项目计划中");
    }
    // A project entry supplies a calendar date, not a payment timestamp. Use a stable domestic
    // midday so changing the phone timezone cannot invalidate the project/ledger relationship.
    private static long flowTime(LocalDate date) { return date.atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
    private static String flat(String value) { return value.replace('\n',' ').replace('\r',' ').replace('\t',' ').trim(); }
    private static void writeTransaction(SQLiteDatabase db,String source,Transaction tx) {
        ContentValues values = new ContentValues(); values.put("source",source); values.put("transaction_id",tx.id);
        values.put("payload",tx.toJson()); values.put("occurred_at",tx.occurredAt); values.put("updated_at",System.currentTimeMillis());
        if (db.insertWithOnConflict("transactions",null,values,SQLiteDatabase.CONFLICT_REPLACE) == -1) throw new IllegalStateException("未能保存实际流水");
    }
    private static void addEntry(Draft draft,MoneyFinance.PotEntry entry) {
        for (MoneyFinance.PotEntry old : draft.entries) if (old.id.equals(entry.id)) throw new IllegalArgumentException("这次操作已保存，请返回列表查看");
        draft.entries.add(entry);
    }
    private static <T> void upsert(List<T> list,T value,Function<T,String> key) {
        String id = key.apply(value);
        for (int n = 0; n < list.size(); n++) if (key.apply(list.get(n)).equals(id)) { list.set(n,value); return; }
        list.add(value);
    }
    private static void require(Object value) { if (value == null) throw new IllegalArgumentException("缺少要保存的内容"); }
    private interface Update { void apply(SQLiteDatabase db,Draft draft); }
    private static final class Draft {
        final List<MoneyFinance.PotEntry> entries;
        final List<MoneyFinance.Project> projects;
        final List<MoneyFinance.ProjectFlow> flows;
        final List<MoneyFinance.Item> items;
        Draft(MoneyFinance.State state) { entries = new ArrayList<>(state.entries); projects = new ArrayList<>(state.projects); flows = new ArrayList<>(state.flows); items = new ArrayList<>(state.items); }
        MoneyFinance.State freeze() { return new MoneyFinance.State(entries,projects,flows,items); }
    }
}
