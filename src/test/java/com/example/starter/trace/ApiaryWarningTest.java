package com.example.starter.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 蜂场预警测试：同蜂场同花期累计恰 3 批疑似触发一次预警。
 */
class ApiaryWarningTest extends BaseTraceTest {

    @Test
    void 累计2批疑似_不触发预警() {
        rapid(newBatch(1L), "SMP-A1", "60.00");
        rapid(newBatch(1L), "SMP-A2", "61.00");
        assertThat(warningService.listWarnings()).isEmpty();
    }

    @Test
    void 累计恰3批疑似_触发一次预警() {
        rapid(newBatch(1L), "SMP-B1", "60.00");
        rapid(newBatch(1L), "SMP-B2", "61.00");
        rapid(newBatch(1L), "SMP-B3", "62.00");

        var warnings = warningService.listWarnings();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).apiaryId()).isEqualTo(1L);
        assertThat(warnings.get(0).flowerPeriod()).isEqualTo(PERIOD);
        assertThat(warnings.get(0).suspectedCount()).isEqualTo(3);
    }

    @Test
    void 第4批疑似_不重复触发同蜂场同花期预警() {
        for (int i = 1; i <= 4; i++) {
            rapid(newBatch(1L), "SMP-C" + i, "60.00");
        }
        assertThat(warningService.listWarnings()).hasSize(1);
    }

    @Test
    void 不同花期分别累计_互不影响() {
        rapid(newBatch(1L, "2026-洋槐"), "SMP-D1", "60.00");
        rapid(newBatch(1L, "2026-洋槐"), "SMP-D2", "60.00");
        rapid(newBatch(1L, "2026-荆条"), "SMP-D3", "60.00");
        assertThat(warningService.listWarnings()).isEmpty();

        rapid(newBatch(1L, "2026-荆条"), "SMP-D4", "60.00");
        rapid(newBatch(1L, "2026-荆条"), "SMP-D5", "60.00");
        var warnings = warningService.listWarnings();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).flowerPeriod()).isEqualTo("2026-荆条");
    }

    @Test
    void 多蜂场贡献批_疑似计入每个贡献蜂场() {
        // 蜂场2先积累2批疑似
        rapid(newBatch(2L), "SMP-E1", "60.00");
        rapid(newBatch(2L), "SMP-E2", "60.00");
        // 蜂场1与蜂场2共同贡献的第3批疑似：蜂场2恰达3批触发，蜂场1仅1批不触发
        long shared = newSharedBatch();
        rapid(shared, "SMP-E3", "60.00");

        var warnings = warningService.listWarnings();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).apiaryId()).isEqualTo(2L);
    }

    private long newSharedBatch() {
        var response = harvestBatchService.createBatch(new com.example.starter.trace.dto.CreateHarvestBatchRequest(
                "REQ-SHARED-1", "HB-SHARED-1", PERIOD,
                java.util.List.of(
                        new com.example.starter.trace.dto.CreateHarvestBatchRequest.ContributionItem(
                                1L, new java.math.BigDecimal("50.00")),
                        new com.example.starter.trace.dto.CreateHarvestBatchRequest.ContributionItem(
                                2L, new java.math.BigDecimal("50.00")))));
        return response.id();
    }
}
