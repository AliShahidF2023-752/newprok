package com.rebootinterceptor;

import android.util.Log;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        // Only hook system server
        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded in system_server");
        Log.i(TAG, TAG + ": Loaded in system_server");

        try {
            // ---- Scan ShutdownThread for all methods ----
            try {
                Class<?> shutdownThreadClass = XposedHelpers.findClass(
                        "com.android.server.power.ShutdownThread",
                        lpparam.classLoader
                );

                for (Method m : shutdownThreadClass.getDeclaredMethods()) {
                    XposedBridge.log(TAG + ": ShutdownThread method: " + m.getName());
                }

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": ShutdownThread class not found: " + t);
            }

            // ---- Scan PowerManagerService and hook multiple shutdown/reboot methods ----
            try {
                Class<?> pmsClass = XposedHelpers.findClass(
                        "com.android.server.power.PowerManagerService",
                        lpparam.classLoader
                );

                for (Method m : pmsClass.getDeclaredMethods()) {
                    XposedBridge.log(TAG + ": PowerManagerService method: " + m.getName());
                }

                // Candidate methods for shutdown/reboot
                String[] methods = new String[]{
                        "shutdown",
                        "shutdownLocked",
                        "rebootOrShutdown",
                        "rebootOrShutdownInternal",
                        "shutdownInner"
                };

                for (String methodName : methods) {
                    try {
                        XposedHelpers.findAndHookMethod(
                                pmsClass,
                                methodName,
                                boolean.class,   // reboot
                                String.class,    // reason
                                boolean.class,   // wait
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        boolean reboot = (Boolean) param.args[0];
                                        String reason = (String) param.args[1];

                                        XposedBridge.log(TAG + ": Intercepted " + methodName + "! reboot=" + reboot + ", reason=" + reason);
                                        Log.i(TAG, TAG + ": Intercepted " + methodName + "! reboot=" + reboot + ", reason=" + reason);

                                        // Cancel the original shutdown
                                        param.setResult(null);

                                        // Soft reboot via zygote
                                        new Thread(() -> {
                                            try {
                                                XposedBridge.log(TAG + ": Performing soft reboot via zygote...");
                                                Log.i(TAG, TAG + ": Performing soft reboot via zygote...");

                                                Runtime.getRuntime().exec(new String[]{
                                                        "/system/bin/su",
                                                        "-c",
                                                        "setprop ctl.restart zygote"
                                                });
                                            } catch (Exception e) {
                                                XposedBridge.log(TAG + ": Soft reboot failed: " + e.getMessage());
                                                Log.e(TAG, TAG + ": Soft reboot failed: " + e.getMessage());
                                            }
                                        }, "RebootInterceptorThread").start();
                                    }
                                }
                        );
                        XposedBridge.log(TAG + ": Hook installed for PowerManagerService." + methodName);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Method " + methodName + " not found: " + t);
                    }
                }

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": PowerManagerService class not found: " + t);
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Unexpected error: " + t);
            Log.e(TAG, TAG + ": Unexpected error: " + t);
        }
    }
}
