package com.example.starter;

import com.example.starter.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结案：终态语义、重复结案幂等成功、结案后迟到日志只记事件并入审查队列。
 */
class ClosureTest extends ApiTestSupport {

    private long settledVoyage(String prefix) throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        long voyageId = departLogArrive(prefix, 1, "COD", "600", "2026-03-02T10:00:00Z");
        post("/api/v1/voyages/" + voyageId + "/settlement", requestIdBody(prefix + "-set"), 200);
        return voyageId;
    }

    @Test
    @DisplayName("结案为终态：重复结案返回幂等成功，状态与结案时刻不变")
    void closureIsTerminalAndIdempotent() throws Exception {
        long voyageId = settledVoyage("close-idem");
        JsonNode first = post("/api/v1/voyages/" + voyageId + "/closure",
                requestIdBody("close-idem-1"), 200);
        assertEquals("CLOSED", first.get("status").asText());

        // 异键重复结案：幂等成功，结案时刻保持首次值
        JsonNode again = post("/api/v1/voyages/" + voyageId + "/closure",
                requestIdBody("close-idem-2"), 200);
        assertEquals("CLOSED", again.get("status").asText());
        assertEquals(first.get("closedAt").asText(), again.get("closedAt").asText());
        assertEquals("CLOSED", fixtures.voyageStatus(voyageId));
    }

    @Test
    @DisplayName("结案后迟到日志：只记迟到事件并入审查队列，不写日志")
    void lateLogAfterClosureRecordedAndReviewed() throws Exception {
        long voyageId = settledVoyage("close-late");
        post("/api/v1/voyages/" + voyageId + "/closure", requestIdBody("close-late-c"), 200);

        JsonNode response = post("/api/v1/voyages/" + voyageId + "/logs",
                logBody("close-late-log2", "A1",
                        new String[]{"COD", "50", "2026-03-03T10:00:00Z"}), 200);
        assertTrue(response.get("late").asBoolean());
        assertEquals("CLOSED", response.get("status").asText());
        assertEquals(1, fixtures.lateEvents(voyageId));
        assertEquals(1, fixtures.openReviews(voyageId, "LATE_LOG"));
        assertEquals(0, fixtures.logCount(1, "2026-03-03", "A1"));
    }

    @Test
    @DisplayName("未核销不得结案")
    void closureBeforeSettlementRejected() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        long voyageId = departLogArrive("close-early", 1, "COD", "600", "2026-03-02T10:00:00Z");
        JsonNode response = post("/api/v1/voyages/" + voyageId + "/closure",
                requestIdBody("close-early-c"), 422);
        assertEquals("INVALID_STATE", response.get("code").asText());
        assertEquals("ARRIVED", fixtures.voyageStatus(voyageId));
    }

    @Test
    @DisplayName("结案后核销与进港报告均拒绝")
    void closedVoyageRejectsFurtherBusiness() throws Exception {
        long voyageId = settledVoyage("close-term");
        post("/api/v1/voyages/" + voyageId + "/closure", requestIdBody("close-term-c"), 200);
        JsonNode settle = post("/api/v1/voyages/" + voyageId + "/settlement",
                requestIdBody("close-term-set2"), 409);
        assertEquals("CONFLICT", settle.get("code").asText());
        JsonNode arrival = post("/api/v1/voyages/" + voyageId + "/arrival",
                arrivalBody("close-term-arr2", "2026-03-06T10:00:00Z", null, "COD", "600"), 422);
        assertEquals("INVALID_STATE", arrival.get("code").asText());
    }
}
