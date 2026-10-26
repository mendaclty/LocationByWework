package org.gallonyin.weworkhk;

import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Wework前台时的音量键快捷入口：
 * 音量+ 呼出/恢复选点弹窗（占用），音量- 在弹窗可见时隐藏（弹窗未开时保持正常音量功能）。
 *
 * 拦截点选在 PhoneWindow.superDispatchKeyEvent：进程内所有窗口（Activity/Dialog）的按键
 * 都会经过这里，不受宿主 Activity/Dialog 覆写 dispatchKeyEvent 影响。
 * 注意：音量+ 在Wework前台期间被占用，语音/视频通话无法用音量键调音量。
 */
final class VolumeKeyInjector {
    private static final String TAG = "WeWork";

    private VolumeKeyInjector() {
    }

    static void install(ClassLoader classLoader) {
        String[] candidates = {
                "com.android.internal.policy.PhoneWindow",
                "com.android.internal.policy.impl.PhoneWindow",
        };
        for (String name : candidates) {
            try {
                Class<?> phoneWindow = XposedHelpers.findClass(name, classLoader);
                XposedBridge.hookAllMethods(phoneWindow, "superDispatchKeyEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        handle(param);
                    }
                });
                Log.d(TAG, "volume key injector installed on " + name);
                return;
            } catch (Throwable t) {
                Log.d(TAG, "volume hook candidate failed: " + name + " " + t);
            }
        }
        XposedBridge.log(TAG + " volume key injector failed: no PhoneWindow found");
    }

    private static void handle(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args.length < 1 || !(param.args[0] instanceof KeyEvent)) return;
            KeyEvent event = (KeyEvent) param.args[0];
            int code = event.getKeyCode();
            boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
            boolean down = code == KeyEvent.KEYCODE_VOLUME_DOWN;
            if (!up && !down) return;

            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                // 抬起/重复：凡是被我们占用按下事件的键，一并吞掉，避免残留音量变化
                if (up || WeworkLocationPicker.isShowing()) param.setResult(Boolean.TRUE);
                return;
            }

            if (up) {
                param.setResult(Boolean.TRUE);
                Context context = param.thisObject instanceof Window ? ((Window) param.thisObject).getContext() : null;
                WeworkLocationPicker.showOrRestore(context);
                Log.d(TAG, "volume up: picker shown/restored");
            } else if (WeworkLocationPicker.isShowing()) {
                param.setResult(Boolean.TRUE);
                WeworkLocationPicker.hideByVolumeKey();
                Log.d(TAG, "volume down: picker hidden");
            }
            // 弹窗未开时音量- 不拦截，保留正常音量功能
        } catch (Throwable t) {
            Log.d(TAG, "volume key handle failed: " + t);
        }
    }
}
