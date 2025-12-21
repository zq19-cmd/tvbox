package main

import (
    "encoding/json"
    "flag"
    "fmt"
    "log"
    "net"
    "net/http"
    "os"
    "strings"
    "sync"
    "time"

    "github.com/gorilla/websocket"
)

var (
    allowMulti bool
    timeout    time.Duration
    logfile    string
    showHelp   bool
    serverIP   string
    upgrader   = websocket.Upgrader{
        CheckOrigin: func(r *http.Request) bool { return true }, // 允许所有来源
    }
    clients   = make(map[string]*websocket.Conn)
    mu        sync.RWMutex
    connLocks = make(map[string]*sync.Mutex) // 每个连接的锁
    logger    *log.Logger
    startTime = time.Now()
    version   = "1.0.1"
)

// 从 WebSocket 握手请求中获取客户端 ID
// 客户端需要在 URL 参数中传递 client_id: ws://server:1189/ws?client_id=abc123
func getClientID(r *http.Request) string {
    // 从 URL 参数获取客户端 ID
    id := r.URL.Query().Get("client_id")
    if id != "" {
        return strings.TrimSpace(id)
    }
    
    // 兼容：从自定义 Header 获取
    id = r.Header.Get("X-Client-ID")
    if id != "" {
        return strings.TrimSpace(id)
    }
    
    return ""
}

// 获取本机局域网IP地址（排除虚拟网卡）
func getLocalIP() string {
    interfaces, err := net.InterfaceAddrs()
    if err != nil {
        return "127.0.0.1"
    }

    // 用于存储候选IP，按优先级排序
    type candidate struct {
        ip       string
        priority int // 数字越小优先级越高
    }
    var candidates []candidate

    for _, addr := range interfaces {
        ipNet, ok := addr.(*net.IPNet)
        if !ok {
            continue
        }

        ip := ipNet.IP.To4()
        if ip == nil {
            continue // 跳过 IPv6
        }

        ipStr := ip.String()

        // 跳过回环地址和特殊IP段
        if ip[0] == 127 ||
            (ip[0] == 169 && ip[1] == 254) ||
            (ip[0] == 198 && (ip[1] == 18 || ip[1] == 19)) ||
            ip[0] >= 224 {
            continue
        }

        // 检查是否为私有网段 IP
        isPrivate := ip[0] == 10 ||
            (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) ||
            (ip[0] == 192 && ip[1] == 168)

        if !isPrivate {
            continue // 只考虑私有IP
        }

        // 确定优先级
        priority := 100

        // 192.168.x.x 最高优先级
        if ip[0] == 192 && ip[1] == 168 {
            priority = 1
        } else if ip[0] == 10 {
            // 10.x.x.x 次之
            priority = 5
        } else if ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31 {
            // 172.16-31.x.x 最低
            priority = 10
        }

        candidates = append(candidates, candidate{ip: ipStr, priority: priority})
    }

    // 按优先级排序并返回最高优先级的IP
    if len(candidates) > 0 {
        bestCandidate := candidates[0]
        for _, c := range candidates {
            if c.priority < bestCandidate.priority {
                bestCandidate = c
            }
        }
        return bestCandidate.ip
    }

    return "127.0.0.1"
}

