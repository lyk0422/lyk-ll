package com.example.starter.spectrum.service;

import com.example.starter.spectrum.ConflictDetail;
import com.example.starter.spectrum.LeaseMode;
import com.example.starter.spectrum.domain.ActiveLease;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 三维（区域 × 时间 × 频率）冲突与共享功率检测器。
 *
 * <p>相遇定义：至少一个相同区域单元、时间与频率均存在<b>正长度</b>交集；
 * 半开区间 {@code [start,end)} / {@code [low,high)}，端点相接不相遇。
 * 相遇且任一为独占即冲突；全为共享时，按实际重叠的时间×频率切分网格，
 * 逐网格（区域单元内）汇总生效 EIRP，任一网格超过上限即冲突——
 * 而非只检查整段总量或成对总量。</p>
 */
public final class ConflictDetector {

    /** 单次检测最多返回的功率超限明细数量，避免病态数据下明细爆炸。 */
    private static final int MAX_DETAILS = 50;

    private ConflictDetector() {
    }

    /**
     * 计算候选租约与已存在活动租约集合之间的全部冲突解释。
     *
     * @param candidate 候选租约
     * @param existing  已存在活动租约（不含候选自身）
     * @param limitMw   共享 EIRP 上限（mW）
     * @return 冲突明细；空列表表示无冲突
     */
    public static List<ConflictDetail> detect(ActiveLease candidate, List<ActiveLease> existing,
                                              BigDecimal limitMw) {
        List<ConflictDetail> details = new ArrayList<>();
        // 按区域单元收集裁剪到候选框内的共享租约矩形
        Map<String, List<Rect>> sharedByCell = new LinkedHashMap<>();

        for (ActiveLease other : existing) {
            Set<String> common = new TreeSet<>(candidate.regions());
            common.retainAll(other.regions());
            if (common.isEmpty()) {
                continue;
            }

            Instant t0 = candidate.start().isAfter(other.start()) ? candidate.start() : other.start();
            Instant t1 = candidate.end().isBefore(other.end()) ? candidate.end() : other.end();
            // 半开区间：正长度交集要求 t1 > t0，端点相接不相遇
            if (!t1.isAfter(t0)) {
                continue;
            }

            BigDecimal f0 = candidate.freqLowMHz().max(other.freqLowMHz());
            BigDecimal f1 = candidate.freqHighMHz().min(other.freqHighMHz());
            // 频率同为半开区间
            if (f1.compareTo(f0) <= 0) {
                continue;
            }

            if (candidate.mode() == LeaseMode.EXCLUSIVE || other.mode() == LeaseMode.EXCLUSIVE) {
                for (String cell : common) {
                    details.add(new ConflictDetail(
                            "EXCLUSIVE_OVERLAP", other.leaseId(), cell,
                            t0, t1, f0, f1, null, limitMw));
                    if (details.size() >= MAX_DETAILS) {
                        return details;
                    }
                }
            } else {
                Rect rect = new Rect(other.leaseId(), t0, t1, f0, f1, other.eirpMw());
                for (String cell : common) {
                    sharedByCell.computeIfAbsent(cell, k -> new ArrayList<>()).add(rect);
                }
            }
        }

        // 逐区域单元做时间×频率切点网格，网格内生效租约集合恒定
        for (Map.Entry<String, List<Rect>> entry : sharedByCell.entrySet()) {
            if (details.size() >= MAX_DETAILS) {
                break;
            }
            checkSharedCell(candidate, entry.getKey(), entry.getValue(), limitMw, details);
        }
        return details;
    }

    /**
     * 在单个区域单元内，用候选框与各共享矩形的端点构造时间/频率切点，
     * 逐网格汇总 EIRP 并与上限比较。
     */
    private static void checkSharedCell(ActiveLease candidate, String cell, List<Rect> rects,
                                        BigDecimal limitMw, List<ConflictDetail> details) {
        TreeSet<Instant> timeCuts = new TreeSet<>();
        timeCuts.add(candidate.start());
        timeCuts.add(candidate.end());
        TreeSet<BigDecimal> freqCuts = new TreeSet<>(Comparator.naturalOrder());
        freqCuts.add(candidate.freqLowMHz());
        freqCuts.add(candidate.freqHighMHz());
        for (Rect rect : rects) {
            timeCuts.add(rect.t0());
            timeCuts.add(rect.t1());
            freqCuts.add(rect.f0());
            freqCuts.add(rect.f1());
        }
        List<Instant> times = new ArrayList<>(timeCuts);
        List<BigDecimal> freqs = new ArrayList<>(freqCuts);

        for (int ti = 0; ti + 1 < times.size(); ti++) {
            Instant segStart = times.get(ti);
            Instant segEnd = times.get(ti + 1);
            for (int fi = 0; fi + 1 < freqs.size(); fi++) {
                BigDecimal segLow = freqs.get(fi);
                BigDecimal segHigh = freqs.get(fi + 1);

                BigDecimal total = candidate.eirpMw();
                String topLeaseId = null;
                BigDecimal topEirp = null;
                for (Rect rect : rects) {
                    // 切点保证网格不会跨越矩形边界；半开语义下整体包含才计权
                    boolean covers = !rect.t0().isAfter(segStart)
                            && !segEnd.isAfter(rect.t1())
                            && rect.f0().compareTo(segLow) <= 0
                            && segHigh.compareTo(rect.f1()) <= 0;
                    if (covers) {
                        total = total.add(rect.eirp());
                        if (topEirp == null || rect.eirp().compareTo(topEirp) > 0) {
                            topEirp = rect.eirp();
                            topLeaseId = rect.leaseId();
                        }
                    }
                }
                if (total.compareTo(limitMw) > 0) {
                    details.add(new ConflictDetail(
                            "SHARED_EIRP_EXCEEDED", topLeaseId, cell,
                            segStart, segEnd, segLow, segHigh, total, limitMw));
                    if (details.size() >= MAX_DETAILS) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * 裁剪到候选租约框内的共享租约矩形。
     */
    private record Rect(String leaseId, Instant t0, Instant t1,
                        BigDecimal f0, BigDecimal f1, BigDecimal eirp) {
    }
}
