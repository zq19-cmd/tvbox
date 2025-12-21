package com.fongmi.android.tv.server.process;

import android.text.TextUtils;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.utils.Sniffer;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URLDecoder;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Response;

public class AdvancedSniff implements Process {

    private final com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    
    // 常见的视频格式正则
    private static final Pattern VIDEO_PATTERN = Pattern.compile(
        "https?://[^\\s]*\\.(m3u8|mp4|mkv|avi|flv|webm|mov|wmv|rmvb|3gp|ts|m4v|f4v|mpd)" +
        "(?:\\?[^\\s]*)?(?:#[^\\s]*)?", 
        Pattern.CASE_INSENSITIVE
    );
    
    // HLS流媒体正则
    private static final Pattern HLS_PATTERN = Pattern.compile(
        "https?://[^\\s]*(?:m3u8|playlist\\.m3u8|index\\.m3u8)(?:\\?[^\\s]*)?",
        Pattern.CASE_INSENSITIVE
    );
    
    // DASH流媒体正则
    private static final Pattern DASH_PATTERN = Pattern.compile(
        "https?://[^\\s]*\\.mpd(?:\\?[^\\s]*)?",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return url.startsWith("/sniff/");
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        try {
            NanoHTTPD.Response response = handleSniffRequest(session, url);
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
            
            if (session.getMethod() == NanoHTTPD.Method.OPTIONS) {
                return createJsonResponse(new JsonObject());
            }
            
            return response;
        } catch (Exception e) {
            return createErrorResponse(500, "嗅探错误：" + (e == null ? "" : e.getMessage()));
        }
    }

    private NanoHTTPD.Response handleSniffRequest(NanoHTTPD.IHTTPSession session, String url) throws Exception {
        Map<String, String> params = session.getParms();
        
        if (url.equals("/sniff/video")) {
            return handleVideoSniff(params);
        } else if (url.equals("/sniff/url")) {
            return handleUrlSniff(params);
        } else if (url.equals("/sniff/spider")) {
            return handleSpiderSniff(params);
        } else if (url.equals("/sniff/web")) {
            return handleWebSniff(params);
        }
        
    return createErrorResponse(404, "未找到嗅探接口");
    }

    // 基础视频嗅探
    private NanoHTTPD.Response handleVideoSniff(Map<String, String> params) throws Exception {
        String targetUrl = params.get("url");
        if (TextUtils.isEmpty(targetUrl)) {
            return createErrorResponse(400, "缺少 url 参数");
        }
        
        targetUrl = URLDecoder.decode(targetUrl, "UTF-8");
        
        JsonObject result = new JsonObject();
        result.addProperty("url", targetUrl);
        result.addProperty("isVideo", Sniffer.isVideoFormat(targetUrl));
        result.addProperty("sniffed", Sniffer.getUrl(targetUrl));
        
        // 检测视频类型
        String videoType = detectVideoType(targetUrl);
        result.addProperty("type", videoType);
        
        return createJsonResponse(result);
    }

    // URL内容嗅探
    private NanoHTTPD.Response handleUrlSniff(Map<String, String> params) throws Exception {
        String targetUrl = params.get("url");
        if (TextUtils.isEmpty(targetUrl)) {
            return createErrorResponse(400, "缺少 url 参数");
        }
        
        targetUrl = URLDecoder.decode(targetUrl, "UTF-8");
        
        JsonObject result = new JsonObject();
        result.addProperty("url", targetUrl);
        
        try {
            // 获取页面内容
            Response response = OkHttp.newCall(targetUrl).execute();
            String content = response.body().string();
            response.close();
            
            // 从内容中提取视频链接
            JsonArray videos = extractVideoUrls(content);
            result.add("videos", videos);
            result.addProperty("videoCount", videos.size());
            
        } catch (Exception e) {
            result.addProperty("error", e.getMessage());
        }
        
        return createJsonResponse(result);
    }

    // 使用爬虫嗅探
    private NanoHTTPD.Response handleSpiderSniff(Map<String, String> params) throws Exception {
        String siteKey = params.get("site");
        String targetUrl = params.get("url");
        
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(targetUrl)) {
            return createErrorResponse(400, "缺少 site 或 url 参数");
        }
        
        Site site = VodConfig.get().getSite(siteKey);
        if (site.isEmpty()) {
            return createErrorResponse(404, "未找到站点：" + siteKey);
        }
        
        targetUrl = URLDecoder.decode(targetUrl, "UTF-8");
        
        JsonObject result = new JsonObject();
        result.addProperty("site", siteKey);
        result.addProperty("url", targetUrl);
        
        try {
            Spider spider = site.spider();
            
            // 检查爬虫是否支持视频检测
            if (spider.manualVideoCheck()) {
                boolean isVideo = spider.isVideoFormat(targetUrl);
                result.addProperty("isVideo", isVideo);
                result.addProperty("manualCheck", true);
            } else {
                result.addProperty("isVideo", Sniffer.isVideoFormat(targetUrl));
                result.addProperty("manualCheck", false);
            }
            
        } catch (Exception e) {
            result.addProperty("error", e.getMessage());
        }
        
