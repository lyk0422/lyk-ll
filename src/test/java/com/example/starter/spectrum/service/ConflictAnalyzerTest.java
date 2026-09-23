package com.example.starter.spectrum.service;

import com.example.starter.spectrum.domain.ConflictDetail;
import com.example.starter.spectrum.domain.ConflictReport;
import com.example.starter.spectrum.domain.LeaseMode;
import com.example.starter.spectrum.domain.LeaseSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三维半开区间冲突分析器纯单元测试：
 * 半开边界、独占冲突、多共享片段聚合超限（非整段/成对总量）。
 */
class ConflictAnalyzerTest {

    private static final double LIMIT = 1000.0;

    private static LeaseSpec spec(long start, long end, double low, double high,
                                  LeaseMode mode, double eirp, String... cells) {
        return new LeaseSpec(start, end, low, high, mode, eirp, new TreeSet<>(List.of(cells)));
    }

    private static ConflictAnalyzer.EffectiveLease lease(long id, long start, long end,
                                                         double low, double high,
                                                         LeaseMode mode, double eirp,
                                                         String... cells) {
        return new ConflictAnalyzer.EffectiveLease(id, 1, start, end, low, high, mode, eirp,
                new TreeSet<>(List.of(cells)));
    }

    @Test
    void timeEndpointTouchingIsNotAConflict() {
        // 既有租约 [100,200)，候选 [200,300)：端点相接不算相遇
        LeaseSpec candidate = spec(200, 300, 100.0, 200.0, LeaseMode.EXCLUSIVE, 10, "A");
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0,
                List.of(lease(1, 100, 200, 100.0, 200.0, LeaseMode.EXCLUSIVE, 10, "A")), LIMIT);
        assertTrue(report.compatible());
        assertTrue(report.conflicts().isEmpty());
    }

    @Test
    void frequencyEndpointTouchingIsNotAConflict() {
        // 频率 [100,200) 与 [200,300) 端点相接不算相遇
        LeaseSpec candidate = spec(0, 100, 200.0, 300.0, LeaseMode.EXCLUSIVE, 10, "A");
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0,
                List.of(lease(1, 0, 100, 100.0, 200.0, LeaseMode.EXCLUSIVE, 10, "A")), LIMIT);
        assertTrue(report.compatible());
    }

    @Test
    void disjointCellsAreNotAConflict() {
        LeaseSpec candidate = spec(0, 100, 100.0, 200.0, LeaseMode.EXCLUSIVE, 10, "B");
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0,
                List.of(lease(1, 0, 100, 100.0, 200.0, LeaseMode.EXCLUSIVE, 10, "A")), LIMIT);
        assertTrue(report.compatible());
    }

    @Test
    void positiveOverlapInAllDimensionsConflicts() {
        // 时间 (50,100)、频率 (150,200)、区域 A 三维均正长度相交
        LeaseSpec candidate = spec(50, 150, 150.0, 250.0, LeaseMode.EXCLUSIVE, 10, "A");
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0,
                List.of(lease(7, 0, 100, 100.0, 200.0, LeaseMode.SHARED, 10, "A")), LIMIT);
        assertFalse(report.compatible());
        assertEquals(1, report.conflicts().size());
        ConflictDetail detail = report.conflicts().get(0);
        assertEquals("EXCLUSIVE_CONFLICT", detail.type());
        assertEquals("A", detail.cell());
        assertEquals(50, detail.overlapStart());
        assertEquals(100, detail.overlapEnd());
        assertEquals(150.0, detail.freqLowMhz());
        assertEquals(200.0, detail.freqHighMhz());
    }

    @Test
    void exclusiveVsSharedMeetingConflicts() {
        LeaseSpec candidate = spec(0, 100, 100.0, 200.0, LeaseMode.SHARED, 10, "A");
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0,
                List.of(lease(1, 0, 100, 100.0, 200.0, LeaseMode.EXCLUSIVE, 10, "A")), LIMIT);
        assertFalse(report.compatible());
        assertEquals("EXCLUSIVE_CONFLICT", report.conflicts().get(0).type());
    }

    @Test
    void sharedSumWithinLimitIsCompatible() {
        // 400 + 500 = 900 <= 1000
        LeaseSpec candidate = spec(0, 100, 100.0, 200.0, LeaseMode.SHARED, 400, "A");
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0,
                List.of(lease(1, 0, 100, 100.0, 200.0, LeaseMode.SHARED, 500, "A")), LIMIT);
        assertTrue(report.compatible());
    }

    @Test
    void sharedSumExactlyAtLimitIsCompatible() {
        LeaseSpec candidate = spec(0, 100, 100.0, 200.0, LeaseMode.SHARED, 400, "A");
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0,
                List.of(lease(1, 0, 100, 100.0, 200.0, LeaseMode.SHARED, 600, "A")), LIMIT);
        assertTrue(report.compatible());
    }

    @Test
    void pairwiseOkButTripleOverlapExceedsLimit() {
        // 三条共享租约两两之和 800 <= 1000，但三者公共片段之和 1200 > 1000。
        // 候选 [0,100)x[100,200) 与 L1、L2 全交；L1 与 L2 也全交。
        LeaseSpec candidate = spec(0, 100, 100.0, 200.0, LeaseMode.SHARED, 400, "A");
        List<ConflictAnalyzer.EffectiveLease> effective = List.of(
                lease(1, 0, 100, 100.0, 200.0, LeaseMode.SHARED, 400, "A"),
                lease(2, 0, 100, 100.0, 200.0, LeaseMode.SHARED, 400, "A"));
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0, effective, LIMIT);
        assertFalse(report.compatible());
        ConflictDetail detail = report.conflicts().get(0);
        assertEquals("SHARED_EIRP_EXCEEDED", detail.type());
        assertEquals(1200.0, detail.totalEirpMw(), 1e-9);
        assertEquals(LIMIT, detail.eirpLimitMw(), 1e-9);
        assertEquals(3, detail.contributors().size());
    }

    @Test
    void onlyOverlappingFragmentIsChecked() {
        // L1 与候选在 [40,60)x[140,160) 相交；L2 与候选在 [60,80)x[160,180) 相交；
        // 任一片段总和 700 <= 1000，整段总量 1400 超限也不应拒绝。
        LeaseSpec candidate = spec(0, 100, 100.0, 200.0, LeaseMode.SHARED, 300, "A");
        List<ConflictAnalyzer.EffectiveLease> effective = List.of(
                lease(1, 40, 60, 140.0, 160.0, LeaseMode.SHARED, 400, "A"),
                lease(2, 60, 80, 160.0, 180.0, LeaseMode.SHARED, 400, "A"));
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0, effective, LIMIT);
        assertTrue(report.compatible());
    }

    @Test
    void fragmentExceedReportCarriesPreciseBounds() {
        // 候选 [0,100)x[100,200)；L1 [20,80)x[150,250)；超限片段应精确为 [20,80)x[150,200)
        LeaseSpec candidate = spec(0, 100, 100.0, 200.0, LeaseMode.SHARED, 800, "A");
        ConflictReport report = ConflictAnalyzer.analyze(candidate, null, 0,
                List.of(lease(1, 20, 80, 150.0, 250.0, LeaseMode.SHARED, 400, "A")), LIMIT);
        assertFalse(report.compatible());
        ConflictDetail detail = report.conflicts().get(0);
        assertEquals(20, detail.overlapStart());
        assertEquals(80, detail.overlapEnd());
        assertEquals(150.0, detail.freqLowMhz());
        assertEquals(200.0, detail.freqHighMhz());
        assertEquals(1200.0, detail.totalEirpMw(), 1e-9);
    }
}
