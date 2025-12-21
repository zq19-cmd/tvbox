package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import dalvik.system.DexClassLoader;

public class JsLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    public void clear() {
        for (Spider spider : spiders.values()) App.execute(spider::destroy);
        spiders.clear();
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        try {
            System.out.println("===== JsLoader.getSpider: 开始调用 =====");
            System.out.println("  key=" + key);
            System.out.println("  api_length=" + (api == null ? 0 : api.length()));
            if (api != null && api.length() > 100) {
                System.out.println("  api 前100字符: " + api.substring(0, 100));
            } else {
                System.out.println("  api: " + api);
            }
            System.out.println("  ext=" + ext);
            System.out.println("  jar=" + jar);
            
            if (spiders.containsKey(key)) {
                System.out.println("JsLoader.getSpider: 缓存命中，返回已有 spider");
                return spiders.get(key);
            }
            
            System.out.println("JsLoader.getSpider: 缓存未命中，创建新 Spider 实例");
            System.out.println("JsLoader.getSpider: 获取 DexClassLoader");
            DexClassLoader dex = BaseLoader.get().dex(jar);
            System.out.println("JsLoader.getSpider: DexClassLoader=" + (dex == null ? "null" : dex.getClass().getName()));
            
            System.out.println("JsLoader.getSpider: 创建 com.fongmi.quickjs.crawler.Spider 实例");
            Spider spider = new com.fongmi.quickjs.crawler.Spider(key, api, dex);
            System.out.println("JsLoader.getSpider: Spider 实例创建成功: " + spider.getClass().getName());
            
            System.out.println("JsLoader.getSpider: 调用 spider.init()");
            spider.init(App.get(), ext);
            System.out.println("JsLoader.getSpider: spider.init() 完成");
            
            spiders.put(key, spider);
            System.out.println("JsLoader.getSpider: Spider 缓存完成");
            System.out.println("===== JsLoader.getSpider: 成功返回 =====");
            return spider;
        } catch (Throwable e) {
            System.out.println("===== JsLoader.getSpider: 异常捕获 =====");
            System.out.println("异常类型: " + e.getClass().getName());
            System.out.println("异常信息: " + e.getMessage());
            e.printStackTrace();
            System.out.println("===== JsLoader.getSpider: 返回 SpiderNull =====");
            return new SpiderNull();
        }
    }

    public Object[] proxyInvoke(Map<String, String> params) {
        try {
            if (!params.containsKey("siteKey")) return spiders.get(recent).proxyLocal(params);
            return BaseLoader.get().getSpider(params).proxyLocal(params);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }
}
