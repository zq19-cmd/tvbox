package com.fongmi.quickjs.crawler;

import android.content.Context;

import com.fongmi.quickjs.bean.Res;
import com.fongmi.quickjs.method.Console;
import com.fongmi.quickjs.method.Global;
import com.fongmi.quickjs.method.Local;
import com.fongmi.quickjs.utils.Async;
import com.fongmi.quickjs.utils.JSUtil;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.whl.quickjs.wrapper.JSArray;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dalvik.system.DexClassLoader;
import java9.util.concurrent.CompletableFuture;

public class Spider extends com.github.catvod.crawler.Spider {

    private final ExecutorService executor;
    private final DexClassLoader dex;
    private QuickJSContext ctx;
    private JSObject jsObject;
    private final String key;
    private final String api;
    private boolean cat;

    public Spider(String key, String api, DexClassLoader dex) throws Exception {
        System.out.println("QuickJS Spider 构造函数: key=" + key + ", api_length=" + (api == null ? 0 : api.length()) + ", dex=" + (dex == null ? "null" : dex.getClass().getName()));
        this.executor = Executors.newSingleThreadExecutor();
        this.key = key;
        this.api = api;
        this.dex = dex;
        System.out.println("QuickJS Spider 构造函数: 调用 initializeJS");
        initializeJS();
        System.out.println("QuickJS Spider 构造函数: initializeJS 完成");
    }

