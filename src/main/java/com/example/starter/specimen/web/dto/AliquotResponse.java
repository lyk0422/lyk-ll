package com.example.starter.specimen.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分装响应：aliquotIds 为本批生成的唯一分装标识；remainingVolumeUl 为分装后剩余量。
 */
public record AliquotResponse(
        String specimenId,
        List<String> aliquotIds,
        BigDecimal remainingVolumeUl,
        long version) {
}
