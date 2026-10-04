package dev.tallybook.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import dev.tallybook.core.MoneyCoach;
import dev.tallybook.core.LearningMath;
import dev.tallybook.core.MonthlyReview;
import dev.tallybook.core.SavingsGoal;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local, user-authored exercises. Nothing in this Activity moves or imports money. */
public final class BookCoachActivity extends Activity {
    private static final int BG = Color.rgb(245, 246, 242);
    private static final int INK = Color.rgb(25, 47, 38);
    private static final int MUTED = Color.rgb(108, 120, 111);
    private static final int GREEN = Color.rgb(27, 69, 53);
    private static final int PALE = Color.rgb(229, 237, 225);
    private static final int PICK_DREAM_IMAGE = 501;
    // One queue across Activity recreations: an already requested save precedes the next load.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService IMAGE_IO = Executors.newSingleThreadExecutor();
    private static final ThreadLocal<Map<String, String>> IO_INPUTS = new ThreadLocal<>();
    private static final String[] SECTIONS = {"今天", "愿望与梦想", "分配历史演算", "成功日记", "72 小时行动", "每日准则", "每周复盘", "方法库", "学习试算", "月度回顾"};
    private final Map<String, EditText> inputs = new LinkedHashMap<>();
    private Bundle drafts = new Bundle();
    private MoneyCoachStore store;
    private MoneyCoach.State data;
    private MonthlyReviewStore monthlyStore;
    private List<MonthlyReview> monthlyData = Collections.emptyList();
    private boolean monthlyReadable;
    private String source;
    private int section;
    private int wishSlot;
    private String successDay = LocalDate.now().toString();
    private String practiceDay = LocalDate.now().toString();
    private String reviewDay = LocalDate.now().toString();
    private String reviewMonth = YearMonth.now().toString();
    private int learningMode;
    private int periodsPerYear = 1;
    private String actionId = UUID.randomUUID().toString();
    private String allocationId = UUID.randomUUID().toString();
    private int methodIndex;
    private LinearLayout body;
    private ScrollView scroll;
    private CheckBox priority;
    private String prefix;
    private int restoreScroll;
    private int pendingImageSlot = -1;
    private int historyLimit = 20;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout rootView;
    private TextView ioStatus;
    private boolean busy;
    private volatile int imageGeneration;
    private ImageView previewView;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        source = saved == null ? getIntent().getStringExtra("source") : saved.getString("source");
        if (!LedgerStore.WECHAT.equals(source) && !LedgerStore.DEMO.equals(source)) {
            Toast.makeText(this, "请从账本内进入钱钱练习", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (saved != null) {
            section = Math.max(0, Math.min(SECTIONS.length - 1, saved.getInt("section")));
            wishSlot = Math.max(0, Math.min(9, saved.getInt("wish_slot")));
            successDay = saved.getString("success_day", successDay);
            practiceDay = saved.getString("practice_day", practiceDay);
            reviewDay = saved.getString("review_day", reviewDay);
            reviewMonth = saved.getString("review_month", reviewMonth);
            learningMode = Math.max(0, Math.min(2, saved.getInt("learning_mode")));
            periodsPerYear = saved.getInt("periods_per_year", 1) == 12 ? 12 : 1;
            actionId = saved.getString("action_id", actionId);
            allocationId = saved.getString("allocation_id", allocationId);
            methodIndex = saved.getInt("method_index");
            pendingImageSlot = saved.getInt("pending_image_slot", -1);
            historyLimit = Math.max(20, Math.min(20000, saved.getInt("history_limit", 20)));
            Bundle oldDrafts = saved.getBundle("drafts");
            if (oldDrafts != null) drafts = oldDrafts;
            restoreScroll = saved.getInt("scroll_y");
        } else {
            section = Math.max(0, Math.min(SECTIONS.length - 1, getIntent().getIntExtra("section", 0)));
            wishSlot = Math.max(0, Math.min(9, getIntent().getIntExtra("wish_slot", 0)));
            if (getIntent().hasExtra("action_title") || getIntent().hasExtra("action_note")) {
                section = 4;
                prefillAction(getIntent().getStringExtra("action_title"), getIntent().getStringExtra("action_note"));
            }
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        store = new MoneyCoachStore(this, source);
        monthlyStore = new MonthlyReviewStore(this, source);
        reload();
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        remember();
        saved.putString("source", source);
        saved.putInt("section", section);
        saved.putInt("wish_slot", wishSlot);
        saved.putString("success_day", successDay);
        saved.putString("practice_day", practiceDay);
        saved.putString("review_day", reviewDay);
        saved.putString("review_month", reviewMonth);
        saved.putInt("learning_mode", learningMode);
        saved.putInt("periods_per_year", periodsPerYear);
        saved.putString("action_id", actionId);
        saved.putString("allocation_id", allocationId);
        saved.putInt("method_index", methodIndex);
        saved.putInt("pending_image_slot", pendingImageSlot);
        saved.putInt("history_limit", historyLimit);
        saved.putBundle("drafts", drafts);
        saved.putInt("scroll_y", scroll == null ? 0 : scroll.getScrollY());
        super.onSaveInstanceState(saved);
    }

    private void reload() {
        shell();
        heading("正在读取本机练习", "请稍等，现有记录会在读取完成后显示。");
        setBusy(true, "正在读取…");
        IO.execute(() -> {
            MoneyCoach.State loaded = null;
            try { loaded = store.load(); } catch (RuntimeException error) { /* Raw state stays private. */ }
            final MoneyCoach.State result = loaded;
            List<MonthlyReview> monthly = null;
            try { monthly = monthlyStore.load(); } catch (RuntimeException error) { /* Never reset corrupt records. */ }
            final List<MonthlyReview> loadedMonthly = monthly;
            main.post(() -> {
                if (!alive()) return;
                busy = false;
                data = result;
                monthlyReadable = loadedMonthly != null;
                if (monthlyReadable) monthlyData = loadedMonthly;
                if (result != null) render();
                else {
                    shell();
                    heading("暂时无法读取练习", "现有数据没有被清空。请返回后再试；此时不能保存新的内容。");
                    action("重试读取", this::reload, true);
                    action("返回账本", this::finish, false);
                }
            });
        });
    }

    private void shell() {
        releasePreview();
        inputs.clear();
        priority = null;
        LinearLayout root = column();
        rootView = root;
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        LinearLayout top = column();
        top.setPadding(dp(20), dp(12), dp(20), dp(8));
        TextView back = text("‹ 返回账本", 14, GREEN, true);
        back.setMinHeight(dp(44));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back);
        top.addView(text("钱钱练习" + (LedgerStore.DEMO.equals(source) ? " · 演示" : " · 真实账本"), 26, INK, true));
        ioStatus = text("", 12, MUTED, false);
        ioStatus.setVisibility(View.GONE);
        top.addView(ioStatus);
        root.addView(top);
        if (data != null) {
            Spinner selector = spinner(SECTIONS, section);
            selector.setContentDescription("选择钱钱练习页面");
            top.addView(selector);
            selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position != section) open(position);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
        }
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSaveEnabled(false);
        body = column();
        body.setPadding(dp(20), dp(8), dp(20), dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void render() {
        prefix = "s" + section + (section == 1 ? "_w" + wishSlot : section == 8 ? "_m" + learningMode : "") + "_";
        shell();
        switch (section) {
            case 0: today(); break;
            case 1: wishes(); break;
            case 2: allocations(); break;
            case 3: successes(); break;
            case 4: actions(); break;
            case 5: practices(); break;
            case 6: reviews(); break;
            case 7: methods(); break;
            case 8: learning(); break;
            case 9: monthlyReviews(); break;
            default: break;
        }
        if (restoreScroll > 0) {
            final int y = restoreScroll;
            restoreScroll = 0;
            scroll.post(() -> scroll.scrollTo(0, y));
        }
    }

    private void open(int page) {
        if (busy) return;
        remember();
        section = page;
        historyLimit = 20;
        restoreScroll = 0;
        render();
    }

    private void finance(int page) {
        remember();
        startActivity(new Intent().setClassName(this, "dev.tallybook.app.FinancePracticeActivity")
                .putExtra("source", source).putExtra("section", page));
    }

    private void prefillAction(String title, String note) {
        clearSectionDraft(4);
        actionId = UUID.randomUUID().toString();
        String complete = title == null ? "" : title.trim();
        String singleLine = complete.replace('\n', ' ').replace('\r', ' ');
        drafts.putString("s4_title", singleLine.length() > 512 ? singleLine.substring(0, 512) : singleLine);
        String detail = note == null ? "" : note.trim();
        if (singleLine.length() > 512) detail = complete + (detail.isEmpty() ? "" : "\n" + detail);
        drafts.putString("s4_obstacle", detail.length() > 4000 ? detail.substring(0, 4000) : detail);
    }

    private void actionFromReflection(String nextStep, String origin) {
        if (nextStep.trim().isEmpty()) { message("先写一个下一步", "用一句话说明接下来能做的小事，再打开行动草稿。"); return; }
        remember(); prefillAction(nextStep, origin); section = 4; restoreScroll = 0; render();
    }

    private void today() {
        heading("为想要的生活做一点", "看方向，完成一件小事，再留下真实经历。练习时间由你安排，约 10 分钟只是书中的建议。");
        int selected = 0;
        for (MoneyCoach.Wish wish : data.wishes) if (wish.priority) {
            line("重点 · " + wish.title, wish.reason.isEmpty() ? "还没有写下原因" : wish.reason);
            selected++;
        }
        if (selected == 0) note("先想一想：钱对你意味着什么？可以从一个愿望开始，慢慢补足自己的理由。");
        action(selected == 0 ? "写下愿望与理由" : "看看我的愿望", () -> open(1), true);
        MoneyCoach.Theme theme = theme(LocalDate.now());
        line("今天 · " + theme.title, theme.prompt);
        action("写今天的理解与经历", () -> {
            if (!practiceDay.equals(LocalDate.now().toString())) clearSectionDraft(5);
            practiceDay = LocalDate.now().toString(); open(5);
        }, false);
        int openCount = 0;
        for (MoneyCoach.Action item : data.actions) if (item.completedAt == 0) openCount++;
        line("72 小时小行动", openCount == 0 ? "把一个决定缩小为现在能做的事。" : "还有 " + openCount + " 项未完成；可以继续、调整或取消。");
        action("安排或回顾行动", () -> open(4), false);
        action("记录今天做成的小事", () -> {
            if (!successDay.equals(LocalDate.now().toString())) clearSectionDraft(3);
            successDay = LocalDate.now().toString(); open(3);
        }, false);
        action("做一次每周回顾", () -> open(6), false);
        action("回顾这个月", () -> open(9), false);
        action("整理钱罐与实际用途", () -> finance(0), false);
        note("练习只保存到本机。梦想手填进度和历史演算不会移动真实资金；钱罐在独立页面记录用途安排。");
    }

    private void wishes() {
        heading("把愿望写具体", "第一册第 1、2 章：写下十个理由，从中选择三个重点。可以先写一个，留空的位置以后再补。");
        String[] slots = new String[10];
        for (int i = 0; i < 10; i++) slots[i] = (i + 1) + ". " + fallback(data.wishes.get(i).title, "还没写愿望")
                + (data.wishes.get(i).priority ? " · 重点" : "");
        Spinner selector = spinner(slots, wishSlot);
        selector.setContentDescription("选择愿望位置");
        body.addView(selector);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != wishSlot) { remember(); wishSlot = position; render(); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        MoneyCoach.Wish wish = data.wishes.get(wishSlot);
        field("title", "愿望", wish.title, "例如：有足够的钱完成一个个人项目", 256, false);
        field("reason", "为什么它对我重要", wish.reason, "写自己的理由", 4000, true);
        priority = new CheckBox(this);
        priority.setText("选为当前重点（最多三个）");
        priority.setTextColor(INK);
        priority.setChecked(drafts.containsKey(prefix + "priority") ? drafts.getBoolean(prefix + "priority") : wish.priority);
        body.addView(priority);
        moneyField("target", "目标金额（元，可留空）", wish.targetMinor);
        moneyField("saved", "已存金额（元，手动记录）", wish.savedMinor);
        field("date", "希望完成日期（可留空）", wish.targetDate == null ? "" : wish.targetDate.toString(), "YYYY-MM-DD", 10, false);
        note("没有金额的愿望也可保存。已存金额由你核对填写；它不会转账或改变生活费预算。");
        String imageUri = drafts.getString(prefix + "image_uri", wish.imageUri);
        note(imageUri.isEmpty() ? "梦想相册 · 可选择一张自己的图片，给愿望一个具体画面。" : "梦想相册 · 已选择一张图片。保存愿望后会保留，只读取你选择的这一张。");
        action(imageUri.isEmpty() ? "选择梦想图片" : "更换梦想图片", this::pickDreamImage, false);
        if (!imageUri.isEmpty()) {
            dreamPreview(imageUri);
            action("打开已选图片", () -> viewDreamImage(imageUri), false);
            action("移除图片关联", () -> { remember(); drafts.putString(prefix + "image_uri", ""); render(); }, false);
        }
        action("保存这个愿望", () -> {
            final boolean selected = priority.isChecked();
            final int slot = wishSlot;
            commit(() -> store.saveWish(new MoneyCoach.Wish(slot, value("title"), value("reason"), selected,
                    money("target"), money("saved"), optionalDate(value("date")), imageUri)));
        }, true);
        if (!wish.title.isEmpty() || !wish.reason.isEmpty() || !wish.imageUri.isEmpty() || wish.targetMinor != 0 || wish.savedMinor != 0 || wish.targetDate != null) action("清空这个位置", () -> confirm("清空这个愿望？", "只删除本练习的愿望记录，不会改变账本或真实资金。",
                () -> commit(() -> store.saveWish(new MoneyCoach.Wish(wishSlot, "", "", false, 0, 0, null, "")))), false);
        if (wish.targetMinor > 0) line("保存后的进度", "已存 ¥ " + currency(wish.savedMinor) + " / 目标 ¥ " + currency(wish.targetMinor)
                + "；还差 ¥ " + currency(Math.max(0, wish.targetMinor - wish.savedMinor)));
        action("去钱罐安排梦想用途", () -> finance(0), false);
        action("复制原存钱目标到一个空愿望", () -> confirm("复制原存钱目标？", "会找一个完全空白的愿望位置，复制名称、目标金额、手填进度和日期。原目标保留，不转移任何资金，也不覆盖你正在写的草稿。", this::copyLegacyGoal), false);
    }

    private void allocations() {
        heading("分配历史演算", "此页保留之前的独立演算。计划和‘已自行执行’仅是旧记录，不代表钱罐余额。50 / 40 / 10 是书中可修改的例子。");
        action("打开钱罐记录实际用途", () -> finance(0), true);
        MoneyCoach.AllocationRecord existing = allocation(allocationId);
        moneyField("amount", "这次可分配金额（元）", existing == null ? 0 : existing.amountMinor);
        numberField("goose", "长期积累（%）", existing == null ? data.goosePercent : existing.goosePercent);
        numberField("dream", "梦想（%）", existing == null ? data.dreamPercent : existing.dreamPercent);
        numberField("daily", "日常（%）", existing == null ? data.dailyPercent : existing.dailyPercent);
        field("note", "这笔钱的来源或安排", existing == null ? "" : existing.note, "先确认已到账，并留足必要生活开支", 2000, true);
        note("这里是独立的分配记录，不读取账户余额。计划与‘已自行执行’分别保存；两者都不会改动真实账本或梦想的手填进度。同一笔钱请只记录一次。");
        TextView preview = text("填写金额和比例后，查看三类用途的金额。", 15, INK, false);
        body.addView(preview);
        action("计算分配金额", () -> guarded(() -> {
            MoneyCoach.Allocation split = MoneyCoach.allocate(money("amount"), integer("goose"), integer("dream"), integer("daily"));
            preview.setText("长期积累 ¥ " + currency(split.gooseMinor) + "\n梦想 ¥ " + currency(split.dreamMinor) + "\n日常 ¥ " + currency(split.dailyMinor));
        }), false);
        action("保存为常用比例", () -> commit(() -> store.saveRatios(integer("goose"), integer("dream"), integer("daily")), false), false);
        action(existing == null ? "保存分配计划" : "更新这条分配记录", () -> saveAllocation(existing, false), true);
        if (existing == null || existing.executedAt == 0) action("记录为已自行执行", () -> confirm("你已自行完成这次安排？", "这只记录你的确认，不会发起转账，也不会再记一笔收入。请先核对钱的实际去向。",
                () -> saveAllocation(existing, true)), false);
        if (existing != null) action("新建另一笔分配", () -> { clearDraft(); allocationId = UUID.randomUUID().toString(); render(); }, false);
        subheading("已保存的分配");
        List<MoneyCoach.AllocationRecord> allocationHistory = new ArrayList<>(data.allocations);
        Collections.sort(allocationHistory, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        for (int i = 0; i < Math.min(allocationHistory.size(), historyLimit); i++) {
            MoneyCoach.AllocationRecord item = allocationHistory.get(i);
            line((item.executedAt == 0 ? "计划" : "已自行执行") + " · ¥ " + currency(item.amountMinor),
                    stamp(item.createdAt) + " · " + item.goosePercent + "/" + item.dreamPercent + "/" + item.dailyPercent + "\n" + item.note);
            action("查看或修改这笔分配", () -> { clearDraft(); allocationId = item.id; render(); }, false);
            action("删除此分配记录", () -> confirm("删除记录？", "只删除练习记录，不会移动资金。",
                    () -> commit(() -> store.deleteAllocation(item.id), true,
                            () -> { if (allocationId.equals(item.id)) allocationId = UUID.randomUUID().toString(); })), false);
        }
        moreHistory(allocationHistory.size());
        if (data.allocations.isEmpty()) note("还没有保存分配计划。");
    }

    private void saveAllocation(MoneyCoach.AllocationRecord existing, boolean executed) {
        commit(() -> {
            long now = System.currentTimeMillis();
            store.saveAllocation(new MoneyCoach.AllocationRecord(allocationId, money("amount"), integer("goose"),
                    integer("dream"), integer("daily"), existing == null ? now : existing.createdAt,
                    executed ? now : existing == null ? 0 : existing.executedAt, value("note")));
        });
    }

    private void successes() {
        heading("看见自己已经做成的事", "第一册第 3、11 章：小事也算，写下事实和自己的行动。五件是练习建议，写到几件都可以先保存。");
        MoneyCoach.SuccessEntry existing = success(successDay);
        field("date", "记录日期", successDay, "YYYY-MM-DD", 10, false);
        action("打开这个日期的记录", () -> guarded(() -> {
            successDay = LocalDate.parse(value("date")).toString(); clearDraft(); render();
        }), false);
        for (int i = 0; i < 5; i++) field("item" + i, "成功小事 " + (i + 1), existing != null && existing.items.size() > i ? existing.items.get(i) : "",
                "事实：发生了什么？\n原因：我做对了什么？\n能力：下次还能用到哪一点？", 1000, true);
        StringBuilder extra = new StringBuilder();
        if (existing != null) for (int i = 5; i < existing.items.size(); i++) { if (extra.length() > 0) extra.append('\n'); extra.append(existing.items.get(i)); }
        field("extra", "更多小事（可选，每行一件）", extra.toString(), "也可以记录学习、求助和关系中的进展", 8000, true);
        action("保存日记 / 草稿", () -> commit(() -> {
            LocalDate date = LocalDate.parse(value("date"));
            if (!date.toString().equals(successDay)) throw new IllegalArgumentException("日期已改变，请先点‘打开这个日期的记录’，再填写和保存。");
            List<String> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) if (!value("item" + i).isEmpty()) items.add(value("item" + i));
            for (String item : value("extra").split("\\r?\\n")) if (!item.trim().isEmpty()) items.add(item.trim());
            store.saveSuccess(new MoneyCoach.SuccessEntry(date, items));
        }), true);
        subheading("回看我的记录");
        List<MoneyCoach.SuccessEntry> entries = new ArrayList<>(data.successes);
        Collections.sort(entries, (a, b) -> b.date.compareTo(a.date));
        for (int i = 0; i < Math.min(entries.size(), historyLimit); i++) {
            MoneyCoach.SuccessEntry entry = entries.get(i);
            line(entry.date + " · " + entry.items.size() + " 件", entry.items.isEmpty() ? "空白草稿" : String.join("\n", entry.items));
            action("继续写 " + entry.date, () -> { clearDraft(); successDay = entry.date.toString(); render(); }, false);
            action("删除这天的日记", () -> confirm("删除日记？", "此日期的成功记录将从当前账本练习中删除。", () -> commit(() -> store.deleteSuccess(entry.date))), false);
        }
        moreHistory(entries.size());
        if (entries.isEmpty()) note("还没有日记。今天的一件小事就可以作为开始。");
    }

    private void actions() {
        heading("把决定变成一个小行动", "第一册第 5 章的 72 小时要求用于可完成的小事。例如列一份材料清单，而不是三天内实现整个梦想。");
        MoneyCoach.Action existing = actionRecord(actionId);
        field("title", "准备完成什么", existing == null ? "" : existing.title, "具体到一个可做的动作", 512, false);
        field("obstacle", "实际结果 / 阻碍 / 下一步", existing == null ? "" : existing.obstacle, "记录事实，也可以承认需要更多帮助", 4000, true);
        if (existing != null) note("开始于 " + stamp(existing.createdAt) + "；72 小时到期：" + stamp(existing.deadlineAt));
        action(existing == null ? "开始这个小行动" : "保存行动记录", () -> commit(() -> {
            store.saveAction(new MoneyCoach.Action(actionId, value("title"), existing == null ? System.currentTimeMillis() : existing.createdAt,
                    existing == null ? 0 : existing.completedAt, value("obstacle")));
        }), true);
        if (existing != null && existing.completedAt == 0) action("记录为已完成", () -> guarded(() -> {
            if (value("obstacle").isEmpty()) { message("写一句实际结果", "在上方记录你实际做了什么，再确认完成。无需金额或成绩。 "); return; }
            commit(() -> store.saveAction(new MoneyCoach.Action(existing.id, value("title"), existing.createdAt,
                    System.currentTimeMillis(), value("obstacle"))));
        }), false);
        if (existing != null) action("安排另一个小行动", () -> { clearDraft(); actionId = UUID.randomUUID().toString(); render(); }, false);
        note("逾期不代表失败。你可以修改动作；需要重新安排 72 小时时，先记录旧行动的阻碍，再新建一个更小的动作。");
        subheading("我的行动");
        List<MoneyCoach.Action> actionHistory = new ArrayList<>(data.actions);
        Collections.sort(actionHistory, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        for (int i = 0; i < Math.min(actionHistory.size(), historyLimit); i++) {
            MoneyCoach.Action item = actionHistory.get(i);
            String status = item.completedAt > 0 ? "已完成" : item.deadlineAt <= System.currentTimeMillis() ? "已到期，可调整" : "进行中";
            line(status + " · " + item.title, "到期 " + stamp(item.deadlineAt) + (item.obstacle.isEmpty() ? "" : "\n" + item.obstacle));
            action("查看 / 记录结果", () -> { clearDraft(); actionId = item.id; render(); }, false);
            action("删除这个行动", () -> confirm("删除行动？", "将删除此行动与其结果，不影响账本。",
                    () -> commit(() -> store.deleteAction(item.id), true,
                            () -> { if (actionId.equals(item.id)) actionId = UUID.randomUUID().toString(); })), false);
        }
        moreHistory(actionHistory.size());
        if (data.actions.isEmpty()) note("还没有行动。可以从一个十分钟内能开始的小步骤着手。");
    }

    private void practices() {
        LocalDate date = LocalDate.parse(practiceDay);
        MoneyCoach.PracticeEntry existing = practice(date);
        MoneyCoach.Theme theme = existing == null ? theme(date) : MoneyCoach.THEMES.get(existing.themeIndex);
        heading(theme.title, "第二册第 9、10 章：一天专注一条，持续循环。写自己的理解和经历，不给自己打人格分。");
        field("date", "练习日期", practiceDay, "YYYY-MM-DD", 10, false);
        action("打开这一天的准则", () -> guarded(() -> { practiceDay = LocalDate.parse(value("date")).toString(); clearDraft(); render(); }), false);
        line("这一条的意思", theme.explanation);
        note(theme.prompt);
        field("understanding", "这条对我意味着什么", existing == null ? "" : existing.understanding,
                "可以先写一句自己的理解", 3000, true);
        field("experience", "真实经历与下一次想试的变化", existing == null ? "" : existing.experience,
                "我做了什么，发生了什么，还有什么不确定？", 4000, true);
        action("保存理解与经历", () -> commit(() -> {
            LocalDate chosen = LocalDate.parse(value("date"));
            if (!chosen.equals(date)) throw new IllegalArgumentException("日期已改变，请先点‘打开这一天的准则’，再填写和保存。");
            store.savePractice(new MoneyCoach.PracticeEntry(chosen, theme.index, value("understanding"), value("experience")));
        }), true);
        note("周一友好亲和 · 周二勇于承担 · 周三善待他人 · 周四帮助给予 · 周五感恩之心 · 周六勤学不辍 · 周日值得信赖。可补记，跳过一天也能继续。");
        subheading("以前的实践");
        List<MoneyCoach.PracticeEntry> entries = new ArrayList<>(data.practices);
        Collections.sort(entries, (a, b) -> b.date.compareTo(a.date));
        for (int i = 0; i < Math.min(entries.size(), historyLimit); i++) {
            MoneyCoach.PracticeEntry item = entries.get(i);
            line(item.date + " · " + MoneyCoach.THEMES.get(item.themeIndex).title, item.understanding + (item.experience.isEmpty() ? "" : "\n" + item.experience));
            action("继续思考这条", () -> { clearDraft(); practiceDay = item.date.toString(); render(); }, false);
            action("删除这次练习", () -> confirm("删除练习？", "只删除本次理解与经历。", () -> commit(() -> store.deletePractice(item.date))), false);
        }
        moreHistory(entries.size());
    }

    private void reviews() {
        LocalDate week = MoneyCoach.weekStart(LocalDate.parse(reviewDay));
        MoneyCoach.WeeklyReview existing = review(week);
        heading("每周只选一个调整", "这是本产品的复盘安排。结合自己的账本、目标和行动事实，找出接下来值得做的一件事。");
        field("date", "选择这一周中的任意日期", reviewDay, "YYYY-MM-DD", 10, false);
        action("打开这一周", () -> guarded(() -> {
            LocalDate chosen = LocalDate.parse(value("date"));
            MoneyCoach.weekStart(chosen);
            reviewDay = chosen.toString();
            clearDraft();
            render();
        }), false);
        note("本次回顾：" + week + " 至 " + week.plusDays(6));
        field("progress", "1. 这周有什么进展，哪件事值得继续？", existing == null ? "" : existing.progress, "用真实经历说明", 4000, true);
        field("obstacle", "2. 什么阻碍了我，哪些条件能调整？", existing == null ? "" : existing.obstacle, "也可以记录目前无法控制的事情", 4000, true);
        field("next", "3. 下周只改一件事，会是什么？", existing == null ? "" : existing.nextStep, "写一个具体的下一步", 4000, true);
        action("保存这周的回顾", () -> commit(() -> {
            LocalDate chosen = LocalDate.parse(value("date"));
            if (!MoneyCoach.weekStart(chosen).equals(week)) throw new IllegalArgumentException("日期已移到另一周，请先点‘打开这一周’。");
            store.saveReview(new MoneyCoach.WeeklyReview(week, value("progress"), value("obstacle"), value("next")));
        }), true);
        action("把下一步带到行动草稿", () -> actionFromReflection(value("next"), "来自 " + week + " 这一周的复盘；可继续缩小动作，点开始后才计时。"), false);
        subheading("以前的回顾");
        List<MoneyCoach.WeeklyReview> entries = new ArrayList<>(data.reviews);
        Collections.sort(entries, (a, b) -> b.weekStart.compareTo(a.weekStart));
        for (int i = 0; i < Math.min(entries.size(), historyLimit); i++) {
            MoneyCoach.WeeklyReview item = entries.get(i);
            line("从 " + item.weekStart + " 开始的一周", "进展：" + item.progress + "\n阻碍：" + item.obstacle + "\n下一步：" + item.nextStep);
            action("继续这次回顾", () -> { clearDraft(); reviewDay = item.weekStart.toString(); render(); }, false);
            action("删除这次回顾", () -> confirm("删除回顾？", "只删除本周回顾，不删除日记或交易。", () -> commit(() -> store.deleteReview(item.weekStart))), false);
        }
        moreHistory(entries.size());
    }

    private void monthlyReviews() {
        heading("回顾一个月，选下一步", "这是产品对持续学习与定期复盘的改编。按实际记录写变化，不把余额或完成次数当作个人价值。");
        if (!monthlyReadable) {
            note("月度记录暂时无法读取，原有数据保留。可以重试，其他练习仍可使用。");
            action("重新读取月度记录", this::reload, true);
            return;
        }
        YearMonth month = YearMonth.parse(reviewMonth);
        MonthlyReview current = null;
        for (MonthlyReview item : monthlyData) if (item.month.equals(month)) current = item;
        field("month", "回顾月份", reviewMonth, "YYYY-MM", 7, false);
        action("打开这个月", () -> guarded(() -> {
            YearMonth chosen = YearMonth.parse(value("month")); MonthlyReview.validateMonth(chosen);
            reviewMonth = chosen.toString(); clearDraft(); render();
        }), false);
        field("facts", "1. 钱、梦想与生活发生了什么变化？", current == null ? "" : current.facts,
                "区分到账与预期、手填进度与实际用途；写你核对过的事实", 8000, true);
        field("learning", "2. 哪种做法有效，我学到了什么？", current == null ? "" : current.learning,
                "参考本月日记、准则与项目反馈，也记录不确定的地方", 8000, true);
        field("next", "3. 下个月想保留或调整什么？", current == null ? "" : current.nextStep,
                "选择一个可尝试的小改变", 8000, true);
        action("保存这个月的回顾", () -> commit(() -> {
            YearMonth chosen = YearMonth.parse(value("month"));
            if (!chosen.equals(month)) throw new IllegalArgumentException("月份已改变，请先点‘打开这个月’。");
            monthlyStore.save(new MonthlyReview(chosen, value("facts"), value("learning"), value("next")));
        }), true);
        action("把月度下一步带到行动草稿", () -> actionFromReflection(value("next"), "来自 " + month + " 的月度回顾；先选一件可做的小事。"), false);
        action("核对资产与债务", () -> finance(2), false);
        action("回看收入项目", () -> finance(1), false);
        subheading("以前的月度回顾");
        for (int i = 0; i < Math.min(monthlyData.size(), historyLimit); i++) {
            MonthlyReview item = monthlyData.get(i);
            line(item.month.toString(), "事实：" + item.facts + "\n学习：" + item.learning + "\n下一步：" + item.nextStep);
            action("继续回顾 " + item.month, () -> { clearDraft(); reviewMonth = item.month.toString(); render(); }, false);
            action("删除 " + item.month + " 回顾", () -> confirm("删除这个月的回顾？", "只删除月度文字记录，不影响账本、钱罐与其他练习。",
                    () -> commit(() -> monthlyStore.delete(item.month))), false);
        }
        moreHistory(monthlyData.size());
    }

    private void methods() {
        heading("把方法用到自己的生活里", "依据《小狗钱钱》两册的简短转述。章号供回到原书核对；以下操作是产品改编，个人比例和情节不等于通用规则。");
        String[] names = new String[METHODS.length];
        for (int i = 0; i < METHODS.length; i++) names[i] = (i + 1) + ". " + METHODS[i][0];
        methodIndex = Math.max(0, Math.min(methodIndex, METHODS.length - 1));
        Spinner selector = spinner(names, methodIndex);
        selector.setContentDescription("选择书中方法");
        body.addView(selector);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != methodIndex) { methodIndex = position; render(); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        String[] item = METHODS[methodIndex];
        line(item[0], item[1]);
        note(item[2]);
        action(item[3], () -> {
            if (item[4].startsWith("finance:")) finance(Integer.parseInt(item[4].substring(8)));
            else if (item[4].startsWith("learn:")) { learningMode = Integer.parseInt(item[4].substring(6)); open(8); }
            else open(Integer.parseInt(item[4]));
        }, true);
        note("钱罐记录用途，项目区分待收与到账，资产债务清单保存当前手动盘点。学习试算只改变假设，不记录实际收益。");
    }

    private void learning() {
        heading("让假设看得见", "离线实验：改变条件，观察结果。这里只计算假设，不写入账本、钱罐或梦想进度。");
        Spinner mode = spinner(new String[]{"复利、追加与通胀", "集中、分散与共同下跌", "消费债务余款示例"}, learningMode);
        mode.setContentDescription("选择学习实验"); body.addView(mode);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (learningMode != position) { remember(); learningMode = position; render(); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        if (learningMode == 1) { diversificationExperiment(); return; }
        if (learningMode == 2) { debtExperiment(); return; }
        note("第一册第 16、17 章及附录。按年时每年末结算；按月时使用名义年率 ÷ 12，每月末结算并追加。这两种利率口径不是同一个实际年收益率。");
        moneyField("principal", "假设本金（元）", 0);
        EditText rate = field("rate", "假设年变化率（%，可为负）", "", "例如 3 或 -5，不代表实际收益", 12, false);
        rate.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        field("years", "年数（0—100 的整数）", "", "例如 5", 3, false).setInputType(InputType.TYPE_CLASS_NUMBER);
        moneyField("addition", "每期末追加（元，可留空）", 0);
        Spinner frequency = spinner(new String[]{"每年末追加", "每月末追加"}, periodsPerYear == 12 ? 1 : 0);
        frequency.setContentDescription("选择追加频率"); body.addView(frequency);
        frequency.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { periodsPerYear = position == 1 ? 12 : 1; }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        field("inflation", "假设年通胀率（%，可为负）", "0", "仅用于购买力比较", 12, false)
                .setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        TextView result = text("填写一个场景，再试着改变利率或年数。", 17, INK, true);
        result.setPadding(0, dp(18), 0, dp(12));
        body.addView(result);
        action("计算这个假设", () -> guarded(() -> {
            BigDecimal annual = decimal("rate");
            int years;
            try { years = Integer.parseInt(value("years")); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("年数请填写 0—100 的整数。"); }
            long principal = money("principal");
            long addition = money("addition");
            BigDecimal inflation = decimal("inflation");
            LearningMath.Growth calculated = LearningMath.growth(principal, addition, annual, years, periodsPerYear, inflation);
            long ending = calculated.endingMinor;
            String approximation = annual.signum() <= 0 ? "72 法则：零或负变化率不适用翻倍近似。"
                    : "72 法则：72 ÷ " + annual.stripTrailingZeros().toPlainString() + " ≈ "
                    + String.format(Locale.CHINA, "%.2f", MoneyCoach.ruleOf72Years(annual.doubleValue())) + " 年翻倍（粗略近似）。";
            String difference = (ending >= calculated.contributedMinor ? "+" : "−") + currency(Math.abs(ending - calculated.contributedMinor));
            result.setText("本次计算：本金 ¥ " + currency(principal) + "，假设年率 " + annual.stripTrailingZeros().toPlainString()
                    + "%，年通胀 " + inflation.toPlainString() + "%\n每" + (periodsPerYear == 12 ? "月" : "年") + "末追加 ¥ " + currency(addition)
                    + "\n假设 " + years + " 年后：¥ " + currency(ending) + "\n累计投入：¥ " + currency(calculated.contributedMinor)
                    + "\n相对累计投入：" + difference + "\n按今天购买力：¥ " + currency(calculated.purchasingPowerMinor)
                    + "\n\n" + approximation + "\n72 近似只看一次性本金的变化，不包含追加或通胀。");
            remember();
            drafts.putString(prefix + "result", result.getText().toString());
        }), true);
        if (drafts.containsKey(prefix + "result")) result.setText(drafts.getString(prefix + "result"));
        note("每期先按假设率变化，四舍五入到分，再加期末投入；购买力用期末金额除以 (1 + 年通胀率)^年数，最后取整到分。未计费税、波动或真实产品规则；负率会亏损，长期与分散不保证安全。");
    }

    private void diversificationExperiment() {
        note("第一册第 16 章的例子把其他项目视为不变。本实验同时展示共同下跌，不能据此判断任何真实组合的风险。");
        moneyField("risk_principal", "假设总金额（元）", 0);
        field("holdings", "平均分成几项（1—1000）", "20", "整数", 4, false).setInputType(InputType.TYPE_CLASS_NUMBER);
        field("single_loss", "其中一项下跌（%）", "40", "0—100", 10, false).setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field("common_loss", "全部共同下跌（%）", "40", "0—100", 10, false).setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextView result = text(drafts.getString(prefix + "result", "先改变一个条件，再比较三个结果。"), 17, INK, true); body.addView(result);
        action("比较三个场景", () -> guarded(() -> {
            int holdings;
            try { holdings = Integer.parseInt(value("holdings")); } catch (NumberFormatException error) { throw new IllegalArgumentException("项目数请填写 1—1000 的整数。"); }
            long principal = money("risk_principal"); BigDecimal one = decimal("single_loss"), all = decimal("common_loss");
            LearningMath.Diversification r = LearningMath.diversify(principal, holdings, one, all);
            result.setText("假设总额 ¥ " + currency(principal) + "；单项跌 " + one.toPlainString() + "% / 全部跌 " + all.toPlainString() + "%"
                    + "\n集中在一项后：¥ " + currency(r.concentratedMinor) + "\n分成 " + holdings + " 项，仅第一项下跌：¥ " + currency(r.oneFallsMinor)
                    + "\n所有项目共同下跌：¥ " + currency(r.allFallMinor) + "\n第一项初始金额：¥ " + currency(r.largestHoldingMinor));
            remember(); drafts.putString(prefix + "result", result.getText().toString());
        }), true);
        note("按分等额分配，余分依次给前面的项目；单项下跌取第一项，其他不变。共同下跌按总额计算，结果四舍五入到分。项目数量增加不能消除共同风险。");
    }

    private void debtExperiment() {
        note("第一册第 6 章的消费债务情境。这里先扣除必要开支与合同要求，再观察假设余款怎样一分为二；住房贷款不套用此例，也不据此决定实际还款。");
        moneyField("income", "假设已到账金额（元）", 0);
        moneyField("necessary", "假设必要开支（元）", 0);
        moneyField("required", "假设合同要求本期支付（元）", 0);
        TextView result = text(drafts.getString(prefix + "result", "自行输入假设，不使用固定最低还款比例。"), 17, INK, true); body.addView(result);
        action("观察余款示例", () -> guarded(() -> {
            long income = money("income"), necessary = money("necessary"), required = money("required");
            LearningMath.Remainder r = LearningMath.discretionary(income, necessary, required);
            String parameters = "假设到账 ¥ " + currency(income) + " − 必要开支 ¥ " + currency(necessary) + " − 合同要求 ¥ " + currency(required);
            result.setText(parameters + (r.shortfallMinor > 0 ? "\n此假设存在缺口 ¥ " + currency(r.shortfallMinor) + "，没有可供平分的余款。"
                    : "\n假设余款 ¥ " + currency(r.availableMinor) + "\n一半留存 ¥ " + currency(r.illustrativeSavingMinor)
                    + "\n另一半追加偿还 ¥ " + currency(r.illustrativeExtraRepaymentMinor) + "（不含已扣除的合同款）"));
            remember(); drafts.putString(prefix + "result", result.getText().toString());
        }), true);
        note("有奇数分时，多出的一分放在假设追加偿还一边，仅为金额守恒。此算式不计算利息、罚息、提前还款条件或实际应还款额。");
        action("核对自己的资产与债务记录", () -> finance(2), false);
    }

    private BigDecimal decimal(String key) {
        String value = value(key);
        if (!value.matches("-?[0-9]+(?:\\.[0-9]{1,6})?")) throw new IllegalArgumentException("百分比请填写数字，最多六位小数。");
        return new BigDecimal(value);
    }

    private void pickDreamImage() {
        remember();
        pendingImageSlot = wishSlot;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_DREAM_IMAGE);
        } catch (ActivityNotFoundException error) {
            pendingImageSlot = -1;
            message("暂时无法选图", "此设备没有可用的文件选择器。文字草稿仍然保留。");
        }
    }

    private void copyLegacyGoal() {
        remember();
        boolean[] hasDraft = new boolean[10];
        for (int slot = 0; slot < 10; slot++) {
            String starts = "s1_w" + slot + "_";
            for (String key : drafts.keySet()) if (key.startsWith(starts)) {
                Object value = drafts.get(key);
                if (Boolean.TRUE.equals(value) || (value instanceof String && !((String) value).trim().isEmpty())) hasDraft[slot] = true;
            }
        }
        final int[] copied = {-1};
        commit(() -> {
            SavingsGoal goal = new PlanStore(this, source).loadGoal();
            if (goal == null) throw new IllegalArgumentException("此账本还没有原存钱目标可复制。");
            MoneyCoach.State latest = store.load();
            for (MoneyCoach.Wish wish : latest.wishes) if (!hasDraft[wish.slot] && wish.title.isEmpty() && wish.reason.isEmpty()
                    && !wish.priority && wish.targetMinor == 0 && wish.savedMinor == 0 && wish.targetDate == null && wish.imageUri.isEmpty()) {
                store.saveWish(new MoneyCoach.Wish(wish.slot, goal.name, "从原存钱目标复制；已存金额仍是手动记录，没有迁移资金。",
                        false, goal.targetMinor, goal.savedMinor, goal.targetDate, "")); copied[0] = wish.slot; return;
            }
            throw new IllegalArgumentException("没有空白且无草稿的愿望位置。请先整理一个空位置，再复制。");
        }, false, () -> { section = 1; wishSlot = copied[0]; });
    }

    private void dreamPreview(String imageUri) {
        final int generation = imageGeneration;
        final Uri uri = Uri.parse(imageUri);
        ImageView image = new ImageView(this);
        image.setContentDescription("梦想图片缩略预览，点击查看");
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        image.setOnClickListener(v -> viewDreamImage(imageUri));
        previewView = image;
        body.addView(image, new LinearLayout.LayoutParams(-1, dp(190)));
        TextView status = text("正在读取所选文件的缩略预览…", 13, MUTED, false);
        body.addView(status);
        IMAGE_IO.execute(() -> {
            if (generation != imageGeneration) return;
            Bitmap bitmap = null;
            String label = "";
            try {
                if (!"content".equals(uri.getScheme())) throw new java.io.IOException();
                String name = "所选图片";
                try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex) && cursor.getLong(sizeIndex) > 32L * 1024 * 1024) throw new java.io.IOException();
                    }
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream stream = limitedImageStream(uri)) { BitmapFactory.decodeStream(stream, null, options); }
                int width = options.outWidth, height = options.outHeight;
                if (width <= 0 || height <= 0 || width > 100000 || height > 100000
                        || (long) width * height > 500000000L || options.outMimeType == null
                        || !options.outMimeType.startsWith("image/")) throw new java.io.IOException();
                options.inSampleSize = 1;
                while ((width + (long) options.inSampleSize - 1) / options.inSampleSize > 512
                        || (height + (long) options.inSampleSize - 1) / options.inSampleSize > 512) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false; options.inScaled = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                if (generation != imageGeneration) return;
                try (InputStream stream = limitedImageStream(uri)) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
                if (bitmap == null) throw new java.io.IOException();
                if (bitmap.getWidth() > 1024 || bitmap.getHeight() > 1024) { bitmap.recycle(); bitmap = null; throw new java.io.IOException(); }
                name = name == null ? "所选图片" : name.replace('\n', ' ').replace('\r', ' ');
                if (name.length() > 120) name = name.substring(0, 120) + "…";
                label = "梦想图片：" + name + "\n点缩略图可打开原图。图片保留在原位置；移动或删除原文件后，请重新选择。";
            } catch (Exception | OutOfMemoryError error) {
                if (bitmap != null) { bitmap.recycle(); bitmap = null; }
                label = "无法预览这张图片：可能已移动、权限失效、文件过大或格式不支持。可以重新选择，愿望文字仍保留。";
            }
            final Bitmap result = bitmap;
            final String detail = label;
            main.post(() -> {
                if (!alive() || generation != imageGeneration || previewView != image) {
                    if (result != null) result.recycle(); return;
                }
                image.setImageBitmap(result);
                image.setVisibility(result == null ? View.GONE : View.VISIBLE);
                status.setText(detail);
            });
        });
    }

    @android.annotation.SuppressLint("Recycle") // Ownership transfers to FilterInputStream; both callers use try-with-resources.
    private InputStream limitedImageStream(Uri uri) throws java.io.IOException {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new java.io.IOException();
        return new java.io.FilterInputStream(input) {
            private long remaining = 32L * 1024 * 1024;
            @Override public int read() throws java.io.IOException {
                int value = in.read();
                if (value != -1 && --remaining < 0) throw new java.io.IOException();
                return value;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                int count = in.read(buffer, offset, (int) Math.min(length, remaining + 1));
                if (count > 0 && (remaining -= count) < 0) throw new java.io.IOException();
                return count;
            }
        };
    }

    private void releasePreview() {
        imageGeneration++;
        if (previewView != null) previewView.setImageDrawable(null);
        // A displayed bitmap may still be referenced by a renderer; detach and let GC release it.
        previewView = null;
    }

    @Override protected void onDestroy() { releasePreview(); super.onDestroy(); }

    @Override public void onBackPressed() {
        if (busy) { Toast.makeText(this, "正在保存或读取，请稍等完成。", Toast.LENGTH_SHORT).show(); return; }
        super.onBackPressed();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode != PICK_DREAM_IMAGE) return;
        int slot = pendingImageSlot;
        pendingImageSlot = -1;
        if (resultCode != RESULT_OK || result == null || result.getData() == null || slot < 0 || slot > 9) return;
        Uri uri = result.getData();
        if (!"content".equals(uri.getScheme())) { message("无法使用这张图片", "请选择系统文件选择器中的图片。原草稿仍保留。"); return; }
        try {
            int flags = result.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags == 0) throw new SecurityException("No read grant");
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            drafts.putString("s1_w" + slot + "_image_uri", uri.toString());
            section = 1;
            wishSlot = slot;
            render();
        } catch (SecurityException error) {
            message("图片访问权限未保留", "请换一个允许持续读取的图片文件。原图片与文字草稿仍保留。");
        }
    }

    private void viewDreamImage(String imageUri) {
        remember();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(imageUri), "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException error) {
            message("没有图片查看器", "图片关联已保留；可安装图片查看器后打开。");
        } catch (SecurityException error) {
            message("图片暂时无法打开", "文件可能已移动或访问权限已变化。可以重新选择，文字记录不受影响。");
        }
    }

    // Short original summaries and actionable mappings; no book text or illustrations are bundled.
    private static final String[][] METHODS = {
        {"钱对我的意义", "第一册第 1 章《白色的拉布拉多犬》", "写十个自己的理由，可以分次补齐。先问它为何重要，愿望不一定都需要很多钱。", "写愿望与理由", "1"},
        {"三个重点", "第一册第 2 章《梦想储蓄罐和梦想相册》", "从愿望中选最多三个当前重点，其他愿望保留。每天回看方向，再选择下一步。", "选择重点", "1"},
        {"梦想相册", "第一册第 2 章", "通过具体画面提醒自己想要的生活，然后行动。选择自己的图片并保存到愿望，可随时打开回看；图片不代表支出凭证。", "给梦想选一张图片", "1"},
        {"梦想储蓄罐", "第一册第 2 章", "给具体目标留钱。愿望页保存目标与手填进度；钱罐页记录已到账的钱如何安排用途，两者不自动相加。", "打开梦想钱罐", "finance:0"},
        {"量入为出与购买取舍", "第一册第 2、6 章", "收入增加不能代替分配。回顾一笔消费的用途、必要性及替代方案；不把少花钱当作品格高低。账本首页已有消费试算。", "写本周取舍", "6"},
        {"成功日记", "第一册第 3 章《达瑞，一个很会挣钱的男孩》", "每天记五件成功小事是书中建议。写实际做过的事；少于五件也可保存，不必为了凑数虚构。", "写成功日记", "3"},
        {"从事实建立信心", "第一册第 11、13、15 章", "补充自己做对了什么，困难或新挑战之前再回看；好成绩不是唯一值得记录的事。", "回看我的证据", "3"},
        {"约十分钟的重要练习", "第一册第 5 章《钱钱以前的主人》", "留一个适合自己的时段，回顾目标并记录成功；早起是人物选择，忙碌时也允许缩小练习。", "回到今天", "0"},
        {"72 小时行动", "第一册第 5 章", "把决定拆成能在 72 小时内完成的小事。这不是消费冷静期，也不是三天完成全部梦想。", "开始小行动", "4"},
        {"从已有能力出发", "第一册第 3 章", "列自己知道、会做和拥有的资源，再选一个能尝试的方向。", "记录能力证据", "3"},
        {"解决他人的具体问题", "第一册第 3、4 章", "先询问谁有什么需要，再尝试提供服务。把需求、时间、成本、待收与实际到账放在一个项目里。", "写一个收入项目", "finance:1"},
        {"寻找机会与合作", "第一册第 4、8、18 章", "向有经验的人请教，尝试不同机会，明确分工与报酬。不要机械复制人物的生意。", "记录机会与合作项目", "finance:1"},
        {"待收与实际净收入", "第一册第 3、17、18 章", "项目把预计报酬、实际到账、成本与时间分开；结果由实际记录计算，未到账的钱不能当现金分配。", "核对项目结果", "finance:1"},
        {"停止新增消费债务的习惯", "第一册第 6 章《爸爸妈妈犯下的错误》", "先看是什么消费方式造成压力，再选择一个可改变的行为。书中停用信用卡的情境不是对所有人的统一指令。", "记录一个调整", "4"},
        {"还款计划留出生活空间", "第一册第 6、11、13 章", "整理合同要求、必要生活费和可协商的问题，在资产债务页保存当前手动盘点。软件不会修改合同或自动延迟还款。", "核对债务记录", "finance:2"},
        {"债务中的半存半还例子", "第一册第 6 章", "原文针对消费贷及生活费之外的余钱。实验先扣除必要开支与合同要求，再看假设余款；不适用于住房贷或实际还款决策。", "观察余款数学示例", "learn:2"},
        {"财务沟通与求助", "第一册第 6、11、13 章", "带着真实情况向适当的人求助，记录谈妥的结果。故事里降低月供的比例不能套到自己的账单上。", "准备一次沟通", "4"},
        {"鹅与金蛋", "第一册第 7、9 章", "区分长期积累本金和它实际产生的收益。钱罐记录用途，假设收益不能写进真实收入。", "查看长期积累钱罐", "finance:0"},
        {"三类用途分配", "第一册第 7 章《在金先生家》", "日常、梦想、长期积累的比例由自己确定。吉娅的 50/40/10 只是例子；先留足必要开支，再确认可分配额。", "去钱罐安排用途", "finance:0"},
        {"钱在哪里与准备做什么", "第一册第 9、12 章", "账户位置与用途是两回事。钱罐之间调整用途不是新增收入，资产清单另行记录钱在哪里。", "检查钱罐用途", "finance:0"},
        {"本人资产清单", "第一册第 10 章《在地下室里》的情境改编", "整理自己掌握的现金、存款、负债及未知项，保存当前手动盘点。估值不直接当作可用现金。", "整理资产与债务", "finance:2"},
        {"应急与流动资金", "第一册第 12、17 章", "留出近期需要使用的钱。书中 10% 与 20% 的情境口径不同，不给每位学生强设同一比例。", "设自己的准备目标", "1"},
        {"面对恐惧，按能力行动", "第一册第 10、12、15 章", "说明担心什么、已有准备和需要的帮助，再做可控的小尝试。冒险不是勇敢的唯一证明。", "缩小下一步", "4"},
        {"定期共同复盘", "第一册第 14 章《投资俱乐部》", "书中五条为每月聚会、每人出席、交约定出资、不取出累积金、共同决策。个人产品借用月度复盘，不组织真实集资。", "写本月回顾", "9"},
        {"投资前先理解", "第一册第 14、16 章", "先提出资金期限、可能损失、是否理解和未知项。本期可记学习行动，尚无真实产品研究或买卖功能。", "记一个学习问题", "4"},
        {"时间与分散的实验", "第一册第 16 章《俱乐部的投资行动》", "改变项目数和跌幅，对比集中、仅一项下跌与共同下跌。其他项目不变只是特定假设，长期与分散都不等于保本。", "比较三个假设场景", "learn:1"},
        {"历史基金研究清单", "第一册第 16 章", "书中讨论历史、规模、地区、持仓和波动。这是历史方法，不能按旧规则或过去排名自动推荐现实产品。", "记录尚不理解的概念", "4"},
        {"复利、72 法则与通胀", "第一册第 16、17 章及附录", "72 除以正年率百分数值近似得到一次性本金的翻倍年数。试算还可改变期末追加、年数与通胀，比较名义金额和购买力。", "打开复利与购买力实验", "learn:0"},
        {"波动与流动性", "第一册第 17、18 章", "区分浮动损益、实际支出和何时需要钱。不能把未卖出当作没有损失，或按下跌幅度给自己设置自动买入指令。", "写下决策问题", "6"},
        {"持续学习与自主判断", "第一册第 18 章与成年人的后记", "回顾经验、向擅长的人学习，也保留自己的判断。成人后记强调真实实践、公平和责任。", "写本周调整", "6"},
        {"慈善与公益", "第一册前言财富法则第 18 项", "回馈可以出于自己的意愿。原书此处没有统一比例或金额，软件不强制捐赠或额外设第四个资金桶。", "想一件可做的帮助", "5"},
        {"金钱与幸福", "第一册第 12 章；第二册第 2、4 章", "金钱服务生活，学习、关系和能力也有价值。余额不能代表幸福或人的价值。", "回看真正重要的愿望", "1"},
        {"每日一条，长期实践", "第二册第 9 章《好老师的秘密》", "每天专注一张卡，七天后继续。正面写自己的理解，背面记经历；七天不是人格通关。", "写准则理解与经历", "5"},
        {"尊重、承担与善待", "第二册第 7、9、10 章与附录", "周一友好亲和、周二勇于承担、周三善待他人。选择可控动作、看见他人长处，同时保留拒绝与求助的边界。", "查看每日准则", "5"},
        {"帮助、感恩、学习与守约", "第二册第 10 章与附录", "周四帮助给予、周五感恩之心、周六勤学不辍、周日值得信赖。真实做一件事，再回顾结果。", "写一次实践", "5"},
        {"非金钱的帮助", "第二册第 3、10 章", "给予可以是时间和技能；书中制作卡片与实际打扫是例子。先确认需要，再履行，不计算人情回报。", "安排一次实际帮助", "4"},
        {"感恩变成表达", "第二册第 13 章《回家》", "先想到值得感谢的人和事，再写一封自己的感谢信。是否发送由你决定，软件不替你联系他人。", "记录感谢与经历", "5"},
        {"关系账户", "第二册第 12、13 章", "通过平时的关心与守约建立信任，误会时核对事实并修复。不把朋友换算成余额，也不把过去付出当作伤害的额度。", "写一次关系复盘", "6"},
        {"向榜样学习", "第二册第 10、13 章", "写你欣赏某人的哪种做法，再想自己可以怎样回应。记录是你的判断，不是假装榜样亲自回答。", "记下理解与选择", "5"},
        {"练习、反馈、再练习", "第二册第 7、10、11 章", "表达真实经历，说明希望听众采取的动作；先向熟悉的人练习，再根据具体反馈改一处。人物的三天频率可自行调整。", "开始一次练习", "4"},
        {"人有不同长处，也能互学", "第二册第 6 章《寄宿学校》", "尊重差异与多重归属，找出可互相学习的长处。不按身份评价，不把自己与别人财富比较。", "记录学到的东西", "3"},
        {"人的价值与新的方向", "第二册第 2、11 章", "人的价值不由财富或成绩决定。目标达成后可以选择新方向；失败也不减损人的价值。", "更新我的愿望", "1"},
        {"适当求助并逐渐自主", "第二册第 8、13、14 章", "挑战前想清楚可控范围和需要的帮助。经验积累后可以减少提示；魔法、危险营救与医疗奇迹都只是故事。", "写下一步与所需帮助", "4"}
    };

    private MoneyCoach.Theme theme(LocalDate date) { return MoneyCoach.THEMES.get(date.getDayOfWeek().getValue() - 1); }
    private MoneyCoach.SuccessEntry success(String date) { for (MoneyCoach.SuccessEntry item : data.successes) if (item.date.toString().equals(date)) return item; return null; }
    private MoneyCoach.Action actionRecord(String id) { for (MoneyCoach.Action item : data.actions) if (item.id.equals(id)) return item; return null; }
    private MoneyCoach.AllocationRecord allocation(String id) { for (MoneyCoach.AllocationRecord item : data.allocations) if (item.id.equals(id)) return item; return null; }
    private MoneyCoach.PracticeEntry practice(LocalDate date) { for (MoneyCoach.PracticeEntry item : data.practices) if (item.date.equals(date)) return item; return null; }
    private MoneyCoach.WeeklyReview review(LocalDate week) { for (MoneyCoach.WeeklyReview item : data.reviews) if (item.weekStart.equals(week)) return item; return null; }

    private void commit(Runnable mutation) { commit(mutation, true); }
    private void commit(Runnable mutation, boolean discardForm) { commit(mutation, discardForm, () -> { }); }
    private void commit(Runnable mutation, boolean discardForm, Runnable afterSave) {
        if (busy || data == null) return;
        remember();
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, EditText> input : inputs.entrySet()) {
            snapshot.put(input.getKey().substring(prefix.length()), input.getValue().getText().toString().trim());
        }
        setBusy(true, "正在保存，填写的内容会保留…");
        final boolean savingMonth = section == 9;
        IO.execute(() -> {
            MoneyCoach.State loaded = null;
            List<MonthlyReview> monthly = null;
            RuntimeException failure = null;
            IO_INPUTS.set(snapshot);
            try { mutation.run(); loaded = store.load(); if (savingMonth) monthly = monthlyStore.load(); }
            catch (RuntimeException error) { failure = error; }
            finally { IO_INPUTS.remove(); }
            final MoneyCoach.State result = loaded;
            final RuntimeException error = failure;
            final List<MonthlyReview> loadedMonthly = monthly;
            main.post(() -> {
                if (!alive()) return;
                setBusy(false, "");
                if (error != null) { report(error); return; }
                if (discardForm) clearDraft();
                afterSave.run();
                data = result;
                if (savingMonth) { monthlyData = loadedMonthly; monthlyReadable = true; }
                render();
                Toast.makeText(this, "已保存到" + (LedgerStore.DEMO.equals(source) ? "演示练习" : "本机练习"), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void guarded(Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException error) { report(error); }
    }

    private void report(RuntimeException error) {
        if (error instanceof java.time.DateTimeException) message("请检查日期", "使用 YYYY-MM-DD，例如 2026-10-03，并填写实际存在的日期。");
        else if (error instanceof ArithmeticException) message("请检查金额", "请填写范围内的金额，最多两位小数。");
        else if (error instanceof IllegalArgumentException) message("请检查填写内容", fallback(error.getMessage(), "请检查名称、金额、比例和日期。"));
        else message("未能保存", "本机练习暂时无法操作。已填写内容仍保留，请稍后再试；现有记录没有被清空。");
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void setBusy(boolean saving, String status) {
        busy = saving;
        setEnabled(rootView, !saving);
        ioStatus.setText(status);
        ioStatus.setVisibility(saving ? View.VISIBLE : View.GONE);
    }
    private void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
        }
    }

    private void remember() {
        for (Map.Entry<String, EditText> input : inputs.entrySet()) drafts.putString(input.getKey(), input.getValue().getText().toString());
        if (priority != null) drafts.putBoolean(prefix + "priority", priority.isChecked());
    }

    private void clearDraft() {
        for (String key : new ArrayList<>(drafts.keySet())) if (key.startsWith(prefix)) drafts.remove(key);
    }

    private void clearSectionDraft(int page) {
        String start = "s" + page + "_";
        for (String key : new ArrayList<>(drafts.keySet())) if (key.startsWith(start)) drafts.remove(key);
    }

    private void moreHistory(int total) {
        if (total <= historyLimit) return;
        note("已显示最近 " + historyLimit + " 条，共保存 " + total + " 条。");
        action("再显示 20 条历史记录", () -> {
            remember();
            historyLimit = Math.min(total, historyLimit + 20);
            restoreScroll = scroll.getScrollY();
            render();
        }, false);
    }

    private EditText field(String key, String label, String initial, String hint, int max, boolean multiline) {
        TextView caption = text(label, 14, INK, true);
        caption.setPadding(0, dp(15), 0, dp(7));
        body.addView(caption);
        EditText input = new EditText(this);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        input.setContentDescription(label);
        input.setSaveEnabled(false);
        String content = drafts.getString(prefix + key, initial == null ? "" : initial);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Math.max(max, content.length()))});
        input.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : 0));
        input.setSingleLine(!multiline);
        if (multiline) { input.setMinLines(2); input.setGravity(Gravity.TOP); }
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(round(Color.WHITE));
        input.setText(content);
        inputs.put(prefix + key, input);
        body.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void moneyField(String key, String label, long minor) {
        field(key, label, minor == 0 ? "" : currency(minor), "0.00", 18, false).setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    }
    private void numberField(String key, String label, int number) { field(key, label, String.valueOf(number), "0—100", 3, false).setInputType(InputType.TYPE_CLASS_NUMBER); }
    private String value(String key) {
        Map<String, String> snapshot = IO_INPUTS.get();
        return snapshot == null ? inputs.get(prefix + key).getText().toString().trim() : snapshot.get(key);
    }
    private long money(String key) {
        String raw = value(key);
        if (raw.isEmpty()) return 0;
        if (!raw.matches("[0-9]+(?:\\.[0-9]{1,2})?")) throw new IllegalArgumentException("金额请使用非负数字，最多两位小数。");
        return new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }
    private int integer(String key) { try { return Integer.parseInt(value(key)); } catch (NumberFormatException error) { throw new IllegalArgumentException("三类比例请都填写 0—100 的整数，合计 100%。"); } }
    private static LocalDate optionalDate(String text) { return text.isEmpty() ? null : LocalDate.parse(text); }
    private static String currency(long minor) { return BigDecimal.valueOf(minor, 2).toPlainString(); }
    private static String fallback(String value, String other) { return value == null || value.isEmpty() ? other : value; }
    private static String stamp(long time) { return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); }
    private void heading(String title, String explanation) { body.addView(text(title, 23, INK, true)); note(explanation); }
    private void subheading(String title) { TextView view = text(title, 19, INK, true); view.setPadding(0, dp(28), 0, dp(8)); body.addView(view); }
    private void note(String value) { TextView view = text(value, 14, MUTED, false); view.setPadding(0, dp(10), 0, dp(10)); view.setLineSpacing(dp(3), 1); body.addView(view); }
    private void line(String title, String detail) { subheading(title); note(detail); }
    private void action(String label, Runnable click, boolean primary) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextSize(14);
        view.setTextColor(primary ? Color.WHITE : GREEN);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(round(primary ? GREEN : PALE));
        view.setStateListAnimator(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        body.addView(view, params);
        view.setOnClickListener(v -> click.run());
    }
    private Spinner spinner(String[] values, int selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(48));
        spinner.setSelection(selected);
        return spinner;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String content, float size, int color, boolean bold) { TextView view = new TextView(this); view.setText(content); view.setTextSize(size); view.setTextColor(color); view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); return view; }
    private GradientDrawable round(int color) { GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(12)); return background; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void message(String title, String detail) { new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("知道了", null).show(); }
    private void confirm(String title, String detail, Runnable action) { new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setNegativeButton("取消", null).setPositiveButton("确认", (dialog, which) -> action.run()).show(); }
}
