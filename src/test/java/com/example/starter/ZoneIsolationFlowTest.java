package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 分区拦截主流程：确认后仅禁该库区出库，其它库区不受影响；解除后恢复。
 */
class ZoneIsolationFlowTest extends BaseIntegrationTest {

    @Test
    void outboundBlockedOnlyForZoneInDisposal() throws Exception {
        long caseId = reportCase("A", "rpt-A-1");

        // 仅上报未确认：不拦截出库
        postJson("/api/zones/A/outbounds",
                Map.of("requestId", "ob-0", "itemName", "档案甲", "borrower", "李四"))
                .andExpect(status().isOk());

        confirmCase(caseId, "cfm-A-1");

        // 确认后：该库区进入处置状态，禁止出库登记
        postJson("/api/zones/A/outbounds",
                Map.of("requestId", "ob-1", "itemName", "档案甲", "borrower", "李四"))
                .andExpect(status().isConflict());

        // 其它库区借阅不受影响
        postJson("/api/zones/D/outbounds",
                Map.of("requestId", "ob-2", "itemName", "档案乙", "borrower", "王五"))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateActiveChainInSameZoneRejected() throws Exception {
        reportCase("A", "rpt-A-1");

        // 同库区同时刻只允许一条进行中处置链
        postJson("/api/zones/A/pest-reports",
                Map.of("requestId", "rpt-A-2", "reporter", "张三", "description", "再次发现"))
                .andExpect(status().isConflict());

        // 其它库区可正常上报
        reportCase("B", "rpt-B-1");
    }

    @Test
    void fullFlowFromReportToReleaseRestoresOutbound() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacement(caseId, "pl-1", "张三");
        confirmPlacement(placementId, "pc-1", "李四");

        // 恰满 14 天监测期
        clock.setInstant(T0.plus(14, ChronoUnit.DAYS));
        applyRelease(caseId, "ra-1");
        confirmRelease(caseId, "rc-1", "DISINFECTION");
        confirmRelease(caseId, "rc-2", "STORAGE");

        mockMvc.perform(get("/api/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.zoneCode").value("A"));

        // 解除后该库区出库恢复
        postJson("/api/zones/A/outbounds",
                Map.of("requestId", "ob-9", "itemName", "档案丙", "borrower", "赵六"))
                .andExpect(status().isOk());
    }
}
