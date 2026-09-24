package com.example.starter.ili.model;

/**
 * 缺陷深度增长分级。
 * STABLE：年增长率 &lt; 5%；GROWING：[5%, 15%)；SEVERE：&ge; 15%。
 */
public enum Grade {
    STABLE,
    GROWING,
    SEVERE
}
