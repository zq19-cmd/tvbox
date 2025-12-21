package com.fongmi.chaquo;

import android.content.Context;

import androidx.annotation.Keep;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.github.catvod.utils.Path;

public class Loader {

    private static final String TAG = "ChaquopyLoader";
    private static Loader instance = null;
    private PyObject app;

    /**
     * 获取全局单例实例（供 jar 调用）
     */
    @Keep
    public static Loader getInstance() {
        if (instance == null) {
            instance = new Loader();
        }
        return instance;
    }

    /**
     * 静态方法：直接创建 Spider（供 jar 反射调用）
     */
    @Keep
    public static Spider getSpider(Context context, String api) {
        Loader loader = getInstance();
        return loader.spider(context, api);
    }

    /**
     * 静态方法：创建 JS Spider（供 jar 反射调用）
     * 注意：此方法需要宿主有 JS 解释器支持
     */
    @Keep
    public static Spider getJsSpider(Context context, String api) {
        Loader loader = getInstance();
        return loader.jsSpider(context, api);
    }

    @Keep
    private void init(Context context) {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context));
            }
            
            Python python = Python.getInstance();
            app = python.getModule("app");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Keep
    public Spider spider(Context context, String api) {
        try {
            if (app == null) {
                init(context);
            }
            PyObject obj = app.callAttr("spider", Path.py().getAbsolutePath(), api);
            return new Spider(app, obj, api);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 创建 JS Spider（已弃用）
     * jar 现在直接通过 SimpleHostLoader 调用 QuickJS，无需通过 Loader
     * 保留此方法仅用于向后兼容
     */
    @Keep
    public Spider jsSpider(Context context, String api) {
        // jar 应该直接调用 SimpleHostLoader.getJsSpider()
        // 此方法不再使用
        throw new RuntimeException("jsSpider() 应该通过 SimpleHostLoader.getJsSpider() 调用");
    }
}
