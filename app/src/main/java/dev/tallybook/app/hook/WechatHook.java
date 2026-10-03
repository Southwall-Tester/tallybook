package dev.tallybook.app.hook;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.net.Uri;
import android.webkit.ValueCallback;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.tallybook.core.ParseResult;
import dev.tallybook.core.WechatBillParser;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The evaluateJavascript/respbuf interception point follows AutoAccounting's
 * WebViewHooker. See THIRD_PARTY_NOTICES.md. This module only observes callbacks;
 * it never executes JS, alters a response, performs a payment, or exports a bill.
 */
public final class WechatHook implements IXposedHookLoadPackage {
    private static final Uri BRIDGE = Uri.parse("content://dev.tallybook.app.capture");
    private static final String XWEB = "com.tencent.xweb.WebView";
    private static final int MAX_PAYLOAD = 262144;
    private final Set<Class<?>> installed = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile Context context;
    private volatile long enabledUntil;
    private volatile long stateCheckedAt;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "tallybook-capture");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardPolicy());

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"com.tencent.mm".equals(load.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        context = (Context) param.args[0];
                        install(android.webkit.WebView.class);
                        Class<?> existing = XposedHelpers.findClassIfExists(XWEB, context.getClassLoader());
                        if (existing != null) install(existing);
                        worker.execute(() -> safeCall("hello", null, null));
                    } catch (Throwable ignored) { /* Never interrupt the host app. */ }
                }
            });
            // XWeb may live in a dynamically loaded classloader. Hook only its exact class.
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (XWEB.equals(param.args[0]) && param.getResult() instanceof Class<?>) {
                                install((Class<?>) param.getResult());
                            }
                        } catch (Throwable ignored) { }
                    }
                });
        } catch (Throwable ignored) {
            XposedBridge.log("Tallybook: hook setup unavailable (no bill data logged)");
        }
    }

    private void install(Class<?> webViewClass) {
        if (!installed.add(webViewClass)) return;
        try {
            XposedHelpers.findAndHookMethod(webViewClass, "evaluateJavascript", String.class,
                ValueCallback.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object value = param.args[0];
                            if (!(value instanceof String)) return;
                            String script = (String) value;
                            if (script.length() > MAX_PAYLOAD || !script.contains("nativeWXPayCgiTunnel:ok")) return;
                            worker.execute(() -> collect(script));
                        } catch (Throwable ignored) { }
                    }
                });
        } catch (Throwable unavailable) { installed.remove(webViewClass); }
    }

    private void collect(String script) {
        try {
            if (context == null) return;
            long now = System.currentTimeMillis();
            if (now - stateCheckedAt > 3000) {
                Bundle state = safeCall("state", null, null);
                enabledUntil = state == null ? 0 : state.getLong("enabledUntil", 0);
                stateCheckedAt = now;
            }
            if (enabledUntil <= now) return;
            ParseResult parsed = WechatBillParser.parse(script);
            if (!parsed.isSuccess()) {
                safeCall("diagnostic", "UNSUPPORTED_PAYLOAD", null);
                return;
            }
            Bundle values = new Bundle();
            values.putString("transaction", parsed.transaction.toJson());
            safeCall("record", null, values);
        } catch (Throwable ignored) { /* No exceptions or private payloads leave the hook. */ }
    }

    private Bundle safeCall(String method, String arg, Bundle extras) {
        try {
            Context current = context;
            return current == null ? null : current.getContentResolver().call(BRIDGE, method, arg, extras);
        } catch (Throwable unavailable) { return null; }
    }
}
