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
        if (Transaction.SCREEN_PROVIDER.equals(transaction.provider)) {
            throw new IllegalArgumentException("屏幕识别记录须使用专用入口，避免覆盖已核对记录");
        }
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

    /** Inserts a pending screen candidate only in the real ledger; repeats never overwrite review decisions. */
    public boolean insertScreenIfAbsent(Transaction transaction) {
        if (transaction == null || !Transaction.SCREEN_PROVIDER.equals(transaction.provider)
                || !transaction.reviewRequired) {
            throw new IllegalArgumentException("只接受待核对的屏幕识别记录");
        }
        ContentValues values = new ContentValues();
        values.put("source", WECHAT);
        values.put("transaction_id", transaction.id);
        values.put("payload", transaction.toJson());
        values.put("occurred_at", transaction.occurredAt);
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("transactions", null, values,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    /** Confirms exactly one pending screen record in the real ledger. */
    public boolean confirmScreen(String id) {
        if (!isScreenId(id)) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Transaction transaction = findRealScreen(db, id);
            if (transaction == null || !transaction.reviewRequired) return false;
            ContentValues values = new ContentValues();
            values.put("payload", transaction.confirmScreen().toJson());
            values.put("updated_at", System.currentTimeMillis());
            boolean updated = db.update("transactions", values,
                    "source = ? AND transaction_id = ?", new String[]{WECHAT, id}) == 1;
            db.setTransactionSuccessful();
            return updated;
        } finally {
            db.endTransaction();
        }
    }

    /** Removes only a screen-derived record in the real ledger, including an incorrectly confirmed one. */
    public boolean deleteScreen(String id) {
        if (!isScreenId(id)) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (findRealScreen(db, id) == null) return false;
            boolean deleted = db.delete("transactions", "source = ? AND transaction_id = ?",
                    new String[]{WECHAT, id}) == 1;
            db.setTransactionSuccessful();
            return deleted;
        } finally {
            db.endTransaction();
        }
    }

    private static boolean isScreenId(String id) {
        return id != null && id.matches("wechat_screen:[0-9a-f]{64}");
    }

    private static Transaction findRealScreen(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.query("transactions", new String[]{"payload"},
                "source = ? AND transaction_id = ?", new String[]{WECHAT, id}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            Transaction transaction = Transaction.fromJson(cursor.getString(0));
            return Transaction.SCREEN_PROVIDER.equals(transaction.provider) && transaction.id.equals(id)
                    ? transaction : null;
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
