package com.example.starter;

import com.example.starter.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 配额核销：核销与余额扣减同事务（失败整体回滚）、状态机约束、requestId 幂等。
 */
class SettlementTest extends ApiTestSupport {

    @Test
    @DisplayName("核销超配额：整笔回滚，余额未扣、航次未流转、无核销单")
    void settlementExceedingQuotaRollsBack() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        long voyageId = departLogArrive("set-over", 1, "COD", "600", "2026-03-02T10:00:00Z");
        // 申报后配额核定调减为 500，核销 600 将超限
        fixtures.updateTotalQuota(1, "COD", 2026, "500");
        JsonNode response = post("/api/v1/voyages/" + voyageId + "/settlement",
                requestIdBody("set-over-set"), 422);
        assertEquals("QUOTA_EXCEEDED", response.get("code").asText());
        assertEquals(0, fixtures.usedQuota(1, "COD", 2026).compareTo(BigDecimal.ZERO));
        assertEquals("ARRIVED", fixtures.voyageStatus(voyageId));
        assertEquals(0, fixtures.settlementCount(voyageId));
    }

    @Test
    @DisplayName("核销成功：余额与核销单同事务落库，航次流转 SETTLED")
    void settlementDeductsAtomically() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        long voyageId = departLogArrive("set-ok", 1, "COD", "600", "2026-03-02T10:00:00Z");
        JsonNode response = post("/api/v1/voyages/" + voyageId + "/settlement",
                requestIdBody("set-ok-set"), 200);
        assertEquals("SETTLED", response.get("status").asText());
        assertEquals(0, fixtures.usedQuota(1, "COD", 2026).compareTo(new BigDecimal("600")));
        assertEquals(1, fixtures.settlementCount(voyageId));
        assertEquals("SETTLED", fixtures.voyageStatus(voyageId));
    }

    @Test
    @DisplayName("同 requestId 核销重放：返回首次结果，余额只扣一次")
    void settlementIdempotentReplay() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        long voyageId = departLogArrive("set-idem", 1, "COD", "600", "2026-03-02T10:00:00Z");
        post("/api/v1/voyages/" + voyageId + "/settlement", requestIdBody("set-idem-set"), 200);
        post("/api/v1/voyages/" + voyageId + "/settlement", requestIdBody("set-idem-set"), 200);
        assertEquals(0, fixtures.usedQuota(1, "COD", 2026).compareTo(new BigDecimal("600")));
        assertEquals(1, fixtures.settlementCount(voyageId));

        // 同键异参（此处体现为不同航次复用同一 requestId）返回 409
        fixtures.setQuota(2, "COD", 2026, "1000");
        long other = departLogArrive("set-idem2", 2, "COD", "100", "2026-03-02T10:00:00Z");
        JsonNode conflict = post("/api/v1/voyages/" + other + "/settlement",
                requestIdBody("set-idem-set"), 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());
    }

    @Test
    @DisplayName("未进港不得核销；已核销航次重复核销（异键）返回 409")
    void settlementStateMachine() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        JsonNode departure = post("/api/v1/departures",
                departureBody("set-sm-dep", 1, "2026-03-01T08:00:00Z", "A1", "COD", "100"), 200);
        long voyageId = departure.get("voyageId").asLong();
        JsonNode early = post("/api/v1/voyages/" + voyageId + "/settlement",
                requestIdBody("set-sm-early"), 422);
        assertEquals("INVALID_STATE", early.get("code").asText());

        post("/api/v1/voyages/" + voyageId + "/logs",
                logBody("set-sm-log", "A1",
                        new String[]{"COD", "100", "2026-03-02T10:00:00Z"}), 200);
        post("/api/v1/voyages/" + voyageId + "/arrival",
                arrivalBody("set-sm-arr", "2026-03-05T10:00:00Z", null, "COD", "100"), 200);
        post("/api/v1/voyages/" + voyageId + "/settlement", requestIdBody("set-sm-set"), 200);
        JsonNode again = post("/api/v1/voyages/" + voyageId + "/settlement",
                requestIdBody("set-sm-again"), 409);
        assertEquals("CONFLICT", again.get("code").asText());
    }
}
