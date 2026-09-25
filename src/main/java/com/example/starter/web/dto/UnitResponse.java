package com.example.starter.web.dto;

import java.time.LocalDate;

import com.example.starter.domain.TestResultValue;
import com.example.starter.domain.UnitStatus;

/**
 * 成分血单位状态视图。
 */
public record UnitResponse(
        String barcode,
        UnitStatus status,
        TestResultValue initialResult,
        TestResultValue recheckResult,
        LocalDate expiryDate) {
}
