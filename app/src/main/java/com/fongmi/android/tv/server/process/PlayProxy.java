package com.fongmi.android.tv.server.process;

import android.text.TextUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import fi.iki.elonen.NanoHTTPD;
import com.github.catvod.utils.Prefers;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 播放代理处理器
 * 功能：
 * 1. 检测和转换 vod_play_url（如果包含分隔符）
 * 2. 生成唯一 ID 并建立映射表
 * 3. 处理 /vod/play/{ID}.m3u8 请求，转发到 /vod/api?ac=play
 */
public class PlayProxy {

    // 播放 URL 映射表：ID -> PlayUrlEntry
    private static final ConcurrentHashMap<String, PlayUrlEntry> PLAY_URL_MAP = new ConcurrentHashMap<>();
    private static final long PLAY_URL_TTL_MS = 4 * 60 * 60 * 1000; // 4 小时 (足够播放长电影及其ts分段)

    /**
     * 播放 URL 条目
     */
    private static class PlayUrlEntry {
        String lineTitle;        // 线路标题
        String episodeTitle;     // 集标题
        String originUrl;        // 原始地址（base64 或 HTTP URL）
        ResourceType type;       // 资源类型：ENCODED 或 URL
        long expiryTime;         // 过期时间

        PlayUrlEntry(String lineTitle, String episodeTitle, String originUrl, ResourceType type) {
            this.lineTitle = lineTitle;
            this.episodeTitle = episodeTitle;
            this.originUrl = originUrl;
            this.type = type;
            this.expiryTime = System.currentTimeMillis() + PLAY_URL_TTL_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }

    /**
     * 资源类型枚举
     */
    private enum ResourceType {
        ENCODED,  // base64 编码等，无 http 头
        URL       // http:// 或 https:// 开头的 URL
    }

    /**
     * 检查是否需要处理 vod_play_url
     * 如果包含 # 或 $，则视为需要处理
     */
    private static boolean shouldProcess(String vodPlayUrl) {
        return !TextUtils.isEmpty(vodPlayUrl) && (vodPlayUrl.contains("#") || vodPlayUrl.contains("$"));
    }
    
    /**
     * 存储单个播放URL并返回ID (用于 ac=play 接口)
     * 
     * @param lineTitle 线路标题
     * @param episodeTitle 集标题  
     * @param originUrl 原始播放地址
     * @param headers 请求头
     * @return 生成的ID
     */
    public static String storePlayUrl(String lineTitle, String episodeTitle, String originUrl, Map<String, String> headers) {
        String id = generateId(lineTitle, episodeTitle, originUrl);
        ResourceType type = originUrl.startsWith("http://") || originUrl.startsWith("https://") ?
                ResourceType.URL : ResourceType.ENCODED;
        PlayUrlEntry entry = new PlayUrlEntry(lineTitle, episodeTitle, originUrl, type);
        PLAY_URL_MAP.put(id, entry);
        return id;
    }

    /**
     * 处理 vod_play_url，返回转换后的 URL
     * 
     * @param vodPlayFrom 线路标题（可能包含 $$$）
     * @param vodPlayUrl  播放 URL（包含 $ 和 #）
     * @param currentIp   当前访问 IP
     * @param port        当前访问端口
     * @return 转换后的 vod_play_url，如果不需要处理则返回原值
     */
    public static String processVodPlayUrl(String vodPlayFrom, String vodPlayUrl,
                                           String currentIp, int port) {
        // 检查是否需要处理
        if (!shouldProcess(vodPlayUrl)) {
            return vodPlayUrl;
        }

        try {
            // 解析线路和集数
            String[] lines = vodPlayUrl.split("\\$\\$\\$");
            String[] lineNames = TextUtils.isEmpty(vodPlayFrom) ? new String[]{"线路1"} : vodPlayFrom.split("\\$\\$\\$");

            StringBuilder result = new StringBuilder();

            for (int i = 0; i < lines.length; i++) {
                if (i > 0) result.append("$$$");

                String lineTitle = i < lineNames.length ? lineNames[i] : ("线路" + (i + 1));
                String episodes = lines[i];

                // 解析该线路的集数
                String[] episodeList = episodes.split("#");
                for (int j = 0; j < episodeList.length; j++) {
                    if (j > 0) result.append("#");

                    String episode = episodeList[j];
                    String[] parts = episode.split("\\$", 2);

                    if (parts.length == 2) {
                        String episodeTitle = parts[0];
                        String originUrl = parts[1];

                        // 生成唯一 ID
                        String id = generateId(lineTitle, episodeTitle, originUrl);

                        // 判断资源类型
                        ResourceType type = originUrl.startsWith("http://") || originUrl.startsWith("https://") ?
                                ResourceType.URL : ResourceType.ENCODED;

                        // 建立映射
                        PlayUrlEntry entry = new PlayUrlEntry(lineTitle, episodeTitle, originUrl, type);
                        PLAY_URL_MAP.put(id, entry);

                        // 生成代理 URL（使用 .tvp.m3u8 双后缀：tvp 作为独特标识，m3u8 保证播放器兼容）
                        String proxyUrl = "http://" + currentIp + ":" + port + "/vod/play/" + id + ".tvp.m3u8";
                        result.append(episodeTitle).append("$").append(proxyUrl);
                    } else {
                        // 异常格式，保持原样
                        result.append(episode);
                    }
                }
            }

            return result.toString();
        } catch (Exception e) {
            // 异常则返回原值
            return vodPlayUrl;
        }
    }

