package dev.tallybook.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import dev.tallybook.core.Transaction;

import java.util.ArrayList;
import java.util.List;

/** Keeps imported records separate from the deliberately fictional demo ledger. */
public final class LedgerStore extends SQLiteOpenHelper {
    public static final String WECHAT = "wechat";
    public static final String DEMO = "demo";

    public LedgerStore(Context context) {
        super(context.getApplicationContext(), "tallybook.db", null, 2);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transactions (source TEXT NOT NULL, transaction_id TEXT NOT NULL, "
                + "payload TEXT NOT NULL, occurred_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY(source, transaction_id))");
        db.execSQL("CREATE INDEX transactions_time ON transactions(source, occurred_at DESC)");
        createFinanceTable(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createFinanceTable(db);
    }

    private static void createFinanceTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE finance_states (source TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)");
    }

    /** Returns true only when a new ID is inserted; repeats update its latest known state. */
    public boolean insert(Transaction transaction, String source) {
        requireSource(source);
        if (transaction == null) throw new IllegalArgumentException("缺少交易记录");
        if (Transaction.QUERY_PROVIDER.equals(transaction.provider))
            throw new IllegalArgumentException("接口查询记录须通过专用入口保存");
        if (transaction.tradeId.startsWith("finance_flow_"))
            throw new IllegalArgumentException("项目流水请在收入项目中修改");
        ContentValues values = new ContentValues();
        values.put("source", source);
        values.put("transaction_id", transaction.id);
        values.put("payload", transaction.toJson());
        values.put("occurred_at", transaction.occurredAt);
        values.put("updated_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            MoneyFinanceStore.requireLedgerMutationAllowed(db, source, transaction.id);
            int updated = db.update("transactions", values,
                    "source = ? AND transaction_id = ?", new String[]{source, transaction.id});
            if (updated == 0) db.insertOrThrow("transactions", null, values);
            db.setTransactionSuccessful();
            return updated == 0;
        } finally {
            db.endTransaction();
        }
    }

    public List<Transaction> list(String source) {
        requireSource(source);
        List<Transaction> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("transactions", new String[]{"payload"},
                "source = ?", new String[]{source}, null, null, "occurred_at DESC, transaction_id ASC")) {
            while (cursor.moveToNext()) {
                // Payloads are normalized before insertion; reject corruption rather than guessing values.
                result.add(Transaction.fromJson(cursor.getString(0)));
            }
        }
        return result;
    }

    /** Query candidates never replace a previously reviewed transaction. */
    public boolean insertQueryIfAbsent(Transaction transaction) {
        if (transaction == null || !Transaction.QUERY_PROVIDER.equals(transaction.provider)
                || !transaction.reviewRequired) throw new IllegalArgumentException("仅接受待核对的接口查询记录");
        ContentValues values = new ContentValues();
        values.put("source", WECHAT);
        values.put("transaction_id", transaction.id);
        values.put("payload", transaction.toJson());
        values.put("occurred_at", transaction.occurredAt);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("transactions", null, values,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public boolean confirmQuery(String id) { return changeQuery(id, false); }
    public boolean deleteQuery(String id) { return changeQuery(id, true); }

    private boolean changeQuery(String id, boolean delete) {
        if (id == null || !id.matches("wechat_query:[a-f0-9]{64}")) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Transaction transaction;
            try (Cursor cursor = db.query("transactions", new String[]{"payload"},
                    "source = ? AND transaction_id = ?", new String[]{WECHAT, id}, null, null, null)) {
                if (!cursor.moveToFirst()) return false;
                transaction = Transaction.fromJson(cursor.getString(0));
                if (!Transaction.QUERY_PROVIDER.equals(transaction.provider) || !id.equals(transaction.id)) return false;
            }
            boolean changed;
            if (delete) {
                MoneyFinanceStore.requireLedgerMutationAllowed(db, WECHAT, id);
                changed = db.delete("transactions", "source = ? AND transaction_id = ?",
                        new String[]{WECHAT, id}) == 1;
            } else {
                if (!transaction.reviewRequired) return false;
                ContentValues values = new ContentValues();
                values.put("payload", transaction.confirmQuery().toJson());
                values.put("updated_at", System.currentTimeMillis());
                changed = db.update("transactions", values, "source = ? AND transaction_id = ?",
                        new String[]{WECHAT, id}) == 1;
            }
            db.setTransactionSuccessful();
            return changed;
        } finally { db.endTransaction(); }
    }

    public void clear(String source) {
        requireSource(source);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            dev.tallybook.core.MoneyFinance.State state = MoneyFinanceStore.readState(db, source);
            if (!state.flows.isEmpty() || state.entries.stream().anyMatch(entry -> !entry.transactionId.isEmpty()))
                throw new IllegalArgumentException("账本仍有钱罐或项目关联，请先在资金练习中撤销关联；清空不会删除练习记录");
            db.delete("transactions", "source = ?", new String[]{source});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Removes only a manually entered record, in exactly the selected ledger. */
    public boolean deleteManual(String id, String source) {
        requireSource(source);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor cursor = db.query("transactions", new String[]{"payload"},
                    "source = ? AND transaction_id = ?", new String[]{source, id}, null, null, null)) {
                if (!cursor.moveToFirst()) return false;
                if (!"manual".equals(Transaction.fromJson(cursor.getString(0)).provider)) return false;
            }
            MoneyFinanceStore.requireLedgerMutationAllowed(db, source, id);
            boolean deleted = db.delete("transactions", "source = ? AND transaction_id = ?",
                    new String[]{source, id}) == 1;
            db.setTransactionSuccessful();
            return deleted;
        } finally {
            db.endTransaction();
        }
    }

    private static void requireSource(String source) {
        if (!WECHAT.equals(source) && !DEMO.equals(source)) {
            throw new IllegalArgumentException("未知账本来源");
        }
    }
}
