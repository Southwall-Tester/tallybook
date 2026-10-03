package dev.tallybook.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.tallybook.app.capture.VisibleCaptureSettings;

/** User-controlled, time-limited screen reading. Never grants system permissions itself. */
public final class ScreenCaptureActivity extends Activity {
    private static final int BG = Color.rgb(245, 246, 242);
    private static final int INK = Color.rgb(25, 47, 38);
    private static final int MUTED = Color.rgb(108, 120, 111);
    private static final int GREEN = Color.rgb(27, 69, 53);
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView state;
    private TextView timing;
    private TextView diagnostic;
    private TextView counts;
    private Button toggle;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); main.postDelayed(this, 1000L); }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout container = column();
        container.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            container.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(22), dp(12), dp(22), dp(28));
        Button back = button("返回小账本", false);
        back.setOnClickListener(v -> finish());
        body.addView(back);
        gap(body, 16);
        body.addView(text("读取微信账单", 27, INK, true));
        gap(body, 10);
        body.addView(text("开始读取后，切到微信账单列表，慢慢翻动即可收集屏幕上的记录。无需 Root。", 15, MUTED, false));
        gap(body, 20);

        LinearLayout status = card();
        state = text("", 22, INK, true);
        timing = text("", 13, MUTED, false);
        diagnostic = text("", 14, GREEN, false);
        counts = text("", 13, MUTED, false);
        status.addView(state);
        gap(status, 8);
        status.addView(timing);
        gap(status, 12);
        status.addView(diagnostic);
        gap(status, 12);
        status.addView(counts);
        gap(status, 18);
        toggle = button("开始读取", true);
        toggle.setOnClickListener(v -> {
            if (VisibleCaptureSettings.isEnabled(this)) {
                VisibleCaptureSettings.disable(this);
                refresh();
            } else beginReading();
        });
        status.addView(toggle);
        gap(status, 8);
        Button wechat = button("打开微信账单入口", false);
        wechat.setOnClickListener(v -> openWechat());
        status.addView(wechat);
        body.addView(status);
        gap(body, 16);

        LinearLayout help = card();
        help.addView(text("第一次使用", 18, INK, true));
        gap(help, 10);
        help.addView(text("1. 在系统无障碍设置中启用「小账本 · 微信账单读取」。\n2. 回到这里点「开始读取」。\n3. 微信 → 我 → 服务 → 钱包 → 账单。先显示年月，再缓慢翻动；每次停留约 3 秒，保留一两笔重叠记录。\n4. 回到小账本停止读取，在真实账本逐笔核对。", 14, MUTED, false));
        gap(help, 12);
        Button permission = button("打开系统权限设置", false);
        permission.setOnClickListener(v -> openPermissionSettings());
        help.addView(permission);
        gap(help, 12);
        help.addView(text("屏幕读取需要 Android 14 或以上。只在微信位于前台且你开启读取时工作，最长 30 分钟；切离微信会暂停读取。截图仅在内存中用于本机文字识别，不保存、不上传。", 12, MUTED, false));
        body.addView(help);
        gap(body, 16);

        LinearLayout review = card();
        review.addView(text("识别后先核对", 18, INK, true));
        gap(review, 10);
        review.addView(text("列表没有交易单号，名称还可能被截断。识别结果先保存为待核对，不计入收支；点开记录可确认或删除。无正负号、缺日期、只露出半行的记录会跳过。\n\n同一分钟、相同名称和金额会视为同一笔，可能合并真实的相似交易；也不会与手动记录或微信详情导入自动合并。请对照微信检查遗漏与重复。", 13, MUTED, false));
        gap(review, 14);
        Button ledger = button("查看已读账单", true);
        ledger.setOnClickListener(v -> { setResult(RESULT_OK); finish(); });
        review.addView(ledger);
        body.addView(review);
        scroll.addView(body);
        container.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(container);
        refresh();
    }

    @Override protected void onResume() { super.onResume(); main.post(ticker); }
    @Override protected void onPause() { main.removeCallbacks(ticker); super.onPause(); }

    private void refresh() {
        if (state == null) return;
        boolean supported = Build.VERSION.SDK_INT >= 34;
        boolean permission = VisibleCaptureSettings.isServiceEnabled(this);
        boolean enabled = VisibleCaptureSettings.isEnabled(this);
        state.setText(!supported ? "当前系统不支持屏幕读取" : enabled ? "读取已开启" : "读取已停止");
        long seconds = Math.max(0, (VisibleCaptureSettings.enabledUntil(this) - System.currentTimeMillis() + 999) / 1000);
        timing.setText(enabled ? "剩余 " + seconds / 60 + " 分 " + seconds % 60 + " 秒 · 仅识别微信前台窗口"
                : permission ? "系统权限已开启，点击开始后才会读取。" : "首次需要启用系统无障碍服务。" );
        diagnostic.setText(VisibleCaptureSettings.statusMessage(this));
        counts.setText("累计收集 " + VisibleCaptureSettings.captureCount(this) + " 笔 · 到真实账本核对\n"
                + "最近一屏跳过 " + VisibleCaptureSettings.skippedRows(this) + " 行");
        toggle.setText(enabled ? "停止读取" : permission ? "开始读取" : "先开启系统权限");
        toggle.setEnabled(supported);
    }

    private void beginReading() {
        if (Build.VERSION.SDK_INT < 34) return;
        if (!VisibleCaptureSettings.isServiceEnabled(this)) { openPermissionSettings(); return; }
        new AlertDialog.Builder(this).setTitle("开始读取微信账单？")
                .setMessage("接下来最多 30 分钟，在微信位于前台时读取窗口截图，并在本机识别账单列表。可识别记录保存到真实账本的待核对记录。\n\n不会点击、翻页或操作支付；不保存截图、不上传账单。你可以随时回来停止。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始读取", (dialog, which) -> {
                    VisibleCaptureSettings.enableFor30Minutes(this);
                    refresh();
                    openWechat();
                }).show();
    }

    private void openPermissionSettings() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (ActivityNotFoundException error) {
            new AlertDialog.Builder(this).setTitle("请在系统设置中开启")
                    .setMessage("进入手机设置，搜索「无障碍」，启用「小账本 · 微信账单读取」，再返回这里。")
                    .setPositiveButton("知道了", null).show();
        }
    }

    private void openWechat() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        try {
            if (intent == null) throw new ActivityNotFoundException();
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            new AlertDialog.Builder(this).setTitle("请打开微信")
                    .setMessage("请从手机桌面打开微信，再进入「我 → 服务 → 钱包 → 账单」。")
                    .setPositiveButton("知道了", null).show();
        }
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }
    private LinearLayout card() {
        LinearLayout result = column();
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE); shape.setCornerRadius(dp(18));
        result.setBackground(shape); result.setPadding(dp(18), dp(18), dp(18), dp(18));
        return result;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value); result.setTextSize(size); result.setTextColor(color);
        if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        result.setLineSpacing(dp(4), 1);
        return result;
    }
    private Button button(String label, boolean primary) {
        Button result = new Button(this); result.setText(label); result.setAllCaps(false);
        result.setTextColor(primary ? Color.WHITE : GREEN); result.setTextSize(14);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? GREEN : Color.rgb(229, 237, 225)); shape.setCornerRadius(dp(12));
        result.setBackground(shape); result.setMinHeight(dp(48)); result.setPadding(dp(12), dp(9), dp(12), dp(9));
        result.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return result;
    }
    private void gap(LinearLayout parent, int height) { View space = new View(this); parent.addView(space, new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
