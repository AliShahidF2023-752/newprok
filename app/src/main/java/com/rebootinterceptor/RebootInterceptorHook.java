package com.rebootinterceptor;

import android.content.Context;
import android.os.SystemClock;

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

        if (!"android".equals(lpparam.packageName))
            return;

        XposedBridge.log(TAG + ": Loaded in system_server");

        try {

            Class<?> shutdownThreadClass =
                    lpparam.classLoader.loadClass(
                            "com.android.server.power.ShutdownThread"
                    );

            XposedBridge.log(TAG + ": ShutdownThread loaded");

            for (Method method : shutdownThreadClass.getDeclaredMethods()) {

                String name = method.getName();

                XposedBridge.log(TAG + ": Found method -> " + name);

                if (!name.toLowerCase().contains("shutdown")
                        && !name.toLowerCase().contains("reboot"))
                    continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {

                        String reason = null;

                        for (Object arg : param.args) {
                            if (arg instanceof String) {
                                reason = (String) arg;
                            }
                        }

                        XposedBridge.log(TAG +
                                ": Intercepted " +
                                param.method.getName() +
                                " reason=" + reason);

                        if (reason != null &&
                                !reason.contains("user") &&
                                !reason.contains("global"))
                            return;

                        param.setResult(null);

                        Context ctx = (Context) XposedHelpers.getObjectField(
                                param.thisObject,
                                "mContext"
                        );

                        fakeReboot(ctx);
                    }
                });

                XposedBridge.log(TAG +
                        ": Hook installed on ShutdownThread." + name);
            }

        } catch (Throwable t) {

            XposedBridge.log(TAG + ": Failed to hook ShutdownThread: " + t);
        }
    }

    private void setProp(String key, String value) {

        try {

            Class<?> sp = Class.forName("android.os.SystemProperties");

            Method set =
                    sp.getDeclaredMethod("set", String.class, String.class);

            set.invoke(null, key, value);

        } catch (Throwable e) {

            XposedBridge.log(TAG + ": setprop failed " + e);
        }
    }

    private void fakeReboot(Context context) {

        new Thread(() -> {

            try {

                XposedBridge.log(TAG + ": Starting fake reboot");

                // screen black
                XposedHelpers.callMethod(
                        context.getSystemService(Context.POWER_SERVICE),
                        "goToSleep",
                        SystemClock.uptimeMillis()
                );

                // wait 1 second
                Thread.sleep(1000);

                // shutdown animation
                setProp("service.bootanim.exit", "0");
                setProp("ctl.start", "bootanim");

                Thread.sleep(5000);

                // black screen
                setProp("ctl.stop", "bootanim");

                Thread.sleep(5000);

                // boot animation
                setProp("ctl.start", "bootanim");

                Thread.sleep(10000);

                setProp("ctl.stop", "bootanim");

                // wake screen
                XposedHelpers.callMethod(
                        context.getSystemService(Context.POWER_SERVICE),
                        "wakeUp",
                        SystemClock.uptimeMillis()
                );

                XposedBridge.log(TAG + ": Fake reboot complete");

            } catch (Throwable e) {

                XposedBridge.log(TAG + ": Fake reboot error " + e);
            }

        }).start();
    }
}
