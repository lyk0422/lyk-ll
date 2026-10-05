package com.example.starter.epidemic.domain;

/**
 * 协查结论：CONFIRMED 确认聚集 / EXCLUDED 排除聚集；结论回写聚集事件状态。
 */
public enum Conclusion {
    CONFIRMED,
    EXCLUDED
}
