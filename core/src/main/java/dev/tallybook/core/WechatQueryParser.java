package dev.tallybook.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The query script's raw list format, never a signed/confirmed transaction import. */
public final class WechatQueryParser {
    public static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 200;
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
            "bill_id", "trans_id", "title", "timestamp", "fee", "fee_type", "fee_attr",
            "current_state", "current_state_type", "bill_type", "icon_url", "out_trade_no"));

    private WechatQueryParser() { }

    /** Strict, bounded outer-envelope decoder shared with the app's private inbox. */
    public static JSONObject decodeDocument(String input) {
        try { return JsonInput.object(input, MAX_INPUT_BYTES); }
        catch (Exception rejected) { throw new IllegalArgumentException("查询 JSON 格式无效"); }
    }

    public static final class Result {
        public final List<Transaction> transactions;
        public final int totalRows, skippedRows, neutralRows, unsupportedRows, invalidRows, duplicateRows;
        private Result(List<Transaction> transactions, int total, int neutral, int unsupported, int invalid, int duplicate) {
            this.transactions = Collections.unmodifiableList(new ArrayList<>(transactions));
            totalRows = total; neutralRows = neutral; unsupportedRows = unsupported;
            invalidRows = invalid; duplicateRows = duplicate;
            skippedRows = neutral + unsupported + invalid + duplicate;
        }
    }

    public static Result parse(String input) {
        try {
            JSONObject document = decodeDocument(input);
            if (document.has("format") && !"wechat-bill-query-v1".equals(document.get("format")))
                throw new IllegalArgumentException("不支持的查询文件格式");
            Object payload = document.get("records");
            if (!(payload instanceof JSONArray)) throw new IllegalArgumentException("查询记录列表无效");
            JSONArray rows = (JSONArray) payload;
            if (rows.length() > MAX_ROWS) throw new IllegalArgumentException("查询记录超过本次上限");
            List<Transaction> transactions = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int neutral = 0, unsupported = 0, invalid = 0, duplicate = 0;
            for (int index = 0; index < rows.length(); index++) {
                try {
                    JSONObject row = rows.getJSONObject(index);
                    Set<String> keys = new HashSet<>();
                    row.keys().forEachRemaining(keys::add);
                    if (!FIELDS.containsAll(keys)) throw new IllegalArgumentException();
                    String direction = text(row, "fee_attr");
                    if ("neutral".equals(direction)) { neutral++; continue; }
                    if (!"CNY".equals(text(row, "fee_type"))
                            || (!"positive".equals(direction) && !"negtive".equals(direction))) {
                        unsupported++; continue;
                    }
                    long fee = integer(row, "fee");
                    if (fee <= 0 || fee > Transaction.MAX_ABS_AMOUNT_MINOR) throw new IllegalArgumentException();
                    long seconds = integer(row, "timestamp");
                    if (seconds < Transaction.MIN_OCCURRED_AT / 1000
                            || seconds >= Transaction.MAX_OCCURRED_AT / 1000) throw new IllegalArgumentException();
                    Transaction transaction = Transaction.query(text(row, "bill_id"), text(row, "trans_id"),
                            text(row, "title").trim(), row.has("current_state") ? text(row, "current_state").trim() : "",
                            "positive".equals(direction) ? fee : -fee, seconds * 1000);
                    if (!seen.add(transaction.id)) { duplicate++; continue; }
                    transactions.add(transaction);
                } catch (Exception rejected) {
                    invalid++; // Never include a row, counterpart, identifier or exception in diagnostics.
                }
            }
            return new Result(transactions, rows.length(), neutral, unsupported, invalid, duplicate);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("查询文件内容无效");
        }
    }

    private static String text(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException();
        return (String) value;
    }

    private static long integer(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }
}
