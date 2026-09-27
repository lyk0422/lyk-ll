package com.example.starter.cosmetic.domain;

/**
 * 单个分龄档的儿童备案材料（受理时冻结的清单单元）。
 *
 * @param bandCode  分龄档编码
 * @param report    该档独立的儿童安全报告
 * @param labelText 该档适用年龄标识文本
 */
public record ChildBandDossier(String bandCode,
                               ChildSafetyReportRecord report,
                               String labelText) {
}