// WS连接入口
func wsHandler(w http.ResponseWriter, r *http.Request) {
    conn, err := upgrader.Upgrade(w, r, nil)
    if err != nil {
        log.Println("WS升级失败:", err)
        return
    }

    mu.Lock()
    
    // 单客户端模式使用固定ID "api"，多客户端模式直接使用客户端提供的ID
    var id string
    var isReconnect bool
    
    if allowMulti {
        // 多客户端模式：使用客户端自己生成的 ID
        id = getClientID(r)
        
        if id == "" {
            // 客户端没有提供 ID，拒绝连接
            mu.Unlock()
            conn.WriteMessage(websocket.TextMessage, []byte(`{"code":400,"msg":"缺少 client_id 参数","tip":"请在 URL 中添加 ?client_id=xxx"}`))
            conn.Close()
            log.Println("❌ 拒绝连接: 客户端未提供 ID")
            return
        }
        
        // 检查是否是重连（ID 已存在）
        if oldConn, exists := clients[id]; exists {
            isReconnect = true
            log.Printf("⚠ 客户端 %s 重连，关闭旧连接", id)
            oldConn.Close()
        }
    } else {
        // 单客户端模式：固定使用 "api"
        id = "api"
        
        // 检查是否已有连接
        if oldConn, exists := clients[id]; exists {
            isReconnect = true
            log.Printf("⚠ 单客户端模式重连，关闭旧连接")
            oldConn.Close()
        }
    }
    
    clients[id] = conn
    connLock := &sync.Mutex{}
    connLocks[id] = connLock // 为新连接创建锁
    count := len(clients)
    mu.Unlock()

    if isReconnect {
        log.Printf("✓ 客户端重连: %s，当前在线: %d", id, count)
    } else {
        log.Printf("✓ 新客户端连接: %s，当前在线: %d", id, count)
    }

    // 返回专属HTTP入口（JSON格式） - 加锁保护
    // 注意：单客户端模式下，服务器会强制使用 "api" 作为 ID
    response := map[string]interface{}{
        "code":        200,
        "msg":         "连接成功",
        "client_id":   id,                                         // 服务器实际使用的 ID
        "http_url":    fmt.Sprintf("http://server/%s", id),
        "reconnect":   isReconnect,
        "multi_mode":  allowMulti,                                 // 告知客户端服务器模式
        "server_mode": func() string { if allowMulti { return "multi" } else { return "single" } }(),
    }
    respJSON, _ := json.Marshal(response)
    connLock.Lock()
    conn.WriteMessage(websocket.TextMessage, respJSON)
    connLock.Unlock()
    
    // 设置断开回调
    conn.SetCloseHandler(func(code int, text string) error {
        mu.Lock()
        delete(clients, id)
        delete(connLocks, id)
        mu.Unlock()
        log.Printf("✗ 客户端断开: %s (code: %d)", id, code)
        return nil
    })
    
    // 设置 Pong 处理器(自动响应 Ping)
    conn.SetPongHandler(func(string) error {
        conn.SetReadDeadline(time.Now().Add(90 * time.Second))
        return nil
    })
    
    // 启动心跳检测 goroutine (使用 channel 控制退出)
    heartbeatDone := make(chan struct{})
    go func() {
        ticker := time.NewTicker(30 * time.Second)
        defer ticker.Stop()
        defer close(heartbeatDone)
        
        for {
            select {
            case <-ticker.C:
                // 检查连接是否还在 clients map 中
                mu.RLock()
                _, exists := clients[id]
                mu.RUnlock()
                
                if !exists {
                    // 连接已被移除,退出心跳检测
                    return
                }
                
                // 发送 Ping 帧检查连接是否活跃
                connLock.Lock()
                err := conn.WriteControl(websocket.PingMessage, []byte{}, time.Now().Add(10*time.Second))
                connLock.Unlock()
                
                if err != nil {
                    // Ping 失败,连接已断开,清理资源
                    mu.Lock()
                    if _, stillExists := clients[id]; stillExists {
                        delete(clients, id)
                        delete(connLocks, id)
                        log.Printf("✗ 客户端心跳检测失败，已断开: %s", id)
                    }
                    mu.Unlock()
                    conn.Close()
                    return
                }
            }
        }
    }()
}



