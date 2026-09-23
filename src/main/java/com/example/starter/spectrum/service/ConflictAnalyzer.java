package com.example.starter.spectrum.service;

import com.example.starter.spectrum.domain.ConflictDetail;
import com.example.starter.spectrum.domain.ConflictReport;
import com.example.starter.spectrum.domain.LeaseMode;
import com.example.starter.spectrum.domain.LeaseSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 三维（时间 × 频率 × 区域）半开区间冲突分析器（纯函数，无持久化依赖）。
 *
 * <p>相遇定义：至少一个区域单元相同，且时间、频率两个半开区间均有正长度交集，
 * 端点相接不冲突。冲突规则：
 * <ul>
 *   <li>候选与任一生效租约相遇，且其中任一为独占 → 独占冲突；</li>
 *   <li>全为共享时，对每个实际重叠的时间 × 频率 × 区域片段聚合生效租约 EIRP，
 *       任一片段总和超过上限 → 共享功率超限（逐片段判断，而非整段或成对总量）。</li>
 * </ul>
 */
public final class ConflictAnalyzer {

    /** 浮点聚合比较容差：总和恰好等于上限时允许。 */
    private static final double EPS = 1e-9;

    private ConflictAnalyzer() {
    }

    /**
     * 分析候选规格与生效租约集合的冲突。
     *
     * @param candidate 候选租约规格
     * @param candidateId 候选租约 ID（已存在租约时非 null；纯候选探测为 null）
     * @param candidateVersion 候选版本号（纯候选创建探测为 0）
     * @param effective 生效租约列表（时间区间已按取消时刻裁剪，且应排除被修改租约自身）
     * @param eirpLimitMw 共享片段 EIRP 总和上限 mW
     */
    public static ConflictReport analyze(LeaseSpec candidate,
                                         Long candidateId,
                                         int candidateVersion,
                                         List<EffectiveLease> effective,
                                         double eirpLimitMw) {
        List<ConflictDetail> conflicts = new ArrayList<>();

        for (String cell : candidate.cells()) {
            // 该区域内、与候选在时间和频率上均正长度相交的生效租约
            List<EffectiveLease> inCell = new ArrayList<>();
            for (EffectiveLease lease : effective) {
                if (!lease.cells().contains(cell)) {
                    continue;
                }
                if (!Intervals.overlaps(candidate.startTime(), candidate.endTime(),
                        lease.startTime(), lease.endTime())) {
                    continue;
                }
                if (!Intervals.overlaps(candidate.freqLowMhz(), candidate.freqHighMhz(),
                        lease.freqLowMhz(), lease.freqHighMhz())) {
                    continue;
                }
                inCell.add(lease);
            }
            if (inCell.isEmpty()) {
                continue;
            }

            // 时间轴扫描：候选边界 + 各相交租约边界
            TreeSet<Long> timeBounds = new TreeSet<>();
            timeBounds.add(candidate.startTime());
            timeBounds.add(candidate.endTime());
            for (EffectiveLease lease : inCell) {
                timeBounds.add(Math.max(lease.startTime(), candidate.startTime()));
                timeBounds.add(Math.min(lease.endTime(), candidate.endTime()));
            }
            List<Long> tb = new ArrayList<>(timeBounds);

            for (int i = 0; i + 1 < tb.size(); i++) {
                long segStart = tb.get(i);
                long segEnd = tb.get(i + 1);
                if (segStart >= segEnd) {
                    continue;
                }
                List<EffectiveLease> timeActive = new ArrayList<>();
                for (EffectiveLease lease : inCell) {
                    if (lease.startTime() <= segStart && lease.endTime() >= segEnd) {
                        timeActive.add(lease);
                    }
                }
                if (timeActive.isEmpty()) {
                    continue;
                }
                analyzeFrequencySegments(cell, segStart, segEnd, candidate, candidateId,
                        candidateVersion, timeActive, eirpLimitMw, conflicts);
            }
        }

        return new ConflictReport(conflicts.isEmpty(), conflicts);
    }

