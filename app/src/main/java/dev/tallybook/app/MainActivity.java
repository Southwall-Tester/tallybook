package dev.tallybook.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.tallybook.core.Transaction;
import dev.tallybook.core.WechatBillParser;
import dev.tallybook.app.capture.CaptureDiagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A deliberately small local ledger with visible boundaries around the capture experiment. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(245, 246, 242);
    private static final int INK = Color.rgb(25, 47, 38);
    private static final int MUTED = Color.rgb(108, 120, 111);
    private static final int GREEN = Color.rgb(27, 69, 53);
    private static final int PALE = Color.rgb(229, 237, 225);
    private static final int WHITE = Color.WHITE;
    private static final int AMBER = Color.rgb(139, 100, 36);
    private static final int REQUEST_IMPORT = 101;
    private static final int REQUEST_EXPORT = 102;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final Uri EVENTS = Uri.parse("content://dev.tallybook.app.capture/events");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Transaction> records = new ArrayList<>();
    private String source = LedgerStore.WECHAT;
    private String pendingExportSource = LedgerStore.WECHAT;
    private int page = 0;
    private boolean busy = false;
    private boolean loading = true;
    private String loadError = "";
    private long loadGeneration = 0;
    private LinearLayout shell;
    private LinearLayout content;
    private TextView captureStatus;
    private TextView captureTiming;
    private TextView captureReceived;
    private TextView captureHook;
    private TextView captureDiagnostic;
    private Button captureToggle;

    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            loadRecords();
            updateCaptureStatus();
        }
    };
    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            updateCaptureStatus();
            main.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            source = LedgerStore.DEMO.equals(state.getString("source")) ? LedgerStore.DEMO : LedgerStore.WECHAT;
            pendingExportSource = LedgerStore.DEMO.equals(state.getString("pending_export")) ? LedgerStore.DEMO : LedgerStore.WECHAT;
            page = Math.max(0, Math.min(2, state.getInt("page", 0)));
        }
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(WHITE);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        getContentResolver().registerContentObserver(EVENTS, true, observer);
        main.removeCallbacks(statusTicker);
        main.post(statusTicker);
        loadRecords();
    }

    @Override protected void onPause() {
        main.removeCallbacks(statusTicker);
        getContentResolver().unregisterContentObserver(observer);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdown();
        main.removeCallbacks(statusTicker);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("source", source);
        state.putString("pending_export", pendingExportSource);
        state.putInt("page", page);
        super.onSaveInstanceState(state);
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private void loadRecords() {
        if (io.isShutdown()) return;
        final String requestedSource = source;
        final long generation = ++loadGeneration;
        io.execute(() -> {
            List<Transaction> loaded;
            String error = "";
            try (LedgerStore store = new LedgerStore(this)) {
                loaded = store.list(requestedSource);
            } catch (Exception exception) {
                loaded = new ArrayList<>();
                error = "本机账本暂时无法读取。数据没有被清空，请重新打开后再试。";
            }
            final List<Transaction> result = loaded;
            final String finalError = error;
            main.post(() -> {
                if (!alive() || generation != loadGeneration || !requestedSource.equals(source)) return;
                records.clear();
                records.addAll(result);
                loading = false;
                loadError = finalError;
                render();
            });
        });
    }

    private void render() {
        captureStatus = null;
        captureTiming = null;
        captureReceived = null;
        captureHook = null;
        captureDiagnostic = null;
        captureToggle = null;
        shell = column();
        shell.setBackgroundColor(BG);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(shell);
        shell.requestApplyInsets();

        LinearLayout heading = row();
        heading.setPadding(dp(24), dp(18), dp(24), dp(12));
        LinearLayout titles = column();
        titles.addView(label("TALLYBOOK", 11, MUTED, true));
        TextView title = label(page == 0 ? "我的账本" : page == 1 ? "微信采集" : "本机与数据", 29, INK, true);
        title.setPadding(0, dp(5), 0, 0);
        titles.addView(title);
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = label("实验版", 11, GREEN, true);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(round(PALE, 20));
        heading.addView(badge);
        shell.addView(heading);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = column();
        content.setPadding(dp(20), dp(7), dp(20), dp(24));
        scroll.addView(content);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (page == 0) renderLedger();
        else if (page == 1) renderCapture();
        else renderSettings();

        LinearLayout navigation = row();
        navigation.setPadding(dp(12), dp(8), dp(12), dp(8));
        navigation.setBackgroundColor(WHITE);
        String[] tabs = {"账本", "采集", "设置"};
        for (int i = 0; i < tabs.length; i++) {
            final int selected = i;
            Button tab = button(tabs[i], false);
            tab.setTextColor(page == i ? GREEN : MUTED);
            tab.setBackground(round(page == i ? PALE : WHITE, 13));
            tab.setOnClickListener(v -> { page = selected; render(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMargins(dp(4), 0, dp(4), 0);
            navigation.addView(tab, params);
        }
        shell.addView(navigation);
    }

    private void renderLedger() {
        LinearLayout switcher = row();
        switcher.setPadding(dp(4), dp(4), dp(4), dp(4));
        switcher.setBackground(round(PALE, 14));
        Button real = button("真实账本", false);
        Button demo = button("演示账本", false);
        real.setBackground(round(isDemo() ? PALE : WHITE, 11));
        demo.setBackground(round(isDemo() ? WHITE : PALE, 11));
        real.setOnClickListener(v -> switchLedger(LedgerStore.WECHAT));
        demo.setOnClickListener(v -> enterDemo());
        switcher.addView(real, new LinearLayout.LayoutParams(0, dp(42), 1));
        switcher.addView(demo, new LinearLayout.LayoutParams(0, dp(42), 1));
        content.addView(switcher);
        addSpace(content, 15);

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        int review = 0;
        for (Transaction transaction : records) {
            if (transaction.reviewRequired) { review++; continue; }
            BigDecimal amount = BigDecimal.valueOf(transaction.amountMinor, 2);
            if (amount.signum() >= 0) income = income.add(amount);
            else expense = expense.subtract(amount);
        }
        LinearLayout summary = card(GREEN);
        summary.addView(label(isDemo() ? "演示数据 · 全部为虚构" : "真实账本 · 已确认记录", 12, Color.rgb(205, 223, 202), false));
        addSpace(summary, 19);
        summary.addView(label("支出合计", 13, Color.rgb(219, 232, 216), false));
        TextView total = label("¥ " + decimal(expense), 38, WHITE, true);
        total.setPadding(0, dp(3), 0, dp(22));
        summary.addView(total);
        LinearLayout amounts = row();
        amounts.addView(metric("收入", "¥ " + decimal(income)), new LinearLayout.LayoutParams(0, -2, 1));
        amounts.addView(metric("结余", "¥ " + decimal(income.subtract(expense))), new LinearLayout.LayoutParams(0, -2, 1));
        summary.addView(amounts);
        content.addView(summary);
        addSpace(content, 10);
        TextView note = label(isDemo() ? "虚构样例与真实账本分开保存，采集数据只进入真实账本。"
                : "仅统计本机已导入记录，不代表微信账户的全部收支。", 12, MUTED, false);
        note.setLineSpacing(dp(3), 1);
        content.addView(note);
        if (review > 0) {
            addSpace(content, 10);
            TextView reviewNotice = label(review + " 笔待核对，暂不计入汇总（包括退款等未确认情况）。", 12, AMBER, false);
            reviewNotice.setLineSpacing(dp(3), 1);
            content.addView(reviewNotice);
        }
        addSpace(content, 24);

        LinearLayout listHeading = row();
        listHeading.addView(label("交易记录", 19, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        listHeading.addView(label(loading ? "读取中" : records.size() + " 笔", 12, MUTED, false));
        content.addView(listHeading);
        addSpace(content, 12);
        if (!loadError.isEmpty()) {
            LinearLayout error = card(WHITE);
            error.addView(label(loadError, 14, AMBER, false));
            Button retry = button("重新读取", false);
            retry.setOnClickListener(v -> loadRecords());
            addSpace(error, 12);
            error.addView(retry);
            content.addView(error);
        } else if (records.isEmpty()) {
            LinearLayout empty = card(WHITE);
            empty.addView(label(loading ? "正在读取本机账本…" : "从第一笔开始", 21, INK, true));
            addSpace(empty, 9);
            TextView body = label(loading ? "稍等片刻。" : "查看页采集仍是实验功能。你可以先体验虚构账本，或导入一份支持的微信详情 JSON。", 14, MUTED, false);
            body.setLineSpacing(dp(5), 1);
            empty.addView(body);
            addSpace(empty, 19);
            Button example = button(busy ? "正在处理…" : "体验演示账本", true);
            example.setEnabled(!busy && !loading);
            example.setOnClickListener(v -> enterDemo());
            empty.addView(example);
            if (!isDemo()) {
                addSpace(empty, 9);
                Button importing = button("导入 JSON", false);
                importing.setEnabled(!busy);
                importing.setOnClickListener(v -> startImport());
                empty.addView(importing);
            }
            content.addView(empty);
        } else {
            LinearLayout list = card(WHITE);
            list.setPadding(dp(16), dp(2), dp(16), dp(2));
            int shown = Math.min(100, records.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) addDivider(list);
                list.addView(transactionRow(records.get(i)));
            }
            content.addView(list);
            if (records.size() > shown) {
                addSpace(content, 9);
                content.addView(label("此处显示最近 100 笔；汇总和 CSV 导出包含当前账本全部记录。", 12, MUTED, false));
            }
            addSpace(content, 15);
            Button next = button(isDemo() ? "回到真实账本" : "继续采集 / 导入", false);
            next.setOnClickListener(v -> { if (isDemo()) switchLedger(LedgerStore.WECHAT); else { page = 1; render(); } });
            content.addView(next);
        }
    }

    private LinearLayout metric(String name, String value) {
        LinearLayout box = column();
        box.addView(label(name, 12, Color.rgb(202, 220, 198), false));
        TextView text = label(value, 18, WHITE, true);
        text.setPadding(0, dp(5), 0, 0);
        box.addView(text);
        return box;
    }

    private View transactionRow(Transaction transaction) {
        LinearLayout row = row();
        row.setPadding(0, dp(17), 0, dp(17));
        TextView glyph = label(transaction.reviewRequired ? "?" : transaction.amountMinor >= 0 ? "+" : "−", 22,
                transaction.reviewRequired ? AMBER : GREEN, true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(round(transaction.reviewRequired ? Color.rgb(250, 241, 220) : PALE, 13));
        row.addView(glyph, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout description = column();
        description.setPadding(dp(12), 0, dp(9), 0);
        TextView merchant = label(valueOr(transaction.counterparty, "未识别交易方"), 15, INK, true);
        merchant.setMaxLines(2);
        description.addView(merchant);
        TextView when = label(formatDate(transaction.occurredAt, "MM月dd日 HH:mm") + " · "
                + (transaction.reviewRequired ? "待核对" : valueOr(transaction.status, "状态未知")), 11,
                transaction.reviewRequired ? AMBER : MUTED, false);
        when.setPadding(0, dp(5), 0, 0);
        when.setMaxLines(2);
        description.addView(when);
        row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = label(signedAmount(transaction.amountMinor), 17,
                transaction.amountMinor > 0 ? GREEN : INK, true);
        row.addView(amount);
        row.setOnClickListener(v -> showTransaction(transaction));
        row.setContentDescription(valueOr(transaction.counterparty, "未识别交易方") + "，" + signedAmount(transaction.amountMinor)
                + "元，" + (transaction.reviewRequired ? "待核对" : valueOr(transaction.status, "状态未知")) + "，查看详情");
        return row;
    }

    private void showTransaction(Transaction transaction) {
        String body = "金额：" + signedAmount(transaction.amountMinor) + " 元\n"
                + "时间：" + formatDate(transaction.occurredAt, "yyyy年MM月dd日 HH:mm:ss") + "\n"
                + "状态：" + valueOr(transaction.status, "未提供") + "\n"
                + "支付方式：" + valueOr(transaction.paymentMethod, "未提供") + "\n"
                + "商品 / 说明：" + valueOr(transaction.description, "未提供") + "\n"
                + "交易单号：" + valueOr(transaction.tradeId, "未提供") + "\n\n"
                + (transaction.reviewRequired ? "待核对：" + valueOr(transaction.reviewReason, "信息不足，暂不计入汇总。")
                : "该记录计入收支汇总。") + "\n\n"
                + (isDemo() ? "这是纯虚构演示记录。" : "来源：本机接收或手动导入的微信详情数据。请与微信原始账单核对。") ;
        TextView detail = label(body, 14, INK, false);
        detail.setTextIsSelectable(true);
        detail.setLineSpacing(dp(6), 1);
        detail.setPadding(dp(24), dp(12), dp(24), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(detail);
        new AlertDialog.Builder(this).setTitle(valueOr(transaction.counterparty, "交易详情"))
                .setView(scroll).setPositiveButton("知道了", null).show();
    }

    private void renderCapture() {
        TextView introduction = label("在你查看微信账单详情时，尝试读取页面已经收到的数据。", 15, MUTED, false);
        introduction.setLineSpacing(dp(5), 1);
        content.addView(introduction);
        addSpace(content, 18);

        LinearLayout status = card(WHITE);
        status.addView(label("本机采集窗口", 12, MUTED, true));
        addSpace(status, 9);
        captureStatus = label("", 23, INK, true);
        status.addView(captureStatus);
        captureTiming = label("", 13, MUTED, false);
        captureTiming.setPadding(0, dp(7), 0, dp(16));
        status.addView(captureTiming);
        captureToggle = button("", true);
        captureToggle.setOnClickListener(v -> {
            if (CaptureSettings.isEnabled(this)) {
                CaptureSettings.disable(this);
                getContentResolver().notifyChange(EVENTS, null);
                updateCaptureStatus();
            } else confirmCapture();
        });
        status.addView(captureToggle);
        addSpace(status, 10);
        Button wechat = button("打开微信查看账单", false);
        wechat.setOnClickListener(v -> openWechat());
        status.addView(wechat);
        content.addView(status);
        addSpace(content, 14);

        LinearLayout evidence = card(WHITE);
        evidence.addView(label("实际接收记录", 16, INK, true));
        addSpace(evidence, 12);
        captureReceived = label("", 13, MUTED, false);
        captureReceived.setLineSpacing(dp(5), 1);
        evidence.addView(captureReceived);
        captureHook = label("", 12, MUTED, false);
        captureHook.setPadding(0, dp(11), 0, 0);
        captureHook.setLineSpacing(dp(4), 1);
        evidence.addView(captureHook);
        captureDiagnostic = label("", 13, GREEN, false);
        captureDiagnostic.setPadding(0, dp(11), 0, 0);
        captureDiagnostic.setLineSpacing(dp(4), 1);
        evidence.addView(captureDiagnostic);
        addSpace(evidence, 11);
        evidence.addView(label("开启窗口不等于连接成功。收到可解析的交易后，真实账本才会新增或更新记录。", 12, MUTED, false));
        content.addView(evidence);
        addSpace(content, 21);
        content.addView(label("开始前需要知道", 19, INK, true));
        addSpace(content, 12);

        LinearLayout guide = card(WHITE);
        addStep(guide, "1", "需要对应运行环境", "此版采集使用 Xposed / LSPosed Hook。须在兼容环境中启用模块，并勾选微信作用域。普通未 Root 手机安装后可体验账本与 JSON 导入。");
        addDivider(guide);
        addStep(guide, "2", "打开具体账单详情", "启用模块后重启微信，再开启采集窗口。进入微信 → 我 → 服务 → 钱包 → 账单，打开一笔交易详情。菜单可能随微信版本变化。");
        addDivider(guide);
        addStep(guide, "3", "回到真实账本核对", "目前是详情页回调实验，不承诺批量列表、历史全量或每天后台自动同步。退款及信息不明的交易标记待核对，不计入汇总。");
        content.addView(guide);
        addSpace(content, 13);
        Button importing = button("没有对应环境？先导入 JSON 试验", false);
        importing.setEnabled(!busy);
        importing.setOnClickListener(v -> startImport());
        content.addView(importing);
        updateCaptureStatus();
    }

    private void updateCaptureStatus() {
        if (captureStatus == null) return;
        boolean enabled = CaptureSettings.isEnabled(this);
        captureStatus.setText(enabled ? "等待微信详情数据" : "采集已关闭");
        if (enabled) {
            long seconds = Math.max(0, (CaptureSettings.enabledUntil(this) - System.currentTimeMillis() + 999) / 1000);
            captureTiming.setText("剩余 " + (seconds / 60) + " 分 " + (seconds % 60) + " 秒 · 到时自动停止接收");
        } else captureTiming.setText("默认关闭，每次开启后最多接收 30 分钟。");
        captureToggle.setText(enabled ? "停止采集" : "开启 30 分钟采集");
        long last = CaptureSettings.lastCaptureAt(this);
        captureReceived.setText("最近收到交易：" + (last == 0 ? "尚未收到" : formatDate(last, "MM月dd日 HH:mm:ss"))
                + "\n累计采集新增：" + CaptureSettings.captureCount(this) + " 笔（不含手动导入）");
        long hook = CaptureSettings.lastHookAt(this);
        captureHook.setText("模块最近报告：" + (hook == 0 ? "尚无记录" : formatDate(hook, "MM月dd日 HH:mm:ss"))
                + "\n模块报告仅说明 Hook 已运行，不代表已读取账单。");
        String diagnostic = switch (CaptureDiagnostics.latest(this)) {
            case "HOOK_READY" -> "已收到模块报告，等待可识别的账单详情。";
            case "BILL_SAVED" -> "最近结果：已保存一笔交易到真实账本。";
            case "BILL_UPDATED" -> "最近结果：已更新已有交易，没有重复新增。";
            case "UNSUPPORTED_PAYLOAD" -> "最近结果：收到的数据格式暂不支持，未入账。微信版本可能与已适配结构不同。";
            case "PARSE_REJECTED" -> "最近结果：交易字段未通过校验，未入账。需要核对当前微信的详情格式。";
            case "CAPTURE_OFF" -> "最近结果：接收时采集窗口已关闭。";
            case "IPC_FAILED" -> "最近结果：模块与账本通信失败。请重新开启本应用与微信后尝试。";
            default -> "尚未收到模块或账单数据。";
        };
        captureDiagnostic.setText(diagnostic);
    }

    private void confirmCapture() {
        new AlertDialog.Builder(this).setTitle("开启查看页采集？")
                .setMessage("接下来 30 分钟，允许本模块接收微信账单详情数据并保存到本机真实账本。\n\n需要已启用兼容的 Xposed / LSPosed 环境。可随时停止；不保存原始回调，不上传账单。")
                .setNegativeButton("暂不开启", null)
                .setPositiveButton("开启 30 分钟", (dialog, which) -> {
                    CaptureSettings.enableFor30Minutes(this);
                    getContentResolver().notifyChange(EVENTS, null);
                    updateCaptureStatus();
                }).show();
    }

    private void openWechat() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if (launch == null) { message("未找到微信", "请先安装微信；若已安装，请检查微信是否在其他用户或应用分身中。"); return; }
        try { startActivity(launch); }
        catch (ActivityNotFoundException | SecurityException error) { message("无法打开微信", "请从手机桌面打开微信，再进入账单详情页。"); }
    }

    private void addStep(LinearLayout parent, String number, String title, String body) {
        LinearLayout line = row();
        line.setGravity(Gravity.TOP);
        line.setPadding(0, dp(12), 0, dp(12));
        TextView marker = label(number, 13, GREEN, true);
        marker.setGravity(Gravity.CENTER);
        marker.setBackground(round(PALE, 10));
        line.addView(marker, new LinearLayout.LayoutParams(dp(27), dp(27)));
        LinearLayout words = column();
        words.setPadding(dp(12), dp(1), 0, 0);
        words.addView(label(title, 15, INK, true));
        TextView text = label(body, 13, MUTED, false);
        text.setPadding(0, dp(7), 0, 0);
        text.setLineSpacing(dp(4), 1);
        words.addView(text);
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(line);
    }

    private void renderSettings() {
        LinearLayout current = card(GREEN);
        current.addView(label("当前操作的账本", 12, Color.rgb(205, 223, 202), false));
        addSpace(current, 8);
        current.addView(label(isDemo() ? "演示账本" : "真实账本", 25, WHITE, true));
        addSpace(current, 8);
        current.addView(label(isDemo() ? "全部记录为虚构 · 与真实数据隔离" : "仅保存在此设备 · " + records.size() + " 笔记录", 13, WHITE, false));
        content.addView(current);
        addSpace(content, 18);

        LinearLayout files = card(WHITE);
        files.addView(label("导入与导出", 18, INK, true));
        addSpace(files, 10);
        TextView fileNote = label("JSON 导入支持已适配的微信详情回调或本应用规范交易格式，每个文件一笔，最大 256 KiB。未知格式会拒绝入账。", 13, MUTED, false);
        fileNote.setLineSpacing(dp(4), 1);
        files.addView(fileNote);
        addSpace(files, 14);
        Button importing = button("导入 JSON 到真实账本", true);
        importing.setEnabled(!busy);
        importing.setOnClickListener(v -> startImport());
        files.addView(importing);
        addSpace(files, 9);
        Button exporting = button("导出当前" + (isDemo() ? "演示" : "真实") + "账本 CSV", false);
        exporting.setEnabled(!busy && !loading && loadError.isEmpty());
        exporting.setOnClickListener(v -> startExport());
        files.addView(exporting);
        addSpace(files, 10);
        files.addView(label("导出为普通 CSV，包含单号等交易信息；请自行保管。UTF-8 编码，待核对记录也会导出并标明。", 12, MUTED, false));
        content.addView(files);
        addSpace(content, 14);

        LinearLayout data = card(WHITE);
        data.addView(label("数据与边界", 18, INK, true));
        addSpace(data, 10);
        TextView privacy = label("• 账单仅存本机 SQLite，不联网上传。\n• 不保存密码、登录态或完整原始回调。\n• 微信查看页结构变化时，采集可能失效。\n• 同一账本按交易标识去重，并更新后续状态。\n• 演示数据不能证明手机上的微信已采集成功。", 13, MUTED, false);
        privacy.setLineSpacing(dp(6), 1);
        data.addView(privacy);
        addSpace(data, 15);
        Button clear = button("清空当前" + (isDemo() ? "演示" : "真实") + "账本", false);
        clear.setTextColor(Color.rgb(150, 66, 57));
        clear.setEnabled(!busy && !loading && loadError.isEmpty());
        clear.setOnClickListener(v -> confirmClear());
        data.addView(clear);
        content.addView(data);
        addSpace(content, 17);
        content.addView(label("Tallybook · 本地安卓研究原型\n采集实现参考 AutoAccounting 的微信查看页 Hook 思路。", 11, MUTED, false));
    }

    private void switchLedger(String nextSource) {
        if (busy || source.equals(nextSource)) return;
        source = nextSource;
        records.clear();
        loading = true;
        loadError = "";
        render();
        loadRecords();
    }

    private void enterDemo() {
        if (busy) return;
        if (isDemo() && !records.isEmpty()) return;
        busy = true;
        render();
        io.execute(() -> {
            String failure = null;
            try (LedgerStore store = new LedgerStore(this)) {
                if (store.list(LedgerStore.DEMO).isEmpty()) {
                    int inserted = 0;
                    String[] names = {"demo-wechat.json", "demo-wechat-2.json", "demo-wechat-3.json", "demo-wechat-4.json"};
                    for (String name : names) {
                        try (InputStream stream = demoResource(name)) {
                            if (stream == null) continue;
                            var parsed = WechatBillParser.parse(readLimited(stream));
                            if (!parsed.isSuccess()) throw new IOException("演示文件格式无效");
                            if (store.insert(parsed.transaction, LedgerStore.DEMO)) inserted++;
                        }
                    }
                    if (inserted == 0) throw new IOException("缺少演示数据");
                }
            } catch (Exception exception) { failure = "无法加载演示数据。请重新安装完整的应用包后再试。"; }
            final String result = failure;
            main.post(() -> {
                if (!alive()) return;
                busy = false;
                if (result != null) { render(); message("演示暂时不可用", result); return; }
                source = LedgerStore.DEMO;
                page = 0;
                loading = true;
                records.clear();
                render();
                loadRecords();
            });
        });
    }

    private InputStream demoResource(String name) throws IOException {
        try { return getAssets().open(name); }
        catch (IOException missingAsset) { return WechatBillParser.class.getResourceAsStream("/" + name); }
    }

    private void startImport() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("导入到真实账本")
                .setMessage("选择一份微信详情 JSON 或本应用规范交易 JSON。每份文件一笔交易，最大 256 KiB。\n\n此功能用于验证解析与入账，不是微信官方 CSV / ZIP 账单导入。只导入你有权读取的数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择 JSON 文件", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "text/json", "application/octet-stream"});
                    try { startActivityForResult(intent, REQUEST_IMPORT); }
                    catch (ActivityNotFoundException error) { message("无法选择文件", "手机上没有可用的系统文件选择器。"); }
                }).show();
    }

    private void startExport() {
        if (busy) return;
        pendingExportSource = source;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "tallybook-" + (isDemo() ? "demo-" : "real-")
                + formatDate(System.currentTimeMillis(), "yyyyMMdd-HHmmss") + ".csv");
        try { startActivityForResult(intent, REQUEST_EXPORT); }
        catch (ActivityNotFoundException error) { message("无法保存文件", "手机上没有可用的系统文件选择器。"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_IMPORT) importUri(data.getData());
        if (requestCode == REQUEST_EXPORT) exportUri(data.getData(), pendingExportSource);
    }

    private void importUri(Uri uri) {
        busy = true;
        render();
        io.execute(() -> {
            String title;
            String body;
            boolean success = false;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IOException("无法读取所选文件");
                String raw = readLimited(stream);
                var parsed = WechatBillParser.parse(raw);
                Transaction transaction = parsed.transaction;
                if (!parsed.isSuccess()) {
                    try { transaction = Transaction.fromJson(raw); }
                    catch (IllegalArgumentException unsupportedNormalized) { transaction = null; }
                }
                if (transaction == null) {
                    title = "没有导入记录";
                    body = "这份文件暂不支持或缺少必要字段。\n\n" + parsed.message
                            + "\n\n当前仅适配微信详情数据，不接受未知列表或完整 CSV / ZIP 账单。原始文件没有保存到应用。";
                } else {
                    boolean inserted;
                    try (LedgerStore store = new LedgerStore(this)) { inserted = store.insert(transaction, LedgerStore.WECHAT); }
                    success = true;
                    title = inserted ? "已导入 1 笔记录" : "已更新已有记录";
                    body = "已保存到真实账本。" + (transaction.reviewRequired
                            ? "这笔交易需要核对，暂不计入汇总。" : "请与微信原始账单核对金额和时间。")
                            + "\n\n手动导入不表示微信采集已连接。";
                    getContentResolver().notifyChange(EVENTS, null);
                }
            } catch (Exception error) {
                title = "导入未完成";
                body = error instanceof FileTooLargeException ? "文件超过 256 KiB。请选择一份单笔交易的 JSON 文件。"
                        : "无法读取或保存这份文件。请确认文件为有效 UTF-8 JSON，并重新选择。";
            }
            final String finalTitle = title;
            final String finalBody = body;
            final boolean imported = success;
            main.post(() -> {
                if (!alive()) return;
                busy = false;
                if (imported) { source = LedgerStore.WECHAT; page = 0; records.clear(); loading = true; }
                render();
                loadRecords();
                message(finalTitle, finalBody);
            });
        });
    }

    private void exportUri(Uri uri, String selectedSource) {
        busy = true;
        render();
        io.execute(() -> {
            String title;
            String body;
            try (LedgerStore store = new LedgerStore(this)) {
                List<Transaction> snapshot = store.list(selectedSource);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("无法写入所选位置");
                    out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                    writeCsvLine(out, "账本", "时间", "交易方", "金额(元)", "状态", "支付方式", "说明", "交易单号", "待核对", "核对原因");
                    for (Transaction transaction : snapshot) {
                        writeCsvLine(out, LedgerStore.DEMO.equals(selectedSource) ? "演示（虚构）" : "真实",
                                formatDate(transaction.occurredAt, "yyyy-MM-dd HH:mm:ss"),
                                safeSpreadsheetText(transaction.counterparty), decimal(BigDecimal.valueOf(transaction.amountMinor, 2)),
                                safeSpreadsheetText(transaction.status), safeSpreadsheetText(transaction.paymentMethod),
                                safeSpreadsheetText(transaction.description), "'" + valueOr(transaction.tradeId, ""),
                                transaction.reviewRequired ? "是" : "否", safeSpreadsheetText(transaction.reviewReason));
                    }
                }
                title = "CSV 已导出";
                body = "已导出" + (LedgerStore.DEMO.equals(selectedSource) ? "演示" : "真实") + "账本的 " + snapshot.size()
                        + " 笔记录。\n\n文件是普通 CSV，包含交易单号，请自行保管。待核对记录有单独标记。";
            } catch (Exception error) {
                title = "导出未完成";
                body = "文件可能没有完整写入。请检查保存位置的空间和权限后重新导出。";
            }
            final String finalTitle = title;
            final String finalBody = body;
            main.post(() -> { if (!alive()) return; busy = false; render(); message(finalTitle, finalBody); });
        });
    }

    private void confirmClear() {
        final String selectedSource = source;
        new AlertDialog.Builder(this).setTitle("清空" + (isDemo() ? "演示" : "真实") + "账本？")
                .setMessage("将删除此账本的全部本机记录。" + (isDemo() ? "真实账本不受影响，之后可重新加载演示数据。"
                        : "此操作不能撤销，建议先导出 CSV。微信中的原始账单不受影响。"))
                .setNegativeButton("取消", null)
                .setPositiveButton("清空此账本", (dialog, which) -> {
                    busy = true;
                    render();
                    io.execute(() -> {
                        boolean cleared;
                        try (LedgerStore store = new LedgerStore(this)) { store.clear(selectedSource); cleared = true; }
                        catch (Exception error) { cleared = false; }
                        final boolean result = cleared;
                        main.post(() -> {
                            if (!alive()) return;
                            busy = false;
                            loadRecords();
                            if (!result) message("未能清空", "本机数据暂时无法操作，请稍后重试。");
                        });
                    });
                }).show();
    }

    private static String readLimited(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int count;
        while ((count = stream.read(bytes)) != -1) {
            if (buffer.size() + count > MAX_JSON_BYTES) throw new FileTooLargeException();
            buffer.write(bytes, 0, count);
        }
        String text = buffer.toString(StandardCharsets.UTF_8.name());
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static final class FileTooLargeException extends IOException {}

    private static void writeCsvLine(OutputStream out, String... cells) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) line.append(',');
            line.append('"').append(valueOr(cells[i], "").replace("\"", "\"\"")).append('"');
        }
        line.append("\r\n");
        out.write(line.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String safeSpreadsheetText(String value) {
        String safe = valueOr(value, "");
        int i = 0;
        while (i < safe.length() && (Character.isWhitespace(safe.charAt(i)) || Character.isISOControl(safe.charAt(i)))) i++;
        if (i < safe.length() && "=+-@".indexOf(safe.charAt(i)) >= 0) return "'" + safe;
        if (!safe.isEmpty() && (safe.charAt(0) == '\t' || safe.charAt(0) == '\r' || safe.charAt(0) == '\n')) return "'" + safe;
        return safe;
    }

    private boolean isDemo() { return LedgerStore.DEMO.equals(source); }
    private static String valueOr(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private static String decimal(BigDecimal amount) { return amount.setScale(2).toPlainString(); }
    private static String signedAmount(long minor) { return (minor > 0 ? "+" : minor < 0 ? "−" : "") + decimal(BigDecimal.valueOf(minor, 2).abs()); }
    private static String formatDate(long time, String pattern) { return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(time)); }

    private void message(String title, String body) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("知道了", null).show();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private LinearLayout card(int color) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(19), dp(20), dp(19));
        card.setBackground(round(color, 19));
        return card;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(primary ? WHITE : GREEN);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setStateListAnimator(null);
        button.setBackground(round(primary ? GREEN : PALE, 12));
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void addDivider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(BG);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void addSpace(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
