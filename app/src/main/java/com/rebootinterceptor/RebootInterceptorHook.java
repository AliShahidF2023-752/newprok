package com.rebootinterceptor;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RebootInterceptorHook v9
 *
 * Root cause: Nubia's power menu lives in SystemUI process (com.android.systemui).
 * When user swipes Restart:
 *   NubiaSlideView → ShutdownOrRebootListener.reboot()
 *   → GlobalActionsDialogLite.CallBackShutdownReboot.rebooting()
 *   → mWindowManagerFuncs.reboot(false)          [Binder call to WMS in system_server]
 *
 * Hooking ShutdownThread.reboot() in system_server was wrong process.
 * Instead we hook PowerManager.reboot(String) in com.android.systemui — intercepts
 * the call before it ever crosses the Binder boundary to WMS/ShutdownThread.
 *
 * Secondary hook: GlobalActionsDialogLite$CallBackShutdownReboot.rebooting() in case
 * Nubia bypasses PowerManager.
 */
public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        Log.d(TAG, "[handleLoadPackage] pkg=" + lpparam.packageName);

        // ── Hook inside SystemUI ────────────────────────────────────────────
        if ("com.android.systemui".equals(lpparam.packageName)) {
            Log.i(TAG, "[SYSUI] Loaded in SystemUI — installing hooks");
            XposedBridge.log(TAG + ": [SYSUI] Loaded in SystemUI");

            hookPowerManagerInSysui(lpparam.classLoader);
            hookCallBackShutdownReboot(lpparam.classLoader);
        }

        // ── Keep system_server hook as last-resort fallback ─────────────────
        if ("android".equals(lpparam.packageName)) {
            Log.i(TAG, "[SERVER] Loaded in system_server — installing fallback hook");
            XposedBridge.log(TAG + ": [SERVER] Loaded in system_server");
            hookShutdownThreadFallback(lpparam.classLoader);
        }
    }

    // ── PRIMARY: PowerManager.reboot(String) in SystemUI process ──────────
    private void hookPowerManagerInSysui(ClassLoader cl) {
        try {
            Class<?> pmClass = XposedHelpers.findClass("android.os.PowerManager", cl);
            Log.i(TAG, "[PM] PowerManager class found");
            XposedBridge.log(TAG + ": [PM] PowerManager class found");

            XposedHelpers.findAndHookMethod(pmClass, "reboot", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String reason = (String) param.args[0];
                    Log.i(TAG, "[PM] PowerManager.reboot() called, reason='" + reason + "'");
                    XposedBridge.log(TAG + ": [PM] PowerManager.reboot() reason='" + reason + "'");

                    // Only intercept explicit user restart; pass through null/shutdown/recovery etc.
                    if (!"userrequested".equals(reason) && reason != null) {
                        Log.i(TAG, "[PM] passing through, reason='" + reason + "'");
                        XposedBridge.log(TAG + ": [PM] passing through");
                        return;
                    }
                    // Also intercept null reason when coming from SystemUI reboot path
                    // (mWindowManagerFuncs.reboot(false) may pass null)

                    Log.w(TAG, "[PM] *** INTERCEPTED PowerManager.reboot() — suppressing ***");
                    XposedBridge.log(TAG + ": [PM] *** INTERCEPTED ***");
                    param.setResult(null);
                    spawnReboot("[PM]");
                }
            });

            Log.i(TAG, "[PM] PowerManager.reboot hook installed");
            XposedBridge.log(TAG + ": [PM] hook installed");

        } catch (Throwable t) {
            Log.e(TAG, "[PM] hook failed: " + t.getMessage());
            XposedBridge.log(TAG + ": [PM] hook failed: " + t.getMessage());
        }
    }

    // ── SECONDARY: GlobalActionsDialogLite$CallBackShutdownReboot.rebooting() ──
    private void hookCallBackShutdownReboot(ClassLoader cl) {
        try {
            // Inner class name uses $ separator
            Class<?> cbClass = XposedHelpers.findClass(
                    "com.android.systemui.globalactions.GlobalActionsDialogLite$CallBackShutdownReboot", cl);

            Log.i(TAG, "[CB] CallBackShutdownReboot class found");
            XposedBridge.log(TAG + ": [CB] CallBackShutdownReboot class found");

            XposedHelpers.findAndHookMethod(cbClass, "rebooting", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Log.w(TAG, "[CB] *** INTERCEPTED CallBackShutdownReboot.rebooting() ***");
                    XposedBridge.log(TAG + ": [CB] *** INTERCEPTED rebooting() ***");
                    param.setResult(null);
                    spawnReboot("[CB]");
                }
            });

            Log.i(TAG, "[CB] rebooting() hook installed");
            XposedBridge.log(TAG + ": [CB] rebooting() hook installed");

        } catch (Throwable t) {
            Log.e(TAG, "[CB] hook failed: " + t.getMessage());
            XposedBridge.log(TAG + ": [CB] hook failed: " + t.getMessage());
        }
    }

    // ── FALLBACK: ShutdownThread.reboot() in system_server ────────────────
    private void hookShutdownThreadFallback(ClassLoader cl) {
        try {
            Class<?> st = XposedHelpers.findClass(
                    "com.android.server.power.ShutdownThread", cl);

            XposedHelpers.findAndHookMethod(st, "reboot",
                    android.content.Context.class, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String reason = (String) param.args[1];
                            Log.i(TAG, "[ST] ShutdownThread.reboot() reason='" + reason + "'");
                            XposedBridge.log(TAG + ": [ST] ShutdownThread.reboot() reason='" + reason + "'");

                            if (!"userrequested".equals(reason)) {
                                Log.i(TAG, "[ST] passing through");
                                return;
                            }

                            Log.w(TAG, "[ST] *** INTERCEPTED ShutdownThread.reboot() ***");
                            XposedBridge.log(TAG + ": [ST] *** INTERCEPTED ***");
                            param.setResult(null);
                            spawnReboot("[ST]");
                        }
                    });

            Log.i(TAG, "[ST] ShutdownThread fallback hook installed");
            XposedBridge.log(TAG + ": [ST] ShutdownThread fallback hook installed");

        } catch (Throwable t) {
            Log.e(TAG, "[ST] fallback hook failed: " + t.getMessage());
            XposedBridge.log(TAG + ": [ST] fallback hook failed: " + t.getMessage());
        }
    }

    // ── Shared: spawn KSU reboot script ───────────────────────────────────
    private void spawnReboot(String source) {
        new Thread(() -> {
            try {
                Log.i(TAG, source + " worker thread started, sleeping 300ms");
                XposedBridge.log(TAG + ": " + source + " worker started");
                Thread.sleep(300);

                Log.i(TAG, source + " spawning: su -c /system/bin/reboot userrequested");
                XposedBridge.log(TAG + ": " + source + " spawning su -c");

                Process p = new ProcessBuilder("su", "-c", "/system/bin/reboot userrequested")
                        .redirectErrorStream(true)
                        .start();

                Log.i(TAG, source + " process spawned");
                XposedBridge.log(TAG + ": " + source + " spawned");

                // Drain output (KSU script may print something useful)
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    Log.i(TAG, source + " script: " + line);
                    XposedBridge.log(TAG + ": " + source + " script: " + line);
                }

                // Poll exit — if stop/start kills us we'll never reach here
                for (int i = 0; i < 15; i++) {
                    Thread.sleep(1000);
                    Log.i(TAG, source + " alive after " + (i + 1) + "s");
                    XposedBridge.log(TAG + ": " + source + " alive " + (i + 1) + "s");
                    try {
                        int exit = p.exitValue();
                        Log.w(TAG, source + " su exited code=" + exit);
                        XposedBridge.log(TAG + ": " + source + " su exit=" + exit);
                        break;
                    } catch (IllegalThreadStateException ignored) { }
                }

            } catch (Exception e) {
                Log.e(TAG, source + " error: " + e.getClass().getName() + ": " + e.getMessage());
                XposedBridge.log(TAG + ": " + source + " error: " + e.getMessage());
            }
        }, "RebootInterceptor-Worker").start();
    }
}
