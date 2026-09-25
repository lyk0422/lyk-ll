package com.example.starter.survey.domain;

import java.time.LocalDate;

/**
 * 船舶（合成数据）。
 *
 * @param id                 主键
 * @param imo                IMO 编号，唯一
 * @param name               船名
 * @param annualAnniversary  年度检验周年基准日（UTC 日期，窗口按每年该日复算）
 * @param deliveryDate       交付/换证基准日（UTC 日期，用于中间、特别检验周期复算）
 */
public record Ship(
        Long id,
        String imo,
        String name,
        LocalDate annualAnniversary,
        LocalDate deliveryDate
) {
}
