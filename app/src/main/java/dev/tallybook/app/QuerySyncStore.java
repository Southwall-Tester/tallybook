package dev.tallybook.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import dev.tallybook.core.Transaction;
import dev.tallybook.core.WechatQueryParser;

/** Private, one-request USB inbox. Call all methods from a background executor. */
public final class QuerySyncStore {
    public static final long REQUEST_LIFETIME_MS = 5 * 60 * 1000L;
    public static final String PURPOSE = "wechat_query_v1";
    private static final Object LOCK = new Object();
    private static final String WAITING = "等待电脑查询。请切换到微信账单页，保持 USB 连接。";
    private final Context context;
    private final SharedPreferences preferences;
    private final File directory;

    public QuerySyncStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("query_sync", Context.MODE_PRIVATE);
        directory = new File(this.context.getFilesDir(), "query-sync");
    }

    public static final class Request {
        public final String requestId;
        public final long expiresAt;
        public final int pages;
        private Request(String id, long expires, int pages) {
            requestId = id; expiresAt = expires; this.pages = pages;
        }
    }

    public static final class State {
        public final boolean pending;
        public final long expiresAt;
        public final String lastMessage;
        private State(boolean pending, long expiresAt, String message) {
            this.pending = pending; this.expiresAt = expiresAt; lastMessage = message;
        }
    }

    public static final class ConsumeResult {
        public final boolean consumed;
        public final int inserted, duplicates, totalRows, skippedRows;
        public final String message;
        private ConsumeResult(boolean consumed, int inserted, int duplicates, int total, int skipped, String message) {
            this.consumed = consumed; this.inserted = inserted; this.duplicates = duplicates;
            totalRows = total; skippedRows = skipped; this.message = message;
        }
    }

    public Request beginRequest(int pages) {
        synchronized (LOCK) {
            if (!debuggable()) throw new IllegalStateException("当前版本不支持 USB 开发查询");
            if (pages < 1 || pages > 10) throw new IllegalArgumentException("查询页数应为 1 到 10");
            long issued = System.currentTimeMillis();
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            StringBuilder hex = new StringBuilder();
            for (byte value : random) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            String id = hex.toString();
            long expires = issued + REQUEST_LIFETIME_MS;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException();
                delete("inbox.json");
                JSONObject request = new JSONObject().put("schemaVersion", 1).put("requestId", id)
                        .put("issuedAt", issued).put("expiresAt", expires).put("pages", pages)
                        .put("source", LedgerStore.WECHAT).put("purpose", PURPOSE);
                if (!preferences.edit().putString("request_id", id).putLong("issued_at", issued)
                        .putLong("expires_at", expires).putInt("pages", pages).putString("message", WAITING).commit())
                    throw new IllegalStateException();
                writeRequest(request.toString());
                return new Request(id, expires, pages);
            } catch (Exception error) {
                clearAuthorization("无法创建查询请求，请稍后重试。");
                throw new IllegalStateException("无法创建查询请求，请稍后重试。");
            }
        }
    }

    public void cancelRequest() {
        synchronized (LOCK) { clearAuthorization("已取消本次查询。"); }
    }

    public State state() {
        synchronized (LOCK) {
            expireIfNeeded();
            return new State(!preferences.getString("request_id", "").isEmpty(),
                    preferences.getLong("expires_at", 0), preferences.getString("message", "尚未开始查询。"));
        }
    }

    public ConsumeResult consumeInbox() {
        synchronized (LOCK) {
            expireIfNeeded();
            File inbox = new File(directory, "inbox.json");
            if (!inbox.isFile()) return result(false, "");
            String currentId = preferences.getString("request_id", "");
            if (!debuggable() || currentId.isEmpty()) {
                delete("inbox.json");
                return result(true, "已忽略过期或未授权的查询结果。");
            }
            try {
                JSONObject envelope = WechatQueryParser.decodeDocument(readBounded(inbox, WechatQueryParser.MAX_INPUT_BYTES));
                Set<String> expected = new HashSet<>(Arrays.asList(
                        "schemaVersion", "requestId", "createdAt", "source", "purpose", "records", "status"));
                Set<String> keys = new HashSet<>();
                envelope.keys().forEachRemaining(keys::add);
                long now = System.currentTimeMillis();
                if (!keys.equals(expected) || integer(envelope, "schemaVersion") != 1
                        || !currentId.equals(envelope.get("requestId"))
                        || !LedgerStore.WECHAT.equals(envelope.get("source"))
                        || !PURPOSE.equals(envelope.get("purpose"))
                        || integer(envelope, "createdAt") < preferences.getLong("issued_at", 0) - 30_000
                        || integer(envelope, "createdAt") > now + 30_000
                        || integer(envelope, "createdAt") > preferences.getLong("expires_at", 0)) {
                    delete("inbox.json");
                    return result(true, "已忽略不属于本次查询的结果，继续等待电脑。");
                }
                Object value = envelope.get("records");
                if (!(value instanceof JSONArray) || ((JSONArray) value).length() > preferences.getInt("pages", 0) * 20)
                    throw new IllegalArgumentException();
                if ("query_failed".equals(envelope.get("status")) && ((JSONArray) value).length() == 0) {
                    String message = "电脑未能完成查询。请重新打开微信账单并检查连接，再发起查询。";
                    clearAuthorization(message);
                    return result(true, message);
                }
                if (!"ok".equals(envelope.get("status"))) throw new IllegalArgumentException();
                WechatQueryParser.Result parsed = WechatQueryParser.parse(
                        new JSONObject().put("records", value).toString());
                if (System.currentTimeMillis() >= preferences.getLong("expires_at", 0)) {
                    clearAuthorization("本次查询已过期，请重新发起查询。");
                    return result(true, "本次查询已过期，请重新发起查询。");
                }
                int inserted = 0;
                try (LedgerStore store = new LedgerStore(context)) {
                    SQLiteDatabase database = store.getWritableDatabase();
                    database.beginTransaction();
                    try {
                        for (Transaction transaction : parsed.transactions)
                            if (store.insertQueryIfAbsent(transaction)) inserted++;
                        database.setTransactionSuccessful();
                    } finally { database.endTransaction(); }
                }
                int duplicates = parsed.duplicateRows + parsed.transactions.size() - inserted;
                int skipped = parsed.neutralRows + parsed.unsupportedRows + parsed.invalidRows;
                String message = "读取 " + parsed.totalRows + " 条：新增 " + inserted + " 笔待核对，重复 "
                        + duplicates + " 笔，跳过 " + skipped + " 笔（不计收支 " + parsed.neutralRows
                        + "，不支持 " + parsed.unsupportedRows + "，无效 " + parsed.invalidRows + "）。";
                clearAuthorization(message);
                return new ConsumeResult(true, inserted, duplicates, parsed.totalRows, skipped, message);
            } catch (Exception error) {
                clearAuthorization("查询结果无法验证，本次没有新增记录。请重新发起查询。");
                return result(true, preferences.getString("message", "查询结果无法验证。"));
            }
        }
    }

    private ConsumeResult result(boolean consumed, String message) {
        if (consumed && !message.isEmpty()) preferences.edit().putString("message", message).commit();
        return new ConsumeResult(consumed, 0, 0, 0, 0, message);
    }

    private void expireIfNeeded() {
        if (!preferences.getString("request_id", "").isEmpty()
                && (System.currentTimeMillis() >= preferences.getLong("expires_at", 0)
                    || System.currentTimeMillis() < preferences.getLong("issued_at", 0) - 30_000))
            clearAuthorization("本次查询已过期。请重新点击开始查询，再打开微信账单。");
    }

    private void clearAuthorization(String message) {
        preferences.edit().remove("request_id").remove("issued_at").remove("expires_at").remove("pages")
                .putString("message", message).commit();
        delete("request.json");
        delete("inbox.json");
        delete("inbox.tmp");
    }

    private boolean debuggable() {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void delete(String name) {
        new AtomicFile(new File(directory, name)).delete();
    }

    private void writeRequest(String source) throws Exception {
        AtomicFile target = new AtomicFile(new File(directory, "request.json"));
        FileOutputStream output = null;
        try {
            output = target.startWrite();
            output.write(source.getBytes(StandardCharsets.UTF_8));
            target.finishWrite(output);
        } catch (Exception error) {
            if (output != null) target.failWrite(output);
            throw error;
        }
    }

    private static String readBounded(File file, int limit) throws Exception {
        if (file.length() > limit) throw new IllegalArgumentException();
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (output.size() + length > limit) throw new IllegalArgumentException();
                output.write(buffer, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static long integer(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }
}
