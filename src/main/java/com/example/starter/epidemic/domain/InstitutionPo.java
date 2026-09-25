package com.example.starter.epidemic.domain;

/**
 * 医疗机构目录行（合成数据）。
 *
 * @param code 机构编码，业务主键
 * @param name 机构名称
 */
public record InstitutionPo(String code, String name) {
}
