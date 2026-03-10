package com.rebootinterceptor;

import android.content.Context;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

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

        XposedBridge.log(TAG + ": Loaded");

        try {

            Class<?> shutdownThreadClass =
                    lpparam.classLoader.loadClass(
                            "com.android.server.power.ShutdownThread"
                    );

            for (Method method : shutdownThreadClass.getDeclaredMethods()) {

                String name = method.getName();

                if (!name.toLowerCase().contains("shutdown")
                        && !name.toLowerCase().contains("reboot"))
                    continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {

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

            XposedBridge.log(TAG + ": Hook failed " + t);
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

                XposedBridge.log(TAG + ": Starting sequence");

                // 1️⃣ screen black
                XposedHelpers.callMethod(
                        context.getSystemService(Context.POWER_SERVICE),
                        "goToSleep",
                        SystemClock.uptimeMillis()
                );

                Thread.sleep(1000);

                // 2️⃣ boot logo
                setProp("service.bootanim.exit", "0");

                // 3️⃣ shutdown animation
                setProp("ctl.start", "bootanim");

                Thread.sleep(5000);

                // 4️⃣ black screen
                setProp("ctl.stop", "bootanim");

                Thread.sleep(5000);

                // 5️⃣ vibration
                Vibrator v =
                        (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

                if (v != null) {

                    v.vibrate(
                            VibrationEffect.createOneShot(
                                    200,
                                    VibrationEffect.DEFAULT_AMPLITUDE
                            )
                    );
                }

                Thread.sleep(500);

                // 6️⃣ boot logo again
                setProp("service.bootanim.exit", "0");

                // 7️⃣ boot animation
                setProp("ctl.start", "bootanim");

                Thread.sleep(10000);

                setProp("ctl.stop", "bootanim");

                Thread.sleep(500);

                // 8️⃣ wake screen
                XposedHelpers.callMethod(
                        context.getSystemService(Context.POWER_SERVICE),
                        "wakeUp",
                        SystemClock.uptimeMillis()
                );

                XposedBridge.log(TAG + ": Sequence finished");

            } catch (Throwable e) {

                XposedBridge.log(TAG + ": Fake reboot error " + e);
            }

        }).start();
    }
}
