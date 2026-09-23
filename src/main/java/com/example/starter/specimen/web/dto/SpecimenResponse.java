package com.example.starter.specimen.web.dto;

import java.math.BigDecimal;

/**
 * 样本响应：体积单位微升（三位小数），version 为当前乐观锁版本。
 */
public record SpecimenResponse(
        String specimenId,
        BigDecimal totalVolumeUl,
        BigDecimal remainingVolumeUl,
        String custodian,
        long version) {
}
