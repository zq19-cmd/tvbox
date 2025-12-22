package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.utils.Sniffer;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Json;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.bean.Url;
import com.github.catvod.utils.Prefers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;
import java.util.Iterator;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Response;
import com.fongmi.android.tv.server.process.WsClient;

public class Api implements Process {

    private final com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    // 当前请求的页码（由 handleApiRequest 设置），用于统一响应字段
    private final ThreadLocal<String> currentPg = new ThreadLocal<>();
    // 简单的代理 ID 映射表（id -> ProxyEntry）
    private static final ConcurrentHashMap<String, ProxyEntry> PROXY_MAP = new ConcurrentHashMap<>();
    private static final long PROXY_TTL_MS = 4 * 60 * 60 * 1000; // 4 小时 (足够播放长电影及其m3u8分段)
    
    // 性能优化: 添加结果缓存
    private static final ConcurrentHashMap<String, CacheEntry> RESULT_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3 * 60 * 1000; // 3分钟缓存
    
    // 性能优化: 添加站点缓存
    private static final ConcurrentHashMap<String, Site> SITE_CACHE = new ConcurrentHashMap<>();
    
    // 追踪当前站源，用于检测站源变化
    private static volatile String CURRENT_SITE_KEY = null;
    
    // 性能优化: 定时清理过期缓存
    private static final ScheduledExecutorService CLEANER = Executors.newSingleThreadScheduledExecutor();
    
