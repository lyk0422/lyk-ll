package com.example.starter.weight.domain;

/**
 * 称重方式：动态称重 / 静态复检。
 */
public enum WeighMode {
    /**
     * 动态称重（不停车检测）。
     */
    DYNAMIC,
    /**
     * 静态复检（停车复磅，与动态不一致时以此为准）。
     */
    STATIC
}
