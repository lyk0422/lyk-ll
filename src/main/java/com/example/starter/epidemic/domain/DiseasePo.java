package com.example.starter.epidemic.domain;

/**
 * 病种目录行（合成数据）。
 *
 * @param code     病种编码，业务主键
 * @param name     病种名称
 * @param category 病种分类，决定报告时限
 */
public record DiseasePo(String code, String name, DiseaseCategory category) {
}