    private void submit(Runnable runnable) {
        executor.submit(runnable);
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    private Object call(String func, Object... args) throws Exception {
        return CompletableFuture.supplyAsync(() -> Async.run(jsObject, func, args), executor).join().get();
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        if (cat) call("init", submit(() -> cfg(extend)).get());
        else call("init", Json.isObj(extend) ? ctx.parse(extend) : extend);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return (String) call("home", filter);
    }

    @Override
    public String homeVideoContent() throws Exception {
        return (String) call("homeVod");
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        JSObject obj = submit(() -> JSUtil.toObject(ctx, extend)).get();
        return (String) call("category", tid, pg, filter, obj);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return (String) call("detail", ids.get(0));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return (String) call("search", key, quick);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return (String) call("search", key, quick, pg);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSArray array = submit(() -> JSUtil.toArray(ctx, vipFlags)).get();
        return (String) call("play", flag, id, array);
    }

    @Override
    public String liveContent(String url) throws Exception {
        return (String) call("live", url);
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return (Boolean) call("sniffer");
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return (Boolean) call("isVideo", url);
    }

    @Override
    public Object[] proxyLocal(Map<String, String> params) throws Exception {
        if ("catvod".equals(params.get("from"))) return proxy2(params);
        else return submit(() -> proxy1(params)).get();
    }

    @Override
    public String action(String action) throws Exception {
        return (String) call("action", action);
    }

    @Override
    public void destroy() {
        try {
            call("destroy");
        } catch (Throwable e) {
            e.printStackTrace();
        }
        submit(() -> {
            executor.shutdownNow();
            jsObject.release();
            ctx.destroy();
        });
    }

    private void initializeJS() throws Exception {
        System.out.println("QuickJS initializeJS: 开始");
        submit(() -> {
            System.out.println("QuickJS initializeJS: 线程池任务开始");
            createCtx();
            System.out.println("QuickJS initializeJS: createCtx 完成");
            createFun();
            System.out.println("QuickJS initializeJS: createFun 完成");
            createObj();
            System.out.println("QuickJS initializeJS: createObj 完成");
            return null;
        }).get();
        System.out.println("QuickJS initializeJS: 完成");
    }

    private void createCtx() {
        System.out.println("QuickJS createCtx: 开始");
        ctx = QuickJSContext.create();
        System.out.println("QuickJS createCtx: QuickJSContext 创建成功");
        ctx.setConsole(new Console());
        ctx.evaluate(Asset.read("js/lib/http.js"));
        System.out.println("QuickJS createCtx: http.js 加载成功");
        ctx.getGlobalObject().setProperty("local", Local.class);
        ctx.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
            @Override
            public String moduleNormalizeName(String baseModuleName, String moduleName) {
                System.out.println("QuickJS moduleNormalizeName: baseModuleName=" + baseModuleName + ", moduleName=" + moduleName);
                return UriUtil.resolve(baseModuleName, moduleName);
            }

            @Override
            public byte[] getModuleBytecode(String moduleName) {
                System.out.println("QuickJS getModuleBytecode: moduleName=" + moduleName);
                String moduleContent = Module.get().fetch(moduleName);
                if (moduleContent == null || moduleContent.isEmpty()) {
                    System.out.println("QuickJS getModuleBytecode: Module.get().fetch 返回空值！");
                    return new byte[0];
                }
                System.out.println("QuickJS getModuleBytecode: 成功获取模块内容，长度=" + moduleContent.length());
                return ctx.compileModule(moduleContent, moduleName);
            }
        });
        System.out.println("QuickJS createCtx: 完成");
    }

    private void createFun() {
        System.out.println("QuickJS createFun: 开始");
        try {
            Global.create(ctx, executor);
            System.out.println("QuickJS createFun: Global.create 完成");
            Class<?> clz = dex.loadClass("com.github.catvod.js.Function");
            System.out.println("QuickJS createFun: 加载 Function 类成功");
            clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx);
            System.out.println("QuickJS createFun: Function 实例创建成功");
        } catch (Throwable e) {
            System.out.println("QuickJS createFun: 异常 -> " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("QuickJS createFun: 完成");
    }

    private void createObj() {
        System.out.println("QuickJS createObj: 开始");
        String spider = "__JS_SPIDER__";
        String global = "globalThis." + spider;
        System.out.println("QuickJS createObj: 调用 Module.get().fetch(api), api_length=" + (api == null ? 0 : api.length()));
        String content = Module.get().fetch(api);
        System.out.println("QuickJS createObj: 获取内容完成, 返回值=" + (content == null ? "null" : "长度=" + content.length()));
        
        if (content == null || content.isEmpty()) {
            System.out.println("QuickJS createObj: 错误！内容为空，无法继续");
            throw new RuntimeException("Module.get().fetch(api) 返回空值");
        }
        
        cat = content.contains("__jsEvalReturn");
        System.out.println("QuickJS createObj: cat=" + cat);
        ctx.evaluateModule(content.replace(spider, global), api);
        System.out.println("QuickJS createObj: evaluateModule (1) 完成");
        ctx.evaluateModule(String.format(Asset.read("js/lib/spider.js"), api));
        System.out.println("QuickJS createObj: evaluateModule (2) 完成");
        jsObject = (JSObject) ctx.getProperty(ctx.getGlobalObject(), spider);
        System.out.println("QuickJS createObj: jsObject 获取完成, " + (jsObject == null ? "null" : "已获取"));
        System.out.println("QuickJS createObj: 完成");
    }

    private JSObject cfg(String ext) {
        JSObject cfg = ctx.createNewJSObject();
        cfg.setProperty("stype", 3);
        cfg.setProperty("skey", key);
        if (!Json.isObj(ext)) cfg.setProperty("ext", ext);
        else cfg.setProperty("ext", (JSObject) ctx.parse(ext));
        return cfg;
    }

    private Object[] proxy1(Map<String, String> params) throws Exception {
        JSObject object = JSUtil.toObject(ctx, params);
        JSONArray array = new JSONArray(((JSArray) jsObject.getJSFunction("proxy").call(object)).stringify());
        Map<String, String> headers = array.length() > 3 ? Json.toMap(array.optString(3)) : null;
        boolean base64 = array.length() > 4 && array.optInt(4) == 1;
        Object[] result = new Object[4];
        result[0] = array.optInt(0);
        result[1] = array.optString(1);
        result[2] = getStream(array.opt(2), base64);
        result[3] = headers;
        return result;
    }

    private Object[] proxy2(Map<String, String> params) throws Exception {
        String url = params.get("url");
        String header = params.get("header");
        JSArray array = submit(() -> JSUtil.toArray(ctx, Arrays.asList(url.split("/")))).get();
        Object object = submit(() -> ctx.parse(header)).get();
        String json = (String) call("proxy", array, object);
        Res res = Res.objectFrom(json);
        Object[] result = new Object[3];
        result[0] = res.getCode();
        result[1] = res.getContentType();
        result[2] = res.getStream();
        return result;
    }

    private ByteArrayInputStream getStream(Object o, boolean base64) {
        if (o instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) o);
        } else {
            String content = o.toString();
            if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
            return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
        }
    }
}
