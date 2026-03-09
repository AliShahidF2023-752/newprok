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
            // ---- Scan PowerManagerService ----
            Class<?> pmsClass = XposedHelpers.findClass(
                    "com.android.server.power.PowerManagerService",
                    lpparam.classLoader
            );

            // Candidate shutdown/reboot methods
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

                                    // Extract reboot reason
                                    String reason = null;
                                    for (Object arg : param.args) {
                                        if (arg instanceof String) {
                                            reason = (String) arg;
                                            break;
                                        }
                                    }

                                    // Only intercept user/global requested reboots
                                    if (reason == null || 
                                        (!reason.toLowerCase().contains("user") && 
                                         !reason.toLowerCase().contains("global"))) {
                                        return;
                                    }

                                    boolean reboot = (Boolean) param.args[0];
                                    XposedBridge.log(TAG + ": Intercepted " + methodName +
                                                     " reboot=" + reboot + " reason=" + reason);
                                    Log.i(TAG, TAG + ": Intercepted " + methodName +
                                          " reboot=" + reboot + " reason=" + reason);

                                    // Cancel the original shutdown/reboot
                                    param.setResult(null);

                                    // Trigger soft reboot
                                    softReboot();
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
            Log.e(TAG, TAG + ": PowerManagerService class not found: " + t);
        }
    }

    private void softReboot() {
        new Thread(() -> {
            try {
                // Soft reboot without su
                Runtime.getRuntime().exec("setprop ctl.restart zygote");
                Runtime.getRuntime().exec("setprop ctl.restart zygote_secondary");

                XposedBridge.log(TAG + ": Soft reboot triggered");
                Log.i(TAG, TAG + ": Soft reboot triggered");

            } catch (Throwable e) {
                XposedBridge.log(TAG + ": Soft reboot failed: " + e);
                Log.e(TAG, TAG + ": Soft reboot failed: " + e);
            }
        }, "RebootInterceptorThread").start();
    }
}