// 默认首页
func indexHandler(w http.ResponseWriter, r *http.Request) {
    mu.RLock()
    count := len(clients)
    clientIDs := make([]string, 0, len(clients))
    for id := range clients {
        clientIDs = append(clientIDs, id)
    }
    mu.RUnlock()

    uptime := time.Since(startTime).Round(time.Second).String()

    w.Header().Set("Content-Type", "text/html; charset=utf-8")
    fmt.Fprintf(w, `<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>影视+转发服务</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: 'Microsoft YaHei', 'Segoe UI', Arial, sans-serif; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 20px; }
        .container { background: rgba(255,255,255,0.95); border-radius: 20px; box-shadow: 0 20px 60px rgba(0,0,0,0.3); max-width: 800px; width: 100%%; padding: 40px; }
        h1 { color: #333; font-size: 28px; margin-bottom: 10px; text-align: center; }
        .subtitle { color: #666; text-align: center; margin-bottom: 30px; font-size: 14px; }
        .status { display: flex; align-items: center; justify-content: center; gap: 15px; margin-bottom: 30px; padding: 20px; background: #f8f9fa; border-radius: 10px; }
        .status-item { text-align: center; }
        .status-label { color: #666; font-size: 12px; margin-bottom: 5px; }
        .status-value { color: #27ae60; font-size: 24px; font-weight: bold; }
        .status-value.offline { color: #e74c3c; }
        .section { margin-bottom: 25px; }
        .section-title { color: #333; font-size: 18px; margin-bottom: 15px; padding-bottom: 10px; border-bottom: 2px solid #667eea; }
        .info-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; }
        .info-card { background: #f8f9fa; padding: 15px; border-radius: 8px; border-left: 4px solid #667eea; }
        .info-label { color: #666; font-size: 12px; margin-bottom: 5px; }
        .info-value { color: #333; font-size: 16px; font-weight: 500; word-break: break-all; }
        .clients-list { background: #f8f9fa; padding: 15px; border-radius: 8px; max-height: 150px; overflow-y: auto; }
        .client-id { display: inline-block; background: #667eea; color: white; padding: 5px 12px; border-radius: 20px; margin: 5px; font-size: 14px; font-family: monospace; }
        .usage-box { background: #fff3cd; border: 1px solid #ffc107; border-radius: 8px; padding: 15px; margin-top: 20px; }
        .usage-title { color: #856404; font-size: 14px; font-weight: bold; margin-bottom: 10px; }
        .usage-example { background: #f8f9fa; padding: 10px; border-radius: 5px; font-family: 'Courier New', monospace; font-size: 13px; color: #333; margin-bottom: 10px; overflow-x: auto; white-space: nowrap; }
        .tips { background: #d4edda; border: 1px solid #28a745; border-radius: 8px; padding: 15px; margin-top: 20px; }
        .tips-title { color: #155724; font-size: 14px; font-weight: bold; margin-bottom: 10px; }
        .tips-item { color: #155724; font-size: 13px; margin-bottom: 8px; padding-left: 20px; position: relative; }
        .tips-item:before { content: '✓'; position: absolute; left: 0; color: #28a745; font-weight: bold; }
        .footer { text-align: center; margin-top: 30px; color: #666; font-size: 12px; }
        @media (max-width: 600px) { .container { padding: 20px; } h1 { font-size: 22px; } .info-grid { grid-template-columns: 1fr; } }
    </style>
</head>
<body>
    <div class="container">
        <h1>🎬 影视+转发服务</h1>
        <div class="subtitle">WebSocket to HTTP Proxy Server v%s</div>
        
        <div class="status">
            <div class="status-item">
                <div class="status-label">在线客户端</div>
                <div class="status-value%s">%d</div>
            </div>
            <div class="status-item">
                <div class="status-label">运行时长</div>
                <div class="status-value">%s</div>
            </div>
        </div>

        <div class="section">
            <div class="section-title">📊 服务信息</div>
            <div class="info-grid">
                <div class="info-card">
                    <div class="info-label">HTTP端口</div>
                    <div class="info-value">1189</div>
                </div>
                <div class="info-card">
                    <div class="info-label">WebSocket端口</div>
                    <div class="info-value">/ws</div>
                </div>
                <div class="info-card">
                    <div class="info-label">超时设置</div>
                    <div class="info-value">%v</div>
                </div>
                <div class="info-card">
                    <div class="info-label">多客户端</div>
                    <div class="info-value">%s</div>
                </div>
            </div>
        </div>

        <div class="section">
            <div class="section-title">💻 客户端列表</div>
            <div class="clients-list">%s</div>
        </div>

        <div class="usage-box">
            <div class="usage-title">📖 使用说明</div>
            <div style="color: #856404; font-size: 13px; margin-bottom: 10px;">
                1. 客户端连接到 WebSocket 后会获得专属ID<br>
                2. 通过 HTTP 访问: <code style="background: #fff; padding: 2px 6px; border-radius: 3px;">http://服务器:1189/{客户端ID}/接口路径</code>
            </div>
            <div class="usage-example">GET http://服务器:1189/abc123/vod/api?ac=list</div>
        </div>

        <div class="tips">
            <div class="tips-title">💡 优化建议</div>
            <div class="tips-item">使用 <code>--timeout=10s</code> 调整响应超时时间</div>
            <div class="tips-item">使用 <code>--multi</code> 允许多个客户端同时连接</div>
            <div class="tips-item">使用 <code>--logfile=server.log</code> 记录详细请求日志</div>
            <div class="tips-item">访问 <code>/health</code> 查看JSON格式的健康状态</div>
            <div class="tips-item">访问 <code>/clients</code> 查看JSON格式的客户端列表</div>
        </div>

        <div class="footer">
            Power by Go + Gorilla WebSocket | Version %s
        </div>
    </div>
</body>
</html>`, version /* 第1个 %s */, func() string {
        if count == 0 {
            return " offline"
        }
        return ""
    }() /* 第2个 %s */, count /* %d */, uptime /* 第3个 %s */, timeout /* %v */, func() string {
        if allowMulti {
            return "已启用"
        }
        return "未启用"
    }() /* 第4个 %s */, func() string {
        if count == 0 {
            return `<div style="text-align: center; color: #999;">暂无客户端连接</div>`
        }
        result := ""
        for _, id := range clientIDs {
            result += fmt.Sprintf(`<span class="client-id">%s</span>`, id)
        }
        return result
    }() /* 第5个 %s */, version /* 第6个 %s */)
}

