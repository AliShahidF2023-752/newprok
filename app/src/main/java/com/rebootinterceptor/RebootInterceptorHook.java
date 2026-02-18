package com.rebootinterceptor;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RebootInterceptorHook v3
 *
 * Fix: system_server has no PATH to su. Use full binary paths instead.
 * system_server already runs as root so no su needed.
 *
 * Also kills shutdownanim which was left running and blocking the screen.
 */
public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": installed in system_server");
        hookShutdownThread(lpparam.classLoader);
    }

    private void hookShutdownThread(ClassLoader classLoader) {
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
                            String reason   = (String)  param.args[2];

                            Log.i(TAG, "rebootOrShutdown: reboot=" + isReboot + " reason=" + reason);
                            XposedBridge.log(TAG + ": rebootOrShutdown: reboot=" + isReboot + " reason=" + reason);

                            // Only intercept plain reboots - pass through shutdown/recovery/bootloader
                            if (!isReboot) return;
                            if (reason != null && !reason.isEmpty()
                                    && !reason.equals("userrequested")) return;

                            // --- INTERCEPT ---
                            Log.w(TAG, "*** Intercepting reboot -> stop && start ***");
                            XposedBridge.log(TAG + ": *** INTERCEPTED ***");

                            param.setResult(null); // cancel the real reboot

                            new Thread(() -> {
                                try {
                                    // Kill the shutdown animation that's blocking the screen
                                    Log.i(TAG, "Killing shutdownanim...");
                                    exec("/system/bin/stop", "shutdownanim");
                                    Thread.sleep(500);

                                    // Stop all Android services
                                    // system_server is already root - no su needed
                                    Log.i(TAG, "Running: stop");
                                    exec("/system/bin/stop");
                                    Thread.sleep(2000);

                                    // Restart all Android services
                                    Log.i(TAG, "Running: start");
                                    exec("/system/bin/start");

                                    Log.i(TAG, "stop && start completed successfully");
                                    XposedBridge.log(TAG + ": stop && start done");

                                } catch (Exception e) {
                                    Log.e(TAG, "Error: " + e.getMessage());
                                    XposedBridge.log(TAG + ": Error: " + e.getMessage());
                                }
                            }, "RebootInterceptor-Thread").start();
                        }
                    }
            );

            XposedBridge.log(TAG + ": Hook installed on ShutdownThread.rebootOrShutdown");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook failed: " + t.getMessage());
            Log.e(TAG, "Hook failed: " + t.getMessage());
        }
    }

    private void exec(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
        Log.i(TAG, "exec " + String.join(" ", cmd) + " -> exit " + p.exitValue());
    }
}
