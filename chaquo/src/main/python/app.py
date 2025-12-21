#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import json
import sys
from importlib.machinery import SourceFileLoader

try:
    import requests
except ImportError:
    requests = None


def spider(cache, api):
    """
    加载爬虫模块并返回 Spider 实例
    
    参数:
        cache: 缓存目录路径
        api: 爬虫代码（可以是 URL 或 Python 代码字符串）
    
    返回:
        Spider 实例（包含所有必要的方法）
    """
    try:
        # 确定文件名和路径
        if api.startswith('http://') or api.startswith('https://'):
            name = os.path.basename(api)
            path = os.path.join(cache, name)
        else:
            # 直接使用 api 作为 Python 代码
            name = 'spider.py'
            path = os.path.join(cache, name)
        
        # 写入文件
        try:
            os.makedirs(cache, exist_ok=True)
        except:
            pass
        
        # 下载或写入代码
        if api.startswith('http://') or api.startswith('https://'):
            if requests:
                try:
                    rsp = requests.get(api, timeout=10, verify=False)
                    rsp.encoding = 'utf-8'
                    code = rsp.text
                except:
                    code = api
            else:
                code = api
        else:
            code = api
        
        # 写入文件
        with open(path, 'w', encoding='utf-8') as f:
            f.write(code)
        
        # 加载模块
        module_name = os.path.splitext(name)[0]
        module = SourceFileLoader(module_name, path).load_module()
        
        # 返回 Spider 实例
        return module.Spider()
    
    except Exception as e:
        # 返回错误信息
        raise Exception(f"spider() 加载失败: {str(e)}")


# ============ Spider 接口方法包装 ============

def init(spider_obj, extend=''):
    """初始化爬虫"""
    try:
        if hasattr(spider_obj, 'init'):
            return spider_obj.init(extend)
    except:
        pass


def getDependence(spider_obj):
    """获取依赖"""
    try:
        if hasattr(spider_obj, 'getDependence'):
            return spider_obj.getDependence()
    except:
        pass
    return []


def getName(spider_obj):
    """获取爬虫名称"""
    try:
        if hasattr(spider_obj, 'getName'):
            return spider_obj.getName()
    except:
        pass
    return 'Unknown'


def homeContent(spider_obj, filter=False):
    """获取首页内容"""
    try:
        if hasattr(spider_obj, 'homeContent'):
            result = spider_obj.homeContent(filter)
            if isinstance(result, dict):
                return json.dumps(result, ensure_ascii=False)
            return str(result)
    except Exception as e:
        return json.dumps({"error": str(e)})
    return '{}'


def homeVideoContent(spider_obj):
    """获取首页视频内容"""
    try:
        if hasattr(spider_obj, 'homeVideoContent'):
            result = spider_obj.homeVideoContent()
            if isinstance(result, dict):
                return json.dumps(result, ensure_ascii=False)
            return str(result)
    except:
        pass
    return '{}'


def categoryContent(spider_obj, tid, pg, filter=False, extend=''):
    """获取分类内容"""
    try:
        if hasattr(spider_obj, 'categoryContent'):
            extend_dict = {}
            if extend and isinstance(extend, str):
                try:
                    extend_dict = json.loads(extend)
                except:
                    pass
            result = spider_obj.categoryContent(tid, pg, filter, extend_dict)
            if isinstance(result, dict):
                return json.dumps(result, ensure_ascii=False)
            return str(result)
    except Exception as e:
        return json.dumps({"error": str(e)})
    return '{}'


def detailContent(spider_obj, array):
    """获取详情内容"""
    try:
        if hasattr(spider_obj, 'detailContent'):
            vod_ids = array
            if isinstance(array, str):
                try:
                    vod_ids = json.loads(array)
                except:
                    vod_ids = [array]
            result = spider_obj.detailContent(vod_ids)
            if isinstance(result, dict):
                return json.dumps(result, ensure_ascii=False)
            return str(result)
    except:
        pass
    return '{}'


def searchContent(spider_obj, key, quick=''):
    """搜索内容"""
    try:
        if hasattr(spider_obj, 'searchContent'):
            result = spider_obj.searchContent(key, quick)
            if isinstance(result, dict):
                return json.dumps(result, ensure_ascii=False)
            return str(result)
    except:
        pass
    return '{}'


def playContent(spider_obj, flag, id, vipFlags):
    """获取播放内容"""
    try:
        if hasattr(spider_obj, 'playContent'):
            result = spider_obj.playContent(flag, id, vipFlags)
            if isinstance(result, dict):
                return json.dumps(result, ensure_ascii=False)
            return str(result)
    except:
        pass
    return '{}'


def playerContent(spider_obj, flag, id, vipFlags):
    """获取播放器内容（别名）"""
    return playContent(spider_obj, flag, id, vipFlags)
