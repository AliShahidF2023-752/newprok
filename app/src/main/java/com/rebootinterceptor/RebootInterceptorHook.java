package com.rebootinterceptor;

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

                        param.setResult(null);

                        fakeReboot();
                    }
                });

            }

        } catch (Throwable t) {

            XposedBridge.log(TAG + ": Hook failed: " + t);
        }
    }

    private void fakeReboot() {

        new Thread(() -> {

            try {

                XposedBridge.log(TAG + ": Starting fake reboot");

                // stop SystemUI
                Runtime.getRuntime().exec("setprop ctl.stop com.android.systemui");

                Thread.sleep(500);

                // disable input devices
                Runtime.getRuntime().exec(new String[]{
                        "sh","-c",
                        "for i in /sys/class/input/input*/enabled; do echo 0 > $i; done"
                });

                // play shutdown sound
                Runtime.getRuntime().exec(new String[]{
                        "sh","-c",
                        "cmd media_session play /system/media/audio/ui/Shutdown.ogg"
                });

                // 1️⃣ screen black
                Runtime.getRuntime().exec("input keyevent 26");

                Thread.sleep(1000);

                // 2️⃣ boot logo
                Runtime.getRuntime().exec("setprop service.bootanim.exit 0");

                // 3️⃣ shutdown animation
                Runtime.getRuntime().exec("setprop ctl.start bootanim");

                Thread.sleep(5000);

                // 4️⃣ black screen
                Runtime.getRuntime().exec("setprop ctl.stop bootanim");

                Thread.sleep(5000);

                // 5️⃣ vibration
                Runtime.getRuntime().exec("cmd vibrator vibrate 200");

                Thread.sleep(500);

                // 6️⃣ boot logo again
                Runtime.getRuntime().exec("setprop service.bootanim.exit 0");

                // 7️⃣ boot animation
                Runtime.getRuntime().exec("setprop ctl.start bootanim");

                Thread.sleep(10000);

                Runtime.getRuntime().exec("setprop ctl.stop bootanim");

                Thread.sleep(1000);

                // re-enable input devices
                Runtime.getRuntime().exec(new String[]{
                        "sh","-c",
                        "for i in /sys/class/input/input*/enabled; do echo 1 > $i; done"
                });

                // restart SystemUI
                Runtime.getRuntime().exec("setprop ctl.start com.android.systemui");

                Thread.sleep(2000);

                // 8️⃣ wake screen
                Runtime.getRuntime().exec("input keyevent 26");

                XposedBridge.log(TAG + ": Fake reboot finished");

            } catch (Throwable e) {

                XposedBridge.log(TAG + ": Fake reboot error: " + e);
            }

        }).start();
    }
}
