package com.fongmi.android.tv.server.process;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 独立的 WS 客户端：连接远端 WS 服务端，接收 JSON 请求并将请求转发到本地 HTTP 服务（例如 NanoHTTPD 提供的 API），
 * 然后将响应以 JSON 形式回传给服务端。
 *
 * 消息契约示例：
 * 请求:
 * {"id":"uuid","method":"GET","path":"/vod/list","query":"t=1&pg=1","headers":{...},"body":null}
 * 响应:
 * {"id":"uuid","status":200,"headers":{...},"body": {...} }
 */
public class WsClient {

    private final OkHttpClient client;
    private final String wsUrl;
    private final String localBaseUrl; // 如 http://127.0.0.1:9978
    private final Gson gson = new Gson();
    private final ExecutorService workerPool;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Context context;
    private final String clientId; // 客户端唯一ID (6位短ID，永久缓存)

    private volatile WebSocket webSocket;
    private volatile boolean stopped = false;
    private volatile boolean connected = false; // 真实的连接状态
    private volatile String serverClientId = ""; // 服务器实际使用的客户端ID（单客户端模式下可能是"api"）

    public WsClient(String wsUrl, String localBaseUrl, Context context) {
        this(wsUrl, localBaseUrl, context, 8);
    }

    public WsClient(String wsUrl, String localBaseUrl, Context context, int maxWorkers) {
        this.wsUrl = wsUrl;
        this.localBaseUrl = localBaseUrl.endsWith("/") ? localBaseUrl.substring(0, localBaseUrl.length() - 1) : localBaseUrl;
        this.context = context.getApplicationContext();
        this.clientId = getOrCreateClientId();
        this.client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .build();
        this.workerPool = Executors.newFixedThreadPool(Math.max(1, maxWorkers));
    }

    /**
     * 获取或创建客户端 ID
     * ID 为 6 位随机字符，永久缓存，除非重装 APK
     */
    private String getOrCreateClientId() {
        SharedPreferences prefs = context.getSharedPreferences("ws_client", Context.MODE_PRIVATE);
        String id = prefs.getString("client_id", null);
        
        if (id == null || id.isEmpty()) {
            // 生成 6 位随机 ID (使用字母和数字)
            id = generateShortId();
            prefs.edit().putString("client_id", id).apply();
        }
        
        return id;
    }
    
    /**
     * 生成 6 位随机 ID (字母+数字)
     */
    private String generateShortId() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(6);
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    /**
     * 获取当前客户端 ID（本地生成的）
     */
    public String getClientId() {
        return clientId;
    }
    
    /**
     * 获取服务器实际使用的客户端 ID
     * 单客户端模式下可能是 "api"，多客户端模式下是本地生成的 ID
     */
    public String getServerClientId() {
        return serverClientId.isEmpty() ? clientId : serverClientId;
    }

