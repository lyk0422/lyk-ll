package com.example.starter.marpol.service;

/**
 * 合规判定结果。
 *
 * @param compliant 是否合规
 * @param detail    判定依据（命中区域、距最近陆地海里数等）
 */
public record ComplianceResult(boolean compliant, String detail) {
}