// HTTP转发入口
func httpHandler(w http.ResponseWriter, r *http.Request) {
    defer func() {
        if err := recover(); err != nil {
            log.Printf("❌ httpHandler panic: %v", err)
            w.Header().Set("Content-Type", "application/json; charset=utf-8")
            w.WriteHeader(http.StatusInternalServerError)
            fmt.Fprintf(w, `{"code":500,"msg":"服务器内部错误"}`)
        }
    }()
    
    // 根路径显示首页
    if r.URL.Path == "/" {
        indexHandler(w, r)
        return
    }

    // 排除特殊路径
    specialPaths := []string{"/ws", "/clients", "/health", "/proxy_url"}
    for _, path := range specialPaths {
        if r.URL.Path == path {
            w.Header().Set("Content-Type", "application/json; charset=utf-8")
            w.WriteHeader(http.StatusNotFound)
            fmt.Fprintf(w, `{"code":404,"msg":"路径不存在"}`)
            return
        }
    }

    var clientID string
    var rawURL string

    // 单客户端模式: 直接转发所有路径,原封不动
    if !allowMulti {
        clientID = "api"
        // 使用 RequestURI 获取完整的原始请求 URI (包含所有格式和编码)
        rawURL = r.RequestURI
    } else {
        // 多客户端模式: 需要提取客户端ID,然后转发剩余部分
        path := r.URL.Path
        parts := splitPath(path)
        if len(parts) < 2 {
            w.Header().Set("Content-Type", "application/json")
            fmt.Fprintf(w, `{"code":400,"msg":"缺少客户端ID","tip":"正确格式: /客户端ID/接口路径"}`)
            return
        }
        clientID = parts[0]
        // 从原始 URI 中去掉客户端ID部分,保留剩余的原始格式
        rawURL = "/" + parts[1]
        if r.URL.RawQuery != "" {
            rawURL += "?" + r.URL.RawQuery
        }
    }

    mu.RLock()
    conn, ok := clients[clientID]
    connLock, lockOk := connLocks[clientID]
    mu.RUnlock()

    // 判断客户端是否在线
    if !ok || !lockOk || conn == nil {
        w.Header().Set("Content-Type", "application/json; charset=utf-8")
        w.Header().Set("Access-Control-Allow-Origin", "*")
        w.WriteHeader(http.StatusNotFound)
        fmt.Fprintf(w, `{"code":404,"msg":"客户端未连接或不存在"}`)
        logRequest(clientID, rawURL, "客户端未连接")
        return
    }

    // 发给WS客户端 - 加锁保护
    connLock.Lock()
    err := conn.WriteMessage(websocket.TextMessage, []byte(rawURL))
    connLock.Unlock()
    
    if err != nil {
        w.Header().Set("Content-Type", "application/json; charset=utf-8")
        w.Header().Set("Access-Control-Allow-Origin", "*")
        w.WriteHeader(http.StatusInternalServerError)
        fmt.Fprintf(w, `{"code":500,"msg":"转发失败"}`)
        logRequest(clientID, rawURL, "转发失败")
        return
    }

    // 等待WS回复
    ch := make(chan string, 1)
    errCh := make(chan error, 1)
    go func() {
        connLock.Lock()
        _, msg, readErr := conn.ReadMessage()
        connLock.Unlock()
        
        if readErr == nil {
            ch <- string(msg)
        } else {
            errCh <- readErr
        }
    }()

    select {
    case resp := <-ch:
        // 根据响应内容自动判断Content-Type
        contentType := detectContentType(resp)
        w.Header().Set("Content-Type", contentType)
        w.Header().Set("Access-Control-Allow-Origin", "*")
        w.WriteHeader(http.StatusOK)
        fmt.Fprint(w, resp)
        logRequest(clientID, rawURL, fmt.Sprintf("成功响应: %d bytes [%s]", len(resp), contentType))
    case readErr := <-errCh:
        // 读取错误,清理连接(检查是否还存在,避免重复删除)
        mu.Lock()
        if _, stillExists := clients[clientID]; stillExists {
            delete(clients, clientID)
            delete(connLocks, clientID)
            log.Printf("✗ 客户端读取错误: %s, 错误: %v", clientID, readErr)
        }
        mu.Unlock()
        
        w.Header().Set("Content-Type", "application/json; charset=utf-8")
        w.Header().Set("Access-Control-Allow-Origin", "*")
        w.WriteHeader(http.StatusGone)
        fmt.Fprintf(w, `{"code":410,"msg":"客户端连接已断开"}`)
        logRequest(clientID, rawURL, "客户端读取错误")
    case <-time.After(timeout):
        w.Header().Set("Content-Type", "application/json; charset=utf-8")
        w.Header().Set("Access-Control-Allow-Origin", "*")
        w.WriteHeader(http.StatusGatewayTimeout)
        fmt.Fprintf(w, `{"code":408,"msg":"数据响应超时","tip":"可使用 --timeout 参数调整超时时间"}`)
        logRequest(clientID, rawURL, "数据响应超时")
    }
}

