package com.example.starter.forestry.domain;

/**
 * 林种：商品林 / 生态公益林。生态公益林禁止商业性采伐。
 */
public enum ForestCategory {
    /** 商品林 */
    COMMERCIAL,
    /** 生态公益林，采伐申请一律拒绝 */
    PUBLIC_WELFARE
}
