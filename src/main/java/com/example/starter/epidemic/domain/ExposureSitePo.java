package com.example.starter.epidemic.domain;

/**
 * 合成暴露场所库行。
 *
 * @param code           暴露场所编码
 * @param name           场所名称（合成）
 * @param addressKeyword 地址匹配关键词：报告卡现住址包含该词即归入本场所
 */
public record ExposureSitePo(String code, String name, String addressKeyword) {
}
