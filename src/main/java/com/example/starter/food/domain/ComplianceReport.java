package com.example.starter.food.domain;

import java.util.List;

/**
 * 一份配方在指定标准版本下的完整合规报告；任一项违规则 compliant=false，整单拒绝。
 */
public record ComplianceReport(String standardVersion, List<ComplianceItem> items, boolean compliant) {
}
