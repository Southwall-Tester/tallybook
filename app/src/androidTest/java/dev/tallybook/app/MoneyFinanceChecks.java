package dev.tallybook.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import dev.tallybook.core.MoneyFinance;
import dev.tallybook.core.Transaction;
import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;

/** Uses fictional data and resets both ledgers; refuse to run on physical devices. */
public final class MoneyFinanceChecks {
    private static int checks;
    private MoneyFinanceChecks() { }
    public static int run(Context context) throws Exception {
        if (!(Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone") || Build.MODEL.contains("Android SDK")))
            throw new IllegalStateException("Money finance checks require a disposable emulator");
        checks = 0;
        LocalDate today = LocalDate.now().minusDays(1);
        long now = today.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try (MoneyFinanceStore real = new MoneyFinanceStore(context,LedgerStore.WECHAT);
             MoneyFinanceStore demo = new MoneyFinanceStore(context,LedgerStore.DEMO);
             LedgerStore ledger = new LedgerStore(context)) {
            real.clearAll(); demo.clearAll();
            try {
                check(real.load().entries.isEmpty() && real.load().items.isEmpty(),"first use contains no fictional money");
                LocalDate future = LocalDate.now().plusDays(1);
                check(rejects(() -> real.addOpening("future-opening","daily",1,future,""))
                        && rejects(() -> real.transfer("future-transfer","daily","goose",1,future,"")),"future actual pot dates cannot create money");
                Transaction income = Transaction.manual("fiction-income","虚构实际收入","收入","",101,now);
                Transaction expense = Transaction.manual("fiction-expense","虚构实际支出","餐饮","",-10,now);
                Transaction unreviewed = new Transaction("fiction_review","待核对","退款中","","",30,now,true,"需要核对");
                ledger.insert(income,LedgerStore.WECHAT); ledger.insert(expense,LedgerStore.WECHAT); ledger.insert(unreviewed,LedgerStore.WECHAT);
                check(rejects(() -> real.allocateIncome("future-allocation",income.id,50,40,10,0,future,""))
                        && rejects(() -> real.coverExpense("future-cover",expense.id,"daily",future,"")),"future allocation and expense-cover dates are rejected");
                long futureTime = future.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                Transaction futureIncome = Transaction.manual("fiction-future","未来到账","收入","",100,futureTime);
                Transaction futureCost = Transaction.manual("fiction-future-cost","未来成本","支出","",-100,futureTime);
                ledger.insert(futureIncome,LedgerStore.WECHAT); ledger.insert(futureCost,LedgerStore.WECHAT);
                check(real.listEligibleIncome().size() == 1 && real.listEligibleExpenses().size() == 1
                        && rejects(() -> real.allocateIncome("future-linked",futureIncome.id,50,40,10,0,today,""))
                        && rejects(() -> real.coverExpense("future-linked-cost",futureCost.id,"daily",today,"")),"future imported ledger dates excluded in both picker and direct store paths");
                ledger.deleteManual(futureIncome.id,LedgerStore.WECHAT); ledger.deleteManual(futureCost.id,LedgerStore.WECHAT);
                check(real.listEligibleIncome().size() == 1 && real.listEligibleExpenses().size() == 1,"only confirmed direction-correct ledger rows are eligible");
                check(rejects(() -> real.allocateIncome("bad-review",unreviewed.id,50,40,10,0,today,"")),"pending records cannot be allocated through direct store call");
                check(rejects(() -> demo.allocateIncome("wrong-source",income.id,50,40,10,0,today,"")),"transaction IDs cannot cross ledger namespaces");
                real.allocateIncome("allocation",income.id,50,40,10,0,today,"");
                check(real.load().balance("goose") == 51 && real.load().balance("dream_0") == 40 && real.load().balance("daily") == 10,"allocation conserves every cent");
                check(real.listEligibleIncome().isEmpty(),"used income disappears from eligible list");
                check(rejects(() -> real.allocateIncome("allocation",income.id,50,40,10,0,today,""))
                        && rejects(() -> real.allocateIncome("another-allocation",income.id,50,40,10,0,today,"")) && real.load().entries.size() == 1,"retries and new IDs cannot allocate the same income twice");
                real.coverExpense("spend",expense.id,"daily",today,"");
                check(real.load().balance("daily") == 0 && real.listEligibleExpenses().isEmpty() && ledger.list(LedgerStore.WECHAT).size() == 3,"expense cover does not create a duplicate ledger expense");
                check(rejects(() -> real.coverExpense("another-spend",expense.id,"goose",today,"")),"expense cannot be covered twice from another pot");
                check(rejects(() -> real.deletePotEntry("allocation")) && real.load().entries.size() == 2,"undo rejects a negative historical pot and leaves state untouched");
                check(rejects(() -> ledger.deleteManual(income.id,LedgerStore.WECHAT)) && rejects(() -> ledger.clear(LedgerStore.WECHAT)),"ordinary delete and clear preserve linked ledger rows");
                check(rejects(() -> ledger.insert(Transaction.manual("fiction-income","改金额","收入","",102,now),LedgerStore.WECHAT)),"ordinary edits cannot change allocated money");
                real.deletePotEntry("spend"); real.deletePotEntry("allocation");
                check(real.load().balance("daily") == 0 && real.listEligibleIncome().size() == 1 && real.listEligibleExpenses().size() == 1,"undo in dependency order makes original rows reusable");
                int beforeOpening = ledger.list(LedgerStore.WECHAT).size();
                real.addOpening("opening","daily",100,today,"已有资金");
                real.transfer("move","daily","dream_2",25,today,"");
                check(real.load().balance("daily") == 75 && real.load().balance("dream_2") == 25 && beforeOpening == ledger.list(LedgerStore.WECHAT).size(),"opening and movement never masquerade as income");
                check(rejects(() -> real.transfer("overdraw","daily","goose",76,today,"")) && real.load().balance("daily") == 75,"overdraw rolls back state");
                demo.addOpening("opening","daily",999,today,"独立演示");
                check(real.load().balance("daily") == 75 && demo.load().balance("daily") == 999,"same operation ID remains isolated by source");

                MoneyFinance.Project project = project("project",false);
                real.saveProject(project);
                check(rejects(() -> real.saveFlow(new MoneyFinance.ProjectFlow("future-flow","project","income",1,future,"")))
                        && real.load().flows.isEmpty() && find(ledger,"finance_flow_future-flow",LedgerStore.WECHAT) == null,"future actual project flow leaves ledger and state unchanged");
                check(ledger.list(LedgerStore.WECHAT).size() == beforeOpening && real.load().projectNet("project") == 0,"expected project income creates no transaction");
                MoneyFinance.ProjectFlow received = new MoneyFinance.ProjectFlow("flow","project","income",7400,today,"实际收到");
                real.saveFlow(received);
                Transaction flowTx = find(ledger,"finance_flow_flow",LedgerStore.WECHAT);
                check(flowTx != null && flowTx.amountMinor == 7400 && real.load().projectNet("project") == 7400,"actual flow and ledger row persist together");
                real.saveFlow(new MoneyFinance.ProjectFlow("flow","project","income",7500,today.plusDays(1),"修正实际数额"));
                check(real.load().flows.size() == 1 && find(ledger,"finance_flow_flow",LedgerStore.WECHAT).id.equals(flowTx.id)
                        && find(ledger,"finance_flow_flow",LedgerStore.WECHAT).amountMinor == 7500,"flow edits update one stable ledger identity");
                real.saveFlow(new MoneyFinance.ProjectFlow("cost","project","cost",1500,today,"材料实际成本"));
                check(real.load().projectIncome("project") == 7500 && real.load().projectCost("project") == 1500 && real.load().projectNet("project") == 6000,"project net subtracts actual cost");
                check(rejects(() -> ledger.deleteManual(flowTx.id,LedgerStore.WECHAT)) && rejects(() -> real.deleteProject("project")),"project evidence protected from ordinary deletion");
                real.allocateIncome("project-allocation",flowTx.id,50,40,10,1,today,"");
                check(rejects(() -> real.deleteFlow("flow")) && rejects(() -> real.saveFlow(received)),"allocated project income cannot be changed or deleted");
                real.saveProject(project("project",true));
                check(rejects(() -> real.saveFlow(new MoneyFinance.ProjectFlow("later","project","income",1,today,""))),"archived projects require explicit restoration before new flows");
                real.saveProject(project("project",false)); real.deletePotEntry("project-allocation"); real.deleteFlow("flow");
                check(find(ledger,"finance_flow_flow",LedgerStore.WECHAT) == null && real.load().projectNet("project") == -1500,"flow deletion removes exactly its owned transaction atomically");
                demo.saveProject(project("project",false)); demo.saveFlow(new MoneyFinance.ProjectFlow("cost","project","cost",900,today,""));
                real.deleteFlow("cost"); real.deleteProject("project");
                check(demo.load().projectCost("project") == 900 && find(ledger,"finance_flow_cost",LedgerStore.DEMO).amountMinor == -900,"project edits and deletes remain source-isolated");

                real.saveItem(new MoneyFinance.Item("cash","cash","已有现金",1000,null,0,""));
                real.saveItem(new MoneyFinance.Item("asset","asset","物品估值",2000,null,0,""));
                real.saveItem(new MoneyFinance.Item("debt","debt","欠款",4000,today.plusDays(20),200,"按真实账单填写"));
                check(real.load().cashMinor == 1000 && real.load().assetMinor == 2000 && real.load().debtMinor == 4000 && real.load().netWorthMinor == -1000,"net worth and cash are separately derived snapshots");
                check(ledger.list(LedgerStore.WECHAT).size() == beforeOpening && real.load().balance("daily") == 75,"snapshot balances cannot create income or change pot balances");
                real.saveItem(new MoneyFinance.Item("debt","debt","已还后余额",3500,null,0,""));
                real.deleteItem("asset");
                check(real.load().items.size() == 2 && real.load().netWorthMinor == -2500,"snapshot edits and deletes roundtrip");

                real.saveProject(project("overflow",false));
                real.saveFlow(new MoneyFinance.ProjectFlow("max","overflow","income",MoneyFinance.MAX_AMOUNT_MINOR,today,""));
                int beforeFailedFlow = ledger.list(LedgerStore.WECHAT).size();
                check(rejects(() -> real.saveFlow(new MoneyFinance.ProjectFlow("too-much","overflow","income",1,today,""))),"invalid next state rejects overflowing project aggregate");
                check(find(ledger,"finance_flow_too-much",LedgerStore.WECHAT) == null && beforeFailedFlow == ledger.list(LedgerStore.WECHAT).size() && real.load().flows.size() == 1,"state validation failure rolls back already-issued ledger write");

                String valid = real.load().toJson();
                String corrupt = "{preserve-this-original}";
                ContentValues bad = new ContentValues(); bad.put("payload",corrupt);
                ledger.getWritableDatabase().update("finance_states",bad,"source = ?",new String[]{LedgerStore.WECHAT});
                check(rejects(() -> real.load()) && rejects(() -> real.addOpening("bad-write","daily",1,today,""))
                        && rejects(() -> ledger.clear(LedgerStore.WECHAT)),"corruption blocks load mutation and ledger reset");
                check(rawState(ledger.getReadableDatabase(),LedgerStore.WECHAT).equals(corrupt) && demo.load().projectCost("project") == 900,"corrupt payload is retained without harming other source");
                bad.put("payload",valid); ledger.getWritableDatabase().update("finance_states",bad,"source = ?",new String[]{LedgerStore.WECHAT});
                try (MoneyFinanceStore reopened = new MoneyFinanceStore(context,LedgerStore.WECHAT)) {
                    check(reopened.load().balance("dream_2") == 25 && reopened.load().debtMinor == 3500,"new store instance preserves complete state");
                }
                checkMigration(context,ledger,income);
            } finally { real.clearAll(); demo.clearAll(); }
        }
        return checks;
    }
    private static MoneyFinance.Project project(String id,boolean archived) { return new MoneyFinance.Project(id,"虚构帮助项目","需求","服务","能力",100000,null,"预计不算收到",archived); }
    private static Transaction find(LedgerStore ledger,String entryId,String source) {
        for (Transaction tx : ledger.list(source)) if (entryId.equals(tx.tradeId)) return tx; return null;
    }
    private static String rawState(SQLiteDatabase db,String source) {
        try (Cursor c = db.query("finance_states",new String[]{"payload"},"source = ?",new String[]{source},null,null,null)) { if (!c.moveToFirst()) return ""; return c.getString(0); }
    }
    private static void checkMigration(Context context,LedgerStore helper,Transaction existing) {
        File file = new File(context.getCacheDir(),"fiction-finance-v1-migration.db");
        SQLiteDatabase.deleteDatabase(file);
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file,null)) {
            db.execSQL("CREATE TABLE transactions (source TEXT NOT NULL, transaction_id TEXT NOT NULL, payload TEXT NOT NULL, occurred_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(source, transaction_id))");
            db.execSQL("CREATE INDEX transactions_time ON transactions(source, occurred_at DESC)");
            ContentValues values = new ContentValues(); values.put("source",LedgerStore.WECHAT); values.put("transaction_id",existing.id);
            values.put("payload",existing.toJson()); values.put("occurred_at",existing.occurredAt); values.put("updated_at",existing.occurredAt);
            db.insertOrThrow("transactions",null,values);
            helper.onUpgrade(db,1,2);
            try (Cursor c = db.rawQuery("SELECT payload FROM transactions",null)) {
                check(c.moveToFirst() && existing.toJson().equals(c.getString(0)) && !c.moveToNext(),"version one upgrade preserves exact existing ledger payload");
            }
            check(MoneyFinanceStore.readState(db,LedgerStore.WECHAT).entries.isEmpty(),"version one upgrade creates empty finance state without invented balances");
        } finally { SQLiteDatabase.deleteDatabase(file); }
    }
    private interface Operation { void run(); }
    private static boolean rejects(Operation operation) { try { operation.run(); return false; } catch (IllegalArgumentException | IllegalStateException expected) { return true; } }
    private static void check(boolean condition,String name) { if (!condition) throw new IllegalStateException("Money finance check failed: " + name); checks++; }
}