    public void start() {
        stopped = false;
        connected = false;
        // 在 WebSocket URL 中添加客户端 ID 参数
        String urlWithId = wsUrl + (wsUrl.contains("?") ? "&" : "?") + "client_id=" + clientId;
        Request req = new Request.Builder().url(urlWithId).build();
        webSocket = client.newWebSocket(req, new WsListener());

        // 可选：定期检查连接并在断开时重连
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (stopped) return;
                // 检查连接状态：如果未连接且未在重连中，则尝试重连
                if ((!connected || webSocket == null) && !reconnecting) {
                    android.util.Log.d("WsClient", "定时检查: 连接已断开，尝试重连...");
                    reconnect();
                }
            } catch (Throwable e) {
                android.util.Log.e("WsClient", "定时检查异常: " + e.getMessage());
            }
        }, 20, 20, TimeUnit.SECONDS); // 改为 20 秒检查一次，避免与延迟重连冲突
    }

    public void stop() {
        stopped = true;
        connected = false; // 标记为未连接
        try { if (webSocket != null) webSocket.close(1000, "client stopping"); } catch (Throwable ignored) {}
        try { workerPool.shutdownNow(); } catch (Throwable ignored) {}
        try { scheduler.shutdownNow(); } catch (Throwable ignored) {}
        try { client.dispatcher().executorService().shutdownNow(); } catch (Throwable ignored) {}
    }

    private volatile long lastReconnectTime = 0;
    private static final long RECONNECT_INTERVAL = 5000; // 最小重连间隔改为 5 秒
    private volatile boolean reconnecting = false; // 重连状态标记
    
    private synchronized void reconnect() {
        try {
            // 如果已经在重连中,跳过
            if (reconnecting) {
                android.util.Log.d("WsClient", "已在重连中，跳过本次重连");
                return;
            }
            
            // 防止重连过于频繁
            long now = System.currentTimeMillis();
            if (now - lastReconnectTime < RECONNECT_INTERVAL) {
                android.util.Log.d("WsClient", "重连间隔过短(" + (now - lastReconnectTime) + "ms)，跳过本次重连");
                return;
            }
            
            reconnecting = true;
            lastReconnectTime = now;
            
            // 关闭旧连接
            if (webSocket != null) {
                try {
                    webSocket.close(1000, "reconnecting");
                    webSocket = null;
                } catch (Exception e) {
                    android.util.Log.w("WsClient", "关闭旧连接失败: " + e.getMessage());
                }
            }
            
            connected = false;
            android.util.Log.d("WsClient", "开始重连...");
            
            // 延迟 1 秒再重连,避免过快
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            
            if (stopped) {
                reconnecting = false;
                return;
            }
            
            // 重连时也要带上客户端 ID 参数
            String urlWithId = wsUrl + (wsUrl.contains("?") ? "&" : "?") + "client_id=" + clientId;
            Request req = new Request.Builder().url(urlWithId).build();
            webSocket = client.newWebSocket(req, new WsListener());
            
            reconnecting = false;
        } catch (Exception e) {
            android.util.Log.e("WsClient", "重连失败: " + e.getMessage());
            reconnecting = false;
        }
    }

    private class WsListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            connected = true; // 连接成功
            android.util.Log.d("WsClient", "✓ WebSocket 连接已建立");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            // 接收纯文本 URL 并提交到线程池处理
            if (text == null || text.isEmpty()) return;
            
            // 检查是否是服务器返回的连接成功消息（JSON格式）
            if (text.trim().startsWith("{")) {
                try {
                    JsonObject json = gson.fromJson(text, JsonObject.class);
                    // 服务器的欢迎消息包含 code 和 client_id
                    if (json.has("code") && json.has("client_id")) {
                        // 保存服务器实际使用的客户端ID
                        serverClientId = json.get("client_id").getAsString();
                        String mode = json.has("server_mode") ? json.get("server_mode").getAsString() : "unknown";
                        android.util.Log.d("WsClient", "✓ 服务器确认客户端ID: " + serverClientId + " (模式: " + mode + ")");
                        return; // 不处理此消息,直接返回
                    }
                } catch (Exception e) {
                    android.util.Log.w("WsClient", "解析服务器消息失败: " + e.getMessage());
                    // 如果解析失败,当作普通消息处理
                }
            }
            
            // 提交到线程池处理
            workerPool.submit(() -> handleIncoming(text));
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            connected = false; // 连接失败
            android.util.Log.e("WsClient", "✗ WebSocket 连接失败: " + (t != null ? t.getMessage() : "unknown"));
            
            // 连接失败,延迟后尝试重连(只有未在停止状态且未在重连中)
            if (!stopped && !reconnecting) {
                scheduler.schedule(() -> {
                    if (!stopped && !connected && !reconnecting) {
                        reconnect();
                    }
                }, 5, TimeUnit.SECONDS); // 延迟 5 秒重连
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            connected = false; // 连接关闭
            android.util.Log.d("WsClient", "✗ WebSocket 连接已关闭 (code: " + code + ", reason: " + reason + ")");
            
            // 只有在非正常关闭时才重连(且未在重连中)
            if (!stopped && code != 1000 && !reconnecting) {
                scheduler.schedule(() -> {
                    if (!stopped && !connected && !reconnecting) {
                        reconnect();
                    }
                }, 3, TimeUnit.SECONDS); // 延迟 3 秒重连
            }
        }
    }

    private void handleIncoming(String text) {
        // 纯文本模式：直接接收 URL 路径（如 "/vod/api" 或 "/config/0?ac=list"）
        String path = text.trim();
        android.util.Log.d("WsClient", "收到请求: " + path);
        if (path.isEmpty()) return;

        // 构建本地 HTTP 请求 URL
        String url = localBaseUrl + path;
        android.util.Log.d("WsClient", "请求URL: " + url);

        // 发起 GET 请求
        Request.Builder rb = new Request.Builder().url(url);
        client.newCall(rb.build()).enqueue(new ForwardCallback());
    }

    private class ForwardCallback implements Callback {
        ForwardCallback() {}

        @Override
        public void onFailure(Call call, IOException e) {
            // 返回错误信息纯文本
            String errorMsg = "ERROR: " + (e == null ? "unknown error" : e.getMessage());
            android.util.Log.e("WsClient", "请求失败: " + errorMsg);
            sendSafe(errorMsg);
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            // 纯文本模式：直接返回响应体内容
            android.util.Log.d("WsClient", "收到响应: " + response.code());
            String body = null;
            try {
                body = response.body() != null ? response.body().string() : null;
            } catch (Exception ignored) {}
            
            android.util.Log.d("WsClient", "响应体长度: " + (body != null ? body.length() : 0));
            
            if (body != null && !body.isEmpty()) {
                sendSafe(body);
            } else {
                sendSafe(""); // 空响应也要返回，避免超时
            }
        }
    }

    private void sendSafe(String text) {
        if (webSocket == null) {
            android.util.Log.e("WsClient", "WebSocket为空，无法发送响应");
            return;
        }
        try {
            android.util.Log.d("WsClient", "发送响应: " + (text.length() > 100 ? text.substring(0, 100) + "..." : text));
            webSocket.send(text);
            android.util.Log.d("WsClient", "响应已发送");
        } catch (Exception e) {
            android.util.Log.e("WsClient", "发送响应失败: " + e.getMessage());
        }
    }

    // 检查连接状态（返回真实的连接状态）
    public boolean isConnected() {
        return connected && !stopped && webSocket != null;
    }
}
