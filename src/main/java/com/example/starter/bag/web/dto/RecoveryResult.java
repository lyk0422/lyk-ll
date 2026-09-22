package com.example.starter.bag.web.dto;

import java.util.List;

/**
 * 一次恢复操作的整体结果。
 *
 * @param segments 各受损分段的恢复明细
 */
public record RecoveryResult(List<SegmentRecoveryReport> segments) {
}