// 客户端列表接口
func clientsHandler(w http.ResponseWriter, r *http.Request) {
    mu.RLock()
    defer mu.RUnlock()

    ids := make([]string, 0, len(clients))
    for id := range clients {
        ids = append(ids, id)
    }

    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(map[string]interface{}{
        "online_clients": ids,
        "count":          len(ids),
    })
}

// 健康检查接口
func healthHandler(w http.ResponseWriter, r *http.Request) {
    mu.RLock()
    count := len(clients)
    mu.RUnlock()

    uptime := time.Since(startTime).String()
    resp := map[string]interface{}{
        "status":         "ok",
        "online_clients": count,
        "uptime":         uptime,
        "version":        version,
    }

    w.Header().Set("Content-Type", "application/json; charset=utf-8")
    json.NewEncoder(w).Encode(resp)
}

// 透传地址获取接口
func proxyUrlHandler(w http.ResponseWriter, r *http.Request) {
    mu.RLock()
    var clientID string
    var proxyURL string
    count := len(clients)
    
    if count > 0 {
        // 获取第一个客户端ID
        for id := range clients {
            clientID = id
            break
        }
        
        // 实时检测模式：根据客户端ID判断是否为单客户端模式
        isSingleClient := (clientID == "api")
        
        // 构建透传地址
        if isSingleClient {
            // 单客户端模式: 直接使用服务器地址,不需要ID前缀
            proxyURL = fmt.Sprintf("http://%s:1189/", serverIP)
        } else {
            // 多客户端模式: 需要客户端ID前缀
            proxyURL = fmt.Sprintf("http://%s:1189/%s/", serverIP, clientID)
        }
    }
    mu.RUnlock()

    resp := map[string]interface{}{
        "connected":     count > 0,
        "client_id":     clientID,
        "proxy_url":     proxyURL,
        "multi_mode":    allowMulti,
        "client_count":  count,
        "server_ip":     serverIP,
    }

    w.Header().Set("Content-Type", "application/json; charset=utf-8")
    json.NewEncoder(w).Encode(resp)
}

// 辅助函数：拆分路径
func splitPath(path string) []string {
    if len(path) > 1 {
        path = path[1:]
    }
    return splitOnce(path, "/")
}

func splitOnce(s, sep string) []string {
    i := findSep(s, sep)
    if i >= 0 {
        // 找到分隔符,分割成两部分
        return []string{s[:i], s[i+1:]}
    }
    // 没找到分隔符,返回整个字符串和空字符串
    return []string{s, ""}
}

func findSep(s, sep string) int {
    for i := 0; i < len(s); i++ {
        if s[i] == sep[0] {
            return i
        }
    }
    return -1
}