        return createJsonResponse(result);
    }

    // Web页面嗅探（模拟WebView）
    private NanoHTTPD.Response handleWebSniff(Map<String, String> params) throws Exception {
        String targetUrl = params.get("url");
        if (TextUtils.isEmpty(targetUrl)) {
            return createErrorResponse(400, "缺少 url 参数");
        }
        
        targetUrl = URLDecoder.decode(targetUrl, "UTF-8");
        
        JsonObject result = new JsonObject();
        result.addProperty("url", targetUrl);
        
        try {
            // 简化的网页内容获取（实际应用中可能需要更复杂的JavaScript执行）
            Response response = OkHttp.newCall(targetUrl).execute();
            String content = response.body().string();
            response.close();
            
            // 分析页面中的媒体资源
            JsonObject analysis = analyzeWebContent(content);
            result.add("analysis", analysis);
            
        } catch (Exception e) {
            result.addProperty("error", e.getMessage());
        }
        
    return createJsonResponse(result);
    }

    // 检测视频类型
    private String detectVideoType(String url) {
        if (HLS_PATTERN.matcher(url).find()) {
            return "hls";
        } else if (DASH_PATTERN.matcher(url).find()) {
            return "dash";
        } else if (url.contains(".mp4")) {
            return "mp4";
        } else if (url.contains(".mkv")) {
            return "mkv";
        } else if (url.contains(".flv")) {
            return "flv";
        } else if (url.contains(".webm")) {
            return "webm";
        } else if (url.startsWith("rtmp://")) {
            return "rtmp";
        }
        return "unknown";
    }

    // 从内容中提取视频URL
    private JsonArray extractVideoUrls(String content) {
        JsonArray videos = new JsonArray();
        
        // 提取直接的视频链接
        Matcher matcher = VIDEO_PATTERN.matcher(content);
        while (matcher.find()) {
            JsonObject video = new JsonObject();
            String url = matcher.group();
            video.addProperty("url", url);
            video.addProperty("type", detectVideoType(url));
            video.addProperty("source", "direct");
            videos.add(video);
        }
        
        // 提取HLS流
        matcher = HLS_PATTERN.matcher(content);
        while (matcher.find()) {
            JsonObject video = new JsonObject();
            String url = matcher.group();
            video.addProperty("url", url);
            video.addProperty("type", "hls");
            video.addProperty("source", "stream");
            videos.add(video);
        }
        
        // 提取DASH流
        matcher = DASH_PATTERN.matcher(content);
        while (matcher.find()) {
            JsonObject video = new JsonObject();
            String url = matcher.group();
            video.addProperty("url", url);
            video.addProperty("type", "dash");
            video.addProperty("source", "stream");
            videos.add(video);
        }
        
        return videos;
    }

    // 分析网页内容
    private JsonObject analyzeWebContent(String content) {
        JsonObject analysis = new JsonObject();
        
        // 统计各种媒体资源
        int videoCount = countMatches(content, VIDEO_PATTERN);
        int hlsCount = countMatches(content, HLS_PATTERN);
        int dashCount = countMatches(content, DASH_PATTERN);
        
        analysis.addProperty("videoFiles", videoCount);
        analysis.addProperty("hlsStreams", hlsCount);
        analysis.addProperty("dashStreams", dashCount);
        analysis.addProperty("totalMedia", videoCount + hlsCount + dashCount);
        
        // 检查是否包含视频播放器相关脚本
        boolean hasPlayer = content.contains("video") || 
                           content.contains("player") || 
                           content.contains("jwplayer") ||
                           content.contains("videojs");
        analysis.addProperty("hasPlayer", hasPlayer);
        
        // 检查是否是流媒体页面
        boolean isStreaming = content.contains("live") || 
                             content.contains("stream") ||
                             hlsCount > 0 || 
                             dashCount > 0;
        analysis.addProperty("isStreaming", isStreaming);
        
        return analysis;
    }

    // 计算正则匹配次数
    private int countMatches(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // 创建JSON响应
    private NanoHTTPD.Response createJsonResponse(Object data) {
        try {
            String json = gson.toJson(data);
            byte[] bytes = json.getBytes("UTF-8");
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json; charset=utf-8",
                new java.io.ByteArrayInputStream(bytes),
                bytes.length
            );
        } catch (Exception e) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "");
        }
    }

    // 创建错误响应
    private NanoHTTPD.Response createErrorResponse(int status, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", true);
        error.addProperty("message", message);
        error.addProperty("status", status);

    NanoHTTPD.Response.Status responseStatus = NanoHTTPD.Response.Status.lookup(status);
    if (responseStatus == null) responseStatus = NanoHTTPD.Response.Status.INTERNAL_ERROR;

        try {
            String json = gson.toJson(error);
            byte[] bytes = json.getBytes("UTF-8");
            return NanoHTTPD.newFixedLengthResponse(
                responseStatus,
                "application/json; charset=utf-8",
                new java.io.ByteArrayInputStream(bytes),
                bytes.length
            );
        } catch (Exception e) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "");
        }
    }
}
