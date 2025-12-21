package com.fongmi.android.tv.event;

import com.fongmi.android.tv.bean.Filter;

import java.util.List;

/**
 * 筛选项更新事件
 * 用于外部插件（jar爬虫）主动推送筛选项更新
 */
public class FiltersUpdateEvent {

    private final String typeId;
    private final List<Filter> filters;

    public FiltersUpdateEvent(String typeId, List<Filter> filters) {
        this.typeId = typeId;
        this.filters = filters;
    }

    public String getTypeId() {
        return typeId;
    }

    public List<Filter> getFilters() {
        return filters;
    }
}
