package com.example.starter.tailings.domain;

/**
 * 监测读数类型：RAINFALL 降雨(毫米)、WATER_LEVEL 水位(米)、
 * DRY_BEACH 干滩长度(米)、PHREATIC_LINE 浸润线埋深(米)。
 */
public enum ReadingType {

    RAINFALL,
    WATER_LEVEL,
    DRY_BEACH,
    PHREATIC_LINE
}
