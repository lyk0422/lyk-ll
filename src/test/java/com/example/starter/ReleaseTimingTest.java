package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 监测期与解除规则：恰满 14 天无新增虫迹方可申请解除，解除须消杀与库房双方确认。
 */
class ReleaseTimingTest extends BaseIntegrationTest {

    private long treatedCase() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacement(caseId, "pl-1", "张三");
        confirmPlacement(placementId, "pc-1", "李四");
        return caseId;
    }

    @Test
    void releaseBeforeFourteenDaysRejected() throws Exception {
        long caseId = treatedCase();

        // 差 1 秒满 14 天：拒绝
        clock.setInstant(T0.plus(14, ChronoUnit.DAYS).minusSeconds(1));
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", "ra-1", "operator", "库房员"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void releaseExactlyFourteenDaysAllowed() throws Exception {
        long caseId = treatedCase();

        // 恰满 14 天：允许申请
        clock.setInstant(T0.plus(14, ChronoUnit.DAYS));
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", "ra-1", "operator", "库房员"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASE_APPLIED"));
    }

    @Test
    void newTraceDuringMonitoringBlocksRelease() throws Exception {
        long caseId = treatedCase();

        // 监测期内出现新增虫迹
        clock.setInstant(T0.plus(3, ChronoUnit.DAYS));
        postJson("/api/cases/" + caseId + "/traces",
                Map.of("requestId", "tr-1", "description", "墙角新虫迹"))
                .andExpect(status().isOk());

        clock.setInstant(T0.plus(14, ChronoUnit.DAYS));
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", "ra-1", "operator", "库房员"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void releaseRequiresBothSidesConfirmation() throws Exception {
        long caseId = treatedCase();
        clock.setInstant(T0.plus(14, ChronoUnit.DAYS));
        applyRelease(caseId, "ra-1");

        // 消杀方确认后仍未解除，出库仍被禁
        confirmRelease(caseId, "rc-1", "DISINFECTION");
        mockMvc.perform(get("/api/cases/" + caseId))
                .andExpect(jsonPath("$.status").value("RELEASE_APPLIED"));
        postJson("/api/zones/A/outbounds",
                Map.of("requestId", "ob-1", "itemName", "档案甲", "borrower", "李四"))
                .andExpect(status().isConflict());

        // 库房方确认后解除，出库恢复
        confirmRelease(caseId, "rc-2", "STORAGE");
        mockMvc.perform(get("/api/cases/" + caseId))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        postJson("/api/zones/A/outbounds",
                Map.of("requestId", "ob-2", "itemName", "档案甲", "borrower", "李四"))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateSideConfirmationRejected() throws Exception {
        long caseId = treatedCase();
        clock.setInstant(T0.plus(14, ChronoUnit.DAYS));
        applyRelease(caseId, "ra-1");
        confirmRelease(caseId, "rc-1", "DISINFECTION");

        // 同一方重复确认（不同 requestId）：拒绝
        postJson("/api/cases/" + caseId + "/release-confirmation",
                Map.of("requestId", "rc-2", "side", "DISINFECTION"))
                .andExpect(status().isConflict());
    }
}
