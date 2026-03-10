package com.rebootinterceptor;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
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

                        Context context = (Context) XposedBridge
                                .getObjectField(param.thisObject, "mContext");

                        fakeReboot(context);
                    }
                });
            }

        } catch (Throwable t) {

            XposedBridge.log(TAG + ": Hook failed " + t);
        }
    }

    private void fakeReboot(Context context) {

        new Thread(() -> {

            try {

                PowerManager pm =
                        (PowerManager) context.getSystemService(Context.POWER_SERVICE);

                Vibrator vibrator =
                        (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

                InputManager inputManager = InputManager.getInstance();

                XposedBridge.log(TAG + ": Starting fake reboot sequence");

                // disable touch / gestures
                inputManager.setInputDispatchMode(false, false);

                // 1️⃣ screen turns black
                pm.goToSleep(SystemClock.uptimeMillis());

                Thread.sleep(1000);

                // 2️⃣ boot logo
                SystemProperties.set("service.bootanim.exit", "0");

                // 3️⃣ shutdown animation
                SystemProperties.set("ctl.start", "bootanim");

                Thread.sleep(5000);

                // 4️⃣ black screen
                SystemProperties.set("ctl.stop", "bootanim");

                Thread.sleep(5000);

                // 5️⃣ vibration
                if (vibrator != null) {
                    vibrator.vibrate(
                            VibrationEffect.createOneShot(
                                    200,
                                    VibrationEffect.DEFAULT_AMPLITUDE
                            )
                    );
                }

                Thread.sleep(500);

                // 6️⃣ boot logo again
                SystemProperties.set("service.bootanim.exit", "0");

                // 7️⃣ boot animation
                SystemProperties.set("ctl.start", "bootanim");

                Thread.sleep(10000);

                SystemProperties.set("ctl.stop", "bootanim");

                Thread.sleep(500);

                // re-enable input
                inputManager.setInputDispatchMode(true, false);

                // 8️⃣ wake screen
                pm.wakeUp(SystemClock.uptimeMillis());

                XposedBridge.log(TAG + ": Fake reboot finished");

            } catch (Throwable e) {

                XposedBridge.log(TAG + ": Fake reboot error " + e);
            }

        }).start();
    }
}
