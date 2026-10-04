package dev.tallybook.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Monthly reflection is a product adaptation, stored separately from the original coach schema. */
public final class MonthlyReview {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public final YearMonth month;
    public final String facts, learning, nextStep;

    public MonthlyReview(YearMonth month, String facts, String learning, String nextStep) {
        validateMonth(month);
        this.month = month; this.facts = text(facts); this.learning = text(learning); this.nextStep = text(nextStep);
    }
    public static void validateMonth(YearMonth month) {
        if (month == null || month.getYear() < 2000 || month.getYear() >= 2100)
            throw new IllegalArgumentException("月份需在 2000—2099 年之间。");
    }
    private static String text(String value) {
        if (value == null || value.length() > 8000) throw new IllegalArgumentException("每项月度回顾最多 8000 个字符。");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                    || (Character.isHighSurrogate(c) && (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))))
                    || Character.isLowSurrogate(c)) throw new IllegalArgumentException("月度回顾包含不支持的控制字符。");
        }
        return value.trim();
    }
    public static String encode(List<MonthlyReview> records) {
        try {
            if (records == null || records.size() > 1200) throw new IllegalArgumentException("月度回顾超出支持范围。");
            JSONArray items = new JSONArray();
            HashSet<YearMonth> months = new HashSet<>();
            for (MonthlyReview item : records) {
                if (item == null || !months.add(item.month)) throw new IllegalArgumentException("同一月份只能保存一份回顾。");
                items.put(new JSONObject().put("month", item.month.toString()).put("facts", item.facts)
                        .put("learning", item.learning).put("nextStep", item.nextStep));
            }
            String result = new JSONObject().put("schemaVersion", 1).put("records", items).toString();
            if (result.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("月度回顾已达保存上限。");
            return result;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("月度回顾无法保存。", error); }
    }
    public static List<MonthlyReview> decode(String json) {
        try {
            JSONObject root = JsonInput.object(json, MAX_BYTES);
            keys(root, "schemaVersion", "records");
            Object version = root.get("schemaVersion");
            if (!(version instanceof Integer) || ((Integer) version) != 1 || !(root.get("records") instanceof JSONArray)) throw new IllegalArgumentException();
            JSONArray items = root.getJSONArray("records");
            if (items.length() > 1200) throw new IllegalArgumentException();
            List<MonthlyReview> result = new ArrayList<>();
            HashSet<YearMonth> months = new HashSet<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                keys(item, "month", "facts", "learning", "nextStep");
                MonthlyReview record = new MonthlyReview(YearMonth.parse(string(item, "month")), string(item, "facts"),
                        string(item, "learning"), string(item, "nextStep"));
                if (!months.add(record.month)) throw new IllegalArgumentException();
                result.add(record);
            }
            Collections.sort(result, (a, b) -> b.month.compareTo(a.month));
            return Collections.unmodifiableList(result);
        } catch (Exception error) { throw new IllegalStateException("月度回顾数据无法读取，原有记录保持不变。", error); }
    }
    private static String string(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException();
        return (String) value;
    }
    private static void keys(JSONObject object, String... keys) {
        HashSet<String> actual = new HashSet<>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) actual.add(iterator.next());
        if (!actual.equals(new HashSet<>(Arrays.asList(keys)))) throw new IllegalArgumentException();
    }
}
