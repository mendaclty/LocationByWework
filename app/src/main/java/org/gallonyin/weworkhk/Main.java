package org.gallonyin.weworkhk;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Created by gallonyin on 2018/6/13.
 */

public class Main implements IXposedHookLoadPackage {

    /** 模块自身包名（applicationId）。用于在主界面显示“插件是否已生效”。 */
    private static final String MODULE_PACKAGE = "write.LocationGPS";
    /** 历史包名，保留以兼容早期安装的版本。 */
    private static final String LEGACY_MODULE_PACKAGE = "org.gallonyin.weworkhk";
    private static final String WEWORK_PACKAGE = "com.tencent.wework";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            if (!WEWORK_PACKAGE.equals(lpparam.packageName)
                    && !MODULE_PACKAGE.equals(lpparam.packageName)
                    && !LEGACY_MODULE_PACKAGE.equals(lpparam.packageName)) {
                return;
            }
            XposedBridge.log("handleLoadPackage: " + lpparam.packageName + "    " + lpparam.processName);

            if (WEWORK_PACKAGE.equals(lpparam.packageName)) {
                Log.d("Main", "wework");
                new WeWork().start(lpparam.classLoader);
            }
            if (MODULE_PACKAGE.equals(lpparam.packageName)
                    || LEGACY_MODULE_PACKAGE.equals(lpparam.packageName)) {
                // 模块 App 自己也被 hook：MainActivity 启动时调用 confirm() 检测 Xposed 是否生效。
                // 注意 applicationId 与 Java 包名相互独立，这里直接用 Class 对象定位。
                Log.d("Main", "module app");
                XposedHelpers.findAndHookMethod(MainActivity.class, "confirm", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(true);
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            XposedBridge.log(sw.toString());
        }
    }

}
