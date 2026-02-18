package com.rebootinterceptor;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RebootInterceptorHook
 *
 * Hooks ShutdownThread inside system_server.
 * When a plain reboot (reason="userrequested") is triggered from the
 * power menu, we cancel the reboot and run `stop && start` instead.
 *
 * Hook target:  com.android.server.power.ShutdownThread
 * Method:       rebootOrShutdown(Context, boolean reboot, String reason)
 *
 * This is called right before the kernel reboot() JNI syscall fires,
 * giving us the last clean window to intercept it.
 */
public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    // The reason string we saw in the logcat: "userrequested"
    // Also intercept null/empty which is a plain `adb reboot`
    private static final String REASON_USER    = "userrequested";
    private static final String REASON_ADB     = null; // plain adb reboot

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Only hook system_server
        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded in system_server, installing hooks...");

        hookShutdownThread(lpparam.classLoader);
    }

    private void hookShutdownThread(ClassLoader classLoader) {
        try {
            Class<?> shutdownThread = XposedHelpers.findClass(
                    "com.android.server.power.ShutdownThread", classLoader);

            // Hook rebootOrShutdown(Context context, boolean reboot, String reason)
            // This is the final method before the JNI reboot syscall
            XposedHelpers.findAndHookMethod(
                    shutdownThread,
                    "rebootOrShutdown",
                    android.content.Context.class,
                    boolean.class,   // reboot=true means reboot, false=shutdown
                    String.class,    // reason
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean isReboot = (boolean) param.args[1];
                            String reason   = (String)  param.args[2];

                            Log.i(TAG, "rebootOrShutdown called: reboot=" + isReboot + " reason=" + reason);
                            XposedBridge.log(TAG + ": rebootOrShutdown: reboot=" + isReboot + " reason=" + reason);

                            // Only intercept plain reboots
                            // Pass through: shutdown, recovery, bootloader, fastboot, etc.
                            if (!isReboot) {
                                Log.i(TAG, "Shutdown detected - passing through");
                                return;
                            }

                            if (reason != null && !reason.isEmpty()
                                    && !reason.equals(REASON_USER)) {
                                Log.i(TAG, "Special reboot reason '" + reason + "' - passing through");
                                return;
                            }

                            // --- INTERCEPT ---
                            Log.w(TAG, "*** Intercepting reboot (reason=" + reason + ") -> stop && start ***");
                            XposedBridge.log(TAG + ": *** INTERCEPTED - running stop && start instead ***");

                            // Cancel the real reboot
                            param.setResult(null);

                            // Run stop && start on a background thread
                            // (system_server is still alive at this point)
                            new Thread(() -> {
                                try {
                                    Log.i(TAG, "Running: stop");
                                    Runtime.getRuntime().exec(
                                        new String[]{"su", "-c", "stop"}
                                    ).waitFor();

                                    Thread.sleep(2000);

                                    Log.i(TAG, "Running: start");
                                    Runtime.getRuntime().exec(
                                        new String[]{"su", "-c", "start"}
                                    );

                                    Log.i(TAG, "stop && start completed");
                                } catch (Exception e) {
                                    Log.e(TAG, "Error running stop/start: " + e.getMessage());
                                    XposedBridge.log(TAG + ": Error: " + e.getMessage());
                                }
                            }, "RebootInterceptor-Thread").start();
                        }
                    }
            );

            XposedBridge.log(TAG + ": Hook installed on ShutdownThread.rebootOrShutdown");
            Log.i(TAG, "Hook installed successfully");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook ShutdownThread: " + t.getMessage());
            Log.e(TAG, "Hook failed: " + t.getMessage());
        }
    }
}
