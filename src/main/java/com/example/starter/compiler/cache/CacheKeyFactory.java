package com.example.starter.compiler.cache;

import com.example.starter.compiler.api.DependencyInfo;

import java.util.List;
import java.util.Map;

/**
 * 缓存键工厂：聚合入口路径、全部传递依赖内容摘要与规范化参数。
 */
public interface CacheKeyFactory {

    /**
     * 计算缓存键。
     *
     * @param entryPath    入口相对工作目录的规范化路径
     * @param dependencies 全部传递依赖（含入口）
     * @param parameters   规范化后的参数覆盖
     */
    CacheKey create(String entryPath, List<DependencyInfo> dependencies, Map<String, String> parameters);
}
