package com.rebootinterceptor;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RebootInterceptorHook v8 — DEBUG / heavily logged build.
 * Every step logs to both Log.x (visible in adb logcat) and XposedBridge.log.
 */
public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Log every package so we can confirm system_server is seen
        Log.d(TAG, "[handleLoadPackage] pkg=" + lpparam.packageName);

        if (!"android".equals(lpparam.packageName)) return;

        Log.i(TAG, "[STEP 1] Loaded inside system_server — installing hooks");
        XposedBridge.log(TAG + ": [STEP 1] Loaded inside system_server");

        hookShutdownThread(lpparam.classLoader);
    }

    private void hookShutdownThread(ClassLoader classLoader) {

        // ── PRIMARY: ShutdownThread.reboot(Context, String, boolean) ─────────
        try {
            Class<?> shutdownThread = XposedHelpers.findClass(
                    "com.android.server.power.ShutdownThread", classLoader);

            Log.i(TAG, "[STEP 2] ShutdownThread class found: " + shutdownThread);
            XposedBridge.log(TAG + ": [STEP 2] ShutdownThread class found");

            XposedHelpers.findAndHookMethod(
                    shutdownThread,
                    "reboot",
                    android.content.Context.class,
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String  reason  = (String)  param.args[1];
                            boolean confirm = (boolean) param.args[2];

                            Log.i(TAG, "[STEP 3] reboot() called — reason='" + reason + "' confirm=" + confirm);
                            XposedBridge.log(TAG + ": [STEP 3] reboot() called — reason='" + reason + "' confirm=" + confirm);

                            if (!"userrequested".equals(reason)) {
                                Log.i(TAG, "[STEP 3] NOT userrequested — passing through untouched");
                                XposedBridge.log(TAG + ": [STEP 3] passing through (reason='" + reason + "')");
                                return;
                            }

                            Log.w(TAG, "[STEP 4] INTERCEPTED — cancelling ShutdownThread");
                            XposedBridge.log(TAG + ": [STEP 4] INTERCEPTED — cancelling ShutdownThread");

                            param.setResult(null);
                            Log.i(TAG, "[STEP 5] setResult(null) done — ShutdownThread cancelled");
                            XposedBridge.log(TAG + ": [STEP 5] setResult(null) done");

                            new Thread(() -> {
                                try {
                                    Log.i(TAG, "[STEP 6] Worker thread started");
                                    XposedBridge.log(TAG + ": [STEP 6] Worker thread started");

                                    Log.i(TAG, "[STEP 7] Sleeping 300ms for UI thread to return...");
                                    XposedBridge.log(TAG + ": [STEP 7] Sleeping 300ms");
                                    Thread.sleep(300);

                                    Log.i(TAG, "[STEP 8] Sleep done — about to spawn su -c");
                                    XposedBridge.log(TAG + ": [STEP 8] Sleep done");

                                    // Check if su is accessible
                                    java.io.File suFile = new java.io.File("/system/bin/su");
                                    Log.i(TAG, "[STEP 9] /system/bin/su exists=" + suFile.exists());
                                    XposedBridge.log(TAG + ": [STEP 9] /system/bin/su exists=" + suFile.exists());

                                    java.io.File rebootFile = new java.io.File("/system/bin/reboot");
                                    Log.i(TAG, "[STEP 9b] /system/bin/reboot exists=" + rebootFile.exists()
                                            + " length=" + rebootFile.length());
                                    XposedBridge.log(TAG + ": [STEP 9b] /system/bin/reboot exists="
                                            + rebootFile.exists() + " length=" + rebootFile.length());

                                    Log.i(TAG, "[STEP 10] Calling: su -c /system/bin/reboot userrequested");
                                    XposedBridge.log(TAG + ": [STEP 10] Calling: su -c /system/bin/reboot userrequested");

                                    Process p = new ProcessBuilder("su", "-c", "/system/bin/reboot userrequested")
                                            .redirectErrorStream(true)
                                            .start();

                                    Log.i(TAG, "[STEP 11] Process spawned, pid=" + p.toString());
                                    XposedBridge.log(TAG + ": [STEP 11] Process spawned");

                                    // Read output from the script for debugging
                                    java.io.BufferedReader reader = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(p.getInputStream()));
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        Log.i(TAG, "[STEP 12] script output: " + line);
                                        XposedBridge.log(TAG + ": [STEP 12] script: " + line);
                                    }

                                    // Wait max 10 seconds — if script ran stop/start we'll be killed
                                    // before this, but if something went wrong we'll see the exit code
                                    boolean finished = false;
                                    for (int i = 0; i < 10; i++) {
                                        Thread.sleep(1000);
                                        Log.i(TAG, "[STEP 13] still alive after " + (i + 1) + "s...");
                                        XposedBridge.log(TAG + ": [STEP 13] still alive after " + (i + 1) + "s");
                                        try {
                                            int exit = p.exitValue();
                                            Log.w(TAG, "[STEP 14] su process exited with code=" + exit);
                                            XposedBridge.log(TAG + ": [STEP 14] su exited code=" + exit);
                                            finished = true;
                                            break;
                                        } catch (IllegalThreadStateException e) {
                                            // still running — expected while stop/start is in progress
                                        }
                                    }

                                    if (!finished) {
                                        Log.w(TAG, "[STEP 15] su process still running after 10s — script may have hung");
                                        XposedBridge.log(TAG + ": [STEP 15] su still running after 10s");
                                    }

                                } catch (Exception e) {
                                    Log.e(TAG, "[ERROR] Exception in worker: " + e.getClass().getName()
                                            + ": " + e.getMessage());
                                    XposedBridge.log(TAG + ": [ERROR] " + e.getClass().getName()
                                            + ": " + e.getMessage());
                                    e.printStackTrace();
                                }
                            }, "RebootInterceptor-Thread").start();
                        }
                    }
            );

            Log.i(TAG, "[STEP 2b] PRIMARY hook installed on ShutdownThread.reboot()");
            XposedBridge.log(TAG + ": [STEP 2b] PRIMARY hook installed");

        } catch (Throwable primary) {
            Log.e(TAG, "[STEP 2 FAILED] Primary hook failed: " + primary.getMessage());
            XposedBridge.log(TAG + ": [STEP 2 FAILED] " + primary.getMessage());
            hookFallback(classLoader);
        }
    }

    private void hookFallback(ClassLoader classLoader) {
        Log.i(TAG, "[FALLBACK] Installing rebootOrShutdown hook");
        XposedBridge.log(TAG + ": [FALLBACK] Installing rebootOrShutdown hook");
        try {
            Class<?> shutdownThread = XposedHelpers.findClass(
                    "com.android.server.power.ShutdownThread", classLoader);

            XposedHelpers.findAndHookMethod(
                    shutdownThread,
                    "rebootOrShutdown",
                    android.content.Context.class,
                    boolean.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean isReboot = (boolean) param.args[1];
                            String  reason   = (String)  param.args[2];

                            Log.i(TAG, "[FALLBACK STEP 3] rebootOrShutdown() isReboot=" + isReboot
                                    + " reason='" + reason + "'");
                            XposedBridge.log(TAG + ": [FALLBACK STEP 3] isReboot=" + isReboot
                                    + " reason='" + reason + "'");

                            if (!isReboot) return;

                            if (!"userrequested".equals(reason)) {
                                Log.i(TAG, "[FALLBACK] passing through reason='" + reason + "'");
                                XposedBridge.log(TAG + ": [FALLBACK] passing through");
                                return;
                            }

                            Log.w(TAG, "[FALLBACK STEP 4] INTERCEPTED");
                            XposedBridge.log(TAG + ": [FALLBACK STEP 4] INTERCEPTED");
                            param.setResult(null);

                            new Thread(() -> {
                                try {
                                    Thread.sleep(300);
                                    Log.i(TAG, "[FALLBACK STEP 5] spawning su -c reboot");
                                    XposedBridge.log(TAG + ": [FALLBACK STEP 5] spawning su -c");

                                    Process p = new ProcessBuilder("su", "-c", "/system/bin/reboot userrequested")
                                            .redirectErrorStream(true)
                                            .start();

                                    Log.i(TAG, "[FALLBACK STEP 6] spawned");
                                    XposedBridge.log(TAG + ": [FALLBACK STEP 6] spawned");

                                    java.io.BufferedReader reader = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(p.getInputStream()));
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        Log.i(TAG, "[FALLBACK] script: " + line);
                                        XposedBridge.log(TAG + ": [FALLBACK] script: " + line);
                                    }

                                    for (int i = 0; i < 10; i++) {
                                        Thread.sleep(1000);
                                        Log.i(TAG, "[FALLBACK] still alive after " + (i + 1) + "s");
                                        XposedBridge.log(TAG + ": [FALLBACK] alive " + (i + 1) + "s");
                                        try {
                                            int exit = p.exitValue();
                                            Log.w(TAG, "[FALLBACK] su exited code=" + exit);
                                            XposedBridge.log(TAG + ": [FALLBACK] su exited=" + exit);
                                            break;
                                        } catch (IllegalThreadStateException e) { /* still running */ }
                                    }

                                } catch (Exception e) {
                                    Log.e(TAG, "[FALLBACK ERROR] " + e.getMessage());
                                    XposedBridge.log(TAG + ": [FALLBACK ERROR] " + e.getMessage());
                                }
                            }, "RebootInterceptor-Fallback").start();
                        }
                    }
            );

            Log.i(TAG, "[FALLBACK] hook installed on rebootOrShutdown");
            XposedBridge.log(TAG + ": [FALLBACK] hook installed");

        } catch (Throwable t) {
            Log.e(TAG, "[FALLBACK FAILED] " + t.getMessage());
            XposedBridge.log(TAG + ": [FALLBACK FAILED] " + t.getMessage());
        }
    }
}