    static {
        CLEANER.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                PROXY_MAP.entrySet().removeIf(e -> e.getValue().expiry < now);
                RESULT_CACHE.entrySet().removeIf(e -> !e.getValue().isValid());
            } catch (Exception ignored) {}
        }, 1, 1, TimeUnit.MINUTES);
    }

    // 可选的 WS 客户端实例（根据配置动态创建）- 改为静态变量,全局共享
    private static volatile WsClient wsClient = null;
    private static final Object wsLock = new Object();
    private static volatile boolean wsInitialized = false; // 标记是否已初始化
    
    // 缓存条目类
    private static class CacheEntry {
        Object data;
        long expiry;
        
        CacheEntry(Object data, long ttl) {
            this.data = data;
            this.expiry = System.currentTimeMillis() + ttl;
        }
        
        boolean isValid() {
            return System.currentTimeMillis() < expiry;
        }
    }

    private static class ProxyEntry {
        String url;
        long expiry;
        Map<String, String> headers;

        ProxyEntry(String url, long expiry, Map<String, String> headers) {
            this.url = url;
            this.expiry = expiry;
            this.headers = headers;
        }
    }

    // 将一个长 URL 存为短 id，返回 id（带更低冲突概率的 UUID 前缀）
    private String storeProxyUrl(String url) {
        return storeProxyUrl(url, null);
    }

    private String resolveProxyId(String id) {
        ProxyEntry entry = PROXY_MAP.get(id);
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expiry) {
            PROXY_MAP.remove(id);
            return null;
        }
        return entry.url;
    }

    // 获取完整的 ProxyEntry（包含 headers），用于代理请求时重放头信息
    private ProxyEntry resolveProxyEntry(String id) {
        ProxyEntry entry = PROXY_MAP.get(id);
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expiry) {
            PROXY_MAP.remove(id);
            return null;
        }
        return entry;
    }

    // 存储时附带 headers (性能优化: 移除每次调用的清理,改用定时清理)
    private String storeProxyUrl(String url, Map<String, String> headers) {
        try {
            // 使用 UUID + 时间戳组合生成短 id，长度约为16字符
            String uuid = java.util.UUID.randomUUID().toString().replace("-", "");
            String id = uuid.substring(0, 12) + Long.toHexString(System.currentTimeMillis() & 0xffff);
            long expiry = System.currentTimeMillis() + PROXY_TTL_MS;
            PROXY_MAP.put(id, new ProxyEntry(url, expiry, headers == null ? null : new HashMap<>(headers)));
            return id;
        } catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis());
        }
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        // 排除 /vod/menu (由 SiteMenu 处理)
        if (url.startsWith("/vod/menu")) return false;
        return url.startsWith("/vod/") || url.startsWith("/config/") || url.startsWith("/live") || url.startsWith("/index.php/");
    }

    // 默认构造器：只在第一次创建时初始化 WS (双重检查锁定)
    public Api() {
        if (!wsInitialized) {
            synchronized (wsLock) {
                if (!wsInitialized) {
                    try {
                        startWsIfConfigured();
                        wsInitialized = true;
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    // 启动或重连 WS（若配置了自动连接）
    private void startWsIfConfigured() {
        String wsUrl = Prefers.getString("api_ws_url", "");
        String auto = Prefers.getString("api_ws_autoconnect", "0");
        if (!TextUtils.isEmpty(wsUrl) && "1".equals(auto)) {
            startWs(wsUrl);
        }
    }

    private String currentWsUrl = ""; // 记录当前连接的 WS URL
    
    private void startWs(String wsUrl) {
        if (TextUtils.isEmpty(wsUrl)) return;
        synchronized (wsLock) {
            try {
                // 如果已经连接到同一个 URL 且连接正常,则不重复启动
                if (wsClient != null && wsUrl.equals(currentWsUrl)) {
                    if (wsClient.isConnected()) {
                        android.util.Log.d("Api", "WS 已连接到 " + wsUrl + ",跳过重复启动");
                        return; // 连接正常,不需要重启
                    }
                    // 连接已断开,需要重启
                    android.util.Log.d("Api", "WS 连接已断开,准备重启");
                }
                
                // 停止旧连接(如果有)
                if (wsClient != null) {
                    android.util.Log.d("Api", "停止旧的 WS 连接");
                    wsClient.stop();
                    wsClient = null;
                }
            } catch (Exception e) {
                android.util.Log.e("Api", "停止旧 WS 连接失败: " + e.getMessage());
            }
            
            try {
                android.util.Log.d("Api", "启动新的 WS 连接: " + wsUrl);
                String localBase = "http://127.0.0.1:" + getServerPort();
                wsClient = new WsClient(wsUrl, localBase, com.fongmi.android.tv.App.get());
                wsClient.start();
                currentWsUrl = wsUrl; // 记录当前 URL
            } catch (Exception e) {
                android.util.Log.e("Api", "启动 WS 连接失败: " + e.getMessage());
                wsClient = null;
                currentWsUrl = "";
            }
        }
    }

    private void stopWs() {
        synchronized (wsLock) {
            try {
                if (wsClient != null) {
                    android.util.Log.d("Api", "停止 WS 连接");
                    wsClient.stop();
                    wsClient = null;
                }
                currentWsUrl = ""; // 清空当前 URL
            } catch (Exception e) {
                android.util.Log.e("Api", "停止 WS 连接失败: " + e.getMessage());
            }
        }
    }

    // 简单的 WS 检测接口：/config/ws_check?wsUrl=...
    private NanoHTTPD.Response handleWsCheck(Map<String, String> params) {
        String wsUrl = params.get("wsUrl");
        if (wsUrl == null || wsUrl.isEmpty()) return createErrorResponse(400, "缺少 wsUrl 参数");

        final StringBuilder result = new StringBuilder();
        final Object lock = new Object();
        final boolean[] done = new boolean[]{false};

        OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(true).build();
        Request req = new Request.Builder().url(wsUrl).build();
        final WebSocket[] wsRef = new WebSocket[1];

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                // 连接建立后发送一次探测消息，优先等待服务器返回的第一条消息
                try {
                    wsRef[0] = webSocket;
                    webSocket.send("ping");
                } catch (Exception ignored) {}
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                synchronized (lock) {
                    if (!done[0]) {
                        // 直接返回服务端的第一条消息内容
                        result.append(text == null ? "" : text);
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                synchronized (lock) {
                    if (!done[0]) {
                        result.append("failure:" + (t == null ? "" : t.getMessage()));
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }
        };

        try {
            wsRef[0] = client.newWebSocket(req, listener);
            long start = System.currentTimeMillis();
            synchronized (lock) {
                while (!done[0] && System.currentTimeMillis() - start < 5000) {
                    try { lock.wait(5000); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            result.append("exception:" + e.getMessage());
        } finally {
            try { if (wsRef[0] != null) wsRef[0].close(1000, "check done"); } catch (Exception ignored) {}
            try { client.dispatcher().executorService().shutdownNow(); } catch (Exception ignored) {}
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("code", 1);
    String msgVal;
    if (result.length() > 0) msgVal = result.toString();
    else if (wsRef[0] != null) msgVal = "open_no_message";
    else msgVal = "no response";
    resp.addProperty("msg", msgVal);
        try {
            String jsonStr = gson.toJson(resp);
            byte[] bytes = jsonStr.getBytes("UTF-8");
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (UnsupportedEncodingException e) {
            return createErrorResponse(500, "编码错误");
        }
    }

    // 获取当前 NanoHTTPD 服务器端口（尝试从配置或环境推断）
    private int getServerPort() {
        try {
            // 优先从 Prefers 中读取（若保存了 server 地址）
            String override = Prefers.getString("api_server_addr", "");
            if (!TextUtils.isEmpty(override)) {
                if (override.startsWith("http")) {
                    java.net.URL u = new java.net.URL(override);
                    return u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
                }
            }
        } catch (Exception ignored) {}
        // 默认端口
        return 9978;
    }

    // 返回当前 WS 客户端状态
    private NanoHTTPD.Response handleWsStatus(Map<String, String> params) {
        JsonObject resp = new JsonObject();
        resp.addProperty("code", 1);
        boolean connected = false;
        String url = Prefers.getString("api_ws_url", "");
        try {
            synchronized (wsLock) {
                if (wsClient != null) {
                    try { connected = wsClient.isConnected(); } catch (Exception ignored) { connected = false; }
                }
            }
        } catch (Exception ignored) {}
        resp.addProperty("connected", connected);
        resp.addProperty("url", url);
        try {
            String jsonStr = gson.toJson(resp);
            byte[] bytes = jsonStr.getBytes("UTF-8");
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (UnsupportedEncodingException e) {
            return createErrorResponse(500, "编码错误");
        }
    }

    private NanoHTTPD.Response handleProxyUrl(Map<String, String> params) {
        JsonObject resp = new JsonObject();
        resp.addProperty("code", 1);
        boolean connected = false;
        String clientId = "";
        String proxyUrl = "";
        
        try {
            synchronized (wsLock) {
                if (wsClient != null) {
                    try { 
                        connected = wsClient.isConnected();
                        if (connected) {
                            // 获取服务器实际使用的客户端ID
                            // 单客户端模式下服务器会强制使用 "api"
                            clientId = wsClient.getServerClientId();
                            if (clientId != null && !clientId.isEmpty()) {
                                // 构建透传地址
                                String wsUrl = Prefers.getString("api_ws_url", "");
                                if (wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://")) {
                                    // 将 ws:// 替换为 http://，wss:// 替换为 https://
                                    String httpUrl = wsUrl.replace("ws://", "http://").replace("wss://", "https://");
                                    // 移除 /ws 后缀
                                    if (httpUrl.endsWith("/ws")) {
                                        httpUrl = httpUrl.substring(0, httpUrl.length() - 3);
                                    }
                                    // 单客户端模式(clientId="api")直接使用服务器地址,不添加ID前缀
                                    if ("api".equals(clientId)) {
                                        proxyUrl = httpUrl + "/";
                                    } else {
                                        proxyUrl = httpUrl + "/" + clientId + "/";
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) { 
                        connected = false; 
                    }
                }
            }
        } catch (Exception ignored) {}
        
        resp.addProperty("connected", connected);
        resp.addProperty("client_id", clientId);
        resp.addProperty("proxy_url", proxyUrl);
        resp.addProperty("multi_mode", false); // Android客户端不支持multi模式
        resp.addProperty("client_count", connected ? 1 : 0);
        
        try {
            String jsonStr = gson.toJson(resp);
            byte[] bytes = jsonStr.getBytes("UTF-8");
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (UnsupportedEncodingException e) {
            return createErrorResponse(500, "编码错误");
        }
    }

    // 智能站点查找 - 支持多种key格式 (性能优化: 添加缓存)
    private Site findSiteByKey(String requestedKey) {
        // 使用缓存
        Site cached = SITE_CACHE.get(requestedKey);
        if (cached != null) return cached;
        
        List<Site> allSites = VodConfig.get().getSites();
        Site foundSite = null;
        
        // 1. 精确匹配
        for (Site site : allSites) {
            if (site.getKey().equals(requestedKey)) {
                foundSite = site;
                break;
            }
        }
        
        if (foundSite == null) {
            // 2. 忽略大小写匹配
            for (Site site : allSites) {
                if (site.getKey().equalsIgnoreCase(requestedKey)) {
                    foundSite = site;
                    break;
                }
            }
        }
        
        if (foundSite == null) {
            // 3. 去掉前缀匹配 (csp_SP360 -> SP360, 360)
            String withoutPrefix = requestedKey.replaceFirst("^csp_", "");
            for (Site site : allSites) {
                if (site.getKey().equals(withoutPrefix) || site.getKey().equalsIgnoreCase(withoutPrefix)) {
                    foundSite = site;
                    break;
                }
            }
        }
        
        if (foundSite == null) {
            // 5. 名称匹配
            String lowerRequested = requestedKey.toLowerCase();
            for (Site site : allSites) {
                String lowerSiteName = site.getName().toLowerCase();
                if (lowerSiteName.contains(lowerRequested) || lowerRequested.contains(lowerSiteName)) {
                    foundSite = site;
                    break;
                }
            }
        }
        
        // 6. 返回空站点
        if (foundSite == null) {
            foundSite = new Site();
        }
        
        // 缓存结果
        SITE_CACHE.put(requestedKey, foundSite);
        // 限制缓存大小
        if (SITE_CACHE.size() > 100) {
            SITE_CACHE.clear();
        }
        
        return foundSite;
    }
    
    // 清空站点缓存 (配置更新时调用)
    public static void clearSiteCache() {
        SITE_CACHE.clear();
    }
    
    // 清空结果缓存 (站源切换时调用)
    private static void clearResultCache() {
        RESULT_CACHE.clear();
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        try {
            // 如果是预检请求，直接返回带 CORS 的空 JSON 响应
            if (session.getMethod() == NanoHTTPD.Method.OPTIONS) {
                NanoHTTPD.Response options = createJsonResponse(new JsonObject());
                options.addHeader("Access-Control-Allow-Origin", "*");
                options.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                options.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
                return options;
            }

            // 非预检请求，正常处理并在返回前添加 CORS 头
            NanoHTTPD.Response response = handleApiRequest(session, url, files);
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
            return response;
        } catch (Exception e) {
            NanoHTTPD.Response err = createErrorResponse(500, "内部服务器错误：" + (e == null ? "" : e.getMessage()));
            err.addHeader("Access-Control-Allow-Origin", "*");
            err.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            err.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
            return err;
        }
    }

    private NanoHTTPD.Response handleApiRequest(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) throws Exception {
        Map<String, String> params = new HashMap<>();
        
        // 从 URL 中提取路径部分（去掉查询字符串）
        if (url != null && url.contains("?")) {
            url = url.split("\\?")[0];
        }
        
        // 解析GET参数（支持 key 或 key= 或 key=value）
        String rawQuery = session.getQueryParameterString();
        if (rawQuery != null) {
            String[] pairs = rawQuery.split("&");
            for (String pair : pairs) {
                if (pair == null || pair.isEmpty()) continue;
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    params.put(URLDecoder.decode(keyValue[0], "UTF-8"), 
                              URLDecoder.decode(keyValue[1], "UTF-8"));
                } else if (keyValue.length == 1) {
                    // key without '=' -> treat as present with empty value
                    params.put(URLDecoder.decode(keyValue[0], "UTF-8"), "");
                }
            }
            // 保存原始 query 到 params 以便后续逻辑区分 key 与 key=
            params.put("__raw_query", rawQuery);
        }
        
        // 解析POST参数
        if (files.containsKey("postData")) {
            String postData = files.get("postData");
            if (postData != null && !postData.isEmpty()) {
                String[] pairs = postData.split("&");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        params.put(URLDecoder.decode(keyValue[0], "UTF-8"), 
                                  URLDecoder.decode(keyValue[1], "UTF-8"));
                    }
                }
            }
        }

        // 注入当前请求的 Host (用于生成完整的代理地址 如 http://ip:port)
        try {
            Map<String, String> sessionHeaders = session.getHeaders();
            if (sessionHeaders != null) {
                String hostHeader = sessionHeaders.get("host");
                if (!TextUtils.isEmpty(hostHeader)) {
                    // 使用 http 作为默认 scheme
                    params.put("_server", "http://" + hostHeader);
                }
            }
        } catch (Exception ignored) {}
        // 兼容 ext 参数：ext 与 f 等价，ext 可能为 base64 编码，优先解码并赋值给 f
        try {
            String ext = params.get("ext");
            if (!TextUtils.isEmpty(ext)) {
                String fvalue = ext;
                try {
                    // 尝试 base64 解码
                    byte[] decoded = java.util.Base64.getDecoder().decode(ext);
                    String decodedStr = new String(decoded, "UTF-8");
                    if (decodedStr.trim().startsWith("{")) fvalue = decodedStr;
                } catch (IllegalArgumentException ignored) {
                    // 不是 base64，直接使用 ext
                }
                // 覆盖或设置 f
                params.put("f", fvalue);
            }
        } catch (Exception ignored) {}
        
    // 将当前页注入到 ThreadLocal，便于统一响应处理
    currentPg.set(params.getOrDefault("pg", "1"));

    // 静态文件路由：/settings.html 和 /assets/*
    if ("/settings.html".equals(url) || (url != null && url.startsWith("/assets/"))) {
        NanoHTTPD.Response staticResp = serveStatic(url.equals("/settings.html") ? "/settings.html" : url);
        if (staticResp != null) return staticResp;
    }

    try {
    // 路由处理
    
    // 找到你的 603 行，把那几行替换成下面这些：
    if (url.equals("/vod/api")) {
        // 如果有 refresh 参数，先执行刷新逻辑
        if (params.containsKey("refresh")) {
            return handleRefreshSite(params);
        }
        // 如果没有 refresh 参数，正常返回采集数据
        return handleCollectorApi(params);
    }


        // 兼容采集站：伪接口 /index.php/ajax/data.html
        // 将 tid -> t, page -> pg，忽略 mid 和 limit
        if (url.equals("/index.php/ajax/data.html")) {
            // 将 tid 转为 t
            String tid = params.get("tid");
            if (!TextUtils.isEmpty(tid)) params.put("t", tid);
            // 将 page 转为 pg
            String page = params.get("page");
            // 兼容采集站中 page=0 等价于 page=1 的情况
            if (!TextUtils.isEmpty(page)) {
                if ("0".equals(page)) params.put("pg", "1");
                else params.put("pg", page);
            } else {
                params.put("pg", "1");
            }
            // 强制视为列表请求，方便兼容采集站
            params.put("ac", "list");
            // 兼容采集站：不返回 class 字段（仅本 URI 行为）
            params.put("__no_class", "1");
            // 忽略 mid 和 limit（无视或保留均可）
            return handleCollectorApi(params);
        }
        
        if (url.equals("/config/sites")) {
            return handleGetAllSites();
        }
        
        if (url.equals("/config/lives")) {
            return handleGetAllLives();
        }
        
        if (url.equals("/config/site")) {
            return handleGetSite(params);
        }
        
        if (url.equals("/config/live")) {
            return handleGetLive(params);
        }
        
        if (url.equals("/live")) {
            return handleLiveStream(params);
        }
        
        if (url.equals("/vod/sniff")) {
            return handleSniff(params);
        }

        if (url.equals("/vod/proxy")) {
            return handleProxy(session, params);
        }
        
        // 播放代理处理：/vod/play/{ID}.tvp.{ext}（如 .tvp.m3u8, .tvp.ts, .tvp.mp4 等）
        if (url.startsWith("/vod/play/")) {
            String id = url.substring("/vod/play/".length());
            // 支持新格式 .tvp.{ext} 和兼容旧格式
            if (id.contains(".tvp.")) {
                // 新格式：去掉 .tvp.{ext} 后缀，只保留 ID
                int tvpIndex = id.indexOf(".tvp.");
                if (tvpIndex > 0) {
                    id = id.substring(0, tvpIndex);
                }
            } else if (id.endsWith(".m3u8")) {
                // 兼容旧格式 .m3u8
                id = id.substring(0, id.length() - 5);
            } else if (id.endsWith(".tvp")) {
                // 临时兼容 .tvp
                id = id.substring(0, id.length() - 4);
            } else {
                // 尝试去掉任何扩展名（如 .ts, .mp4 等）
                int lastDot = id.lastIndexOf('.');
                if (lastDot > 0) {
                    id = id.substring(0, lastDot);
                }
            }
            String currentIp = session.getHeaders().getOrDefault("host", "127.0.0.1").split(":")[0];
            String portStr = session.getHeaders().getOrDefault("host", "127.0.0.1").contains(":") ?
                    session.getHeaders().get("host").split(":")[1] : "9978";
            int port = Integer.parseInt(portStr);
            return PlayProxy.handlePlayProxyRequest(id, this, currentIp, port);
        }
        
        // 动态覆盖当前 site（仅临时覆盖，不修改 APK 内置配置）
        if (url.equals("/config/settings")) {
            return handleSettings(params);
        }
        if (url.equals("/config/ws_check")) {
            return handleWsCheck(params);
        }
        if (url.equals("/config/ws_status")) {
            return handleWsStatus(params);
        }
        if (url.equals("/config/proxy_url")) {
            return handleProxyUrl(params);
        }
        
        return createErrorResponse(404, "未找到 API 接口");
        } finally {
            currentPg.remove();
        }
    }

    // 简单代理：将上游响应流式转发给客户端，保留关键响应头并支持 Range
    private NanoHTTPD.Response handleProxy(NanoHTTPD.IHTTPSession session, Map<String, String> params) {
        String id = params.get("id");
        String target = null;
        ProxyEntry storedEntry = null;
        if (!TextUtils.isEmpty(id)) {
            // 通过 id 解析原始 URL 并获取保存的 headers
            storedEntry = resolveProxyEntry(id);
            if (storedEntry == null) return createErrorResponse(404, "代理 id 未找到或已过期");
            target = storedEntry.url;
        } else {
            target = params.get("url");
            if (TextUtils.isEmpty(target)) return createErrorResponse(400, "缺少 url 或 id 参数");
        }

        try {
            // 使用 OkHttp 发起请求,保留 URL 中的所有特殊字符(包括 # $ 等)
            okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
            
            okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .url(target); // OkHttp 会保留完整 URL,包括 fragment

            // 优先使用存储的 headers（如果有），再回退到请求头中的值（存储的优先）
            Map<String, String> sessionHeaders = session.getHeaders();
            java.util.Set<String> storedLower = new java.util.HashSet<>();
            if (storedEntry != null && storedEntry.headers != null) {
                for (Map.Entry<String, String> e : storedEntry.headers.entrySet()) {
                    try {
                        requestBuilder.header(e.getKey(), e.getValue());
                        storedLower.add(e.getKey().toLowerCase());
                    } catch (Exception ignored) {}
                }
            }
            if (sessionHeaders != null) {
                String range = sessionHeaders.get("range");
                if (!TextUtils.isEmpty(range) && !storedLower.contains("range")) requestBuilder.header("Range", range);
                String ua = sessionHeaders.get("user-agent");
                if (!TextUtils.isEmpty(ua) && !storedLower.contains("user-agent")) requestBuilder.header("User-Agent", ua);
                String ref = sessionHeaders.get("referer");
                if (!TextUtils.isEmpty(ref) && !storedLower.contains("referer")) requestBuilder.header("Referer", ref);
                String cookie = sessionHeaders.get("cookie");
                if (!TextUtils.isEmpty(cookie) && !storedLower.contains("cookie")) requestBuilder.header("Cookie", cookie);
            }

            okhttp3.Response okResponse = okHttpClient.newCall(requestBuilder.build()).execute();
            
            int code = okResponse.code();
            String contentType = okResponse.header("Content-Type");
            String contentLength = okResponse.header("Content-Length");
            String acceptRanges = okResponse.header("Accept-Ranges");
            String contentRange = okResponse.header("Content-Range");

            // 强判 m3u8：根据 Content-Type、URL 特征或文件内容首行判断
            boolean isM3u8 = false;
            if (contentType != null) {
                String ct = contentType.toLowerCase();
                if (ct.contains("mpegurl") || ct.contains("application/x-mpegurl") || ct.contains("vnd.apple")) isM3u8 = true;
            }
            if (!isM3u8 && target.toLowerCase().contains(".m3u8")) isM3u8 = true;

            okhttp3.ResponseBody responseBody = okResponse.body();
            if (responseBody == null) {
                return createErrorResponse(502, "上游返回空响应，状态码=" + code);
            }
            
            java.io.InputStream rawInput = responseBody.byteStream();

            if (!isM3u8) {
                // 进一步检查文件开头是否为 M3U8 标识
                rawInput = new java.io.BufferedInputStream(rawInput);
                rawInput.mark(8192);
                byte[] head = new byte[8192];
                int read = rawInput.read(head);
                String headStr = "";
                if (read > 0) {
                    try {
                        headStr = new String(head, 0, Math.min(read, 8192), "UTF-8");
                    } catch (Exception ignored) {
                        headStr = new String(head, 0, Math.min(read, 8192));
                    }
                    if (headStr.startsWith("#EXTM3U") || headStr.contains("#EXTINF")) isM3u8 = true;
                }
                rawInput.reset();
            }

            if (isM3u8) {
                // 读取文本并重写每行引用为代理地址；为每个片段生成短 id 并保存对应 headers
                java.io.BufferedReader br = new java.io.BufferedReader(new InputStreamReader(rawInput, "UTF-8"));
                String line;
                StringBuilder outText = new StringBuilder();
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty() || line.startsWith("#")) {
                        outText.append(line).append('\n');
                        continue;
                    }
                    // 如果已经是代理路径,直接保留(避免重复代理)
                    String trimmed = line.trim();
                    if (trimmed.startsWith("/vod/play/")) {
                        outText.append(trimmed).append('\n');
                        continue;
                    }
                    // 解析相对或绝对 URL
                    try {
                        // 使用 target 作为基础 URL 来解析相对路径
                        URL baseUrl = new URL(target);
                        URL resolved = new URL(baseUrl, trimmed);
                        String segUrl = resolved.toString();
                        // 合并 headers：优先使用 storedEntry.headers，其次使用当前请求头
                        Map<String, String> segHeaders = new HashMap<>();
                        if (storedEntry != null && storedEntry.headers != null) segHeaders.putAll(storedEntry.headers);
                        if (sessionHeaders != null) {
                            String ua = sessionHeaders.get("user-agent"); if (!TextUtils.isEmpty(ua)) segHeaders.putIfAbsent("User-Agent", ua);
                            String ref = sessionHeaders.get("referer"); if (!TextUtils.isEmpty(ref)) segHeaders.putIfAbsent("Referer", ref);
                            String cookie = sessionHeaders.get("cookie"); if (!TextUtils.isEmpty(cookie)) segHeaders.putIfAbsent("Cookie", cookie);
                        }
                        String sid = storeProxyUrl(segUrl, segHeaders);
                        String proxied = "/vod/play/" + sid + ".m3u8";
                        outText.append(proxied).append('\n');
                    } catch (Exception e) {
                        outText.append(line).append('\n');
                    }
                }
                br.close();
                byte[] bytes = outText.toString().getBytes("UTF-8");
                NanoHTTPD.Response resp = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.lookup(code), contentType == null ? "application/vnd.apple.mpegurl" : contentType, new java.io.ByteArrayInputStream(bytes), bytes.length);
                if (acceptRanges != null) resp.addHeader("Accept-Ranges", acceptRanges);
                if (contentLength != null) resp.addHeader("Content-Length", String.valueOf(bytes.length));
                return resp;
            }

            // 非 m3u8，直接流式转发
            java.io.InputStream input = rawInput;
            NanoHTTPD.Response response = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.lookup(code), contentType == null ? "application/octet-stream" : contentType, input);
            if (contentLength != null) response.addHeader("Content-Length", contentLength);
            if (acceptRanges != null) response.addHeader("Accept-Ranges", acceptRanges);
            if (contentRange != null) response.addHeader("Content-Range", contentRange);
            String cacheControl = okResponse.header("Cache-Control");
            if (cacheControl != null) response.addHeader("Cache-Control", cacheControl);
            String etag = okResponse.header("ETag");
            if (etag != null) response.addHeader("ETag", etag);

            return response;
        } catch (Throwable e) {
            return createErrorResponse(500, "代理失败：" + (e == null ? "" : e.getMessage()));
        }
    }

    // 核心采集站兼容API - 完全兼容type=1采集站
    private NanoHTTPD.Response handleCollectorApi(Map<String, String> params) throws Exception {
        // 兼容：优先使用请求中的 site 参数（旧客户端），否则使用动态 override 或 APK 内置 home
        String siteKey = params.get("site");
        Site site;
        if (!TextUtils.isEmpty(siteKey)) {
            site = findSiteByKey(siteKey);
            if (site == null || site.isEmpty()) return createErrorResponse(404, "未找到站点：" + siteKey);
        } else {
            site = getActiveSite();
            if (site == null || site.isEmpty()) return createErrorResponse(400, "缺少 site 参数，且没有可用的活动站点");
        }
        
        String ac = params.get("ac");
        String wd = params.get("wd");

        // 搜索功能 - 必须同时有wd参数（site 可为空以使用默认 active site）
        if (!TextUtils.isEmpty(wd)) return handleSearch(site, params);

        // 播放链接获取 - 支持两种方式:
        // 1. ac=play (标准方式)
        // 2. 同时有 id 和 flag 参数 (简化方式,兼容直接传参)
        String flag = params.get("flag");
        String playId = params.get("id");
        if ("play".equals(ac) || (!TextUtils.isEmpty(flag) && !TextUtils.isEmpty(playId))) {
            return handlePlay(site, params);
        }

        // 列表查询 - ac=list 返回简化的视频列表
        if ("list".equals(ac)) return handleList(site, params);

        // 优先 ids 参数：如果传 ids，返回详情（可能与 t 组合）
        String ids = params.get("ids");
        String t = params.get("t");
        String f = params.get("f");

        // 如果有 ids，返回对应详情；如果同时有 t，则在该分类上下文中返回（即确保资源属于该分类）
        if (!TextUtils.isEmpty(ids)) {
            // 对于 HTTP 站点，需确保 API 可用
            if (site.getType() != 3 && TextUtils.isEmpty(site.getApi())) {
                return createErrorResponse(400, "活动站点未配置 API；请提供 site 参数或通过 /config/override_site?set=KEY 设置覆盖站点");
            }
            // 如果指定了分类 t，则尝试在该分类下查找并返回详情（如果找不到，仍然返回普通 detail）
            if (!TextUtils.isEmpty(t)) {
                try {
                    return handleDetailWithCategory(site, t, ids, params);
                } catch (Exception e) {
                    return createErrorResponse(502, "详情获取失败：" + (e == null ? "" : e.getMessage()));
                }
            }
            try {
                return handleDetail(site, ids, params);
            } catch (NullPointerException npe) {
                return createErrorResponse(502, "上游返回无效的 JSON（空对象）；请检查站点 API 或使用显式的 site 参数");
            }
        }

        // 分类列表或筛选：t 表示分类，如果存在 t 则返回该分类的列表（不返回 class 字段）
        if (!TextUtils.isEmpty(t)) {
            String pg = params.getOrDefault("pg", "1");
            // 解析全局筛选 f（可能已通过 ext 赋值）
            java.util.Map<String, String> filters = parseFilters(params.get("f"));
            return handleCategoryApi(site, t, pg, filters, params);
        }

        // 全局筛选（无 t），按 f 返回过滤后的默认列表
        if (!TextUtils.isEmpty(f)) {
            java.util.Map<String, String> filters = parseFilters(f);
            // 使用首页数据进行筛选并返回
            try {
                com.fongmi.android.tv.bean.Result home = performHome(site);
                com.fongmi.android.tv.bean.Result filtered = fetchPic(site, applyFiltersToResult(home, filters));
                JsonObject response = new JsonObject();
                JsonArray list = new JsonArray();
                for (Vod vod : filtered.getList()) list.add(vodToJson(vod));
                response.add("list", list);
                response.add("style", getStyleJson(filtered, site));
                
                // 修复分页字段逻辑
                int limit = list.size() != 20 ? list.size() : 20;  // 根据实际 list 大小调整 limit
                int pagecount = filtered.getPageCount() > 0 ? filtered.getPageCount() : 9999;
                int total = pagecount * limit;  // total = pagecount * limit
                
                response.addProperty("page", 1);
                response.addProperty("pagecount", pagecount);
                response.addProperty("limit", limit);
                response.addProperty("total", total);
                response.add("raw", siteToJson(site));
                return createJsonResponse(response);
            } catch (Exception e) {
                return createErrorResponse(500, "筛选失败：" + (e == null ? "" : e.getMessage()));
            }
        }

        // 默认返回首页（已包含分类和列表）
        // 提取用户自定义的 extend 参数
        String userExtend = params.get("extend");
        return handleHome(site, userExtend);
    }

    // 解析 f 参数（JSON），返回键值映射
    private java.util.Map<String, String> parseFilters(String f) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (TextUtils.isEmpty(f)) return map;
        try {
            com.google.gson.JsonObject obj = com.github.catvod.utils.Json.parse(f).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                map.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception ignored) {}
        return map;
    }

    // 在指定分类下获取详情（先获取分类列表并匹配 ids）
    private NanoHTTPD.Response handleDetailWithCategory(Site site, String tid, String ids, Map<String, String> params) throws Exception {
        // 获取该分类的列表
        String pg = params.getOrDefault("pg", "1");
        com.fongmi.android.tv.bean.Result cat = performCategory(site, tid, pg, new java.util.HashMap<>());
        if (cat.getList().isEmpty()) {
            // 如果该分类没有数据，则回退到普通 detail
            return handleDetail(site, ids, params);
        }
        // 请求的 ids 可能为逗号分隔的多个 id
        java.util.Set<String> idSet = new java.util.HashSet<>(Arrays.asList(ids.split(",")));
        java.util.List<Vod> matched = new java.util.ArrayList<>();
        for (Vod v : cat.getList()) if (idSet.contains(v.getVodId())) matched.add(v);
        if (matched.isEmpty()) {
            // 回退到普通 detail
            return handleDetail(site, ids, params);
        }
        // 构造结果
        JsonObject response = new JsonObject();
        JsonArray list = new JsonArray();
        for (Vod v : matched) list.add(vodToJson(v));
        response.add("list", list);
        response.add("style", getStyleJson(cat, site));
        response.add("raw", siteToJson(site));
        return createJsonResponse(response);
    }

    // 将 filters 应用于 Result，返回新的 Result（只做简单属性匹配）
    private com.fongmi.android.tv.bean.Result applyFiltersToResult(com.fongmi.android.tv.bean.Result src, java.util.Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) return src;
        com.fongmi.android.tv.bean.Result out = new com.fongmi.android.tv.bean.Result();
        for (Vod v : src.getList()) {
            boolean keep = true;
            for (Map.Entry<String, String> e : filters.entrySet()) {
                String key = e.getKey();
                String val = e.getValue();
                // 简单支持 match 于 vod 的若干字段
                String field = "";
                if ("typeName".equalsIgnoreCase(key) || "type".equalsIgnoreCase(key)) field = v.getTypeName();
                else if ("vod_actor".equalsIgnoreCase(key) || "actor".equalsIgnoreCase(key)) field = v.getVodActor();
                else if ("vod_director".equalsIgnoreCase(key) || "director".equalsIgnoreCase(key)) field = v.getVodDirector();
                else if ("vod_year".equalsIgnoreCase(key) || "year".equalsIgnoreCase(key)) field = v.getVodYear();
                else if ("vod_tag".equalsIgnoreCase(key) || "tag".equalsIgnoreCase(key)) field = v.getVodTag();
                else field = "";
                if (!TextUtils.isEmpty(field) && !field.contains(val)) { keep = false; break; }
            }
            if (keep) out.getList().add(v);
        }
        return out;
    }

    // 返回分类的 API（不包含 class 字段）
    private NanoHTTPD.Response handleCategoryApi(Site site, String t, String pg, java.util.Map<String, String> filters, Map<String, String> params) throws Exception {
        // 特殊处理：如果 t 为 site_menu，返回站源切换列表
        if ("site_menu".equals(t)) {
            return handleSiteMenuCategory(params, pg);
        }
        
        // 提取用户自定义的 extend 参数（如果有的话）
        // 这个 extend 会在 callSiteApi 中被传递给底层 API
        // 注意：这里的 filters 是用于前端筛选的，extend 是用于底层 API 的站点级配置
        String userExtend = params.get("extend");
        com.fongmi.android.tv.bean.Result result = performCategory(site, t, pg, new java.util.HashMap<>(), userExtend);
        if (!filters.isEmpty()) result = applyFiltersToResult(result, filters);
        JsonObject response = new JsonObject();
        JsonArray list = new JsonArray();
        for (Vod vod : result.getList()) list.add(vodToJson(vod));
        response.add("list", list);
        response.add("style", getStyleJson(result, site));
        
        // 修复分页字段逻辑
        int page = Integer.parseInt(pg);
        if (page == 0) page = 1;  // page=0 转换为 page=1
        int limit = list.size() != 20 ? list.size() : 20;  // 根据实际 list 大小调整 limit
        int pagecount = result.getPageCount() > 0 ? result.getPageCount() : 9999;
        int total = pagecount * limit;  // total = pagecount * limit
        
        response.addProperty("page", page);
        response.addProperty("pagecount", pagecount);
        response.addProperty("limit", limit);
        response.addProperty("total", total);
        response.add("raw", siteToJson(site));
        return createJsonResponse(response);
    }

    // 获取当前活动 site：优先使用 Prefers 中的临时覆盖，否则使用 VodConfig 的 home
    private Site getActiveSite() {
        try {
            String override = Prefers.getString("api_override_site");
            if (!TextUtils.isEmpty(override)) {
                Site s = VodConfig.get().getSite(override);
                if (!s.isEmpty()) return s;
            }
        } catch (Exception ignored) {}
        // 尝试使用 VodConfig 的 home（正常情况）
        Site home = VodConfig.get().getHome();
        if (home != null && !home.isEmpty()) return home;

        // 如果 VodConfig 还没加载好，退而求其次：从持久化的 Config.vod() 中读取 home key 并解析为 Site
        try {
            String homeKey = com.fongmi.android.tv.bean.Config.vod().getHome();
            if (!TextUtils.isEmpty(homeKey)) {
                Site s = VodConfig.get().getSite(homeKey);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Exception ignored) {}

        // 最后回退到 sites 列表中的第一个
        List<Site> sites = VodConfig.get().getSites();
        if (sites != null && !sites.isEmpty()) return sites.get(0);
        return new Site();
    }

    // 新的配置接口：/config/settings - 严格按用户要求实现
    // 0: 默认返回当前 site、raw、style、filters 状态
    // 2: ?site= 清空覆盖
    // 3: ?site=xxx 设置覆盖（临时，不持久化）
    // 4: ?raw=0/1 控制 raw 字段输出（持久化）
    // 5: ?style=0/1 控制 style 字段输出（持久化）
    // 6: ?filters=0/1 控制 filters 字段输出（持久化）
    private NanoHTTPD.Response handleSettings(Map<String, String> params) {
        JsonObject json = new JsonObject();
        
        // 统一的切换参数处理函数（用于 raw、style、filters 等可扩展的 0/1 切换参数）
        // 返回 null 表示该参数不存在，否则返回响应对象
        java.util.function.Function<String, NanoHTTPD.Response> handleToggleParam = paramName -> {
            String value = params.get(paramName);
            if (value == null) return null; // 参数不存在
            
            String normalized = value.equals("1") ? "1" : "0";
            Prefers.put("api_settings_" + paramName, normalized);
            
            JsonObject resp = new JsonObject();
            resp.addProperty("code", 1);
            String msgFieldName;
            if ("raw".equals(paramName)) {
                msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + "raw字段的输出";
            } else if ("style".equals(paramName)) {
                msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + "style字段的输出";
            } else if ("filters".equals(paramName)) {
                msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + "filters字段的输出";
            } else if ("playProxy".equalsIgnoreCase(paramName)) {
                msgFieldName = "已" + (normalized.equals("1") ? "启用" : "停用") + "全局播放流量转发";
            } else if ("rewritePlayUrl".equals(paramName)) {
                msgFieldName = "已" + (normalized.equals("1") ? "启用" : "停用") + "详情播放地址重写";
            } else if ("siteMenu".equals(paramName)) {
                msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + "站源切换分类";
            } else {
                msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + paramName + "字段的输出";
            }
            resp.addProperty("msg", msgFieldName);
            
            try {
                String jsonStr = gson.toJson(resp);
                byte[] bytes = jsonStr.getBytes("UTF-8");
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", new java.io.ByteArrayInputStream(bytes), bytes.length);
            } catch (java.io.UnsupportedEncodingException e) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "");
            }
        };
        
        // 批量处理：开关、文本、WS 与 site 参数统一在一次提交中保存，并在最后一次性返回合并消息
        java.util.List<String> toggleKeys = java.util.Arrays.asList("raw", "style", "filters", "playProxy", "playproxy", "rewritePlayUrl", "siteMenu");
        java.util.List<String> allMsgs = new java.util.ArrayList<>();

        // 处理所有开关项
        for (String key : toggleKeys) {
            if (!params.containsKey(key)) continue;
            String value = params.get(key);
            String normalized = "1".equals(value) ? "1" : "0";
            String storeKey = key.equals("playproxy") ? "playProxy" : key;
            Prefers.put("api_settings_" + storeKey, normalized);
            String msgFieldName;
            if ("raw".equals(storeKey)) msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + "raw字段的输出";
            else if ("style".equals(storeKey)) msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + "style字段的输出";
            else if ("filters".equals(storeKey)) msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + "filters字段的输出";
            else if ("playProxy".equalsIgnoreCase(storeKey)) msgFieldName = "已" + (normalized.equals("1") ? "启用" : "停用") + "全局播放流量转发";
            else if ("rewritePlayUrl".equals(storeKey)) msgFieldName = "已" + (normalized.equals("1") ? "启用" : "停用") + "详情播放地址重写";
            else if ("siteMenu".equals(storeKey)) msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + "站源切换分类";
            else msgFieldName = "已" + (normalized.equals("1") ? "开启" : "关闭") + storeKey + "字段的输出";
            allMsgs.add(msgFieldName);
            if ("siteMenu".equals(storeKey)) clearResultCache();
        }

        // 批量处理文本类配置
        java.util.List<String> textKeys = java.util.Arrays.asList("sniffKeywords", "sniffHeaders", "sniffJs");
        for (String k : textKeys) {
            if (!params.containsKey(k)) continue;
            String v = params.get(k);
            Prefers.put("api_settings_" + k, v == null ? "" : v);
            if ("sniffKeywords".equals(k)) allMsgs.add("已设置嗅探关键字");
            else if ("sniffHeaders".equals(k)) allMsgs.add("已设置自定义请求头");
            else if ("sniffJs".equals(k)) allMsgs.add("已设置执行JS");
        }

        // WS 配置
        boolean wsUrlChanged = false;
        boolean wsAutoChanged = false;
        String newWsUrl = null;
        String newWsAuto = null;
        if (params.containsKey("wsUrl")) {
            newWsUrl = params.get("wsUrl") == null ? "" : params.get("wsUrl");
            Prefers.put("api_ws_url", newWsUrl);
            allMsgs.add("已设置 WS 地址");
            wsUrlChanged = true;
        }
        if (params.containsKey("wsAutoConnect")) {
            newWsAuto = "1".equals(params.get("wsAutoConnect")) ? "1" : "0";
            Prefers.put("api_ws_autoconnect", newWsAuto);
            allMsgs.add(newWsAuto.equals("1") ? "已启用自动连接WS" : "已禁用自动连接WS");
            wsAutoChanged = true;
        }

        // 根据最新配置动态启动或停止 wsClient
        try {
            if (wsUrlChanged || wsAutoChanged) {
                String effectiveUrl = newWsUrl != null ? newWsUrl : Prefers.getString("api_ws_url", "");
                String effectiveAuto = newWsAuto != null ? newWsAuto : Prefers.getString("api_ws_autoconnect", "0");
                if (!TextUtils.isEmpty(effectiveUrl) && "1".equals(effectiveAuto)) {
                    startWs(effectiveUrl);
                } else {
                    stopWs();
                }
            }
        } catch (Exception ignored) {}

        // site 参数处理（统一在批量中处理，不立即返回）
        boolean hasSiteParam = params.containsKey("site");
        if (hasSiteParam) {
            String siteParam = params.get("site"); // null if absent, "" if site=, "xxx" if site=xxx
            if (siteParam == null || siteParam.isEmpty()) {
                // 清空覆盖，恢复默认
                Prefers.put("api_override_site", "");
                Site newActiveSite = getActiveSite();
                String newSiteKey = newActiveSite.getKey();
                if (!newSiteKey.equals(CURRENT_SITE_KEY)) { clearResultCache(); CURRENT_SITE_KEY = newSiteKey; }
                String def = newActiveSite.getName();
                if (TextUtils.isEmpty(def)) def = newSiteKey;
                allMsgs.add("已恢复壳子默认数据源 【" + def + "】");
            } else {
                Site s = VodConfig.get().getSite(siteParam);
                String name = (s != null && !s.isEmpty()) ? s.getName() : siteParam;
                Prefers.put("api_override_site", siteParam);
                if (!siteParam.equals(CURRENT_SITE_KEY)) { clearResultCache(); CURRENT_SITE_KEY = siteParam; }
                allMsgs.add("已设置数据源为【" + name + "】");
            }
        }

        // 如果有任意变更，统一返回合并消息
        if (!allMsgs.isEmpty()) {
            JsonObject resp = new JsonObject();
            resp.addProperty("code", 1);
            resp.addProperty("msg", String.join("; ", allMsgs));
            try {
                String jsonStr = gson.toJson(resp);
                byte[] bytes = jsonStr.getBytes("UTF-8");
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", new java.io.ByteArrayInputStream(bytes), bytes.length);
            } catch (java.io.UnsupportedEncodingException e) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "");
            }
        }
        
        // 规则 0: 默认访问（无参数）-> 返回当前 site、raw、style、filters、playProxy、rewritePlayUrl、siteMenu 状态
        String currentSite = Prefers.getString("api_override_site", getActiveSite().getKey());
        boolean rawEnabled = "1".equals(Prefers.getString("api_settings_raw", "1"));
        boolean styleEnabled = "1".equals(Prefers.getString("api_settings_style", "0"));
        boolean filtersEnabled = "1".equals(Prefers.getString("api_settings_filters", "1"));
        boolean playProxyEnabled = "1".equals(Prefers.getString("api_settings_playProxy", "0"));
        boolean rewritePlayUrlEnabled = "1".equals(Prefers.getString("api_settings_rewritePlayUrl", "0"));
        boolean siteMenuEnabled = "1".equals(Prefers.getString("api_settings_siteMenu", "0"));
        String sniffKeywordsVal = Prefers.getString("api_settings_sniffKeywords", "");
        String sniffHeadersVal = Prefers.getString("api_settings_sniffHeaders", "");
        String sniffJsVal = Prefers.getString("api_settings_sniffJs", "");
    json.addProperty("site", currentSite);
        json.addProperty("raw", rawEnabled);
        json.addProperty("style", styleEnabled);
        json.addProperty("filters", filtersEnabled);
        json.addProperty("playProxy", playProxyEnabled);
        json.addProperty("rewritePlayUrl", rewritePlayUrlEnabled);
        json.addProperty("siteMenu", siteMenuEnabled);
        json.addProperty("sniffKeywords", sniffKeywordsVal);
        json.addProperty("sniffHeaders", sniffHeadersVal);
        json.addProperty("sniffJs", sniffJsVal);
    // WS 配置
    String wsUrlVal = Prefers.getString("api_ws_url", "");
    String wsAutoVal = Prefers.getString("api_ws_autoconnect", "0");
    json.addProperty("wsUrl", wsUrlVal);
    json.addProperty("wsAutoConnect", "1".equals(wsAutoVal));
        json.addProperty("code", 1);
        json.addProperty("msg", "数据列表");
        // 规则 0 返回完整状态，不经过 createJsonResponse（避免字段被移除）
        try {
            String jsonStr = gson.toJson(json);
            byte[] bytes = jsonStr.getBytes("UTF-8");
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (UnsupportedEncodingException e) {
            return createErrorResponse(500, "编码错误");
        }
    }

    // 列表查询处理 (ac=list) - 返回简化的视频列表
    private NanoHTTPD.Response handleList(Site site, Map<String, String> params) throws Exception {
        String t = params.get("t");
        String pg = params.getOrDefault("pg", "1");
        
        try {
            // 特殊处理：如果 t 为 site_menu，返回站源切换列表
            if ("site_menu".equals(t)) {
                return handleSiteMenuCategory(params, pg);
            }
            
            com.fongmi.android.tv.bean.Result result;
            
            // 如果指定了分类 t，则返回该分类的列表
            if (!TextUtils.isEmpty(t)) {
                result = performCategory(site, t, pg, new java.util.HashMap<>());
            } else {
                // 否则返回首页列表
                result = performHome(site);
            }
            
            JsonObject response = new JsonObject();
            JsonArray list = new JsonArray();
            // 添加 class 字段（兼容要求）：一般返回站点首页的分类信息
            // 但如果请求来自兼容入口 /index.php/ajax/data.html，会设置 __no_class=1，表示不要返回 class
            if (!"1".equals(params.get("__no_class"))) {
                JsonArray classes = new JsonArray();
                try {
                    // 如果启用了站源菜单，添加"站源切换"分类到开头
                    boolean siteMenuEnabled = "1".equals(Prefers.getString("api_settings_siteMenu", "0"));
                    if (siteMenuEnabled) {
                        JsonObject siteMenuType = new JsonObject();
                        siteMenuType.addProperty("type_id", "site_menu");
                        siteMenuType.addProperty("type_name", "站源切换");
                        classes.add(siteMenuType);
                    }
                    
                    com.fongmi.android.tv.bean.Result home = performHome(site);
                    for (com.fongmi.android.tv.bean.Class clazz : home.getTypes()) {
                        JsonObject typeJson = new JsonObject();
                        typeJson.addProperty("type_id", clazz.getTypeId());
                        typeJson.addProperty("type_name", clazz.getTypeName());
                        classes.add(typeJson);
                    }
                } catch (Exception ignored) {}
                response.add("class", classes);
            }
            
            // 转换视频列表，只包含指定字段
            for (Vod vod : result.getList()) {
                JsonObject vodJson = new JsonObject();
                vodJson.addProperty("vod_id", vod.getVodId());
                vodJson.addProperty("vod_name", vod.getVodName());
                vodJson.addProperty("vod_pic", vod.getVodPic());
                vodJson.addProperty("type_id", vod.getVodId());
                vodJson.addProperty("type_name", vod.getTypeName());
                vodJson.addProperty("vod_time", vod.getVodYear());
                vodJson.addProperty("vod_remarks", vod.getVodRemarks());
                vodJson.addProperty("vod_play_from", vod.getVodPlayFrom());
                list.add(vodJson);
            }
            
            response.add("list", list);
            
            // 当 page=0 时，返回的数据应该 page=1
            int page = Integer.parseInt(pg);
            if (page == 0) page = 1;
            
            // 如果 list 成员数不是 20，limit 需要同步修改为 list.count
            int limit = list.size() != 20 ? list.size() : 20;
            int pagecount = result.getPageCount() > 0 ? result.getPageCount() : 9999;
            int total = pagecount * limit;
            
            response.addProperty("page", page);
            response.addProperty("pagecount", pagecount);
            response.addProperty("limit", limit);
            response.addProperty("total", total);
            
            return createJsonResponse(response);
        } catch (Exception e) {
            return createErrorResponse(500, "列表查询失败：" + (e == null ? "" : e.getMessage()));
        }
    }

        // 播放链接处理 (ac=play)
        private NanoHTTPD.Response handlePlay(Site site, Map<String, String> params) {
        String flag = params.get("flag");
        // 兼容 type=1 和 type=4：type=1 使用 id 参数，type=4 使用 play 参数
        // 优先使用 play 参数（如果存在），否则使用 id 参数
        String id = params.get("play");
        if (TextUtils.isEmpty(id)) {
            id = params.get("id");
        }
        String originalSiteKey = params.get("site");
        
        // 检查是否从 PlayProxy 调用 (避免重复代理)
        boolean fromPlayProxy = "1".equals(params.get("_from_play_proxy"));
        
        // 判断是否应该代理 (proxy参数权限最高,如果没传则使用全局设置)
        // 但如果是从 PlayProxy 调用的,则跳过代理(避免重复代理)
        boolean shouldProxy = !fromPlayProxy && shouldUseProxy(params);
        
            try {
                // 参数验证
                if (TextUtils.isEmpty(id)) {
                    return createErrorResponse(400, "缺少播放ID参数（id 或 play）");
                }
                if (TextUtils.isEmpty(flag)) {
                    return createErrorResponse(400, "缺少播放线路参数（flag）");
                }
                
                Result result;
                if (site.getType() == 3) {
                    Spider spider = site.recent().spider();
                    // 安全获取 flags，避免空列表异常
                    List<String> flags = VodConfig.get().getFlags();
                    if (flags == null) flags = new ArrayList<>();
                    String playerContent = spider.playerContent(flag, id, flags);
                    result = Result.fromJson(playerContent);
                    if (result.getFlag().isEmpty()) result.setFlag(flag);
                    result.setUrl(Source.get().fetch(result));
                    result.setHeader(site.getHeader());
                    result.setKey(site.getKey());
                } else if (site.getType() == 4) {
                    java.util.Map<String, String> p = new java.util.HashMap<>();
                    p.put("play", id);
                    p.put("flag", flag);
                    String playerContent = callSiteApi(site, p);
                    result = Result.fromJson(playerContent);
                    if (result.getFlag().isEmpty()) result.setFlag(flag);
                    result.setUrl(Source.get().fetch(result));
                    result.setHeader(site.getHeader());
                    result.setKey(site.getKey());
                } else if (site.isEmpty() && "push_agent".equals(originalSiteKey)) {
                    result = new Result();
                    result.setParse(0);
                    result.setFlag(flag);
                    result.setUrl(Url.create().add(id));
                    result.setUrl(Source.get().fetch(result));
                } else {
                    Url url = Url.create().add(id);
                    result = new Result();
                    result.setUrl(url);
                    result.setFlag(flag);
                    result.setHeader(site.getHeader());
                    result.setPlayUrl(site.getPlayUrl());
                    result.setParse(Sniffer.isVideoFormat(url.v()) && result.getPlayUrl().isEmpty() ? 0 : 1);
                    result.setUrl(Source.get().fetch(result));
                }

                JsonObject out = new JsonObject();
                // 默认可播放地址使用 result.url
                String defaultUrl = result.getUrl() == null ? "" : result.getUrl().v();
                String playableUrl = defaultUrl;
                out.addProperty("flag", result.getFlag());
                out.addProperty("parse", result.getParse());
                out.addProperty("playUrl", "");

                // 如果请求带有 parse=1 参数，则使用 WebView 嗅探真实播放地址
                String parseParam = params.get("parse");
                if ("1".equals(parseParam) && result.getUrl() != null) {
                    try {
                        JsonObject sniffed = performWebViewSniffJson(result.getUrl().v(), params);
                        String real = sniffed.has("realurl") ? sniffed.get("realurl").getAsString() : 
                                     (sniffed.has("url") ? sniffed.get("url").getAsString() : "");
                        String sniffedUrl = sniffed.has("url") ? sniffed.get("url").getAsString() : "";
                        
                        if (!TextUtils.isEmpty(real)) {
                            // 将真实地址放入 playUrl 字段
                            out.addProperty("playUrl", real);
                            playableUrl = sniffedUrl; // 使用嗅探返回的url(可能已经是/vod/play/格式)
                        }
                        if (sniffed.has("headers")) out.add("headers", sniffed.getAsJsonObject("headers"));
                    } catch (Exception ignored) {
                    }
                }
                // 若未使用 parse 或嗅探失败，但需要代理，则为原始 url 提供代理路径
                if (!"1".equals(parseParam) && shouldProxy && result.getUrl() != null) {
                    try {
                        Map<String, String> proxyHeaders = new HashMap<>();
                        if (result.getHeaders() != null) proxyHeaders.putAll(result.getHeaders());
                        // 生成 /vod/play/ID.m3u8 格式的地址
                        String playId = PlayProxy.storePlayUrl(flag, "1", result.getUrl().v(), proxyHeaders);
                        String serverBase = params.getOrDefault("_server", "");
                        String playPath = serverBase.isEmpty() ? ("/vod/play/" + playId + ".m3u8") : (serverBase + "/vod/play/" + playId + ".m3u8");
                        out.addProperty("proxy", playPath);
                        // 把 playableUrl 指向代理地址
                        playableUrl = playPath;
                    } catch (Exception ignored) {}
                }
                if (result.getHeader() != null) {
                    JsonObject headerObj = new JsonObject();
                    for (Map.Entry<String, String> entry : result.getHeaders().entrySet()) {
                        headerObj.addProperty(entry.getKey(), entry.getValue());
                    }
                    out.add("headers", headerObj);
                }

                // 最终确保 url 字段始终返回可播放地址（嗅探优先 -> 代理优先 -> 原始）
                out.addProperty("url", playableUrl == null ? "" : playableUrl);
                //out.add("raw", siteToJson(site));
                
                NanoHTTPD.Response response = createJsonResponse(out);
                addNoCacheHeaders(response); // 播放接口禁用缓存
                return response;
            } catch (Exception e) {
                return createErrorResponse(500, "播放失败：" + (e == null ? "" : e.getMessage()));
            }
        }
    
    // 搜索处理
    private NanoHTTPD.Response handleSearch(Site site, Map<String, String> params) throws Exception {
        String wd = params.get("wd");
        boolean quick = "true".equals(params.get("quick"));
        
        try {
            // 提取用户自定义的 extend 参数
            String userExtend = params.get("extend");
            Result result = TextUtils.isEmpty(userExtend) ? performSearch(site, wd, quick) : performSearch(site, wd, quick, userExtend);
            
            JsonObject response = new JsonObject();
            JsonArray list = new JsonArray();
            
            // 转换搜索结果
            for (Vod vod : result.getList()) {
                JsonObject vodJson = vodToJson(vod);
                list.add(vodJson);
            }
            
            // 获取样式信息
            JsonObject style = getStyleJson(result, site);
            
            response.add("list", list);
            response.add("style", style);
            
            // 修复分页字段逻辑
            int limit = list.size() != 20 ? list.size() : 20;  // 根据实际 list 大小调整 limit
            int pagecount = result.getPageCount() > 0 ? result.getPageCount() : 9999;
            int total = pagecount * limit;  // total = pagecount * limit
            
            response.addProperty("page", 1);
            response.addProperty("pagecount", pagecount);
            response.addProperty("limit", limit);
            response.addProperty("total", total);
            response.add("raw", siteToJson(site));
            
            return createJsonResponse(response);
        } catch (Throwable e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "搜索失败：" + (e == null ? "" : e.getMessage()));
            error.add("raw", siteToJson(site));
            return createJsonResponse(error);
        }
    }
    
    // 站源切换分类处理：返回所有站源的列表（伪装成视频列表）
    private NanoHTTPD.Response handleSiteMenuCategory(Map<String, String> params, String pg) {
        try {
            JsonObject response = new JsonObject();
            JsonArray list = new JsonArray();
            
            List<Site> allSites = VodConfig.get().getSites();
            int index = 1;
            for (Site s : allSites) {
                if (s.isEmpty() || TextUtils.isEmpty(s.getKey())) continue;
                
                JsonObject vodJson = new JsonObject();
                vodJson.addProperty("vod_id", "site_" + s.getKey());
                vodJson.addProperty("vod_name", s.getName());
                vodJson.addProperty("vod_pic", "https://dummyimage.com/450x600/f4e4c1/333333.png&text=" + index);
                vodJson.addProperty("vod_remarks", s.getKey());
                vodJson.addProperty("type_name", "站源切换");
                list.add(vodJson);
                index++;
            }
            
            response.add("list", list);
            
            int page = Integer.parseInt(pg);
            if (page == 0) page = 1;
            int limit = list.size();
            int pagecount = 1; // 所有站源一页显示
            int total = list.size();
            
            response.addProperty("page", page);
            response.addProperty("pagecount", pagecount);
            response.addProperty("limit", limit);
            response.addProperty("total", total);
            
            NanoHTTPD.Response resp = createJsonResponse(response);
            addNoCacheHeaders(resp);
            return resp;
        } catch (Exception e) {
            return createErrorResponse(500, "站源列表获取失败：" + (e == null ? "" : e.getMessage()));
        }
    }
    
    // 站源切换详情处理：执行站源切换并返回结果
    private NanoHTTPD.Response handleSiteMenuDetail(String siteId) {
        try {
            // 提取真实的 site key (去掉 site_ 前缀)
            String siteKey = siteId.startsWith("site_") ? siteId.substring(5) : siteId;
            
            Site s = VodConfig.get().getSite(siteKey);
            String siteName = (s != null && !s.isEmpty()) ? s.getName() : siteKey;
            
            // 设置站源覆盖
            Prefers.put("api_override_site", siteKey);
            
            // 检测站源变化，清除缓存
            if (!siteKey.equals(CURRENT_SITE_KEY)) {
                clearResultCache();
                CURRENT_SITE_KEY = siteKey;
            }
            
            // 返回详情格式（伪装成视频详情）
            JsonObject response = new JsonObject();
            JsonArray list = new JsonArray();
            
            JsonObject vodJson = new JsonObject();
            vodJson.addProperty("vod_id", siteId);
            vodJson.addProperty("vod_name", "切换成功");
            vodJson.addProperty("vod_pic", "https://dummyimage.com/450x600/f4e4c1/333333.png&text=0");
            vodJson.addProperty("vod_content", "已切换到站源：" + siteName);
            vodJson.addProperty("vod_play_from", "提示");
            vodJson.addProperty("vod_play_url", "提示$https://vod.example.com/demo.mp4");
            
            list.add(vodJson);
            response.add("list", list);
            
            NanoHTTPD.Response resp = createJsonResponse(response);
            addNoCacheHeaders(resp);
            return resp;
        } catch (Exception e) {
            return createErrorResponse(500, "站源切换失败：" + (e == null ? "" : e.getMessage()));
        }
    }
    
    // 详情处理
    private NanoHTTPD.Response handleDetail(Site site, String ids, Map<String, String> params) throws Exception {
        // 特殊处理：如果 ids 以 site_ 开头，说明是站源切换请求
        if (ids.startsWith("site_")) {
            return handleSiteMenuDetail(ids);
        }
        
        try {
            // 提取用户自定义的 extend 参数
            String userExtend = params.get("extend");
            Result result = TextUtils.isEmpty(userExtend) ? performDetail(site, ids) : performDetail(site, ids, userExtend);
            
            JsonObject response = new JsonObject();
            JsonArray list = new JsonArray();
            
            // 检查是否启用播放地址重写
            boolean rewritePlayUrlEnabled = "1".equals(Prefers.getString("api_settings_rewritePlayUrl", "0"));
            
            // 从 _server 获取当前服务器地址
            String serverAddr = params.getOrDefault("_server", "http://127.0.0.1:9978");
            String[] parts = serverAddr.replace("http://", "").replace("https://", "").split(":");
            String currentIp = parts.length > 0 ? parts[0] : "127.0.0.1";
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9978;
            
            // 转换详情结果
            for (Vod vod : result.getList()) {
                JsonObject vodJson = vodToJson(vod);
                
                // 如果启用播放地址重写，处理 vod_play_url
                if (rewritePlayUrlEnabled && vodJson.has("vod_play_url")) {
                    String originalPlayFrom = vodJson.has("vod_play_from") ? vodJson.get("vod_play_from").getAsString() : "";
                    String originalPlayUrl = vodJson.get("vod_play_url").getAsString();
                    String processedUrl = PlayProxy.processVodPlayUrl(originalPlayFrom, originalPlayUrl, 
                                                                      currentIp, port);
                    vodJson.addProperty("vod_play_url", processedUrl);
                }
                
                list.add(vodJson);
            }
            
            // 获取样式信息
            JsonObject style = getStyleJson(result, site);
            
            response.add("list", list);
            response.add("style", style);
            response.add("raw", siteToJson(site));
            
            return createJsonResponse(response);
        } catch (Exception e) {
            String msg;
            try {
                String em = e == null ? "" : e.getMessage();
                if (e instanceof NullPointerException || (em != null && em.contains("JsonObject.get"))) {
                    String siteName = (site == null || site.getName() == null) ? site.getKey() : site.getName();
                        msg = siteName + " 站点没有相关分类或资源";
                } else {
                    msg = "详情获取失败：" + (e == null ? "未知错误" : e.getMessage());
                }
            } catch (Exception ex) {
                msg = "Detail failed";
            }
            JsonObject error = new JsonObject();
            error.addProperty("error", msg);
            error.add("raw", siteToJson(site));
            return createJsonResponse(error);
        }
    }
    
    // 分类处理
    private NanoHTTPD.Response handleCategory(Site site, String t, String pg, Map<String, String> params) throws Exception {
        String f = params.get("f");
        HashMap<String, String> extend = new HashMap<>();
        
        if (!TextUtils.isEmpty(f)) {
            try {
                // 解析筛选参数
                extend = gson.fromJson(f, HashMap.class);
            } catch (Exception e) {
                // 忽略JSON解析错误
            }
        }
        
        try {
            Result result = performCategory(site, t, pg, extend);
            
            JsonObject response = new JsonObject();
            JsonArray types = new JsonArray();
            JsonArray list = new JsonArray();
            JsonObject filters = new JsonObject();
            
            // 转换分类列表
            for (com.fongmi.android.tv.bean.Class clazz : result.getTypes()) {
                JsonObject typeJson = new JsonObject();
                typeJson.addProperty("type_id", clazz.getTypeId());
                typeJson.addProperty("type_name", clazz.getTypeName());
                types.add(typeJson);
            }
            
            // 转换视频列表
            for (Vod vod : result.getList()) {
                JsonObject vodJson = vodToJson(vod);
                list.add(vodJson);
            }
            
            // 转换筛选器 - 从Result的filters字段和types的filters字段获取
            // 首先从Result级别的filters获取
            for (Map.Entry<String, List<com.fongmi.android.tv.bean.Filter>> entry : result.getFilters().entrySet()) {
                JsonArray filterArray = new JsonArray();
                for (com.fongmi.android.tv.bean.Filter filter : entry.getValue()) {
                    JsonObject filterJson = filterToJson(filter);
                    filterArray.add(filterJson);
                }
                filters.add(entry.getKey(), filterArray);
            }
            
            // 然后从每个分类的filters获取
            for (com.fongmi.android.tv.bean.Class clazz : result.getTypes()) {
                if (!clazz.getFilters().isEmpty()) {
                    JsonArray filterArray = new JsonArray();
                    for (com.fongmi.android.tv.bean.Filter filter : clazz.getFilters()) {
                        JsonObject filterJson = filterToJson(filter);
                        filterArray.add(filterJson);
                    }
                    // 如果该分类ID还没有筛选器，则添加
                    if (!filters.has(clazz.getTypeId())) {
                        filters.add(clazz.getTypeId(), filterArray);
                    }
                }
            }
            
            // 获取样式信息
            JsonObject style = getStyleJson(result, site);
            
            response.add("class", types);
            response.add("list", list);
            response.add("filters", filters);
            response.add("style", style);
            int limit = 20;
            int pagecount = result.getPageCount() > 0 ? result.getPageCount() : 9999;
            int total = pagecount * limit;
            response.addProperty("page", 1);
            response.addProperty("pagecount", pagecount);
            response.addProperty("limit", limit);
            response.addProperty("total", total);
            response.add("raw", siteToJson(site));
            
            return createJsonResponse(response);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "分类获取失败：" + (e == null ? "" : e.getMessage()));
            error.add("raw", siteToJson(site));
            return createJsonResponse(error);
        }
    }
    
    // 首页处理
    private NanoHTTPD.Response handleHome(Site site) throws Exception {
        return handleHome(site, null);
    }
    
    // 重载版本：支持用户自定义 extend
    private NanoHTTPD.Response handleHome(Site site, String userExtend) throws Exception {
        try {
            Result result = TextUtils.isEmpty(userExtend) ? performHome(site) : performHome(site, userExtend);
            
            JsonObject response = new JsonObject();
            JsonArray types = new JsonArray();
            JsonArray list = new JsonArray();
            JsonObject filters = new JsonObject();
            
            // 如果启用了站源菜单，添加"站源切换"分类到开头
            boolean siteMenuEnabled = "1".equals(Prefers.getString("api_settings_siteMenu", "0"));
            if (siteMenuEnabled) {
                JsonObject siteMenuType = new JsonObject();
                siteMenuType.addProperty("type_id", "site_menu");
                siteMenuType.addProperty("type_name", "站源切换");
                types.add(siteMenuType);
            }
            
            // 转换分类列表
            for (com.fongmi.android.tv.bean.Class clazz : result.getTypes()) {
                JsonObject typeJson = new JsonObject();
                typeJson.addProperty("type_id", clazz.getTypeId());
                typeJson.addProperty("type_name", clazz.getTypeName());
                types.add(typeJson);
            }
            
            // 转换视频列表
            for (Vod vod : result.getList()) {
                JsonObject vodJson = vodToJson(vod);
                list.add(vodJson);
            }
            
            // 转换筛选器 - 从Result的filters字段和types的filters字段获取
            // 首先从Result级别的filters获取
            for (Map.Entry<String, List<com.fongmi.android.tv.bean.Filter>> entry : result.getFilters().entrySet()) {
                JsonArray filterArray = new JsonArray();
                for (com.fongmi.android.tv.bean.Filter filter : entry.getValue()) {
                    JsonObject filterJson = filterToJson(filter);
                    filterArray.add(filterJson);
                }
                filters.add(entry.getKey(), filterArray);
            }
            
            // 然后从每个分类的filters获取
            for (com.fongmi.android.tv.bean.Class clazz : result.getTypes()) {
                if (!clazz.getFilters().isEmpty()) {
                    JsonArray filterArray = new JsonArray();
                    for (com.fongmi.android.tv.bean.Filter filter : clazz.getFilters()) {
                        JsonObject filterJson = filterToJson(filter);
                        filterArray.add(filterJson);
                    }
                    // 如果该分类ID还没有筛选器，则添加
                    if (!filters.has(clazz.getTypeId())) {
                        filters.add(clazz.getTypeId(), filterArray);
                    }
                }
            }
            
            // 获取样式信息
            JsonObject style = getStyleJson(result, site);
            
            response.add("class", types);
            response.add("list", list);
            response.add("filters", filters);
            response.add("style", style);
            response.add("raw", siteToJson(site));
            
            // 修复分页字段逻辑
            int limit = list.size() != 20 ? list.size() : 20;  // 根据实际 list 大小调整 limit
            int pagecount = result.getPageCount() > 0 ? result.getPageCount() : 9999;
            int total = pagecount * limit;  // total = pagecount * limit
            
            response.addProperty("page", 1);
            response.addProperty("pagecount", pagecount);
            response.addProperty("limit", limit);
            response.addProperty("total", total);
            
            return createJsonResponse(response);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "首页获取失败：" + (e == null ? "" : e.getMessage()));
            error.add("raw", siteToJson(site));
            return createJsonResponse(error);
        }
    }
    
    // 配置管理接口
    private NanoHTTPD.Response handleGetAllSites() {
        JsonArray sites = new JsonArray();
        
        List<Site> allSites = VodConfig.get().getSites();
        for (Site site : allSites) {
            sites.add(siteToJson(site));
        }
        
        JsonObject result = new JsonObject();
        result.add("sites", sites);
        result.addProperty("total", sites.size());
        return createJsonResponse(result);
    }
    
    private NanoHTTPD.Response handleGetAllLives() {
        JsonArray lives = new JsonArray();
        
        for (Live live : LiveConfig.get().getLives()) {
            lives.add(liveToJson(live));
        }
        
        JsonObject result = new JsonObject();
        result.add("lives", lives);
        result.addProperty("total", lives.size());
        return createJsonResponse(result);
    }
    
    private NanoHTTPD.Response handleGetSite(Map<String, String> params) {
        String key = params.get("key");
        if (TextUtils.isEmpty(key)) {
            return createErrorResponse(400, "缺少 key 参数");
        }
        
        Site site = findSiteByKey(key);
        if (site.isEmpty()) {
            return createErrorResponse(404, "未找到站点：" + key);
        }
        
        return createJsonResponse(siteToJson(site));
    }
    
    private NanoHTTPD.Response handleGetLive(Map<String, String> params) {
        String name = params.get("name");
        if (TextUtils.isEmpty(name)) {
            return createErrorResponse(400, "缺少 name 参数");
        }
        
        Live live = findLiveByName(name);
        if (live.isEmpty()) {
            return createErrorResponse(404, "未找到 Live：" + name);
        }
        
        return createJsonResponse(liveToJson(live));
    }
    
    // 直播流处理
    private NanoHTTPD.Response handleLiveStream(Map<String, String> params) throws Exception {
        String name = params.get("name");
        if (TextUtils.isEmpty(name)) {
            return createErrorResponse(400, "缺少 name 参数");
        }
        
        Live live = findLiveByName(name);
        if (live.isEmpty()) {
            return createErrorResponse(404, "未找到 Live：" + name);
        }
        
        try {
            // 获取直播流内容
            String content = live.getUrl(); // 这里可能需要实际去获取URL内容
            
            JsonObject result = new JsonObject();
            result.addProperty("content", content);
            result.add("raw", liveToJson(live));
            
            return createJsonResponse(result);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "直播流失败：" + (e == null ? "" : e.getMessage()));
            error.add("raw", liveToJson(live));
            return createJsonResponse(error);
        }
    }
    
    // 视频嗅探处理 - 使用WebView进行智能嗅探
    private NanoHTTPD.Response handleSniff(Map<String, String> params) throws Exception {
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) {
            return createErrorResponse(400, "缺少 url 参数");
        }
        
        try {
            url = URLDecoder.decode(url, "UTF-8");
            
            // 简单嗅探
            String simpleResult = Sniffer.getUrl(url);
            if (Sniffer.isVideoFormat(simpleResult)) {
                // 检查是否需要代理
                boolean shouldProxy = shouldUseProxy(params);
                if (shouldProxy) {
                    // 生成 /vod/play/ID.m3u8 格式的地址
                    String playId = PlayProxy.storePlayUrl("sniff", "1", simpleResult, null);
                    String serverBase = params.getOrDefault("_server", "");
                    String playPath = serverBase.isEmpty() ? ("/vod/play/" + playId + ".m3u8") : (serverBase + "/vod/play/" + playId + ".m3u8");
                    
                    JsonObject result = new JsonObject();
                    result.addProperty("url", playPath);
                    result.addProperty("realurl", simpleResult);
                    result.addProperty("code", 200);
                    result.addProperty("method", "simple");
                    return createJsonResponse(result);
                } else {
                    JsonObject result = new JsonObject();
                    result.addProperty("url", simpleResult);
                    result.addProperty("code", 200);
                    result.addProperty("method", "simple");
                    return createJsonResponse(result);
                }
            }
            
            // 复杂网页需要WebView嗅探
            JsonObject sniff = performWebViewSniffJson(url, params);
            NanoHTTPD.Response response = createJsonResponse(sniff);
            addNoCacheHeaders(response); // 嗅探接口禁用缓存
            return response;
            
        } catch (Exception e) {
            return createErrorResponse(500, "嗅探失败：" + (e == null ? "" : e.getMessage()));
        }
    }
    
    // 判断是否应该使用代理/重写URL (proxy参数权限最高)
    // 这个方法决定是否将URL重写为 /vod/play/ 格式
    private boolean shouldUseProxy(Map<String, String> params) {
        // 如果是从 PlayProxy 调用,不要重复重写
        if ("1".equals(params.get("_from_play_proxy"))) {
            return false;
        }
        
        String proxyParam = params.get("proxy");
        if (proxyParam != null) {
            // proxy 参数权限最高
            return "1".equals(proxyParam);
        }
        
        // 没有 proxy 参数,检查 rewritePlayUrl 设置
        return "1".equals(Prefers.getString("api_settings_rewritePlayUrl", "0"));
    }
    
    // WebView嗅探的具体实现
    private NanoHTTPD.Response performWebViewSniff(final String url) {
        return createJsonResponse(performWebViewSniffJson(url, new HashMap<>()));
    }

    // WebView嗅探返回 JsonObject 版(不带params) - 兼容旧调用
    private JsonObject performWebViewSniffJson(final String url) {
        return performWebViewSniffJson(url, new HashMap<>());
    }

    // WebView嗅探返回 JsonObject 版，便于内部复用 (性能优化: 减少超时时间)
    private JsonObject performWebViewSniffJson(final String url, final Map<String, String> params) {
        // 避免在主线程上阻塞等待 WebView 回调导致死锁：如果在主线程被调用，改为返回一个简单嗅探回退结果
        try {
            if (android.os.Looper.getMainLooper().getThread() == Thread.currentThread()) {
                JsonObject err = new JsonObject();
                err.addProperty("error", true);
                err.addProperty("message", "performWebViewSniffJson cannot be called from main thread; returning simple sniff fallback");
                try {
                    String simple = Sniffer.getUrl(url);
                    // 若 Sniffer 返回空，保留原始 url 字段
                    err.addProperty("url", simple != null && !simple.isEmpty() ? simple : url);
                    err.addProperty("method", "simple");
                    err.addProperty("status", 200);
                } catch (Exception e) {
                    err.addProperty("message", "performWebViewSniffJson on main thread: simple sniff failed: " + (e == null ? "" : e.getMessage()));
                    err.addProperty("status", 500);
                }
                return err;
            }
        } catch (Throwable t) {
            // 如果检测主线程时出错，继续执行原有逻辑以免吞掉正常嗅探
        }

        final JsonObject[] webResult = {null};
        final Object lock = new Object();
        final Exception[] webError = new Exception[1];
        
        // 性能优化: 减少等待超时从30秒到10秒
        final long SNIFF_TIMEOUT = 10000;
        
        // 判断是否应该代理 (proxy参数权限最高)
        final boolean shouldProxy = shouldUseProxy(params);
        
        // 读取嗅探配置
        final String sniffKeywords = Prefers.getString("api_settings_sniffKeywords", "");
        final String sniffHeaders = Prefers.getString("api_settings_sniffHeaders", "");
        final String sniffJs = Prefers.getString("api_settings_sniffJs", "");

        final com.fongmi.android.tv.impl.ParseCallback callback = new com.fongmi.android.tv.impl.ParseCallback() {
            @Override
            public void onParseSuccess(Map<String, String> headers, String videoUrl, String from) {
                synchronized (lock) {
                    JsonObject result = new JsonObject();
                    
                    // 应用嗅探关键字过滤
                    String filteredUrl = videoUrl;
                    if (TextUtils.isEmpty(sniffKeywords)) {
                        // 未设置 sniffKeywords，则使用 APK 内置的 Sniffer 规则作为默认行为
                        try {
                            if (!Sniffer.isVideoFormat(videoUrl)) {
                                // 未命中内置规则，仍保留原始 videoUrl（不强制代理）
                                filteredUrl = videoUrl;
                            }
                        } catch (Exception ignored) {}
                    } else {
                        filteredUrl = filterUrlByKeywords(videoUrl, sniffKeywords);
                    }
                    
                    // 根据 shouldProxy 决定是否代理
                    if (shouldProxy && !TextUtils.isEmpty(filteredUrl)) {
                        try {
                            // 生成 /vod/play/ID.m3u8 格式的地址
                            String playId = PlayProxy.storePlayUrl("sniff", "1", filteredUrl, headers);
                            String serverBase = params.getOrDefault("_server", "");
                            String playPath = serverBase.isEmpty() ? ("/vod/play/" + playId + ".m3u8") : (serverBase + "/vod/play/" + playId + ".m3u8");
                            // 返回代理路径到 url，同时保留未代理的原始地址到 realurl
                            result.addProperty("url", playPath);
                            result.addProperty("realurl", filteredUrl);
                        } catch (Exception e) {
                            result.addProperty("url", filteredUrl);
                        }
                    } else {
                        result.addProperty("url", filteredUrl);
                    }
                    
                    result.addProperty("code", 200);
                    result.addProperty("method", "webview");

                    if (headers != null && !headers.isEmpty()) {
                        JsonObject headerObj = new JsonObject();
                        for (Map.Entry<String, String> entry : headers.entrySet()) {
                            headerObj.addProperty(entry.getKey(), entry.getValue());
                        }
                        result.add("headers", headerObj);
                    }

                    if (!TextUtils.isEmpty(from)) {
                        result.addProperty("from", from);
                    }

                    webResult[0] = result;
                    lock.notify();
                }
            }

            @Override
            public void onParseError() {
                synchronized (lock) {
                    JsonObject result = new JsonObject();
                    result.addProperty("error", "WebView 嗅探失败");
                    result.addProperty("code", 500);
                    result.addProperty("method", "webview");
                    webResult[0] = result;
                    lock.notify();
                }
            }
        };

        final Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

        // 应用自定义请求头
        if (!TextUtils.isEmpty(sniffHeaders)) {
            try {
                com.google.gson.JsonObject customHeaders = com.github.catvod.utils.Json.parse(sniffHeaders).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> entry : customHeaders.entrySet()) {
                    headers.put(entry.getKey(), entry.getValue().getAsString());
                }
            } catch (Exception ignored) {}
        }

        for (com.fongmi.android.tv.bean.Site site : VodConfig.get().getSites()) {
            okhttp3.Headers siteHeaders = site.getHeaders();
            if (siteHeaders != null && siteHeaders.size() > 0) {
                for (String name : siteHeaders.names()) {
                    headers.put(name, siteHeaders.get(name));
                }
                break;
            }
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    com.fongmi.android.tv.ui.custom.CustomWebView webView =
                        com.fongmi.android.tv.ui.custom.CustomWebView.create(com.fongmi.android.tv.App.get());
                    
                    // 如果配置了执行JS，将其传入
                    String jsToExecute = !TextUtils.isEmpty(sniffJs) ? sniffJs : "";
                    webView.start("", "api_sniff", headers, url, jsToExecute, callback, true);

                    // 性能优化: 减少清理延迟从35秒到12秒
                    new android.os.Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                webView.stop(false);
                                webView.destroy();
                            } catch (Exception e) {
                                // 忽略清理错误
                            }
                        }
                    }, 12000);

                } catch (Exception e) {
                    synchronized (lock) {
                        webError[0] = e;
                        lock.notify();
                    }
                }
            }
        });

        synchronized (lock) {
            try {
                lock.wait(SNIFF_TIMEOUT);  // 性能优化: 10秒超时
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (webError[0] != null) {
            JsonObject err = new JsonObject();
            err.addProperty("error", true);
            err.addProperty("message", "WebView error: " + webError[0].getMessage());
            err.addProperty("status", 500);
            return err;
        }

        if (webResult[0] != null) return webResult[0];

        JsonObject result = new JsonObject();
        result.addProperty("url", url);
        result.addProperty("code", 200);
        result.addProperty("method", "timeout");
        result.addProperty("warning", "WebView sniff timeout, using original URL");
        return result;
    }
    
    // 辅助方法
    private Live findLiveByName(String name) {
        for (Live live : LiveConfig.get().getLives()) {
            if (live.getName().equals(name)) {
                return live;
            }
        }
        return new Live();
    }
    
    // 根据关键字过滤 URL（支持通配符）
    // 格式: http*.m3u8*|.mp4|*.flv
    // http*.m3u8* 表示匹配 http://*.m3u8?xxxx 这样的格式
    private String filterUrlByKeywords(String url, String keywords) {
        if (TextUtils.isEmpty(keywords) || TextUtils.isEmpty(url)) return url;
        
        // 按 | 分割多个关键字
        String[] patterns = keywords.split("\\|");
        for (String pattern : patterns) {
            if (TextUtils.isEmpty(pattern)) continue;
            // 将通配符 * 转换为正则 .*
            String regex = "^" + pattern.replace(".", "\\.").replace("*", ".*") + "$";
            try {
                if (url.matches(regex)) {
                    return url;
                }
            } catch (Exception ignored) {}
        }
        
        // 如果没有匹配的关键字，尝试简单包含匹配
        for (String pattern : patterns) {
            if (TextUtils.isEmpty(pattern)) continue;
            if (pattern.startsWith("*") && pattern.endsWith("*")) {
                String subPattern = pattern.substring(1, pattern.length() - 1);
                if (url.contains(subPattern)) return url;
            } else if (pattern.startsWith("*")) {
                String subPattern = pattern.substring(1);
                if (url.endsWith(subPattern)) return url;
            } else if (pattern.endsWith("*")) {
                String subPattern = pattern.substring(0, pattern.length() - 1);
                if (url.startsWith(subPattern)) return url;
            } else {
                if (url.contains(pattern)) return url;
            }
        }
        
        // 都不匹配时，仍然返回原始 URL（不进行过滤）
        return url;
    }
    
    private JsonObject siteToJson(Site site) {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", site.getKey());
        obj.addProperty("name", site.getName());
        obj.addProperty("type", site.getType());
        obj.addProperty("api", site.getApi());
        obj.addProperty("ext", site.getExt());
        obj.addProperty("jar", site.getJar());
        obj.addProperty("searchable", site.getSearchable());
        obj.addProperty("quickSearch", site.getQuickSearch());
        obj.addProperty("filterable", 1); // 默认可筛选
        return obj;
    }
    
    private JsonObject liveToJson(Live live) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", live.getName());
        obj.addProperty("type", 0); // 默认类型
        obj.addProperty("url", live.getUrl());
        obj.addProperty("playerType", 1); // 默认播放器类型
        obj.addProperty("ua", live.getUa());
        obj.addProperty("epg", live.getEpg());
        obj.addProperty("logo", live.getLogo());
        return obj;
    }
    
    // 创建JSON响应（确保字段顺序：code, msg, page, pagecount, limit, total, 其他字段）
    private NanoHTTPD.Response createJsonResponse(Object data) {
        try {
            // 将 data 转为 JsonObject（若无法转换则放入 data 字段）
            JsonObject payload = null;
            if (data instanceof JsonObject) payload = (JsonObject) data;
            else {
                try {
                    String json = gson.toJson(data);
                    payload = com.github.catvod.utils.Json.parse(json).getAsJsonObject();
                } catch (Exception e) {
                    payload = new JsonObject();
                    payload.add("data", com.github.catvod.utils.Json.parse(gson.toJson(data)));
                }
            }

            // 构建有序输出：先 code/msg，再分页字段，最后其他字段
            JsonObject out = new JsonObject();
            
            // 处理错误情况
            if (payload.has("error")) {
                String em = payload.get("error").isJsonPrimitive() ? payload.get("error").getAsString() : payload.get("error").toString();
                out.addProperty("code", 500);
                out.addProperty("msg", em);
                // 保留其他字段（如 raw/style）但移除旧的 error 字段
                for (Map.Entry<String, com.google.gson.JsonElement> e : payload.entrySet()) {
                    if ("error".equals(e.getKey())) continue;
                    out.add(e.getKey(), e.getValue());
                }
            } else {
                // 正常返回
                out.addProperty("code", 1);
                out.addProperty("msg", "数据列表");
                
                // 按优先级添加分页字段（如果存在）
                if (payload.has("page")) out.add("page", payload.get("page"));
                if (payload.has("pagecount")) out.add("pagecount", payload.get("pagecount"));
                if (payload.has("limit")) out.add("limit", payload.get("limit"));
                if (payload.has("total")) out.add("total", payload.get("total"));
                
                // 添加其他字段（除了已处理的分页字段和被过滤的字段）
                java.util.Set<String> paginationFields = new java.util.HashSet<>(
                    java.util.Arrays.asList("page", "pagecount", "limit", "total", "code", "msg")
                );
                for (Map.Entry<String, com.google.gson.JsonElement> e : payload.entrySet()) {
                    if (!paginationFields.contains(e.getKey())) {
                        out.add(e.getKey(), e.getValue());
                    }
                }
            }

            // 根据持久化设置控制是否输出 raw/style/filters 字段（全局统一）
            String rawPref = Prefers.getString("api_settings_raw", "1");
            String stylePref = Prefers.getString("api_settings_style", "0");
            String filtersPref = Prefers.getString("api_settings_filters", "1");
            if (!"1".equals(rawPref) && out.has("raw")) out.remove("raw");
            if (!"1".equals(stylePref) && out.has("style")) out.remove("style");
            if (!"1".equals(filtersPref) && out.has("filters")) out.remove("filters");

            String json = gson.toJson(out);
            byte[] bytes = json.getBytes("UTF-8");
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (Exception e) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "");
        }
    }

    // 创建错误响应
    private NanoHTTPD.Response createErrorResponse(int status, String message) {
        JsonObject error = new JsonObject();
        // 成功时 code=1，否则返回状态码作为 code
        int code = (status >= 200 && status < 300) ? 1 : status;
        error.addProperty("code", code);
        error.addProperty("msg", message == null ? "错误" : message);

        NanoHTTPD.Response.Status responseStatus = NanoHTTPD.Response.Status.lookup(status);
        if (responseStatus == null) responseStatus = NanoHTTPD.Response.Status.INTERNAL_ERROR;

        try {
            String json = gson.toJson(error);
            byte[] bytes = json.getBytes("UTF-8");
            return NanoHTTPD.newFixedLengthResponse(responseStatus, "application/json; charset=utf-8", new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (Exception e) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "");
        }
    }
    
    // 为响应添加缓存控制头 - 禁用缓存（用于动态内容）
    private void addNoCacheHeaders(NanoHTTPD.Response response) {
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.addHeader("Pragma", "no-cache");
        response.addHeader("Expires", "0");
    }
    
    // 为响应添加缓存控制头 - 短时间缓存（用于相对静态的内容）
    private void addShortCacheHeaders(NanoHTTPD.Response response, int seconds) {
        response.addHeader("Cache-Control", "public, max-age=" + seconds);
    }

    // 静态文件服务：从 Android assets 目录读取文件并返回响应；若未找到则返回 null
    private NanoHTTPD.Response serveStatic(String path) {
        java.io.InputStream is = null;
        try {
            // 如果请求以 /assets/ 开头，则映射到 assets/<rest>
            String assetPath;
            if (path.startsWith("/assets/")) {
                assetPath = path.substring("/assets/".length());
            } else {
                assetPath = path.startsWith("/") ? path.substring(1) : path;
            }
            
            // 从 Android assets 中读取文件
            try {
                is = com.fongmi.android.tv.App.get().getAssets().open(assetPath);
            } catch (java.io.FileNotFoundException e) {
                // 如果在 assets 中找不到，尝试从开发环境的文件系统读取（调试用）
                java.io.File f = new java.io.File("app/src/main/assets/" + assetPath);
                if (!f.exists() || !f.isFile()) return null;
                is = new java.io.FileInputStream(f);
            }

            String contentType = "text/plain";
            if (assetPath.endsWith(".html")) contentType = "text/html; charset=utf-8";
            else if (assetPath.endsWith(".css")) contentType = "text/css; charset=utf-8";
            else if (assetPath.endsWith(".js")) contentType = "application/javascript; charset=utf-8";
            else if (assetPath.endsWith(".png")) contentType = "image/png";
            else if (assetPath.endsWith(".jpg") || assetPath.endsWith(".jpeg")) contentType = "image/jpeg";
            else if (assetPath.endsWith(".svg")) contentType = "image/svg+xml";
            else if (assetPath.endsWith(".ico")) contentType = "image/x-icon";

            // 读取所有内容到字节数组（因为 assets 的 InputStream 无法获取准确的长度）
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            byte[] data = baos.toByteArray();
            
            NanoHTTPD.Response resp = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, 
                contentType, 
                new java.io.ByteArrayInputStream(data), 
                data.length
            );
            addShortCacheHeaders(resp, 60);
            return resp;
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }
    
    // 同步执行搜索
    private com.fongmi.android.tv.bean.Result performSearch(Site site, String keyword, boolean quick) throws Exception {
        if (site.getType() == 3) {
            // Spider类型站点
            if (quick && !site.isQuickSearch()) {
                return com.fongmi.android.tv.bean.Result.empty();
            }
            Spider spider = site.spider();
            String searchContent = spider.searchContent(keyword, quick);
            return safeParseResult(searchContent, true);
        } else {
            // HTTP类型站点
            if (quick && !site.isQuickSearch()) {
                return com.fongmi.android.tv.bean.Result.empty();
            }
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("wd", keyword);
            params.put("quick", String.valueOf(quick));
            String searchContent = callSiteApi(site, params);
            return fetchPic(site, safeParseResult(searchContent, false, site.getType()));
        }
    }
    
    // 重载版本：支持用户自定义 extend
    private com.fongmi.android.tv.bean.Result performSearch(Site site, String keyword, boolean quick, String userExtend) throws Exception {
        if (site.getType() == 3) {
            // Spider类型站点不支持 userExtend
            return performSearch(site, keyword, quick);
        } else {
            // HTTP类型站点
            if (quick && !site.isQuickSearch()) {
                return com.fongmi.android.tv.bean.Result.empty();
            }
            java.util.Map<String, String> params = new java.util.HashMap<>();
            if (!TextUtils.isEmpty(userExtend)) {
                params.put("extend", userExtend);
            }
            params.put("wd", keyword);
            params.put("quick", String.valueOf(quick));
            String searchContent = callSiteApi(site, params);
            return fetchPic(site, safeParseResult(searchContent, false, site.getType()));
        }
    }
    
    // 同步执行详情获取
    private com.fongmi.android.tv.bean.Result performDetail(Site site, String ids) throws Exception {
        if (site.getType() == 3) {
            // Spider类型站点
            Spider spider = site.recent().spider();
            String detailContent = spider.detailContent(Arrays.asList(ids));
            com.fongmi.android.tv.bean.Result result = safeParseResult(detailContent, true);
            if (!result.getList().isEmpty()) result.getList().get(0).setVodFlags();
            return result;
        } else {
            // HTTP类型站点
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("ac", site.getType() == 0 ? "videolist" : "detail");
            params.put("ids", ids);
            String detailContent = callSiteApi(site, params);
            com.fongmi.android.tv.bean.Result result = safeParseResult(detailContent, false, site.getType());
            if (!result.getList().isEmpty()) result.getList().get(0).setVodFlags();
            return fetchPic(site, result);
        }
    }
    
    // 重载版本：支持用户自定义 extend
    private com.fongmi.android.tv.bean.Result performDetail(Site site, String ids, String userExtend) throws Exception {
        if (site.getType() == 3) {
            // Spider类型站点不支持 userExtend
            return performDetail(site, ids);
        } else {
            // HTTP类型站点
            java.util.Map<String, String> params = new java.util.HashMap<>();
            if (!TextUtils.isEmpty(userExtend)) {
                params.put("extend", userExtend);
            }
            params.put("ac", site.getType() == 0 ? "videolist" : "detail");
            params.put("ids", ids);
            String detailContent = callSiteApi(site, params);
            com.fongmi.android.tv.bean.Result result = safeParseResult(detailContent, false, site.getType());
            if (!result.getList().isEmpty()) result.getList().get(0).setVodFlags();
            return fetchPic(site, result);
        }
    }
    
    // 安全解析Result，避免null引用错误
    private com.fongmi.android.tv.bean.Result safeParseResult(String content, boolean isJson) {
        try {
            if (TextUtils.isEmpty(content)) {
                return com.fongmi.android.tv.bean.Result.empty();
            }
            
            if (isJson) {
                return com.fongmi.android.tv.bean.Result.fromJson(content);
            } else {
                return com.fongmi.android.tv.bean.Result.fromXml(content);
            }
        } catch (Exception e) {
            // JSON/XML解析失败时返回空结果
            android.util.Log.e("Api", "解析结果失败：" + e.getMessage());
            return com.fongmi.android.tv.bean.Result.empty();
        }
    }
    
    // 安全解析Result（带类型）
    private com.fongmi.android.tv.bean.Result safeParseResult(String content, boolean isJson, int type) {
        try {
            if (TextUtils.isEmpty(content)) {
                return com.fongmi.android.tv.bean.Result.empty();
            }
            
            return com.fongmi.android.tv.bean.Result.fromType(type, content);
        } catch (Exception e) {
            // 解析失败时返回空结果
            android.util.Log.e("Api", "解析结果失败：" + e.getMessage());
            return com.fongmi.android.tv.bean.Result.empty();
        }
    }
    
    // 同步执行分类获取 (性能优化: 添加缓存)
    // extend: 用于分类筛选的参数 (如年份、地区等)
    // 站点级 extend 配置会在 callSiteApi() 中自动添加，用户也可以通过 HTTP 请求参数传递自定义的 extend
    private com.fongmi.android.tv.bean.Result performCategory(Site site, String tid, String page, HashMap<String, String> extend) throws Exception {
        // 使用缓存 (只缓存第一页数据)
        if ("1".equals(page) && (extend == null || extend.isEmpty())) {
            String cacheKey = "category_" + site.getKey() + "_" + tid;
            CacheEntry cached = RESULT_CACHE.get(cacheKey);
            if (cached != null && cached.isValid()) {
                return (com.fongmi.android.tv.bean.Result) cached.data;
            }
        }
        
        com.fongmi.android.tv.bean.Result result;
        
        if (site.getType() == 3) {
            // Spider类型站点
            Spider spider = site.recent().spider();
            String categoryContent = spider.categoryContent(tid, page, true, extend);
            result = safeParseResult(categoryContent, true);
        } else {
            // HTTP类型站点
            java.util.Map<String, String> params = new java.util.HashMap<>();
            if (site.getType() == 1 && !extend.isEmpty()) {
                params.put("f", gson.toJson(extend));
            }
            params.put("ac", site.getType() == 0 ? "videolist" : "detail");
            params.put("t", tid);
            params.put("pg", page);
            // 注意：extend 参数（站点级配置）会在 callSiteApi 中自动处理
            // 如果 params 中没有 extend，会使用 site.getExt()
            String categoryContent = callSiteApi(site, params);
            result = safeParseResult(categoryContent, false, site.getType());
        }
        
        // 缓存第一页数据
        if ("1".equals(page) && (extend == null || extend.isEmpty())) {
            String cacheKey = "category_" + site.getKey() + "_" + tid;
            RESULT_CACHE.put(cacheKey, new CacheEntry(result, CACHE_TTL_MS));
        }
        
        return result;
    }
    
    // 重载版本：支持用户自定义 extend (站点级配置)
    private com.fongmi.android.tv.bean.Result performCategory(Site site, String tid, String page, HashMap<String, String> extend, String userExtend) throws Exception {
        // 使用缓存 (只缓存第一页数据，且没有自定义 extend 的情况)
        if ("1".equals(page) && (extend == null || extend.isEmpty()) && TextUtils.isEmpty(userExtend)) {
            String cacheKey = "category_" + site.getKey() + "_" + tid;
            CacheEntry cached = RESULT_CACHE.get(cacheKey);
            if (cached != null && cached.isValid()) {
                return (com.fongmi.android.tv.bean.Result) cached.data;
            }
        }
        
        com.fongmi.android.tv.bean.Result result;
        
        if (site.getType() == 3) {
            // Spider类型站点不支持 userExtend
            Spider spider = site.recent().spider();
            String categoryContent = spider.categoryContent(tid, page, true, extend);
            result = safeParseResult(categoryContent, true);
        } else {
            // HTTP类型站点
            java.util.Map<String, String> params = new java.util.HashMap<>();
            if (site.getType() == 1 && !extend.isEmpty()) {
                params.put("f", gson.toJson(extend));
            }
            // 添加用户自定义的 extend (如果提供)
            if (!TextUtils.isEmpty(userExtend)) {
                params.put("extend", userExtend);
            }
            params.put("ac", site.getType() == 0 ? "videolist" : "detail");
            params.put("t", tid);
            params.put("pg", page);
            // callSiteApi 会检查 params 中是否有 extend，如果有则使用，否则使用 site.getExt()
            String categoryContent = callSiteApi(site, params);
            result = safeParseResult(categoryContent, false, site.getType());
        }
        
        // 缓存第一页数据
        if ("1".equals(page) && (extend == null || extend.isEmpty()) && TextUtils.isEmpty(userExtend)) {
            String cacheKey = "category_" + site.getKey() + "_" + tid;
            RESULT_CACHE.put(cacheKey, new CacheEntry(result, CACHE_TTL_MS));
        }
        
        return result;
    }
    
    // 同步执行首页获取 (性能优化: 添加缓存,减少重复请求)
    private com.fongmi.android.tv.bean.Result performHome(Site site) throws Exception {
        // 使用缓存
        String cacheKey = "home_" + site.getKey();
        CacheEntry cached = RESULT_CACHE.get(cacheKey);
        if (cached != null && cached.isValid()) {
            return (com.fongmi.android.tv.bean.Result) cached.data;
        }
        
        com.fongmi.android.tv.bean.Result result;
        
        if (site.getType() == 3) {
            // Spider类型站点
            Spider spider = site.recent().spider();
            
            // 获取首页内容（包含分类）
            String homeContent = spider.homeContent(true);
            result = safeParseResult(homeContent, true);
            
            // 如果首页没有视频列表，只尝试第一个分类 (性能优化: 减少循环次数)
            if (result.getList().isEmpty() && !result.getTypes().isEmpty()) {
                try {
                    String firstTypeId = result.getTypes().get(0).getTypeId();
                    String categoryContent = spider.categoryContent(firstTypeId, "1", true, new java.util.HashMap<>());
                    if (!TextUtils.isEmpty(categoryContent)) {
                        com.fongmi.android.tv.bean.Result categoryResult = safeParseResult(categoryContent, true);
                        if (!categoryResult.getList().isEmpty()) {
                            result.setList(categoryResult.getList());
                            // 保留第一个分类的筛选器
                            if (!categoryResult.getFilters().isEmpty()) {
                                result.getFilters().putAll(categoryResult.getFilters());
                            }
                        }
                    }
                } catch (Exception ex) {
                    // 忽略错误
                }
            }
        } else {
            // HTTP类型站点
            java.util.Map<String, String> params = new java.util.HashMap<>();
            try {
                String homeContent = callSiteApi(site, params);
                if (TextUtils.isEmpty(homeContent)) {
                    result = com.fongmi.android.tv.bean.Result.empty();
                } else {
                    result = fetchPic(site, safeParseResult(homeContent, false, site.getType()));
                }
            } catch (Exception e) {
                // HTTP站点错误时返回空结果
                result = com.fongmi.android.tv.bean.Result.empty();
            }
        }
        
        // 缓存结果
        RESULT_CACHE.put(cacheKey, new CacheEntry(result, CACHE_TTL_MS));
        // 限制缓存大小
        if (RESULT_CACHE.size() > 50) {
            long now = System.currentTimeMillis();
            RESULT_CACHE.entrySet().removeIf(e -> !e.getValue().isValid());
        }
        
        return result;
    }
    
    // 重载版本：支持用户自定义 extend
    private com.fongmi.android.tv.bean.Result performHome(Site site, String userExtend) throws Exception {
        // 如果有用户自定义 extend，不使用缓存
        if (TextUtils.isEmpty(userExtend)) {
            return performHome(site);
        }
        
        com.fongmi.android.tv.bean.Result result;
        
        if (site.getType() == 3) {
            // Spider类型站点不支持 userExtend，直接调用原方法
            return performHome(site);
        } else {
            // HTTP类型站点
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("extend", userExtend);
            try {
                String homeContent = callSiteApi(site, params);
                if (TextUtils.isEmpty(homeContent)) {
                    result = com.fongmi.android.tv.bean.Result.empty();
                } else {
                    result = fetchPic(site, safeParseResult(homeContent, false, site.getType()));
                }
            } catch (Exception e) {
                result = com.fongmi.android.tv.bean.Result.empty();
            }
        }
        
        return result;
    }
    
    // 调用站点API (性能优化: 减少超时时间)
    private String callSiteApi(Site site, java.util.Map<String, String> params) throws Exception {
        // 优先使用 params 中用户传递的 extend，如果没有则使用 site.getExt()
        // 这样用户可以通过 HTTP 请求参数临时覆盖站点配置的 ext
        if (!params.containsKey("extend") && !site.getExt().isEmpty()) {
            params.put("extend", site.getExt());
        }
        
        // 构建请求
        StringBuilder urlBuilder = new StringBuilder(site.getApi());
        if (!params.isEmpty()) {
            urlBuilder.append("?");
            boolean first = true;
            for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) urlBuilder.append("&");
                urlBuilder.append(entry.getKey()).append("=").append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
                first = false;
            }
        }
        
        // 发起请求 - 使用 OkHttp 以支持特殊字符
        okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .build();
        
        okhttp3.Response okResponse = okHttpClient.newCall(request).execute();
        okhttp3.ResponseBody responseBody = okResponse.body();
        if (responseBody == null) {
            throw new Exception("站点API返回空响应");
        }
        
        return responseBody.string();
    }
    
    // 获取图片信息（HTTP站点需要）
    private com.fongmi.android.tv.bean.Result fetchPic(Site site, com.fongmi.android.tv.bean.Result result) throws Exception {
        if (site.getType() > 2 || result.getList().isEmpty() || !result.getList().get(0).getVodPic().isEmpty()) {
            return result;
        }
        
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        if (site.getCategories().isEmpty()) {
            for (Vod item : result.getList()) ids.add(item.getVodId());
        } else {
            for (Vod item : result.getList()) {
                if (site.getCategories().contains(item.getTypeName())) {
                    ids.add(item.getVodId());
                }
            }
        }
        
        if (ids.isEmpty()) return result.clear();
        
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("ac", site.getType() == 0 ? "videolist" : "detail");
        params.put("ids", TextUtils.join(",", ids));
        String response = callSiteApi(site, params);
        com.fongmi.android.tv.bean.Result picResult = safeParseResult(response, false, site.getType());
        result.setList(picResult.getList());
        return result;
    }
    
    // 获取样式信息JSON
    private JsonObject getStyleJson(com.fongmi.android.tv.bean.Result result, Site site) {
        JsonObject styleJson = new JsonObject();
        
        try {
            // 获取样式，优先级：结果样式 > 站点样式 > 默认样式
            com.fongmi.android.tv.bean.Style style = result.getStyle(site.getStyle());
            
            if (style != null) {
                styleJson.addProperty("type", style.getType());
                styleJson.addProperty("ratio", style.getRatio());
                styleJson.addProperty("viewType", style.getViewType());
                styleJson.addProperty("isRect", style.isRect());
                styleJson.addProperty("isOval", style.isOval());
                styleJson.addProperty("isList", style.isList());
                styleJson.addProperty("isLand", style.isLand());
            } else {
                // 默认样式
                styleJson.addProperty("type", "rect");
                styleJson.addProperty("ratio", 0.75f);
                styleJson.addProperty("viewType", 0);
                styleJson.addProperty("isRect", true);
                styleJson.addProperty("isOval", false);
                styleJson.addProperty("isList", false);
                styleJson.addProperty("isLand", false);
            }
        } catch (Exception e) {
            // 如果获取样式失败，使用默认样式
            styleJson.addProperty("type", "rect");
            styleJson.addProperty("ratio", 0.75f);
            styleJson.addProperty("viewType", 0);
            styleJson.addProperty("isRect", true);
            styleJson.addProperty("isOval", false);
            styleJson.addProperty("isList", false);
            styleJson.addProperty("isLand", false);
        }
        
        return styleJson;
    }
    
    // 转换Vod对象为JSON
    private JsonObject vodToJson(Vod vod) {
        JsonObject obj = new JsonObject();
        obj.addProperty("vod_id", vod.getVodId());
        obj.addProperty("vod_name", vod.getVodName());
        obj.addProperty("vod_pic", vod.getVodPic());
        obj.addProperty("vod_remarks", vod.getVodRemarks());
        obj.addProperty("vod_year", vod.getVodYear());
        obj.addProperty("vod_area", vod.getVodArea());
        obj.addProperty("vod_director", vod.getVodDirector());
        obj.addProperty("vod_actor", vod.getVodActor());
        obj.addProperty("vod_content", vod.getVodContent());
        obj.addProperty("vod_play_from", vod.getVodPlayFrom());
        obj.addProperty("vod_play_url", vod.getVodPlayUrl());
        obj.addProperty("vod_tag", vod.getVodTag());
        obj.addProperty("type_id", vod.getVodId());
        obj.addProperty("type_name", vod.getTypeName());
        return obj;
    }
    
    // 转换Filter对象为JSON
    private JsonObject filterToJson(com.fongmi.android.tv.bean.Filter filter) {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", filter.getKey());
        obj.addProperty("name", filter.getName());
        obj.addProperty("init", filter.getInit());
        
        JsonArray values = new JsonArray();
        for (com.fongmi.android.tv.bean.Value value : filter.getValue()) {
            JsonObject valueObj = new JsonObject();
            valueObj.addProperty("n", value.getN());
            valueObj.addProperty("v", value.getV());
            values.add(valueObj);
        }
        obj.add("value", values);
        
        return obj;
    }
    
    // 供 PlayProxy 调用的公开方法 - 获取活动站点
    public Site getActiveSiteForPlayProxy() {
        return getActiveSite();
    }
    
    // 供 PlayProxy 调用的公开方法 - 代理流转发
    public NanoHTTPD.Response proxyStream(String url, Map<String, String> headers) {
        try {
            // 如果URL是 /vod/play/ 格式,需要递归解析出真实的HTTP URL
            // 避免无限302循环
            while (url.startsWith("/vod/play/")) {
                String id = url.substring("/vod/play/".length());
                // 支持各种格式：.tvp.{ext}、.{ext}、纯ID
                if (id.contains(".tvp.")) {
                    // 新格式 .tvp.{ext}，提取ID部分
                    int tvpIndex = id.indexOf(".tvp.");
                    if (tvpIndex > 0) {
                        id = id.substring(0, tvpIndex);
                    }
                } else {
                    // 去掉任何扩展名
                    int lastDot = id.lastIndexOf('.');
                    if (lastDot > 0) {
                        id = id.substring(0, lastDot);
                    }
                }
                
                // 尝试从 PROXY_MAP 中解析（用于m3u8分段）
                ProxyEntry proxyEntry = resolveProxyEntry(id);
                if (proxyEntry != null) {
                    url = proxyEntry.url;
                    // 合并headers
                    if (proxyEntry.headers != null && !proxyEntry.headers.isEmpty()) {
                        if (headers == null) headers = new HashMap<>();
                        for (Map.Entry<String, String> e : proxyEntry.headers.entrySet()) {
                            headers.putIfAbsent(e.getKey(), e.getValue());
                        }
                    }
                    continue; // 继续循环，以防URL还是 /vod/play/ 格式
                }
                
                // 如果PROXY_MAP中没有，说明是主播放URL，无法在这里解析
                // 返回404（这种情况不应该发生，因为PlayProxy应该已经处理了）
                return createErrorResponse(404, "无法解析播放URL：" + id);
            }
            
            // 使用 OkHttp 发起请求,保留 URL 中的所有特殊字符(包括 # $ 等)
            okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
            
            okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .url(url); // OkHttp 会保留完整 URL
            
            // 设置请求头
            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    try {
                        requestBuilder.header(e.getKey(), e.getValue());
                    } catch (Exception ignored) {}
                }
            }
            
            okhttp3.Response okResponse = okHttpClient.newCall(requestBuilder.build()).execute();
            
            int code = okResponse.code();
            String contentType = okResponse.header("Content-Type");
            String contentLength = okResponse.header("Content-Length");
            String acceptRanges = okResponse.header("Accept-Ranges");
            String contentRange = okResponse.header("Content-Range");
            
            okhttp3.ResponseBody responseBody = okResponse.body();
            if (responseBody == null) {
                return createErrorResponse(502, "上游返回空响应");
            }
            
            java.io.InputStream input = responseBody.byteStream();
            
            // 检测是否为 m3u8 文件（需要重写片段地址）
            boolean isM3u8 = url.toLowerCase().contains(".m3u8") || 
                           (contentType != null && contentType.toLowerCase().contains("mpegurl")) ||
                           (contentType != null && contentType.toLowerCase().contains("m3u8"));
            
            if (isM3u8) {
                // 读取 m3u8 内容并重写片段地址
                try {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(input, "UTF-8")
                    );
                    StringBuilder m3u8Content = new StringBuilder();
                    String line;
                    
                    while ((line = reader.readLine()) != null) {
                        // 跳过注释行和空行
                        if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                            m3u8Content.append(line).append("\n");
                            continue;
                        }
                        
                        // 处理片段地址行
                        String rewrittenLine = rewriteM3u8Line(line, url, headers);
                        m3u8Content.append(rewrittenLine).append("\n");
                    }
                    reader.close();
                    
                    // 返回重写后的 m3u8 内容
                    byte[] bytes = m3u8Content.toString().getBytes("UTF-8");
                    NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.lookup(code),
                        "application/vnd.apple.mpegurl",
                        new java.io.ByteArrayInputStream(bytes),
                        bytes.length
                    );
                    response.addHeader("Content-Length", String.valueOf(bytes.length));
                    return response;
                } catch (Exception e) {
                    // 如果重写失败，回退到流式转发
                    return createErrorResponse(500, "M3U8重写失败：" + e.getMessage());
                }
            }
            
            // 非 m3u8 文件，直接流式转发
            NanoHTTPD.Response response = NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.lookup(code), 
                contentType == null ? "application/octet-stream" : contentType, 
                input
            );
            
            if (contentLength != null) response.addHeader("Content-Length", contentLength);
            if (acceptRanges != null) response.addHeader("Accept-Ranges", acceptRanges);
            if (contentRange != null) response.addHeader("Content-Range", contentRange);
            
            String cacheControl = okResponse.header("Cache-Control");
            if (cacheControl != null) response.addHeader("Cache-Control", cacheControl);
            
            String etag = okResponse.header("ETag");
            if (etag != null) response.addHeader("ETag", etag);
            
            return response;
        } catch (Exception e) {
            return createErrorResponse(500, "代理失败：" + (e == null ? "" : e.getMessage()));
        }
    }
    
    /**
     * 重写 m3u8 文件中的单行片段地址
     * @param line 原始行
     * @param baseUrl m3u8 文件的 URL（用于解析相对路径）
     * @param headers 原始请求头（将传递给代理）
     * @return 重写后的行
     */
    private String rewriteM3u8Line(String line, String baseUrl, Map<String, String> headers) {
        try {
            // 如果已经是代理地址（包含 .tvp），直接返回
            if (line.contains(".tvp")) {
                return line;
            }
            
            // 解析片段URL
            String segmentUrl;
            if (line.startsWith("http://") || line.startsWith("https://")) {
                // 绝对路径
                segmentUrl = line;
            } else {
                // 相对路径，基于 baseUrl 解析
                URL base = new URL(baseUrl);
                URL resolved = new URL(base, line);
                segmentUrl = resolved.toString();
            }
            
            // 提取原始URL的文件扩展名
            String originalExt = extractFileExtension(segmentUrl);
            
            // 存储到 PROXY_MAP 并生成代理 ID
            String proxyId = storeProxyUrl(segmentUrl, headers);
            
            // 返回代理路径（保留原始扩展名）
            // 格式：/vod/play/{id}.tvp.{原始扩展名}
            return "/vod/play/" + proxyId + ".tvp." + originalExt;
        } catch (Exception e) {
            // 解析失败，返回原始行
            return line;
        }
    }
    
    /**
     * 从URL中提取文件扩展名（智能检测）
     * @param url 原始URL
     * @return 文件扩展名（如 m3u8, ts, mp4 等），默认为 m3u8
     */
    private String extractFileExtension(String url) {
        try {
            // 去除查询参数
            String cleanUrl = url;
            int queryIndex = url.indexOf('?');
            if (queryIndex > 0) {
                cleanUrl = url.substring(0, queryIndex);
            }
            
            // 去除锚点
            int anchorIndex = cleanUrl.indexOf('#');
            if (anchorIndex > 0) {
                cleanUrl = cleanUrl.substring(0, anchorIndex);
            }
            
            // 策略1: 尝试从路径中查找已知的媒体扩展名
            // 常见的媒体格式列表
            String[] knownExts = {"m3u8", "ts", "mp4", "flv", "mkv", "avi", "mov", "wmv", "mpg", "mpeg", "webm", "m4v", "3gp", "f4v"};
            String lowerUrl = cleanUrl.toLowerCase();
            
            for (String ext : knownExts) {
                // 查找 .ext/ 或 .ext? 或结尾的 .ext
                String pattern = "\\." + ext + "($|/|\\?)";
                if (lowerUrl.matches(".*" + pattern + ".*")) {
                    return ext;
                }
            }
            
            // 策略2: 从URL末尾提取扩展名（传统方式）
            int lastDot = cleanUrl.lastIndexOf('.');
            int lastSlash = cleanUrl.lastIndexOf('/');
            
            if (lastDot > lastSlash && lastDot < cleanUrl.length() - 1) {
                String ext = cleanUrl.substring(lastDot + 1).toLowerCase();
                // 验证扩展名（只允许字母数字，最多5个字符）
                if (ext.matches("^[a-z0-9]{1,5}$")) {
                    return ext;
                }
            }
            
            // 策略3: 根据URL路径推断
            // 如果URL包含 "/ts/" 或 "/hls/"，很可能是 ts 分片
            if (lowerUrl.contains("/ts/") || lowerUrl.contains("/hls/")) {
                return "ts";
            }
            
            // 如果URL包含 "m3u8" 关键字（即使不是扩展名）
            if (lowerUrl.contains("m3u8")) {
                return "m3u8";
            }
            
            // 默认返回 m3u8
            return "m3u8";
        } catch (Exception e) {
            return "m3u8";
        }
    }
    
    // 创建302跳转响应
    private NanoHTTPD.Response create302Response(String targetUrl) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.REDIRECT, 
            NanoHTTPD.MIME_HTML, 
            ""
        );
        response.addHeader("Location", targetUrl);
        return response;
    }
    
    // 供 PlayProxy 调用的公开方法 - 处理播放请求并返回 JsonObject
    public JsonObject handlePlayForProxy(Site site, Map<String, String> params) {
        try {
            NanoHTTPD.Response response = handlePlay(site, params);
            // 从响应中提取 JSON
            // 这里需要读取响应体，但 NanoHTTPD.Response 的数据流读取有点操蛋，所以直接用字符串处理
            return handlePlayInternal(site, params);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("code", 0);
            error.addProperty("msg", "播放失败：" + (e == null ? "" : e.getMessage()));
            return error;
        }
    }
    
    // handlePlay 的内部实现，返回 JsonObject
    private JsonObject handlePlayInternal(Site site, Map<String, String> params) {
        String flag = params.get("flag");
        String id = params.get("id");
        String originalSiteKey = params.get("site");
        
        // 检查是否从 PlayProxy 调用 (避免重复代理)
        boolean fromPlayProxy = "1".equals(params.get("_from_play_proxy"));
        
        // 判断是否应该代理 (proxy参数权限最高,如果没传则使用全局设置)
        // 但如果是从 PlayProxy 调用的,则跳过代理(避免重复代理)
        boolean shouldProxy = !fromPlayProxy && shouldUseProxy(params);
        
        try {
            Result result;
            if (site.getType() == 3) {
                Spider spider = site.recent().spider();
                String playerContent = spider.playerContent(flag, id, VodConfig.get().getFlags());
                result = Result.fromJson(playerContent);
                if (result.getFlag().isEmpty()) result.setFlag(flag);
                result.setUrl(Source.get().fetch(result));
                result.setHeader(site.getHeader());
                result.setKey(site.getKey());
            } else if (site.getType() == 4) {
                java.util.Map<String, String> p = new java.util.HashMap<>();
                p.put("play", id);
                p.put("flag", flag);
                String playerContent = callSiteApi(site, p);
                result = Result.fromJson(playerContent);
                if (result.getFlag().isEmpty()) result.setFlag(flag);
                result.setUrl(Source.get().fetch(result));
                result.setHeader(site.getHeader());
                result.setKey(site.getKey());
            } else if (site.isEmpty() && "push_agent".equals(originalSiteKey)) {
                result = new Result();
                result.setParse(0);
                result.setFlag(flag);
                result.setUrl(Url.create().add(id));
                result.setUrl(Source.get().fetch(result));
            } else {
                Url url = Url.create().add(id);
                result = new Result();
                result.setUrl(url);
                result.setFlag(flag);
                result.setHeader(site.getHeader());
                result.setPlayUrl(site.getPlayUrl());
                result.setParse(Sniffer.isVideoFormat(url.v()) && result.getPlayUrl().isEmpty() ? 0 : 1);
                result.setUrl(Source.get().fetch(result));
            }

            JsonObject out = new JsonObject();
            String defaultUrl = result.getUrl() == null ? "" : result.getUrl().v();
            String playableUrl = defaultUrl;
            out.addProperty("flag", result.getFlag());
            out.addProperty("parse", result.getParse());
            out.addProperty("playUrl", "");

            String parseParam = params.get("parse");
            
            if ("1".equals(parseParam) && result.getUrl() != null) {
                try {
                    JsonObject sniffed = performWebViewSniffJson(result.getUrl().v(), params);
                    String real = sniffed.has("realurl") ? sniffed.get("realurl").getAsString() : 
                                 (sniffed.has("url") ? sniffed.get("url").getAsString() : "");
                    String sniffedUrl = sniffed.has("url") ? sniffed.get("url").getAsString() : "";
                    
                    if (!TextUtils.isEmpty(real)) {
                        out.addProperty("playUrl", real);
                        playableUrl = sniffedUrl; // 使用嗅探返回的url(可能已经是/vod/play/格式)
                    }
                    if (sniffed.has("headers")) out.add("headers", sniffed.getAsJsonObject("headers"));
                } catch (Exception ignored) {}
            }
            
            if (!"1".equals(parseParam) && shouldProxy && result.getUrl() != null) {
                try {
                    Map<String, String> proxyHeaders = new HashMap<>();
                    if (result.getHeaders() != null) proxyHeaders.putAll(result.getHeaders());
                    // 生成 /vod/play/ID.m3u8 格式的地址
                    String playId = PlayProxy.storePlayUrl(flag, "1", result.getUrl().v(), proxyHeaders);
                    String serverBase = params.getOrDefault("_server", "");
                    String proxyPath = serverBase.isEmpty() ? ("/vod/play/" + playId + ".m3u8") : (serverBase + "/vod/play/" + playId + ".m3u8");
                    out.addProperty("proxy", proxyPath);
                    playableUrl = proxyPath;
                } catch (Exception ignored) {}
            }
            
            if (result.getHeader() != null) {
                JsonObject headerObj = new JsonObject();
                for (Map.Entry<String, String> entry : result.getHeaders().entrySet()) {
                    headerObj.addProperty(entry.getKey(), entry.getValue());
                }
                out.add("headers", headerObj);
            }

            out.addProperty("url", playableUrl == null ? "" : playableUrl);
            out.addProperty("code", 1);
            out.addProperty("msg", "获取成功");
            return out;
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("code", 0);
            error.addProperty("msg", "播放失败：" + (e == null ? "" : e.getMessage()));
            return error;
        }
    }
    /**
     * 处理自动化刷新：/vod/api?site=xxx&refresh=true
     */
    private fi.iki.elonen.NanoHTTPD.Response handleRefreshSite(java.util.Map<String, String> params) {
        try {
            String siteKey = params.get("site");
            if (android.text.TextUtils.isEmpty(siteKey)) return createErrorResponse(400, "No Site");

            com.fongmi.android.tv.bean.Site site = findSiteByKey(siteKey);
            if (site == null) return createErrorResponse(404, "Site Not Found");

            // 1. 物理切换配置
            com.github.catvod.utils.Prefers.put("api_override_site", site.getKey());
            com.fongmi.android.tv.api.config.VodConfig.get().setHome(site);

            // 2. 清理缓存
            clearResultCache();
            clearSiteCache();

            // 3. 在主线程发送 EventBus 信号 (这是 TVBox 刷新 UI 的标准做法)
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    // 创建刷新事件对象，传入 Type.VIDEO (或第一个枚举值)
                    // 报错提示构造函数需要 Type，我们直接获取枚举列表的第一个
                    Class<?> typeClass = com.fongmi.android.tv.event.RefreshEvent.Type.class;
                    Object typeVideo = typeClass.getEnumConstants()[0]; 
                    
                    com.fongmi.android.tv.event.RefreshEvent event = new com.fongmi.android.tv.event.RefreshEvent((com.fongmi.android.tv.event.RefreshEvent.Type) typeVideo);

                    // 核心：使用 org.greenrobot.eventbus.EventBus 发送
                    // 这是 TVBox 源码中定义的 post 真正实现方式
                    org.greenrobot.eventbus.EventBus.getDefault().post(event);
                    
                } catch (Throwable t) {
                    // 如果 EventBus 失败，直接重启 Activity 作为保底
                    try {
                        android.content.Context context = com.fongmi.android.tv.App.get();
                        android.content.Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        context.startActivity(intent);
                    } catch (Exception ignored) {}
                }
            });

            com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
            resp.addProperty("code", 1);
            resp.addProperty("msg", "OK: " + site.getName());
            return createJsonResponse(resp);
        } catch (Exception e) {
            return createErrorResponse(500, "Error: " + e.getMessage());
        }
    }

}

