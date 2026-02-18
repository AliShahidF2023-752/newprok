package com.rebootinterceptor;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RebootInterceptorHook v4
 *
 * Intercepts ShutdownThread.rebootOrShutdown for plain reboots and
 * delegates to /system/bin/reboot (KernelSU interceptor script)
 * which does stop && start internally.
 *
 * system_server runs as root so no su needed.
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

                            // Pass through: shutdown (power off)
                            if (!isReboot) return;

                            // Pass through: recovery, bootloader, fastboot, etc.
                            if (reason != null && !reason.isEmpty()
                                    && !reason.equals("userrequested")) return;

                            // --- INTERCEPT plain reboot ---
                            Log.w(TAG, "*** Intercepting reboot -> /system/bin/reboot ***");
                            XposedBridge.log(TAG + ": *** INTERCEPTED ***");

                            param.setResult(null); // cancel the real reboot

                            new Thread(() -> {
                                try {
                                    XposedBridge.log(TAG + ": calling /system/bin/reboot");
                                    exec("/system/bin/reboot");
                                    XposedBridge.log(TAG + ": /system/bin/reboot returned");
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
