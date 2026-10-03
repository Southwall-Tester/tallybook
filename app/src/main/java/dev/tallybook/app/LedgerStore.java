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
        super(context.getApplicationContext(), "tallybook.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transactions (source TEXT NOT NULL, transaction_id TEXT NOT NULL, "
                + "payload TEXT NOT NULL, occurred_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY(source, transaction_id))");
        db.execSQL("CREATE INDEX transactions_time ON transactions(source, occurred_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Database migration required");
    }

    /** Returns true only when a new ID is inserted; repeats update its latest known state. */
    public boolean insert(Transaction transaction, String source) {
        requireSource(source);
        if (transaction == null) throw new IllegalArgumentException("缺少交易记录");
        ContentValues values = new ContentValues();
        values.put("source", source);
        values.put("transaction_id", transaction.id);
        values.put("payload", transaction.toJson());
        values.put("occurred_at", transaction.occurredAt);
        values.put("updated_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
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

    public void clear(String source) {
        requireSource(source);
        getWritableDatabase().delete("transactions", "source = ?", new String[]{source});
    }

    private static void requireSource(String source) {
        if (!WECHAT.equals(source) && !DEMO.equals(source)) {
            throw new IllegalArgumentException("未知账本来源");
        }
    }
}
