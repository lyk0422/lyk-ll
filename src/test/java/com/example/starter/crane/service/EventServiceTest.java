package com.example.starter.crane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.crane.AbstractCraneIntegrationTest;
import com.example.starter.crane.NotFoundException;
import com.example.starter.crane.api.CorrectionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 事件不可删除、修正只追加留痕的验证。
 */
class EventServiceTest extends AbstractCraneIntegrationTest {

    @Autowired
    private PoseProcessingService poseProcessingService;
    @Autowired
    private EventService eventService;

    @Test
    void warning_and_brake_queryable_and_correction_is_append_only() {
        // 制造一次持续 3 秒的越限制动
        for (int i = 0; i <= 3; i++) {
            poseProcessingService.ingest(new com.example.starter.crane.api.PoseRequest(
                    "C-101", java.time.Instant.parse("2026-09-25T10:00:0" + i + "Z"),
                    "Z-A1", null, null, null));
        }

        var warnings = eventService.listWarnings("C-101");
        assertThat(warnings).hasSize(1);
        var warning = warnings.get(0);
        assertThat(warning.status()).isEqualTo("BRAKED");
        assertThat(warning.brakeCommandCode()).startsWith("BRK-W-C-101-Z-A1-");

        var brakes = eventService.listBrakes("C-101");
        assertThat(brakes).hasSize(1);
        assertThat(brakes.get(0).warningId()).isEqualTo(warning.id());

        // 追加修正：原事件状态/编号不变，只新增留痕
        eventService.appendCorrection(new CorrectionRequest(
                warning.id(), "MANUAL_REVIEW", "人工复核：越限判定正确，维持制动结论"));
        var warningsAfter = eventService.listWarnings("C-101");
        assertThat(warningsAfter.get(0).id()).isEqualTo(warning.id());
        assertThat(warningsAfter.get(0).status()).isEqualTo("BRAKED");
        var corrections = eventService.listCorrections(warning.id());
        assertThat(corrections).hasSize(1);
        assertThat(corrections.get(0)).contains("MANUAL_REVIEW");

        // 事件不可删除：表中仍恰好一条
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM intrusion_warning", Integer.class)).isEqualTo(1);

        // 对不存在的预警追加修正 → 404
        assertThatThrownBy(() -> eventService.appendCorrection(
                new CorrectionRequest(999999L, "X", "Y")))
                .isInstanceOf(NotFoundException.class);
    }
}
