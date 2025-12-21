package com.fongmi.android.tv.server.process;

import android.text.TextUtils;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fi.iki.elonen.NanoHTTPD;

import java.util.List;
import java.util.Map;

/**
 * 站源切换菜单接口
 * 提供一个伪装成采集站API的菜单，用于在播放器内切换数据源
 */
public class SiteMenu implements Process {

    private static final String CATEGORY_NAME = "站源切换";
    // 使用 dummyimage.com，尺寸 450x600 (3:4比例)，浅黄色背景+深色文字
    private static final String PLACEHOLDER_IMAGE_API = "https://dummyimage.com/450x600/fef3c7/374151&text=";
    
    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return url.startsWith("/vod/menu");
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String ac = params.get("ac");
        
        try {
            if (TextUtils.isEmpty(ac) || "list".equals(ac)) {
                // 返回首页，显示"站源切换"分类
                return handleList(params);
            } else if ("detail".equals(ac)) {
                // 处理站源切换操作
                return handleDetail(params);
            } else {
                return createErrorResponse(400, "不支持的操作");
            }
        } catch (Exception e) {
            return createErrorResponse(500, "处理失败：" + e.getMessage());
        }
    }

    /**
     * 处理列表请求 - 返回所有站源作为视频列表
     */
    private NanoHTTPD.Response handleList(Map<String, String> params) {
        String t = params.get("t");
        
        // 获取所有站源（每次都从配置中重新读取，不缓存）
        List<Site> sites = VodConfig.get().getSites();
        
        JsonObject response = new JsonObject();
        response.addProperty("code", 1);
        response.addProperty("msg", "数据列表");
        response.addProperty("page", 1);
        response.addProperty("pagecount", 1);
        response.addProperty("limit", sites.size());
        response.addProperty("total", sites.size());
        
        // 分类列表
        JsonArray classArray = new JsonArray();
        JsonObject classObj = new JsonObject();
        classObj.addProperty("type_id", "1");
        classObj.addProperty("type_name", CATEGORY_NAME);
        classArray.add(classObj);
        response.add("class", classArray);
        
        // 视频列表
        JsonArray listArray = new JsonArray();
        
        // 如果指定了分类(t=1)或者没有指定分类，都返回站源列表
        if (TextUtils.isEmpty(t) || "1".equals(t)) {
            for (int i = 0; i < sites.size(); i++) {
                Site site = sites.get(i);
                JsonObject videoObj = new JsonObject();
                
                // 使用站源的key作为唯一ID
                videoObj.addProperty("vod_id", site.getKey());
                videoObj.addProperty("vod_name", site.getName());
                videoObj.addProperty("type_id", "1");
                videoObj.addProperty("type_name", CATEGORY_NAME);
                
                // 使用占位图片API，显示序号
                String picUrl = PLACEHOLDER_IMAGE_API + (i + 1);
                videoObj.addProperty("vod_pic", picUrl);
                videoObj.addProperty("vod_remarks", "点击切换");
                
                listArray.add(videoObj);
            }
        }
        
        response.add("list", listArray);
        
        String json = response.toString();
        NanoHTTPD.Response resp = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            json
        );
        
        // 添加缓存控制头，禁止缓存
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.addHeader("Pragma", "no-cache");
        resp.addHeader("Expires", "0");
        
        return resp;
    }

    /**
     * 处理详情请求 - 执行站源切换
     */
    private NanoHTTPD.Response handleDetail(Map<String, String> params) {
        String ids = params.get("ids");
        
        if (TextUtils.isEmpty(ids)) {
            return createErrorResponse(400, "缺少ids参数");
        }
        
        // 查找目标站源
        List<Site> sites = getSites();
        Site targetSite = null;
        for (Site site : sites) {
            if (ids.equals(site.getKey())) {
                targetSite = site;
                break;
            }
        }
        
        if (targetSite == null) {
            return createErrorResponse(404, "未找到指定的站源");
        }
        
        // 执行切换
        String oldSiteKey = Prefers.getString("api_current_site", "");
        Prefers.put("api_current_site", targetSite.getKey());
        
        // 构造详情响应
        JsonObject response = new JsonObject();
        response.addProperty("code", 1);
        response.addProperty("msg", "数据列表");
        
        JsonArray listArray = new JsonArray();
        JsonObject videoObj = new JsonObject();
        
        videoObj.addProperty("vod_id", targetSite.getKey());
        videoObj.addProperty("vod_name", "切换成功");
        videoObj.addProperty("type_id", "1");
        videoObj.addProperty("type_name", CATEGORY_NAME);
        videoObj.addProperty("vod_pic", "https://dummyimage.com/450x600/fde68a/374151&text=OK");
        videoObj.addProperty("vod_content", "已将数据源切换到：" + targetSite.getName());
        videoObj.addProperty("vod_remarks", "切换成功");
        
        // 添加一个假的播放地址（避免播放器报错）
        videoObj.addProperty("vod_play_from", "提示");
        videoObj.addProperty("vod_play_url", "切换成功$" + "data:text/plain;base64,5oiQ5YqfIQ==");
        
        listArray.add(videoObj);
        response.add("list", listArray);
        
        String json = response.toString();
        NanoHTTPD.Response resp = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            json
        );
        
        // 添加缓存控制头，禁止缓存
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.addHeader("Pragma", "no-cache");
        resp.addHeader("Expires", "0");
        
        return resp;
    }

    /**
     * 获取所有站源列表
     */
    private List<Site> getSites() {
        // 从配置中获取站源列表
        return VodConfig.get().getSites();
    }

    /**
     * 创建错误响应
     */
    private NanoHTTPD.Response createErrorResponse(int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("msg", message);
        
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            error.toString()
        );
    }
}
