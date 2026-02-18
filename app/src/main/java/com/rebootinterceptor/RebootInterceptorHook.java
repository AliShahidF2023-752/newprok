package com.rebootinterceptor;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RebootInterceptorHook v7
 *
 * Root cause of stuck boot logo (previous version):
 *   - Hook was intercepting reason=null/empty (internal watchdog/OTA reboots)
 *     and triggering "stop && start" during boot → stuck on logo forever.
 *
 * Root cause of reboot not working (previous version):
 *   - ProcessBuilder("/system/bin/reboot") ran in system_server process context
 *     where KSU bind-mount is hidden by susfs → hit real binary, not KSU script.
 *   - Fix: spawn "su -c /system/bin/reboot userrequested" so command runs in
 *     a fresh root shell where KSU mounts ARE visible.
 *
 * Strict guard:
 *   - ONLY intercept reason == "userrequested"  (power menu Restart tap)
 *   - null / empty                              → internal reboot, pass through
 *   - recovery / bootloader / fastboot etc.     → pass through
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
            // This is the very first call made when user taps Restart in the
            // power menu ActionsDialog. Cancelling here means:
            //   - No shutdown broadcast sent
            //   - No shutdown animation started
            //   - No modem/radio teardown
            //   - No 10-second wait loops
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
                            String  reason  = (String)  param.args[1];
                            boolean confirm = (boolean) param.args[2];

                            XposedBridge.log(TAG + ": reboot() reason='" + reason
                                    + "' confirm=" + confirm);

                            // STRICT guard — only intercept explicit power menu restart.
                            // null or empty = watchdog / OTA / internal → must NOT intercept.
                            if (!"userrequested".equals(reason)) {
                                XposedBridge.log(TAG + ": passing through (reason not userrequested)");
                                return;
                            }

                            XposedBridge.log(TAG + ": *** INTERCEPTED — suppressing ShutdownThread ***");

                            // Cancel the entire ShutdownThread — nothing else runs.
                            param.setResult(null);

                            new Thread(() -> {
                                try {
                                    // Brief grace so UI thread can return cleanly first.
                                    Thread.sleep(300);

                                    // Must use "su -c" to spawn a fresh root shell.
                                    // system_server's own process context has susfs hiding
                                    // the KSU bind-mount, so calling the binary directly
                                    // would hit the real reboot, not your KSU script.
                                    XposedBridge.log(TAG + ": spawning: su -c /system/bin/reboot userrequested");
                                    execSu("/system/bin/reboot", "userrequested");

                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": exec error: " + e.getMessage());
                                    Log.e(TAG, "exec error: " + e.getMessage());
                                }
                            }, "RebootInterceptor-Thread").start();
                        }
                    }
            );

            XposedBridge.log(TAG + ": PRIMARY hook installed on ShutdownThread.reboot()");

        } catch (Throwable primary) {
            // ROM has a different ShutdownThread.reboot() signature — fall back.
            XposedBridge.log(TAG + ": Primary hook failed (" + primary.getMessage()
                    + ") — installing fallback on rebootOrShutdown");
            hookFallback(classLoader);
        }
    }

    // -----------------------------------------------------------------------
    // FALLBACK: rebootOrShutdown(Context, boolean, String)
    // Only reached if the ROM's ShutdownThread.reboot() signature differs.
    // Fires later (after shutdown broadcast + animation start) but still works.
    // -----------------------------------------------------------------------
    private void hookFallback(ClassLoader classLoader) {
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

                            XposedBridge.log(TAG + ": [fallback] rebootOrShutdown()"
                                    + " isReboot=" + isReboot + " reason='" + reason + "'");

                            // Always let power-off through.
                            if (!isReboot) return;

                            // STRICT: only intercept userrequested.
                            if (!"userrequested".equals(reason)) {
                                XposedBridge.log(TAG + ": [fallback] passing through");
                                return;
                            }

                            XposedBridge.log(TAG + ": [fallback] *** INTERCEPTED ***");
                            param.setResult(null);

                            new Thread(() -> {
                                try {
                                    Thread.sleep(300);
                                    XposedBridge.log(TAG + ": [fallback] spawning: su -c /system/bin/reboot userrequested");
                                    execSu("/system/bin/reboot", "userrequested");
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": [fallback] error: " + e.getMessage());
                                }
                            }, "RebootInterceptor-Fallback").start();
                        }
                    }
            );

            XposedBridge.log(TAG + ": FALLBACK hook installed on ShutdownThread.rebootOrShutdown");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Fallback hook also failed: " + t.getMessage());
            Log.e(TAG, "Fallback hook also failed: " + t.getMessage());
        }
    }

    /**
     * Spawns "su -c <cmd>" in a new root shell where KSU bind-mounts are
     * visible. Does NOT call waitFor() — the KSU script runs "stop" which
     * tears down the process tree anyway, so waitFor() would deadlock.
     */
    private void execSu(String... cmd) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String part : cmd) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(part);
        }
        String fullCmd = sb.toString();
        XposedBridge.log(TAG + ": su -c \"" + fullCmd + "\"");

        new ProcessBuilder("su", "-c", fullCmd)
                .redirectErrorStream(true)
                .start();
        // No waitFor() — "stop" kills us before the script ever returns.
    }
}
