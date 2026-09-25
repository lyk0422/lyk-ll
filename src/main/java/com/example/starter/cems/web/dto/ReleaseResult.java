package com.example.starter.cems.web.dto;

/**
 * 复测解除结果：限排令关闭与解除在同一事务完成，resolvedMarks 为同步解除的有效超标标记数。
 */
public record ReleaseResult(OrderView order, OutfallView outfall, int resolvedMarks) {
}
