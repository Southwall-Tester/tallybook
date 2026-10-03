package dev.tallybook.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import dev.tallybook.core.ParseResult;
import dev.tallybook.core.Transaction;
import dev.tallybook.core.WechatBillParser;
import dev.tallybook.core.BudgetPlan;
import dev.tallybook.core.SavingsGoal;
import java.time.LocalDate;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Integration tests for a disposable emulator only: deliberately resets its test ledger. */
public final class PrototypeInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!(Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone")
                    || Build.MODEL.contains("Android SDK"))) {
                throw new IllegalStateException("These tests reset a disposable emulator; physical devices are refused.");
            }
            runChecks();
            result.putString("stream", report + "\nTALLYBOOK_SMOKE_OK checks=" + checks + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", report + "\nTALLYBOOK_SMOKE_FAILED " + failure.getClass().getSimpleName()
                + ": " + failure.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runChecks() throws Exception {
        Context context = getTargetContext();
        LedgerStore store = new LedgerStore(context);
        store.clear("wechat");
        store.clear("demo");
        CaptureSettings.disable(context);
        String fixture;
        try (InputStream input = context.getAssets().open("demo-wechat.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            fixture = output.toString(StandardCharsets.UTF_8.name());
        }
        ParseResult parsed = WechatBillParser.parse(fixture);
        check(parsed.isSuccess(), "Android platform JSON parses synthetic WeChat detail");
        Transaction tx = parsed.transaction;
        check(Transaction.fromJson(tx.toJson()).id.equals(tx.id), "Normalized Android JSON roundtrip preserves stable ID");
        check(!WechatBillParser.parse("{broken}").isSuccess(), "Malformed payload is rejected");
        check(store.insert(tx, "demo"), "First demo insert is new");
        check(!store.insert(tx, "demo"), "Repeated transaction is deduplicated");
        check(store.list("demo").size() == 1 && store.list("wechat").isEmpty(), "Demo never contaminates real ledger");
        Transaction refund = new Transaction(tx.tradeId, tx.counterparty, "已全额退款", tx.paymentMethod,
            tx.description, tx.amountMinor, tx.occurredAt, true, "退款需要核对");
        check(!store.insert(refund, "demo"), "Status update replaces existing ID");
        check(store.list("demo").get(0).reviewRequired, "Refund update is excluded from confirmed summary");

        Uri bridge = Uri.parse("content://dev.tallybook.app.capture");
        Bundle payload = new Bundle();
        payload.putString("transaction", tx.toJson());
        Bundle off = context.getContentResolver().call(bridge, "record", null, payload);
        check(off != null && !off.getBoolean("accepted") && store.list("wechat").isEmpty(), "Capture off refuses incoming transactions");
        CaptureSettings.enableFor30Minutes(context);
        long now = System.currentTimeMillis();
        check(CaptureSettings.enabledUntil(context) > now && CaptureSettings.enabledUntil(context) <= now + 1800000,
            "Capture window expires within 30 minutes");
        Bundle saved = context.getContentResolver().call(bridge, "record", null, payload);
        check(saved != null && saved.getBoolean("accepted") && store.list("wechat").size() == 1, "Enabled provider accepts a validated transaction");
        Bundle duplicate = context.getContentResolver().call(bridge, "record", null, payload);
        check(duplicate != null && duplicate.getBoolean("accepted") && !duplicate.getBoolean("inserted")
            && store.list("wechat").size() == 1, "Provider handles a repeated callback without a second entry");
        Bundle invalid = new Bundle();
        invalid.putString("transaction", "{\"amountMinor\":-1}");
        Bundle rejected = context.getContentResolver().call(bridge, "record", null, invalid);
        check(rejected != null && !rejected.getBoolean("accepted") && store.list("wechat").size() == 1,
            "Provider rejects incomplete normalized records");
        Transaction manual = Transaction.manual("manual-integration-1", "测试午餐", "餐饮", "虚构测试",
            -2500L, tx.occurredAt);
        check("manual".equals(Transaction.fromJson(manual.toJson()).provider), "Manual JSON preserves its provider on Android");
        check(store.insert(manual, "wechat") && store.insert(manual, "demo"), "Manual entry can be saved independently in both ledgers");
        check(store.deleteManual(manual.id, "wechat") && !store.deleteManual(manual.id, "wechat")
            && store.list("demo").size() == 2, "Deleting a manual entry leaves the other ledger intact");
        check(!store.deleteManual(tx.id, "wechat") && store.list("wechat").size() == 1,
            "Manual deletion cannot remove an imported WeChat transaction");
        Bundle manualPayload = new Bundle();
        manualPayload.putString("transaction", manual.toJson());
        Bundle manualRejected = context.getContentResolver().call(bridge, "record", null, manualPayload);
        check(manualRejected != null && !manualRejected.getBoolean("accepted") && store.list("wechat").size() == 1,
            "WeChat capture bridge rejects manual-provider payloads");
        boolean denied = false;
        try { context.getContentResolver().query(bridge, null, null, null, null); }
        catch (SecurityException expected) { denied = true; }
        check(denied, "Exported bridge cannot read the private ledger");
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 4096);
        boolean hasInternet = false;
        if (info.requestedPermissions != null) for (String permission : info.requestedPermissions) {
            if ("android.permission.INTERNET".equals(permission)) hasInternet = true;
        }
        check(!hasInternet, "APK requests no INTERNET permission");
        PlanStore realPlans = new PlanStore(context, "wechat");
        PlanStore demoPlans = new PlanStore(context, "demo");
        realPlans.clear();
        demoPlans.clear();
        LocalDate today = LocalDate.now();
        BudgetPlan plan = new BudgetPlan(today, today.plusDays(29), 200000, 30000, 20000);
        realPlans.saveBudget(plan);
        check(new PlanStore(context, "wechat").loadBudget().openingMinor == 200000
            && realPlans.loadBudget().endInclusive.equals(today.plusDays(29)), "Budget dates and money survive a new store instance");
        realPlans.saveGoal(new SavingsGoal("Test trip", 100000, 25000, today.plusDays(90)));
        check(new PlanStore(context, "wechat").loadGoal().savedMinor == 25000,
            "Savings progress survives a new store instance");
        check(demoPlans.loadBudget() == null && demoPlans.loadGoal() == null,
            "Real budget and goal never create demo plans");
        demoPlans.initializeDemo(today);
        check(demoPlans.loadBudget() != null && realPlans.loadBudget().openingMinor == 200000,
            "Demo initialization does not overwrite real plans");
        demoPlans.clear();
        check(demoPlans.loadGoal() == null && realPlans.loadGoal().savedMinor == 25000,
            "Clearing demo planning leaves real savings unchanged");
        realPlans.clear();
        CaptureSettings.disable(context);
        store.clear("wechat");
        store.clear("demo");
        store.close();
        context.getSharedPreferences("capture_settings", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
        Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = startActivitySync(launch);
        waitForIdleSync();
        check(activity != null && !activity.isFinishing(), "Main activity launches after integration checks");
    }
    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        report.append("PASS ").append(++checks).append(" ").append(label).append('\n');
    }
}
