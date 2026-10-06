package com.example.starter;

import com.example.starter.service.DisposalService;
import com.example.starter.service.EvaluationService;
import com.example.starter.service.MonitoringService;
import com.example.starter.service.RecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日评估：输出各池稳定时长与告警处置链，结果可重复复算。
 */
class EvaluationServiceTest extends BaseIntegrationTest {

    @Autowired
    private MonitoringService monitoringService;
    @Autowired
    private DisposalService disposalService;
    @Autowired
    private RecoveryService recoveryService;
    @Autowired
    private EvaluationService evaluationService;

    @Test
    void 日评估输出稳定时长与告警处置链且可复算() {
        LocalDate day = T0.toLocalDate();
        // 09:00 起连续 3 点越限 → 09:02 进入处置；09:05 回执确认；10:00 复测合格恢复
        for (int i = 0; i < 3; i++) {
            monitoringService.ingest("ZONE-A", "req-ev-" + i, new BigDecimal("4.0"),
                    new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusHours(1).plusMinutes(i));
        }
        long commandId = jdbcTemplate.queryForObject(
                "SELECT id FROM disposal_command WHERE zone_id = ?", Long.class, zoneId("ZONE-A"));
        disposalService.confirmReceipt(commandId, T0.plusHours(1).plusMinutes(5));
        recoveryService.recover("ZONE-A", "req-ev-recover", new BigDecimal("6.0"),
                new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusHours(2));

        List<EvaluationService.ZoneEvaluation> first = evaluationService.evaluate(day);
        List<EvaluationService.ZoneEvaluation> second = evaluationService.evaluate(day);
        assertEquals(first, second, "日评估应可复算，两次结果一致");

        EvaluationService.ZoneEvaluation zoneA = first.stream()
                .filter(e -> e.zoneCode().equals("ZONE-A")).findFirst().orElseThrow();
        // 稳定时长：00:00→09:00 与 10:00→24:00 两段 NORMAL
        assertEquals(9 * 3600L + 14 * 3600L, zoneA.stableSeconds());
        assertEquals(3, zoneA.alarms().size());
        EvaluationService.AlarmChain escalated = zoneA.alarms().get(2);
        assertEquals("RESOLVED", escalated.status(), "恢复后告警应已解除");
        assertEquals(1, escalated.commands().size());
        assertEquals("AERATION", escalated.commands().get(0).commandType());
        assertEquals("COMPLETED", escalated.commands().get(0).status());

        // 未发生事件的池全天稳定
        EvaluationService.ZoneEvaluation zoneB = first.stream()
                .filter(e -> e.zoneCode().equals("ZONE-B")).findFirst().orElseThrow();
        assertEquals(24 * 3600L, zoneB.stableSeconds());
        assertTrue(zoneB.alarms().isEmpty());
    }
}
