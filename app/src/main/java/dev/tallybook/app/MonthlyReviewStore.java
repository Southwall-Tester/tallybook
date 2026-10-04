package dev.tallybook.app;

import android.content.Context;
import android.content.SharedPreferences;
import dev.tallybook.core.MonthlyReview;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Scope-separated, fail-closed local reflections; never reads or changes ledger amounts. */
public final class MonthlyReviewStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    public MonthlyReviewStore(Context context, String source) {
        if (!LedgerStore.WECHAT.equals(source) && !LedgerStore.DEMO.equals(source)) throw new IllegalArgumentException("未知账本。");
        prefs = context.getApplicationContext().getSharedPreferences("monthly_review_" + source, Context.MODE_PRIVATE);
    }
    public List<MonthlyReview> load() { synchronized (LOCK) { return read(); } }
    public void save(MonthlyReview record) {
        if (record == null) throw new IllegalArgumentException("回顾不能为空。");
        synchronized (LOCK) {
            List<MonthlyReview> all = new ArrayList<>(read());
            all.removeIf(item -> item.month.equals(record.month)); all.add(record); write(all);
        }
    }
    public void delete(YearMonth month) {
        MonthlyReview.validateMonth(month);
        synchronized (LOCK) {
            List<MonthlyReview> all = new ArrayList<>(read()); all.removeIf(item -> item.month.equals(month)); write(all);
        }
    }
    private List<MonthlyReview> read() {
        if (!prefs.contains("state")) return Collections.emptyList();
        try { return MonthlyReview.decode(prefs.getString("state", null)); }
        catch (RuntimeException error) { throw new IllegalStateException("月度回顾无法读取，原有记录保持不变。", error); }
    }
    private void write(List<MonthlyReview> records) {
        if (!prefs.edit().putString("state", MonthlyReview.encode(records)).commit()) throw new IllegalStateException("月度回顾未能保存。");
    }
}
