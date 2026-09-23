package com.example.starter.spectrum.service;

import com.example.starter.spectrum.ConflictDetail;
import com.example.starter.spectrum.LeaseMode;
import com.example.starter.spectrum.domain.ActiveLease;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConflictDetector} 的三维半开边界、独占冲突与共享片段功率汇总单元测试。
 */
class ConflictDetectorTest {

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2030-01-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2030-01-03T00:00:00Z");
    private static final Instant T3 = Instant.parse("2030-01-04T00:00:00Z");
    private static final BigDecimal LIMIT = new BigDecimal("1000");

    private ActiveLease lease(String id, LeaseMode mode, BigDecimal eirp,
                              Instant s, Instant e, BigDecimal fl, BigDecimal fh,
                              Set<String> regions) {
        return new ActiveLease(id, 1, s, e, fl, fh, mode, eirp, regions);
    }

    @Test
    void disjointRegionTimeOrFrequencyDoesNotMeet() {
        ActiveLease candidate = lease("new", LeaseMode.EXCLUSIVE, new BigDecimal("100"),
                T0, T1, new BigDecimal("100"), new BigDecimal("200"), Set.of("A"));
        List<ActiveLease> others = List.of(
                // 区域不同
                lease("o1", LeaseMode.EXCLUSIVE, BigDecimal.ONE, T0, T1, bd("100"), bd("200"), Set.of("B")),
                // 时间端点相接（半开，不相遇）
                lease("o2", LeaseMode.EXCLUSIVE, BigDecimal.ONE, T1, T2, bd("100"), bd("200"), Set.of("A")),
                // 时间在更早端点相接
                lease("o3", LeaseMode.EXCLUSIVE, BigDecimal.ONE,
                        Instant.parse("2029-12-31T00:00:00Z"), T0, bd("100"), bd("200"), Set.of("A")),
                // 频率端点相接
                lease("o4", LeaseMode.EXCLUSIVE, BigDecimal.ONE, T0, T1, bd("200"), bd("300"), Set.of("A")),
                lease("o5", LeaseMode.EXCLUSIVE, BigDecimal.ONE, T0, T1, bd("50"), bd("100"), Set.of("A")));

        assertThat(ConflictDetector.detect(candidate, others, LIMIT)).isEmpty();
    }

    @Test
    void positiveOverlapWithExclusiveIsRejectedPerRegion() {
        ActiveLease candidate = lease("new", LeaseMode.SHARED, new BigDecimal("100"),
                T0, T2, bd("100"), bd("200"), Set.of("A", "C"));
        ActiveLease other = lease("old", LeaseMode.EXCLUSIVE, new BigDecimal("100"),
                T1, T3, bd("150"), bd("250"), Set.of("B", "C"));

        List<ConflictDetail> details = ConflictDetector.detect(candidate, List.of(other), LIMIT);

        assertThat(details).hasSize(1);
        ConflictDetail d = details.get(0);
        assertThat(d.reason()).isEqualTo("EXCLUSIVE_OVERLAP");
        assertThat(d.leaseId()).isEqualTo("old");
        // 仅共同区域 C 产生解释
        assertThat(d.regionCell()).isEqualTo("C");
        // 时间交集 [T1,T2)，频率交集 [150,200)
        assertThat(d.overlapStart()).isEqualTo(T1);
        assertThat(d.overlapEnd()).isEqualTo(T2);
        assertThat(d.overlapLowMHz()).isEqualByComparingTo("150");
        assertThat(d.overlapHighMHz()).isEqualByComparingTo("200");
    }

    @Test
    void candidateExclusiveAlsoConflicts() {
        ActiveLease candidate = lease("new", LeaseMode.EXCLUSIVE, BigDecimal.ONE,
                T0, T2, bd("100"), bd("200"), Set.of("A"));
        ActiveLease other = lease("old", LeaseMode.SHARED, new BigDecimal("100"),
                T0, T2, bd("100"), bd("200"), Set.of("A"));

        assertThat(ConflictDetector.detect(candidate, List.of(other), LIMIT))
                .extracting(ConflictDetail::reason)
                .containsExactly("EXCLUSIVE_OVERLAP");
    }

    @Test
    void sharedWithinLimitAcrossWholeSpanPasses() {
        ActiveLease candidate = lease("new", LeaseMode.SHARED, new BigDecimal("600"),
                T0, T2, bd("100"), bd("200"), Set.of("A"));
        ActiveLease other = lease("old", LeaseMode.SHARED, new BigDecimal("400"),
                T0, T2, bd("100"), bd("200"), Set.of("A"));

        assertThat(ConflictDetector.detect(candidate, List.of(other), LIMIT)).isEmpty();
    }

    @Test
    void sharedExceedanceDetectedOnActualFragmentNotWholeOrPairwise() {
        // 两个已存在共享租约，各自与候选在不同的时间子片段、不同频率子片段相遇：
        // 任意两两之和都不超限，但三个在中间时间×中间频率片段同时生效时超限。
        ActiveLease candidate = lease("new", LeaseMode.SHARED, new BigDecimal("400"),
                T0, T3, bd("100"), bd("300"), Set.of("A"));
        ActiveLease o1 = lease("o1", LeaseMode.SHARED, new BigDecimal("400"),
                T0, T2, bd("100"), bd("200"), Set.of("A"));
        ActiveLease o2 = lease("o2", LeaseMode.SHARED, new BigDecimal("400"),
                T1, T3, bd("150"), bd("300"), Set.of("A"));

        List<ConflictDetail> details = ConflictDetector.detect(candidate, List.of(o1, o2), LIMIT);

        // 超限片段必须是三者同时覆盖的时间 [T1,T2) × 频率 [150,200)
        assertThat(details).isNotEmpty();
        ConflictDetail only = details.get(0);
        assertThat(only.reason()).isEqualTo("SHARED_EIRP_EXCEEDED");
        assertThat(only.regionCell()).isEqualTo("A");
        assertThat(only.overlapStart()).isEqualTo(T1);
        assertThat(only.overlapEnd()).isEqualTo(T2);
        assertThat(only.overlapLowMHz()).isEqualByComparingTo("150");
        assertThat(only.overlapHighMHz()).isEqualByComparingTo("200");
        assertThat(only.totalEirpMw()).isEqualByComparingTo("1200");
        assertThat(only.limitMw()).isEqualByComparingTo("1000");
        // 仅有三重重叠的一个片段超限
        assertThat(details).hasSize(1);
    }

    @Test
    void sharedExceedanceIsScopedPerRegion() {
        ActiveLease candidate = lease("new", LeaseMode.SHARED, new BigDecimal("900"),
                T0, T1, bd("100"), bd("200"), Set.of("A", "B"));
        ActiveLease other = lease("old", LeaseMode.SHARED, new BigDecimal("900"),
                T0, T1, bd("100"), bd("200"), Set.of("B"));

        List<ConflictDetail> details = ConflictDetector.detect(candidate, List.of(other), LIMIT);

        assertThat(details).hasSize(1);
        assertThat(details.get(0).regionCell()).isEqualTo("B");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
