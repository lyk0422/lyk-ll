package com.example.starter.model;

/**
 * 缺陷分级：SURFACE 表面 / SANDWICH 夹层 / STRUCTURAL 结构。
 * 结构级缺陷登记后立即限功率并要求 7 日内完成维修。
 */
public enum DefectLevel {
    SURFACE,
    SANDWICH,
    STRUCTURAL
}
