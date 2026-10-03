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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import dev.tallybook.app.capture.VisibleCaptureSettings;
import dev.tallybook.app.capture.OcrLines;
import dev.tallybook.core.WechatScreenParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Integration tests for a disposable emulator only: deliberately resets its test ledger. */
public final class PrototypeInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int checks;
    private boolean ocrFixture;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        ocrFixture = args != null && "true".equals(args.getString("ocrFixture"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!(Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone")
                    || Build.MODEL.contains("Android SDK"))) {
                throw new IllegalStateException("These tests reset a disposable emulator; physical devices are refused.");
            }
            if (ocrFixture) {
                result.putString("stream", OcrFixtureDiagnostics.run(this));
                finish(Activity.RESULT_OK, result);
                return;
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
        Transaction screen = Transaction.screen("虚构屏幕午餐", -1280L, tx.occurredAt);
        check(Transaction.fromJson(screen.toJson()).id.equals(screen.id)
            && screen.reviewRequired && screen.occurredAt % 60000 == 0,
            "Screen candidate roundtrips on Android with minute precision and mandatory review");
        int demoCount = store.list("demo").size();
        check(store.insertScreenIfAbsent(screen) && !store.insertScreenIfAbsent(screen)
            && store.list("wechat").size() == 2 && store.list("demo").size() == demoCount,
            "Screen insertion is deduplicated and cannot add records to the demo ledger");
        // Seed a fictional shadow row only to exercise source isolation of confirmation/deletion.
        android.content.ContentValues shadow = new android.content.ContentValues();
        shadow.put("source", LedgerStore.DEMO);
        shadow.put("transaction_id", screen.id);
        shadow.put("payload", screen.toJson());
        shadow.put("occurred_at", screen.occurredAt);
        shadow.put("updated_at", 0);
        store.getWritableDatabase().insertOrThrow("transactions", null, shadow);
        check(store.confirmScreen(screen.id) && !store.confirmScreen(screen.id),
            "Screen confirmation updates once and keeps the same record ID");
        check(!store.insertScreenIfAbsent(screen)
            && !find(store.list("wechat"), screen.id).reviewRequired
            && find(store.list("demo"), screen.id).reviewRequired,
            "Repeated OCR cannot overwrite confirmation and confirmation cannot change a demo shadow");
        boolean wrongProviderDenied = false;
        try { store.insertScreenIfAbsent(tx); }
        catch (IllegalArgumentException expected) { wrongProviderDenied = true; }
        check(wrongProviderDenied && !store.confirmScreen(tx.id) && !store.deleteScreen(tx.id)
            && !store.confirmScreen(manual.id) && !store.deleteScreen(manual.id),
            "Screen storage operations reject WeChat and manual providers");
        boolean confirmedInsertDenied = false;
        try { store.insertScreenIfAbsent(screen.confirmScreen()); }
        catch (IllegalArgumentException expected) { confirmedInsertDenied = true; }
        boolean genericInsertDenied = false;
        try { store.insert(screen, "wechat"); }
        catch (IllegalArgumentException expected) { genericInsertDenied = true; }
        check(confirmedInsertDenied && genericInsertDenied,
            "Screen records cannot bypass pending insertion or use generic overwrite insertion");
        Bundle screenPayload = new Bundle();
        screenPayload.putString("transaction", screen.toJson());
        Bundle screenRejected = context.getContentResolver().call(bridge, "record", null, screenPayload);
        check(screenRejected != null && !screenRejected.getBoolean("accepted"),
            "Exported WeChat hook bridge rejects the separate screen provider");
        check(!store.deleteManual(screen.id, "wechat") && store.deleteScreen(screen.id)
            && !store.deleteScreen(screen.id) && store.list("wechat").size() == 1
            && find(store.list("demo"), screen.id).reviewRequired,
            "Screen deletion is provider-scoped and leaves the demo shadow and imported WeChat record intact");
        check(!store.confirmScreen(null) && !store.deleteScreen("unknown")
            && !store.confirmScreen("wechat_screen:" + "0".repeat(64)),
            "Missing and malformed screen IDs do not modify storage");
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
        VisibleCaptureSettings.disable(context);
        check(!VisibleCaptureSettings.isEnabled(context), "Screen reading is off before explicit consent");
        VisibleCaptureSettings.enableFor30Minutes(context);
        check(VisibleCaptureSettings.isEnabled(context)
                && VisibleCaptureSettings.enabledUntil(context) <= System.currentTimeMillis() + 1800000L,
                "Screen consent is enabled with a bounded window");
        VisibleCaptureSettings.disable(context);
        check(!VisibleCaptureSettings.isEnabled(context), "Stopping screen reading revokes the consent window");
        checkBundledOcr();
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
        context.getSharedPreferences("visible_capture_settings", Context.MODE_PRIVATE).edit().clear().commit();
        Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = startActivitySync(launch);
        waitForIdleSync();
        check(activity != null && !activity.isFinishing(), "Main activity launches after integration checks");
    }

    /** Synthetic pixels only: exercises bundled Chinese OCR on the real Android runtime, offline. */
    private void checkBundledOcr() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(48);
        canvas.drawText("账单", 480, 170, paint);
        canvas.drawText("全部账单", 50, 310, paint);
        canvas.drawText("查找交易", 380, 310, paint);
        canvas.drawText("2026年10月", 40, 470, paint);
        canvas.drawText("虚构早餐店", 210, 640, paint);
        canvas.drawText("-18.50", 865, 640, paint);
        canvas.drawText("虚构退款记录", 210, 850, paint);
        canvas.drawText("+35.60", 865, 850, paint);
        paint.setTextSize(38);
        canvas.drawText("10月2日 10:14", 210, 710, paint);
        canvas.drawText("10月1日 09:08", 210, 920, paint);
        TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        try {
            Text recognized = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 45, TimeUnit.SECONDS);
            List<WechatScreenParser.TextLine> lines = OcrLines.fromText(recognized, bitmap.getWidth());
            WechatScreenParser.Result parsed = WechatScreenParser.parse(lines, bitmap.getWidth(), bitmap.getHeight());
            check(parsed.transactions.size() == 2, "Bundled Chinese OCR recognizes two synthetic bill rows without INTERNET permission");
            check(parsed.transactions.stream().allMatch(row -> row.reviewRequired
                    && Transaction.SCREEN_PROVIDER.equals(row.provider)), "OCR rows remain pending screen records");
        } finally {
            recognizer.close();
            bitmap.recycle();
        }
    }
    private static Transaction find(java.util.List<Transaction> records, String id) {
        for (Transaction transaction : records) if (transaction.id.equals(id)) return transaction;
        throw new AssertionError("Synthetic test transaction missing");
    }
    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        report.append("PASS ").append(++checks).append(" ").append(label).append('\n');
    }
}
