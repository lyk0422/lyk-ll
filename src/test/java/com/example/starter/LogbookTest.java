package com.example.starter;

import com.example.starter.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渔捞日志：跨日作业按作业时刻 UTC 日界归属、乱序归位、兼捕 5% 边界、按“船＋日期＋渔区”去重。
 */
class LogbookTest extends ApiTestSupport {

    private long depart(String requestId) throws Exception {
        fixtures.setQuota(1, "COD", 2026, "10000");
        fixtures.setQuota(1, "HAR", 2026, "10000");
        fixtures.setQuota(1, "PSH", 2026, "10000");
        JsonNode departure = post("/api/v1/departures",
                departureBody(requestId, 1, "2026-03-01T08:00:00Z", "A1", "COD", "100"), 200);
        return departure.get("voyageId").asLong();
    }

    @Test
    @DisplayName("跨日作业按作业时刻日界划分，乱序明细按作业时刻归位")
    void crossDayItemsAssignedByOperationTime() throws Exception {
        long voyageId = depart("log-boundary-dep");
        // 乱序提交：先 03-02 的明细，后 03-01 的明细
        JsonNode response = post("/api/v1/voyages/" + voyageId + "/logs",
                logBody("log-boundary", "A1",
                        new String[]{"HAR", "50", "2026-03-02T00:30:00Z"},
                        new String[]{"COD", "100", "2026-03-01T23:30:00Z"}), 200);
        JsonNode logs = response.get("logs");
        assertEquals(2, logs.size());
        assertEquals("2026-03-01", logs.get(0).get("logDate").asText());
        assertEquals("2026-03-02", logs.get(1).get("logDate").asText());
        assertEquals(1, fixtures.logCount(1, "2026-03-01", "A1"));
        assertEquals(1, fixtures.logCount(1, "2026-03-02", "A1"));
    }

    @Test
    @DisplayName("兼捕保护鱼种恰 5%：合法，不标注不立案")
    void bycatchExactlyFivePercentLegal() throws Exception {
        long voyageId = depart("log-5pct-dep");
        JsonNode response = post("/api/v1/voyages/" + voyageId + "/logs",
                logBody("log-5pct", "A1",
                        new String[]{"COD", "950", "2026-03-01T10:00:00Z"},
                        new String[]{"PSH", "50", "2026-03-01T11:00:00Z"}), 200);
        assertFalse(response.get("logs").get(0).get("bycatchFlagged").asBoolean());
        assertEquals(0, fixtures.openReviews(voyageId, "BYCATCH"));
    }

    @Test
    @DisplayName("兼捕保护鱼种超过 5%：日志单独标注并触发审查")
    void bycatchAboveFivePercentFlaggedAndReviewed() throws Exception {
        long voyageId = depart("log-over5-dep");
        JsonNode response = post("/api/v1/voyages/" + voyageId + "/logs",
                logBody("log-over5", "A1",
                        new String[]{"COD", "949.999", "2026-03-01T10:00:00Z"},
                        new String[]{"PSH", "50.001", "2026-03-01T11:00:00Z"}), 200);
        assertTrue(response.get("logs").get(0).get("bycatchFlagged").asBoolean());
        assertEquals(1, fixtures.openReviews(voyageId, "BYCATCH"));
    }

    @Test
    @DisplayName("同一船＋日期＋渔区重复日志：去重拒绝（409）")
    void duplicateLogKeyRejected() throws Exception {
        long voyageId = depart("log-dup-dep");
        post("/api/v1/voyages/" + voyageId + "/logs",
                logBody("log-dup-1", "A1",
                        new String[]{"COD", "100", "2026-03-01T10:00:00Z"}), 200);
        JsonNode response = post("/api/v1/voyages/" + voyageId + "/logs",
                logBody("log-dup-2", "A1",
                        new String[]{"COD", "200", "2026-03-01T18:00:00Z"}), 409);
        assertEquals("CONFLICT", response.get("code").asText());
        assertEquals(1, fixtures.logCount(1, "2026-03-01", "A1"));
    }

    @Test
    @DisplayName("同 requestId 日志重放返回首次结果，不重复入库")
    void logIdempotentReplay() throws Exception {
        long voyageId = depart("log-idem-dep");
        JsonNode first = post("/api/v1/voyages/" + voyageId + "/logs",
                logBody("log-idem", "A1",
                        new String[]{"COD", "100", "2026-03-01T10:00:00Z"}), 200);
        JsonNode replay = post("/api/v1/voyages/" + voyageId + "/logs",
                logBody("log-idem", "A1",
                        new String[]{"COD", "100", "2026-03-01T10:00:00Z"}), 200);
        assertEquals(first.get("logs").get(0).get("logId").asLong(),
                replay.get("logs").get(0).get("logId").asLong());
        assertEquals(1, fixtures.logCount(1, "2026-03-01", "A1"));
    }
}
