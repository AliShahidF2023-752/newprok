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

            Class<?> shutdownThread =
                    lpparam.classLoader.loadClass(
                            "com.android.server.power.ShutdownThread"
                    );

            for (Method m : shutdownThread.getDeclaredMethods()) {

                String name = m.getName().toLowerCase();

                if (!name.contains("shutdown") && !name.contains("reboot"))
                    continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {

                        XposedBridge.log(TAG + ": Fake reboot triggered");

                        param.setResult(null);

                        Context ctx = (Context) XposedHelpers.getObjectField(
                                param.thisObject,
                                "mContext"
                        );

                        fakeReboot(ctx);
                    }
                });

            }

        } catch (Throwable t) {

            XposedBridge.log(TAG + ": Hook error " + t);
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

                // 1️⃣ screen black
                XposedHelpers.callMethod(
                        context.getSystemService(Context.POWER_SERVICE),
                        "goToSleep",
                        SystemClock.uptimeMillis()
                );

                // 2️⃣ wait 1 sec
                Thread.sleep(1000);

                // 3️⃣ shutdown animation
                setProp("service.bootanim.exit", "0");
                setProp("ctl.start", "bootanim");

                Thread.sleep(5000);

                // 4️⃣ black screen
                setProp("ctl.stop", "bootanim");

                Thread.sleep(5000);

                // 5️⃣ boot animation
                setProp("ctl.start", "bootanim");

                Thread.sleep(10000);

                setProp("ctl.stop", "bootanim");

                // 6️⃣ wake screen
                XposedHelpers.callMethod(
                        context.getSystemService(Context.POWER_SERVICE),
                        "wakeUp",
                        SystemClock.uptimeMillis()
                );

                XposedBridge.log(TAG + ": Fake reboot finished");

            } catch (Throwable e) {

                XposedBridge.log(TAG + ": Fake reboot error " + e);
            }

        }).start();
    }
}
