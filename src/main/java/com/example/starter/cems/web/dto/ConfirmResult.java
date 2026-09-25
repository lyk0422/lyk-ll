package com.example.starter.cems.web.dto;

/**
 * 确认执行/升级扫描结果。escalated=true 表示恰满 2 小时未确认已升级并联动限产。
 */
public record ConfirmResult(OrderView order, boolean escalated) {
}
