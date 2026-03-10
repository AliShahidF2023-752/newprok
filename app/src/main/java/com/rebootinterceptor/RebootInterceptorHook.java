package com.rebootinterceptor;

import android.util.Log;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

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

        new Thread(() -> {

            try {

                ProcessBuilder pb = new ProcessBuilder(
                        "/system/bin/sh",
                        "-c",
                        "/system/bin/reboot userrequested"
                );

                pb.start();

                XposedBridge.log(TAG + ": Custom reboot script executed");

            } catch (Throwable e) {

                XposedBridge.log(TAG + ": Custom reboot failed " + e);
            }

        }).start();
    }
}
