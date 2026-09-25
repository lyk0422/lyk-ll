package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 年度检验窗口：恰在窗口首日/末日完成有效，窗口外拒绝；
 * 申请同键同参重放、同键异参 409。
 */
class SurveyWindowFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);

    private String registerShip(String reqId, String imo) {
        JsonNode res = postExpectStatus("/api/ships",
                shipBody(reqId, imo, ANNIVERSARY, DELIVERY), 200);
        return res.get("data").get("id").asText();
    }

    @Test
    void applyAndBoardExactlyOnWindowFirstDaySucceeds() {
        registerShip("ship-1", "IMO-W1");
        setClockDate(LocalDate.of(2026, 3, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("apply-first-day", "IMO-W1", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        assertEquals("2026-03-15", applied.get("data").get("windowStart").asText());
        assertFalse(applied.get("replayed").asBoolean());

        JsonNode boarded = postExpectStatus("/api/surveys/board",
                boardBody("board-first-day", surveyId, LocalDate.of(2026, 3, 15),
                        java.util.List.of()), 200);
        assertEquals("BOARDED", boarded.get("data").get("survey").get("status").asText());
        assertFalse(boarded.get("data").get("acceptedAsLate").asBoolean());
    }

    @Test
    void applyAndBoardExactlyOnWindowLastDaySucceeds() {
        registerShip("ship-2", "IMO-W2");
        setClockDate(LocalDate.of(2026, 9, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("apply-last-day", "IMO-W2", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        assertEquals("2026-09-15", applied.get("data").get("windowEnd").asText());

        JsonNode boarded = postExpectStatus("/api/surveys/board",
                boardBody("board-last-day", surveyId, LocalDate.of(2026, 9, 15),
                        java.util.List.of()), 200);
        assertEquals("BOARDED", boarded.get("data").get("survey").get("status").asText());
    }

    @Test
    void applyOneDayBeforeWindowIsRejected() {
        registerShip("ship-3", "IMO-W3");
        setClockDate(LocalDate.of(2026, 3, 14));
        JsonNode err = postExpectStatus("/api/surveys",
                applyBody("apply-too-early", "IMO-W3", "ANNUAL"), 422);
        assertEquals("OUTSIDE_WINDOW", err.get("code").asText());
    }

    @Test
    void applyOneDayAfterWindowIsRejected() {
        registerShip("ship-4", "IMO-W4");
        setClockDate(LocalDate.of(2026, 9, 16));
        JsonNode err = postExpectStatus("/api/surveys",
                applyBody("apply-too-late", "IMO-W4", "ANNUAL"), 422);
        assertEquals("OUTSIDE_WINDOW", err.get("code").asText());
    }

    @Test
    void boardOneDayOutsideWindowIsRejected() {
        registerShip("ship-5", "IMO-W5");
        setClockDate(LocalDate.of(2026, 3, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("apply-b5", "IMO-W5", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();

        JsonNode err = postExpectStatus("/api/surveys/board",
                boardBody("board-b5", surveyId, LocalDate.of(2026, 3, 14),
                        java.util.List.of()), 422);
        assertEquals("OUTSIDE_WINDOW", err.get("code").asText());
    }

    @Test
    void sameRequestSameParamsReplaysFirstResult() {
        registerShip("ship-6", "IMO-W6");
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode first = postExpectStatus("/api/surveys",
                applyBody("replay-key", "IMO-W6", "ANNUAL"), 200);
        long firstId = first.get("data").get("id").asLong();
        assertFalse(first.get("replayed").asBoolean());

        JsonNode again = postExpectStatus("/api/surveys",
                applyBody("replay-key", "IMO-W6", "ANNUAL"), 200);
        assertTrue(again.get("replayed").asBoolean());
        assertEquals(firstId, again.get("data").get("id").asLong());
        // 重放不产生第二条检验。
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM survey WHERE request_id = 'replay-key'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void sameRequestDifferentParamsReturns409AndCreatesNothing() {
        registerShip("ship-7", "IMO-W7");
        setClockDate(LocalDate.of(2026, 6, 15));
        postExpectStatus("/api/surveys",
                applyBody("conflict-key", "IMO-W7", "ANNUAL"), 200);

        JsonNode err = postExpectStatus("/api/surveys",
                applyBody("conflict-key", "IMO-W7", "INTERMEDIATE"), 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", err.get("code").asText());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM survey WHERE ship_id = "
                        + "(SELECT id FROM ship WHERE imo='IMO-W7')", Integer.class);
        assertEquals(1, count);
    }
}