    /**
     * 生成唯一 ID（优化版：使用时间戳+短哈希）
     * 格式：10位时间戳（秒）+ 6位短哈希 = 16位
     */
    private static String generateId(String lineTitle, String episodeTitle, String originUrl) {
        try {
            // 时间戳部分（10位，秒级）
            long timestamp = System.currentTimeMillis() / 1000;
            
            // 短哈希部分（6位）
            String source = lineTitle + "|" + episodeTitle + "|" + originUrl;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(source.getBytes("UTF-8"));
            
            // 取前3字节转成6位十六进制
            StringBuilder shortHash = new StringBuilder();
            for (int i = 0; i < 3 && i < messageDigest.length; i++) {
                String hex = Integer.toHexString(0xff & messageDigest[i]);
                if (hex.length() == 1) shortHash.append("0");
                shortHash.append(hex);
            }
            
            return timestamp + shortHash.toString();
        } catch (Exception e) {
            // 备选方案：纯时间戳 + 简单哈希
            long timestamp = System.currentTimeMillis() / 1000;
            int hash = (lineTitle + episodeTitle + originUrl).hashCode() & 0xFFFFFF; // 取24位
            return String.format("%d%06x", timestamp, hash);
        }
    }

    /**
     * 处理 /vod/play/{ID}.tvp.m3u8 请求
     * 查询映射表，转发到 /vod/api?ac=play，获取最终 URL
     * 根据 playProxy 设置决定是代理还是301跳转
     * 
     * @param id         播放 URL ID（不含后缀）
     * @param api        Api 实例（用于调用 handlePlay）
     * @param currentIp  当前访问 IP
     * @param port       当前端口
     * @return HTTP 响应（代理流或302跳转）
     */
    public static NanoHTTPD.Response handlePlayProxyRequest(String id, Api api, 
                                                            String currentIp, int port) {
        try {
            // 清理过期映射
            cleanupExpiredEntries();

            // 查询映射表
            PlayUrlEntry entry = PLAY_URL_MAP.get(id);
            if (entry == null || entry.isExpired()) {
                PLAY_URL_MAP.remove(id);
                return createErrorResponse(404, "映射已过期或不存在");
            }

            // 获取活动站点
            com.fongmi.android.tv.bean.Site site = api.getActiveSiteForPlayProxy();
            if (site == null || site.isEmpty()) {
                return createErrorResponse(400, "未找到活动站点");
            }

            // 构建参数
            Map<String, String> params = new java.util.HashMap<>();
            params.put("flag", entry.lineTitle);
            params.put("id", entry.originUrl);
            params.put("parse", entry.type == ResourceType.URL ? "1" : "0");
            
            // 特殊标志: 从 PlayProxy 调用,不要再次应用全局 playProxy 设置
            // 因为已经在 /vod/play/ 层面处理了代理,避免重复代理
            params.put("_from_play_proxy", "1");

            // 调用 handlePlay 获取播放信息（直接返回 JsonObject）
            JsonObject playResponse = api.handlePlayForProxy(site, params);

            // 从响应中提取最终 URL
            String finalUrl = playResponse.has("url") ? playResponse.get("url").getAsString() : "";
            if (TextUtils.isEmpty(finalUrl)) {
                return createErrorResponse(500, "无法获取播放地址");
            }
            
            // 检查是否启用全局播放流量转发
            String playProxyEnabled = Prefers.getString("api_settings_playProxy", "0");
            if ("1".equals(playProxyEnabled)) {
                // playProxy=true: 进行流量转发(代理)
                // 获取headers
                Map<String, String> headers = new java.util.HashMap<>();
                if (playResponse.has("headers")) {
                    try {
                        JsonObject hh = playResponse.getAsJsonObject("headers");
                        for (Map.Entry<String, com.google.gson.JsonElement> e : hh.entrySet()) {
                            headers.put(e.getKey(), e.getValue().getAsString());
                        }
                    } catch (Exception ignored) {}
                }
                
                // 调用 Api 的代理方法进行流量转发
                return api.proxyStream(finalUrl, headers);
            } else {
                // playProxy=false: 仅做301跳转
                return create302Response(finalUrl);
            }
        } catch (Exception e) {
            return createErrorResponse(500, "代理请求失败：" + (e == null ? "" : e.getMessage()));
        }
    }

    /**
     * 清理过期映射
     */
    public static void cleanupExpiredEntries() {
        PLAY_URL_MAP.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * 创建 302 跳转响应
     */
    private static NanoHTTPD.Response create302Response(String targetUrl) {
        try {
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.REDIRECT, "text/plain", ""
            );
            response.addHeader("Location", targetUrl);
            return response;
        } catch (Exception e) {
            return createErrorResponse(500, "创建跳转响应失败");
        }
    }

    /**
     * 创建错误响应
     */
    private static NanoHTTPD.Response createErrorResponse(int code, String message) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("code", 0);
            json.addProperty("msg", message);
            String jsonStr = json.toString();
            byte[] bytes = jsonStr.getBytes("UTF-8");
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, "application/json; charset=utf-8",
                    new java.io.ByteArrayInputStream(bytes), bytes.length
            );
            response.setStatus(NanoHTTPD.Response.Status.lookup(code));
            return response;
        } catch (UnsupportedEncodingException e) {
            return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "错误"
            );
        }
    }
}
