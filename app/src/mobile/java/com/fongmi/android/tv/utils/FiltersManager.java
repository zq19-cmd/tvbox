package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.event.FiltersUpdateEvent;
import com.google.gson.reflect.TypeToken;

import org.greenrobot.eventbus.EventBus;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 筛选项管理器
 * 提供给外部jar爬虫调用的接口，用于动态更新筛选项
 */
public class FiltersManager {

    /**
     * 从JSON字符串发布筛选项更新（供jar爬虫调用）
     * @param typeId 分类ID
     * @param filtersJson 筛选项JSON数组字符串
     */
    public static void publishFromJson(String typeId, String filtersJson) {
        try {
            Type listType = new TypeToken<List<Filter>>() {}.getType();
            List<Filter> filters = App.gson().fromJson(filtersJson, listType);
            if (filters != null && !filters.isEmpty()) {
                EventBus.getDefault().post(new FiltersUpdateEvent(typeId, filters));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 直接发布筛选项更新
     * @param typeId 分类ID
     * @param filters 筛选项列表
     */
    public static void publish(String typeId, List<Filter> filters) {
        if (typeId != null && filters != null && !filters.isEmpty()) {
            EventBus.getDefault().post(new FiltersUpdateEvent(typeId, filters));
        }
    }
}
