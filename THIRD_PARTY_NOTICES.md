# Source provenance and third-party notices

## AutoAccounting

- Project: https://github.com/AutoAccountingOrg/AutoAccounting
- Author: Ankio and AutoAccounting contributors.
- Project license: GNU GPL version 3; a copy is supplied in `LICENSE`.
- Reference file: `app/src/main/java/net/ankio/auto/xposed/hooks/wechat/hooks/WebViewHooker.kt`.
- Inspected file revision: https://github.com/AutoAccountingOrg/AutoAccounting/blob/512efd515df61f86ca030796d6b7fe6f221ec56f/app/src/main/java/net/ankio/auto/xposed/hooks/wechat/hooks/WebViewHooker.kt

This prototype follows that file's interception point (`com.tencent.xweb.WebView.evaluateJavascript`) and envelope (`__json_message.__params`, `nativeWXPayCgiTunnel:ok`, `respbuf`). The Java collector and conservative parser were implemented for this prototype. The long upstream example containing account identifiers and signed navigation data was not copied. All bundled transaction fixtures are newly created, clearly fictional samples.

The source file's header says “Apache License, Version 3.0”, while the project root provides GPL-3.0. This prototype is distributed under the project-level GPL-3.0 rather than relying on that inconsistent file-header designation. Redistribution of an APK should include access to this corresponding source and its license.

## Xposed API

- https://github.com/rovo89/XposedBridge
- https://api.xposed.info/
- Artifact: `de.robv.android.xposed:api:82`, Apache-2.0.
- Compile-only API; these classes are supplied by the module runtime and are not bundled in the APK.

## JSON-java and JUnit

- JSON-java: https://github.com/stleary/JSON-java, `org.json:json:20240303`, public domain. Used by JVM builds/tests. The APK uses the Android platform's `org.json` implementation instead.
- JUnit: https://github.com/junit-team/junit4, `junit:junit:4.13.2`, EPL-1.0, test-only.

Android platform/toolchain components remain subject to their own licenses. Gradle, JDK, and Android SDK downloaded into `.tools` are local build prerequisites, not application runtime dependencies bundled for redistribution.