// 检测内容类型
func detectContentType(content string) string {
    trimmed := strings.TrimSpace(content)
    
    // 判断是否为空
    if len(trimmed) == 0 {
        return "text/plain; charset=utf-8"
    }
    
    // 判断 JSON 格式
    if (trimmed[0] == '{' && trimmed[len(trimmed)-1] == '}') || 
       (trimmed[0] == '[' && trimmed[len(trimmed)-1] == ']') {
        return "application/json; charset=utf-8"
    }
    
    // 判断 HTML 格式
    if strings.HasPrefix(strings.ToLower(trimmed), "<!doctype html") ||
       strings.HasPrefix(strings.ToLower(trimmed), "<html") ||
       strings.HasPrefix(strings.ToLower(trimmed), "<?xml") {
        return "text/html; charset=utf-8"
    }
    
    // 判断 M3U8 播放列表
    if strings.HasPrefix(trimmed, "#EXTM3U") {
        return "application/vnd.apple.mpegurl; charset=utf-8"
    }
    
    // 判断 XML 格式
    if strings.HasPrefix(trimmed, "<?xml") || strings.HasPrefix(trimmed, "<") {
        return "application/xml; charset=utf-8"
    }
    
    // 默认为纯文本
    return "text/plain; charset=utf-8"
}

// 日志记录函数
func logRequest(clientID, rawURL, result string) {
    if logger != nil {
        logger.Printf("客户端[%s] 请求: %s | 结果: %s\n", clientID, rawURL, result)
    }
}

func main() {
    flag.BoolVar(&allowMulti, "multi", false, "是否允许多客户端连接")
    flag.DurationVar(&timeout, "timeout", 5*time.Second, "HTTP请求等待WS响应超时时间")
    flag.StringVar(&logfile, "logfile", "", "日志文件路径(默认不记录日志)")
    flag.BoolVar(&showHelp, "help", false, "显示帮助信息")
    flag.Parse()

    // 初始化服务器IP地址
    serverIP = getLocalIP()

    if showHelp {
        fmt.Println("ys 使用说明:\n")
        fmt.Println("命令行参数:")
        fmt.Println("  ./ys                # 默认模式: 单客户端, 超时5秒, 不记录日志")
        fmt.Println("  ./ys --multi        # 允许多客户端连接")
        fmt.Println("  ./ys --timeout=10s  # 设置超时为10秒")
        fmt.Println("  ./ys --logfile=server.log  # 开启日志记录到 server.log")
        fmt.Println("  ./ys --multi --timeout=10s --logfile=server.log  # 多客户端, 超时10秒, 日志记录\n")
        fmt.Println("HTTP 接口:")
        fmt.Println("  /ws         # WebSocket 客户端连接入口, 成功后返回专属HTTP访问地址")
        fmt.Println("  /{id}/...   # HTTP请求入口, 根据客户端ID转发请求给对应WS客户端")
        fmt.Println("               示例: http://server:1189/abc123/vod/api?site=文件播放2&extend=http://123.xyz/ys/js.json")
        fmt.Println("  /clients    # 查看当前在线设备(客户端)列表, 返回JSON")
        fmt.Println("  /health     # 服务健康检查接口, 返回JSON: {status, online_clients, uptime, version}")
        os.Exit(0)
    }

    if logfile != "" {
        f, err := os.OpenFile(logfile, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
        if err != nil {
            log.Fatal("无法打开日志文件:", err)
        }
        logger = log.New(f, "", log.LstdFlags)
        log.Println("日志记录已开启:", logfile)
    }

    http.HandleFunc("/ws", wsHandler)
    http.HandleFunc("/clients", clientsHandler)
    http.HandleFunc("/health", healthHandler)
    http.HandleFunc("/proxy_url", proxyUrlHandler)
    http.HandleFunc("/", httpHandler)

    log.Println("========================================")
    log.Println("影视+转发服务已启动")
    log.Println("========================================")
    log.Printf("HTTP服务: http://%s:1189", serverIP)
    log.Printf("WebSocket: ws://%s:1189/ws", serverIP)
    log.Printf("⏱超时设置: %v", timeout)
    log.Printf("多客户端: %v", allowMulti)
    if logfile != "" {
        log.Printf("日志文件: %s", logfile)
    } else {
        log.Println("日志文件: 未启用")
    }
    log.Println("========================================")
    log.Printf("访问 http://%s:1189 查看管理界面", serverIP)
    log.Println("使用 --help 查看完整帮助信息")
    log.Println("========================================")
    
    log.Fatal(http.ListenAndServe(":1189", nil))
}