    /** 对一个时间片段内的频率轴做扫描并生成冲突明细。 */
    private static void analyzeFrequencySegments(String cell, long segStart, long segEnd,
                                                 LeaseSpec candidate, Long candidateId,
                                                 int candidateVersion,
                                                 List<EffectiveLease> timeActive,
                                                 double eirpLimitMw,
                                                 List<ConflictDetail> conflicts) {
        TreeSet<Double> freqBounds = new TreeSet<>();
        freqBounds.add(candidate.freqLowMhz());
        freqBounds.add(candidate.freqHighMhz());
        for (EffectiveLease lease : timeActive) {
            freqBounds.add(Math.max(lease.freqLowMhz(), candidate.freqLowMhz()));
            freqBounds.add(Math.min(lease.freqHighMhz(), candidate.freqHighMhz()));
        }
        List<Double> fb = new ArrayList<>(freqBounds);

        for (int i = 0; i + 1 < fb.size(); i++) {
            double fLow = fb.get(i);
            double fHigh = fb.get(i + 1);
            if (!(fLow < fHigh)) {
                continue;
            }
            List<EffectiveLease> bucket = new ArrayList<>();
            for (EffectiveLease lease : timeActive) {
                if (lease.freqLowMhz() <= fLow && lease.freqHighMhz() >= fHigh) {
                    bucket.add(lease);
                }
            }
            if (bucket.isEmpty()) {
                continue;
            }

            List<ConflictDetail.Contributor> contributors = new ArrayList<>();
            contributors.add(new ConflictDetail.Contributor(
                    candidateId, candidateVersion, candidate.mode(), candidate.eirpMw()));
            boolean anyExclusive = candidate.mode() == LeaseMode.EXCLUSIVE;
            double total = candidate.eirpMw();
            for (EffectiveLease lease : bucket) {
                contributors.add(new ConflictDetail.Contributor(
                        lease.leaseId(), lease.version(), lease.mode(), lease.eirpMw()));
                anyExclusive |= lease.mode() == LeaseMode.EXCLUSIVE;
                total += lease.eirpMw();
            }

            if (anyExclusive) {
                conflicts.add(new ConflictDetail("EXCLUSIVE_CONFLICT", cell, segStart, segEnd,
                        fLow, fHigh, null, null, contributors));
            } else if (total > eirpLimitMw + EPS) {
                conflicts.add(new ConflictDetail("SHARED_EIRP_EXCEEDED", cell, segStart, segEnd,
                        fLow, fHigh, total, eirpLimitMw, contributors));
            }
        }
    }

    /**
     * 生效租约（冲突分析输入）：时间区间已按取消时刻裁剪，
     * 被修改租约自身应由调用方排除。
     *
     * @param leaseId   租约 ID
     * @param version   版本号
     * @param startTime 生效时间半开区间起点（含），epoch 毫秒 UTC
     * @param endTime   生效时间半开区间终点（不含）
     * @param freqLowMhz  频率半开区间下界 MHz（含）
     * @param freqHighMhz 频率半开区间上界 MHz（不含）
     * @param mode      模式
     * @param eirpMw    EIRP mW
     * @param cells     区域单元集合
     */
    public record EffectiveLease(long leaseId,
                                 int version,
                                 long startTime,
                                 long endTime,
                                 double freqLowMhz,
                                 double freqHighMhz,
                                 LeaseMode mode,
                                 double eirpMw,
                                 SortedSet<String> cells) {

        /** 由版本视图与裁剪后的时间区间构造。 */
        public static EffectiveLease of(long leaseId, int version, LeaseSpec spec,
                                        long effStart, long effEnd, SortedSet<String> cells) {
            return new EffectiveLease(leaseId, version, effStart, effEnd,
                    spec.freqLowMhz(), spec.freqHighMhz(), spec.mode(), spec.eirpMw(), cells);
        }
    }
}
