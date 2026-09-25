package com.example.starter;

import com.example.starter.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进港报告：卸渔偏差恰 3% 视为一致、超 3% 须差异说明并转人工复核、
 * 复核期间配额余额冻结、复核结论与解除冻结/扣减在同一事务内完成。
 */
class ArrivalDeviationTest extends ApiTestSupport {

    private long departAndLog(String prefix, String loggedKg) throws Exception {
        fixtures.setQuota(1, "COD", 2026, "10000");
        JsonNode departure = post("/api/v1/departures",
                departureBody(prefix + "-dep", 1, "2026-03-01T08:00:00Z", "A1", "COD", loggedKg), 200);
        long voyageId = departure.get("voyageId").asLong();
        post("/api/v1/voyages/" + voyageId + "/logs",
                logBody(prefix + "-log", "A1",
                        new String[]{"COD", loggedKg, "2026-03-02T10:00:00Z"}), 200);
        return voyageId;
    }

    @Test
    @DisplayName("卸渔偏差恰 3%：视为一致，不立案不冻结")
    void exactlyThreePercentDeviationConsistent() throws Exception {
        long voyageId = departAndLog("dev-3pct", "1000");
        JsonNode response = post("/api/v1/voyages/" + voyageId + "/arrival",
                arrivalBody("dev-3pct-arr", "2026-03-05T10:00:00Z", null, "COD", "1030"), 200);
        assertFalse(response.get("deviationFlagged").asBoolean());
        assertEquals(0, fixtures.openReviews(voyageId, "DEVIATION"));
        assertFalse(fixtures.ledgerFrozen(1, "COD", 2026));
    }

    @Test
    @DisplayName("卸渔偏差超 3% 且缺差异说明：400 拒绝")
    void aboveThreePercentRequiresNote() throws Exception {
        long voyageId = departAndLog("dev-nonote", "1000");
        JsonNode response = post("/api/v1/voyages/" + voyageId + "/arrival",
                arrivalBody("dev-nonote-arr", "2026-03-05T10:00:00Z", null, "COD", "1030.001"), 400);
        assertEquals("DIFFERENCE_NOTE_REQUIRED", response.get("code").asText());
        assertEquals("DEPARTED", fixtures.voyageStatus(voyageId));
    }

    @Test
    @DisplayName("卸渔偏差超 3%：立案复核并冻结余额，复核办结同事务解除冻结并扣减")
    void deviationReviewFreezeAndAtomicResolution() throws Exception {
        long voyageId = departAndLog("dev-review", "1000");
        JsonNode arrival = post("/api/v1/voyages/" + voyageId + "/arrival",
                arrivalBody("dev-review-arr", "2026-03-05T10:00:00Z", "网具损耗导致卸渔偏多",
                        "COD", "1031"), 200);
        assertTrue(arrival.get("deviationFlagged").asBoolean());
        long reviewId = arrival.get("reviewId").asLong();
        assertEquals(1, fixtures.openReviews(voyageId, "DEVIATION"));
        assertTrue(fixtures.ledgerFrozen(1, "COD", 2026));

        // 复核期间核销被拒
        JsonNode settle = post("/api/v1/voyages/" + voyageId + "/settlement",
                requestIdBody("dev-review-set"), 422);
        assertEquals("REVIEW_PENDING", settle.get("code").asText());

        // 复核结论为最终口径：同一事务内办结、解冻并扣减
        ObjectNode resolution = requestIdBody("dev-review-res");
        resolution.put("conclusion", "差异属实，按卸渔量 1031kg 核销");
        resolution.putArray("finalItems").addObject()
                .put("speciesCode", "COD").put("quantityKg", "1031");
        JsonNode resolved = post("/api/v1/reviews/" + reviewId + "/resolution", resolution, 200);
        assertEquals("RESOLVED", resolved.get("status").asText());
        assertFalse(fixtures.ledgerFrozen(1, "COD", 2026));
        assertEquals(0, fixtures.usedQuota(1, "COD", 2026).compareTo(new BigDecimal("1031")));
        assertEquals("SETTLED", fixtures.voyageStatus(voyageId));
        assertEquals(1, fixtures.settlementCount(voyageId));

        // 核销完成后可结案
        post("/api/v1/voyages/" + voyageId + "/closure", requestIdBody("dev-review-close"), 200);
        assertEquals("CLOSED", fixtures.voyageStatus(voyageId));
    }

    @Test
    @DisplayName("复核仅解冻不扣减：解除冻结后按常规核销")
    void resolutionWithoutFinalItemsOnlyUnfreezes() throws Exception {
        long voyageId = departAndLog("dev-unfreeze", "1000");
        JsonNode arrival = post("/api/v1/voyages/" + voyageId + "/arrival",
                arrivalBody("dev-unfreeze-arr", "2026-03-05T10:00:00Z", "计数误差",
                        "COD", "1040"), 200);
        long reviewId = arrival.get("reviewId").asLong();
        assertTrue(fixtures.ledgerFrozen(1, "COD", 2026));

        ObjectNode resolution = requestIdBody("dev-unfreeze-res");
        resolution.put("conclusion", "差异可接受，按原卸渔量核销");
        post("/api/v1/reviews/" + reviewId + "/resolution", resolution, 200);
        assertFalse(fixtures.ledgerFrozen(1, "COD", 2026));
        assertEquals("ARRIVED", fixtures.voyageStatus(voyageId));

        post("/api/v1/voyages/" + voyageId + "/settlement",
                requestIdBody("dev-unfreeze-set"), 200);
        assertEquals(0, fixtures.usedQuota(1, "COD", 2026).compareTo(new BigDecimal("1040")));
    }
}
