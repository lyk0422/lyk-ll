package com.example.starter.epidemic.domain;

/**
 * 合成暴露场所库行模型：报告卡现住址包含关键词即自动归并到该场所。
 */
public record ExposureSitePo(
        /** 场所编码。 */
        String code,
        /** 场所名称（合成）。 */
        String name,
        /** 地址归并关键词。 */
        String keyword) {
}
