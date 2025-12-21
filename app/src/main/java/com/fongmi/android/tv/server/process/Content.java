package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Json;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class Content implements Process {

    private final Gson gson = new Gson();

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return url.startsWith("/content/");
    }

    // 智能站点查找 - 支持多种key格式
    private Site findSiteByKey(String requestedKey) {
        List<Site> allSites = VodConfig.get().getSites();
        
        // 1. 精确匹配
        for (Site site : allSites) {
            if (site.getKey().equals(requestedKey)) {
                return site;
            }
        }
        
        // 2. 忽略大小写匹配
        for (Site site : allSites) {
            if (site.getKey().equalsIgnoreCase(requestedKey)) {
                return site;
            }
        }
        
        // 3. 去掉前缀匹配 (csp_SP360 -> SP360, 360)
        String withoutPrefix = requestedKey.replaceFirst("^csp_", "");
        for (Site site : allSites) {
            if (site.getKey().equals(withoutPrefix) || site.getKey().equalsIgnoreCase(withoutPrefix)) {
                return site;
            }
        }
        
        // 4. 包含匹配 (csp_SP360包含360的站点)
        String lowerRequested = requestedKey.toLowerCase();
        for (Site site : allSites) {
            String lowerSiteKey = site.getKey().toLowerCase();
            // 如果请求包含360，查找包含360的站点
            if (lowerRequested.contains("360") && lowerSiteKey.contains("360")) {
                return site;
            }
            // 如果请求包含sp，查找对应的站点
            if (lowerRequested.contains("sp") && lowerSiteKey.contains("sp")) {
                return site;
            }
        }
        
        // 5. 名称匹配
        for (Site site : allSites) {
            String lowerSiteName = site.getName().toLowerCase();
            if (lowerSiteName.contains(lowerRequested) || lowerRequested.contains(lowerSiteName)) {
                return site;
            }
        }
        
        // 6. 返回空站点
        return new Site();
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        try {
            NanoHTTPD.Response response = handleContentRequest(session, url, files);
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
            
            if (session.getMethod() == NanoHTTPD.Method.OPTIONS) {
                return createJsonResponse(new JsonObject());
            }
            
            return response;
        } catch (Exception e) {
            return createErrorResponse(500, "Content error: " + e.getMessage());
        }
    }

    private NanoHTTPD.Response handleContentRequest(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) throws Exception {
        Map<String, String> params = session.getParms();
        
        if (url.equals("/content/search")) {
            return handleSearch(params);
        } else if (url.equals("/content/detail")) {
            return handleDetail(params);
        } else if (url.equals("/content/play")) {
            return handlePlay(params);
        } else if (url.equals("/content/recommend")) {
            return handleRecommend(params);
        } else if (url.equals("/content/categories")) {
            return handleCategories(params);
        } else if (url.equals("/content/live")) {
            return handleLive(params);
        }
        
        return createErrorResponse(404, "Content endpoint not found");
    }

    // 全局搜索
    private NanoHTTPD.Response handleSearch(Map<String, String> params) throws Exception {
        String keyword = params.get("wd");
        if (TextUtils.isEmpty(keyword)) {
            return createErrorResponse(400, "Missing keyword parameter");
        }
        
        keyword = URLDecoder.decode(keyword, "UTF-8");
        boolean quick = "true".equals(params.get("quick"));
        String page = params.getOrDefault("pg", "1");
        String siteKey = params.get("site");
        
        JsonObject result = new JsonObject();
        result.addProperty("keyword", keyword);
        result.addProperty("page", page);
        result.addProperty("quick", quick);
        
        JsonArray searchResults = new JsonArray();
        
        if (!TextUtils.isEmpty(siteKey)) {
            // 指定站点搜索
            Site site = findSiteByKey(siteKey);
            
            // 添加调试信息
            JsonObject debugInfo = new JsonObject();
            debugInfo.addProperty("requestedSiteKey", siteKey);
            List<Site> allSites = VodConfig.get().getSites();
            JsonArray availableSites = new JsonArray();
            for (Site s : allSites) {
                JsonObject siteInfo = new JsonObject();
                siteInfo.addProperty("key", s.getKey());
                siteInfo.addProperty("name", s.getName());
                siteInfo.addProperty("keyEquals", s.getKey().equals(siteKey));
                availableSites.add(siteInfo);
            }
            debugInfo.add("availableSites", availableSites);
            debugInfo.addProperty("foundSiteKey", site.getKey());
            debugInfo.addProperty("foundSiteName", site.getName());
            debugInfo.addProperty("foundSiteIsEmpty", site.isEmpty());
            
            if (!site.isEmpty()) {
                JsonObject siteResult = searchSite(site, keyword, quick, page);
                searchResults.add(siteResult);
            } else {
                JsonObject errorResult = new JsonObject();
                errorResult.addProperty("error", true);
                errorResult.addProperty("message", "Site not found: " + siteKey);
                errorResult.addProperty("status", 404);
                errorResult.add("debug", debugInfo);
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    "application/json",
                    gson.toJson(errorResult)
                );
            }
        } else {
            // 全站搜索
            for (Site site : VodConfig.get().getSites()) {
                if (site.getSearchable() == 1) {
                    try {
                        JsonObject siteResult = searchSite(site, keyword, quick, page);
                        searchResults.add(siteResult);
                    } catch (Exception e) {
                        // 忽略单个站点的错误，继续搜索其他站点
                    }
                }
            }
        }
        
        result.add("results", searchResults);
        result.addProperty("siteCount", searchResults.size());
        
        return createJsonResponse(result);
    }

    // 搜索单个站点
    private JsonObject searchSite(Site site, String keyword, boolean quick, String page) throws Exception {
        JsonObject siteResult = new JsonObject();
        siteResult.addProperty("siteKey", site.getKey());
        siteResult.addProperty("siteName", site.getName());
        
        try {
            Spider spider = site.spider();
            String content = spider.searchContent(keyword, quick, page);
            JsonObject searchData = Json.parse(content).getAsJsonObject();
            siteResult.add("data", searchData);
            siteResult.addProperty("success", true);
        } catch (Exception e) {
            siteResult.addProperty("success", false);
            siteResult.addProperty("error", e.getMessage());
        }
        
        return siteResult;
    }

    // 获取详情
    private NanoHTTPD.Response handleDetail(Map<String, String> params) throws Exception {
        String siteKey = params.get("site");
        String id = params.get("id");
        
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(id)) {
            return createErrorResponse(400, "Missing site or id parameter");
        }
        
        Site site = findSiteByKey(siteKey);
        
        // 添加调试信息
        JsonObject debugInfo = new JsonObject();
        debugInfo.addProperty("requestedSiteKey", siteKey);
        List<Site> allSites = VodConfig.get().getSites();
        JsonArray availableSites = new JsonArray();
        for (Site s : allSites) {
            JsonObject siteInfo = new JsonObject();
            siteInfo.addProperty("key", s.getKey());
            siteInfo.addProperty("name", s.getName());
            siteInfo.addProperty("keyEquals", s.getKey().equals(siteKey));
            availableSites.add(siteInfo);
        }
        debugInfo.add("availableSites", availableSites);
        debugInfo.addProperty("foundSiteKey", site.getKey());
        debugInfo.addProperty("foundSiteName", site.getName());
        debugInfo.addProperty("foundSiteIsEmpty", site.isEmpty());
        
        if (site.isEmpty()) {
            JsonObject errorResult = new JsonObject();
            errorResult.addProperty("error", true);
            errorResult.addProperty("message", "Site not found: " + siteKey);
            errorResult.addProperty("status", 404);
            errorResult.add("debug", debugInfo);
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(errorResult)
            );
        }
        
        id = URLDecoder.decode(id, "UTF-8");
        
        JsonObject result = new JsonObject();
        result.addProperty("site", siteKey);
        result.addProperty("id", id);
        
        try {
            Spider spider = site.spider();
            String content = spider.detailContent(Arrays.asList(id));
            JsonObject detailData = Json.parse(content).getAsJsonObject();
            result.add("data", detailData);
            result.addProperty("success", true);
        } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("error", e.getMessage());
        }
        
        return createJsonResponse(result);
    }

    // 获取播放链接
    private NanoHTTPD.Response handlePlay(Map<String, String> params) throws Exception {
        String siteKey = params.get("site");
        String flag = params.get("flag");
        String id = params.get("id");
        
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(flag) || TextUtils.isEmpty(id)) {
            return createErrorResponse(400, "Missing required parameters");
        }
        
        Site site = findSiteByKey(siteKey);
        if (site.isEmpty()) {
            return createErrorResponse(404, "Site not found: " + siteKey);
        }
        
        flag = URLDecoder.decode(flag, "UTF-8");
        id = URLDecoder.decode(id, "UTF-8");
        
        List<String> vipFlags = Arrays.asList(params.getOrDefault("vipFlags", "").split(","));
        
        JsonObject result = new JsonObject();
        result.addProperty("site", siteKey);
        result.addProperty("flag", flag);
        result.addProperty("id", id);
        
        try {
            Spider spider = site.spider();
            String content = spider.playerContent(flag, id, vipFlags);
            JsonObject playData = Json.parse(content).getAsJsonObject();
            result.add("data", playData);
            result.addProperty("success", true);
        } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("error", e.getMessage());
        }
        
        return createJsonResponse(result);
    }

    // 获取推荐内容
    private NanoHTTPD.Response handleRecommend(Map<String, String> params) throws Exception {
        String siteKey = params.get("site");
        
        JsonObject result = new JsonObject();
        JsonArray recommendations = new JsonArray();
        
        if (!TextUtils.isEmpty(siteKey)) {
            // 指定站点推荐
            Site site = VodConfig.get().getSite(siteKey);
            if (!site.isEmpty()) {
                JsonObject siteRecommend = getRecommendFromSite(site);
                recommendations.add(siteRecommend);
            }
        } else {
            // 获取所有站点的推荐内容
            for (Site site : VodConfig.get().getSites()) {
                try {
                    JsonObject siteRecommend = getRecommendFromSite(site);
                    recommendations.add(siteRecommend);
                } catch (Exception e) {
                    // 忽略单个站点的错误
                }
            }
        }
        
        result.add("recommendations", recommendations);
        result.addProperty("siteCount", recommendations.size());
        
        return createJsonResponse(result);
    }

    // 获取单个站点的推荐内容
    private JsonObject getRecommendFromSite(Site site) throws Exception {
        JsonObject siteRecommend = new JsonObject();
        siteRecommend.addProperty("siteKey", site.getKey());
        siteRecommend.addProperty("siteName", site.getName());
        
        try {
            Spider spider = site.spider();
            String content = spider.homeVideoContent();
            if (!TextUtils.isEmpty(content)) {
                JsonObject recommendData = Json.parse(content).getAsJsonObject();
                siteRecommend.add("data", recommendData);
            }
            siteRecommend.addProperty("success", true);
        } catch (Exception e) {
            siteRecommend.addProperty("success", false);
            siteRecommend.addProperty("error", e.getMessage());
        }
        
        return siteRecommend;
    }

    // 获取分类内容
    private NanoHTTPD.Response handleCategories(Map<String, String> params) throws Exception {
        String siteKey = params.get("site");
        String tid = params.get("tid");
        String page = params.getOrDefault("pg", "1");
        boolean filter = "true".equals(params.get("filter"));
        
        if (TextUtils.isEmpty(siteKey)) {
            return createErrorResponse(400, "Missing site parameter");
        }
        
        Site site = findSiteByKey(siteKey);
        
        // 添加调试信息
        JsonObject debugInfo = new JsonObject();
        debugInfo.addProperty("requestedSiteKey", siteKey);
        List<Site> allSites = VodConfig.get().getSites();
        JsonArray availableSites = new JsonArray();
        for (Site s : allSites) {
            JsonObject siteInfo = new JsonObject();
            siteInfo.addProperty("key", s.getKey());
            siteInfo.addProperty("name", s.getName());
            siteInfo.addProperty("keyEquals", s.getKey().equals(siteKey));
            availableSites.add(siteInfo);
        }
        debugInfo.add("availableSites", availableSites);
        debugInfo.addProperty("foundSiteKey", site.getKey());
        debugInfo.addProperty("foundSiteName", site.getName());
        debugInfo.addProperty("foundSiteIsEmpty", site.isEmpty());
        
        if (site.isEmpty()) {
            JsonObject errorResult = new JsonObject();
            errorResult.addProperty("error", true);
            errorResult.addProperty("message", "Site not found: " + siteKey);
            errorResult.addProperty("status", 404);
            errorResult.add("debug", debugInfo);
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(errorResult)
            );
        }
        
        JsonObject result = new JsonObject();
        result.addProperty("site", siteKey);
        result.addProperty("tid", tid);
        result.addProperty("page", page);
        result.addProperty("filter", filter);
        
        try {
            Spider spider = site.spider();
            
            if (TextUtils.isEmpty(tid)) {
                // 获取首页内容（包含分类列表）
                String content = spider.homeContent(filter);
                JsonObject homeData = Json.parse(content).getAsJsonObject();
                result.add("data", homeData);
            } else {
                // 获取指定分类的内容
                HashMap<String, String> extend = new HashMap<>();
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    if (!Arrays.asList("site", "tid", "pg", "filter").contains(entry.getKey())) {
                        extend.put(entry.getKey(), entry.getValue());
                    }
                }
                
                String content = spider.categoryContent(tid, page, filter, extend);
                JsonObject categoryData = Json.parse(content).getAsJsonObject();
                result.add("data", categoryData);
            }
            
            result.addProperty("success", true);
        } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("error", e.getMessage());
        }
        
        return createJsonResponse(result);
    }

    // 获取直播内容
    private NanoHTTPD.Response handleLive(Map<String, String> params) throws Exception {
        String liveKey = params.get("live");
        String url = params.get("url");
        
        JsonObject result = new JsonObject();
        
        if (!TextUtils.isEmpty(liveKey)) {
            // 指定直播源
            Live live = LiveConfig.get().getLive(liveKey);
            if (!live.isEmpty()) {
                result.addProperty("liveKey", liveKey);
                result.addProperty("liveName", live.getName());
                
                try {
                    Spider spider = live.spider();
                    if (!TextUtils.isEmpty(url)) {
                        url = URLDecoder.decode(url, "UTF-8");
                        String content = spider.liveContent(url);
                        JsonObject liveData = Json.parse(content).getAsJsonObject();
                        result.add("data", liveData);
                    }
                    result.addProperty("success", true);
                } catch (Exception e) {
                    result.addProperty("success", false);
                    result.addProperty("error", e.getMessage());
                }
            } else {
                return createErrorResponse(404, "Live source not found: " + liveKey);
            }
        } else {
            // 获取所有直播源列表
            JsonArray lives = new JsonArray();
            for (Live live : LiveConfig.get().getLives()) {
                JsonObject liveObj = new JsonObject();
                liveObj.addProperty("name", live.getName());
                liveObj.addProperty("api", live.getApi());
                liveObj.addProperty("url", live.getUrl());
                lives.add(liveObj);
            }
            result.add("lives", lives);
            result.addProperty("total", lives.size());
        }
        
        return createJsonResponse(result);
    }

    // 创建JSON响应
    private NanoHTTPD.Response createJsonResponse(Object data) {
        String json = gson.toJson(data);
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, 
            "application/json", 
            json
        );
    }

    // 创建错误响应
    private NanoHTTPD.Response createErrorResponse(int status, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", true);
        error.addProperty("message", message);
        error.addProperty("status", status);
        
        NanoHTTPD.Response.Status responseStatus;
        switch (status) {
            case 400: responseStatus = NanoHTTPD.Response.Status.BAD_REQUEST; break;
            case 404: responseStatus = NanoHTTPD.Response.Status.NOT_FOUND; break;
            case 500: responseStatus = NanoHTTPD.Response.Status.INTERNAL_ERROR; break;
            default: responseStatus = NanoHTTPD.Response.Status.INTERNAL_ERROR; break;
        }
        
        return NanoHTTPD.newFixedLengthResponse(
            responseStatus, 
            "application/json", 
            gson.toJson(error)
        );
    }
}
