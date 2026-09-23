package com.example.starter.spectrum.api;

import com.example.starter.spectrum.domain.ConflictReport;

/**
 * 冲突解释响应。
 *
 * @param leaseId 已存在租约做解释时返回其 ID；纯候选探测时为 {@code null}
 * @param report  冲突报告
 */
public record ConflictResponse(Long leaseId, ConflictReport report) {
}
