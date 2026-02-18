package com.rebootinterceptor;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RebootInterceptorHook v6
 *
 * Hooks ShutdownThread.reboot() — the EARLIEST entry point called when the
 * user taps Restart in the power menu. This fires BEFORE any shutdown
 * broadcast, shutdown animation, modem teardown, or 10-second waits.
 *
 * On intercept: immediately execs /system/bin/reboot and suppresses
 * everything else ShutdownThread would have done.
 *
 * Trace-confirmed flow:
 *   ActionsDialog tap (23:06:02.195)
 *     → IPowerManager.reboot("userrequested")
 *       → PowerManagerService.reboot()
 *         → ShutdownThread.reboot()        ← WE HOOK HERE
 *           → ShutdownThread.run()
 *             → [shutdown broadcast, animation, modem teardown ...]
 *               → rebootOrShutdown()       ← old hook was too late
 */
public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": loaded in system_server");
        hookShutdownThread(lpparam.classLoader);
    }

    private void hookShutdownThread(ClassLoader classLoader) {
        try {
            Class<?> shutdownThread = XposedHelpers.findClass(
                    "com.android.server.power.ShutdownThread", classLoader);

            // ----------------------------------------------------------------
            // PRIMARY HOOK: ShutdownThread.reboot(Context, String, boolean)
            //
            // This is the very first method called when a reboot is requested.
            // Signature (AOSP Android 13):
            //   public static void reboot(Context context, String reason, boolean confirm)
            //
            // We intercept here so ZERO shutdown side-effects run at all.
            // ----------------------------------------------------------------
            XposedHelpers.findAndHookMethod(
                    shutdownThread,
                    "reboot",
                    android.content.Context.class,
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String reason  = (String)  param.args[1];
                            boolean confirm = (boolean) param.args[2];

                            Log.i(TAG, "ShutdownThread.reboot() reason=" + reason + " confirm=" + confirm);
                            XposedBridge.log(TAG + ": ShutdownThread.reboot() reason=" + reason + " confirm=" + confirm);

                            // Only intercept plain user-initiated restart.
                            // Let recovery / bootloader / fastboot / safemode through untouched.
                            if (reason != null
                                    && !reason.isEmpty()
                                    && !reason.equals("userrequested")) {
                                XposedBridge.log(TAG + ": passing through (reason=" + reason + ")");
                                return;
                            }

                            // --- INTERCEPT ---
                            Log.w(TAG, "*** Intercepting reboot (suppressing ShutdownThread) ***");
                            XposedBridge.log(TAG + ": *** INTERCEPTED — calling /system/bin/reboot directly ***");

                            // Cancel ShutdownThread entirely — no broadcast, no animation,
                            // no modem teardown, no 10-second waits.
                            param.setResult(null);

                            new Thread(() -> {
                                try {
                                    // Small grace period so the UI thread can return cleanly.
                                    Thread.sleep(200);

                                    XposedBridge.log(TAG + ": exec /system/bin/reboot");
                                    exec("/system/bin/reboot");

                                    // Should never reach here — kernel reboots the device.
                                    XposedBridge.log(TAG + ": /system/bin/reboot returned unexpectedly");

                                } catch (Exception e) {
                                    Log.e(TAG, "exec error: " + e.getMessage());
                                    XposedBridge.log(TAG + ": exec error: " + e.getMessage());
                                }
                            }, "RebootInterceptor-Thread").start();
                        }
                    }
            );

            XposedBridge.log(TAG + ": Hook installed on ShutdownThread.reboot()");

        } catch (Throwable t) {
            // If the primary hook fails (e.g., method signature differs on this ROM),
            // fall back to the later rebootOrShutdown hook so we still catch it.
            XposedBridge.log(TAG + ": Primary hook failed (" + t.getMessage() + "), installing fallback on rebootOrShutdown");
            Log.w(TAG, "Primary hook failed, falling back: " + t.getMessage());
            hookRebootOrShutdownFallback(classLoader);
        }
    }

    // --------------------------------------------------------------------
    // FALLBACK HOOK: rebootOrShutdown — fires later but still works.
    // Only reached if the ROM's ShutdownThread.reboot() signature differs.
    // --------------------------------------------------------------------
    private void hookRebootOrShutdownFallback(ClassLoader classLoader) {
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
                            String reason    = (String)  param.args[2];

                            Log.i(TAG, "[fallback] rebootOrShutdown: reboot=" + isReboot + " reason=" + reason);
                            XposedBridge.log(TAG + ": [fallback] rebootOrShutdown: reboot=" + isReboot + " reason=" + reason);

                            if (!isReboot) return; // let power-off through

                            if (reason != null && !reason.isEmpty()
                                    && !reason.equals("userrequested")) return;

                            Log.w(TAG, "[fallback] *** Intercepting rebootOrShutdown ***");
                            XposedBridge.log(TAG + ": [fallback] *** INTERCEPTED ***");

                            param.setResult(null);

                            new Thread(() -> {
                                try {
                                    Thread.sleep(200);
                                    XposedBridge.log(TAG + ": [fallback] exec /system/bin/reboot");
                                    exec("/system/bin/reboot");
                                } catch (Exception e) {
                                    Log.e(TAG, "[fallback] exec error: " + e.getMessage());
                                    XposedBridge.log(TAG + ": [fallback] exec error: " + e.getMessage());
                                }
                            }, "RebootInterceptor-Fallback").start();
                        }
                    }
            );

            XposedBridge.log(TAG + ": Fallback hook installed on ShutdownThread.rebootOrShutdown");

        } catch (Throwable t) {
            Log.e(TAG, "Fallback hook also failed: " + t.getMessage());
            XposedBridge.log(TAG + ": Fallback hook also failed: " + t.getMessage());
        }
    }

    private void exec(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
        Log.i(TAG, "exec [" + String.join(" ", cmd) + "] exit=" + p.exitValue());
    }
}
