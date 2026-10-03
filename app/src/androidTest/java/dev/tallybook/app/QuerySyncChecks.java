package dev.tallybook.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import dev.tallybook.core.Transaction;

/** Fictional-only helper; invoked by the disposable-emulator instrumentation runner. */
public final class QuerySyncChecks {
    private static int checks;

    public static int run(Context context) throws Exception {
        checks = 0;
        QuerySyncStore sync = new QuerySyncStore(context);
        sync.cancelRequest();
        Transaction candidate = Transaction.query("fiction-sync-bill", "fiction-sync-trade", "虚构午餐",
                "支付成功", -1200, 1_760_000_000_000L);
        try (LedgerStore store = new LedgerStore(context)) {
            int before = store.list(LedgerStore.WECHAT).size();
            int demoBefore = store.list(LedgerStore.DEMO).size();
            QuerySyncStore.Request request = sync.beginRequest(2);
            check(sync.state().pending && request.expiresAt > System.currentTimeMillis(), "request scope");
            JSONObject normal = row("fiction-sync-bill");
            JSONArray records = new JSONArray().put(normal).put(row("fiction-neutral").put("fee_attr", "neutral"));
            write(context, envelope(request, records).put("requestId", "0".repeat(64)));
            QuerySyncStore.ConsumeResult wrongNonce = sync.consumeInbox();
            check(wrongNonce.consumed && wrongNonce.inserted == 0 && sync.state().pending, "wrong nonce rejected");
            write(context, envelope(request, records));
            QuerySyncStore.ConsumeResult first = sync.consumeInbox();
            check(first.inserted == 1 && first.totalRows == 2 && first.skippedRows == 1, "accurate import counts");
            check(!sync.state().pending && store.list(LedgerStore.WECHAT).size() == before + 1, "request consumed once");
            check(store.list(LedgerStore.DEMO).size() == demoBefore, "real demo isolation");
            check(store.confirmQuery(candidate.id), "confirm query");
            check(!store.confirmQuery(candidate.id), "repeat confirmation idempotent");
            check(!store.insertQueryIfAbsent(candidate), "repeat query preserves confirmation");
            Transaction restored = store.list(LedgerStore.WECHAT).stream().filter(t -> t.id.equals(candidate.id)).findFirst().get();
            check(!restored.reviewRequired && restored.id.equals(candidate.id), "stable confirmed identity");
            boolean genericRejected = false;
            try { store.insert(candidate, LedgerStore.DEMO); } catch (IllegalArgumentException expected) { genericRejected = true; }
            check(genericRejected, "generic insert cannot inject query provider");
            boolean confirmedRejected = false;
            try { store.insertQueryIfAbsent(candidate.confirmQuery()); } catch (IllegalArgumentException expected) { confirmedRejected = true; }
            check(confirmedRejected, "preconfirmed import refused");
            check(!store.confirmQuery("manual:fiction") && !store.deleteQuery("wechat:fiction"), "wrong provider refusal");
            ContentValues demo = new ContentValues();
            demo.put("source", LedgerStore.DEMO); demo.put("transaction_id", candidate.id);
            demo.put("payload", candidate.toJson()); demo.put("occurred_at", candidate.occurredAt); demo.put("updated_at", 1L);
            store.getWritableDatabase().insertOrThrow("transactions", null, demo);
            check(store.deleteQuery(candidate.id), "delete only query from real ledger");
            check(store.list(LedgerStore.DEMO).stream().anyMatch(t -> t.id.equals(candidate.id) && t.reviewRequired), "demo shadow untouched");
            store.getWritableDatabase().delete("transactions", "source=? AND transaction_id=?",
                    new String[]{LedgerStore.DEMO, candidate.id});
            write(context, envelope(request, records));
            check(sync.consumeInbox().inserted == 0, "replayed consumed nonce rejected");
            request = sync.beginRequest(1);
            JSONObject expired = envelope(request, new JSONArray().put(normal));
            context.getSharedPreferences("query_sync", Context.MODE_PRIVATE).edit().putLong("expires_at", 1).commit();
            write(context, expired);
            check(sync.consumeInbox().inserted == 0 && !sync.state().pending && sync.state().lastMessage.contains("过期"), "expired request rejected");
            request = sync.beginRequest(1);
            write(context, envelope(request, new JSONArray()).put("status", "query_failed"));
            check(sync.consumeInbox().message.contains("未能完成") && !sync.state().pending, "safe failure reaches app");
            request = sync.beginRequest(1);
            sync.cancelRequest();
            write(context, envelope(request, new JSONArray().put(normal)));
            check(sync.consumeInbox().inserted == 0, "cancel revokes nonce");
            check(store.list(LedgerStore.WECHAT).size() == before, "fixtures cleaned up");
        } finally { sync.cancelRequest(); }
        return checks;
    }

    private static JSONObject row(String bill) throws Exception {
        return new JSONObject().put("bill_id", bill).put("trans_id", "fiction-sync-trade")
                .put("timestamp", 1_760_000_000L).put("fee", 1200).put("fee_type", "CNY")
                .put("fee_attr", "negtive").put("title", "虚构午餐").put("current_state", "支付成功");
    }
    private static JSONObject envelope(QuerySyncStore.Request request, JSONArray records) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("requestId", request.requestId)
                .put("createdAt", System.currentTimeMillis()).put("source", LedgerStore.WECHAT)
                .put("purpose", QuerySyncStore.PURPOSE).put("status", "ok").put("records", records);
    }
    private static void write(Context context, JSONObject envelope) throws Exception {
        File path = new File(context.getFilesDir(), "query-sync/inbox.json");
        try (FileOutputStream output = new FileOutputStream(path)) {
            output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("Query sync check failed: " + name);
        checks++;
    }
}
