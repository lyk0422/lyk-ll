package com.example.starter.spectrum;

import java.math.BigDecimal;

/**
 * 冲突/超限解释：描述一个实际重叠的时间×频率×区域片段。
 *
 * @param reason      原因代码，如 EXCLUSIVE_OVERLAP / SHARED_EIRP_EXCEEDED
 * @param leaseId     与之冲突的对方租约标识（解释时可能有多条）
 * @param regionCell  发生冲突的区域单元
 * @param overlapStart 重叠片段开始时刻（UTC，含）
 * @param overlapEnd   重叠片段结束时刻（UTC，不含）
 * @param overlapLowMHz  重叠频段下限 MHz（含）
 * @param overlapHighMHz 重叠频段上限 MHz（不含）
 * @param totalEirpMw   该片段内生效 EIRP 总和（仅超限时有意义）
 * @param limitMw       配置的共享 EIRP 上限
 */
public record ConflictDetail(
        String reason,
        String leaseId,
        String regionCell,
        java.time.Instant overlapStart,
        java.time.Instant overlapEnd,
        BigDecimal overlapLowMHz,
        BigDecimal overlapHighMHz,
        BigDecimal totalEirpMw,
        BigDecimal limitMw) {
}
