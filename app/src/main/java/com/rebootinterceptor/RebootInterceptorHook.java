package com.rebootinterceptor;

import android.util.Log;
import java.lang.reflect.Method;
import java.io.File;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";
    private static boolean triggered = false;
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded in system_server");

        try {

            Class<?> shutdownThreadClass = lpparam.classLoader.loadClass(
                    "com.android.server.power.ShutdownThread"
            );

            XposedBridge.log(TAG + ": ShutdownThread loaded");

            for (Method method : shutdownThreadClass.getDeclaredMethods()) {

                String name = method.getName();

                XposedBridge.log(TAG + ": Found method -> " + name);

                if (!name.toLowerCase().contains("shutdown") &&
                    !name.toLowerCase().contains("reboot"))
                    continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {

                        String reason = null;

                        for (Object arg : param.args) {
                            if (arg instanceof String) {
                                reason = (String) arg;
                            }
                        }

                        XposedBridge.log(TAG + ": Intercepted " +
                                param.method.getName() +
                                " reason=" + reason);

                        if (reason != null &&
                            !reason.contains("user") &&
                            !reason.contains("global"))
                            return;

                        // stop real reboot
                        param.setResult(null);

                        softReboot();
                    }
                });

                XposedBridge.log(TAG + ": Hook installed on ShutdownThread." + name);
            }

        } catch (Throwable t) {

            XposedBridge.log(TAG + ": Failed to hook ShutdownThread: " + t);
        }
    }

    private void softReboot() {

        if (triggered) return;
        triggered = true;

        new Thread(() -> {

            try {

                // signal root service using system property
                Runtime.getRuntime().exec(new String[]{
                        "/system/bin/setprop",
                        "sys.reboot.interceptor",
                        "1"
                });

                XposedBridge.log(TAG + ": reboot property set");

            } catch (Throwable e) {

                XposedBridge.log(TAG + ": failed to set reboot property " + e);
            }


        }).start();
    }
}
