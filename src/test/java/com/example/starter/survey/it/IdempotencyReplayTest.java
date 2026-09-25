package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 申请、检验（登轮）、整改、发证各环节的 requestId 幂等：
 * 同键同参返回首次结果；同键异参 409 且不产生重复缺陷/证书。
 */
class IdempotencyReplayTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);

    private void registerShip(String reqId, String imo) {
        postExpectStatus("/api/ships",
                shipBody(reqId, imo, ANNIVERSARY, DELIVERY), 200);
    }

    @Test
    void boardSameKeyReplaysWithoutDuplicateDefects() {
        registerShip("ship-b", "IMO-BRD");
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("ap-b", "IMO-BRD", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();

        List<java.util.Map<String, Object>> defects = List.of(
                defectInput("MINOR", "灭火器过期", LocalDate.of(2026, 6, 1)));
        JsonNode first = postExpectStatus("/api/surveys/board",
                boardBody("board-key", surveyId, LocalDate.of(2026, 6, 15), defects), 200);
        assertEquals(1, first.get("data").get("defects").size());

        JsonNode replay = postExpectStatus("/api/surveys/board",
                boardBody("board-key", surveyId, LocalDate.of(2026, 6, 15), defects), 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(1, replay.get("data").get("defects").size());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM defect WHERE survey_id=?", Integer.class, surveyId));
        // 同键异参（不同完成日）→ 409，状态不被篡改。
        JsonNode conflict = postExpectStatus("/api/surveys/board",
                boardBody("board-key", surveyId, LocalDate.of(2026, 6, 16), defects), 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM defect WHERE survey_id=?", Integer.class, surveyId));
    }

    @Test
    void rectifySameKeyReplaysFirstDecision() {
        registerShip("ship-r", "IMO-RCT");
        setClockDate(LocalDate.of(2026, 3, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("ap-r", "IMO-RCT", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-r", surveyId, LocalDate.of(2026, 3, 15),
                        List.of(defectInput("MAJOR", "舵机故障", LocalDate.of(2026, 3, 15)))),
                200);
        long defectId = jdbc.queryForObject(
                "SELECT id FROM defect WHERE survey_id=?", Long.class, surveyId);

        JsonNode first = postExpectStatus("/api/defects/rectify",
                rectifyBody("rect-key", defectId, LocalDate.of(2026, 3, 20)), 200);
        assertTrue(first.get("data").get("accepted").asBoolean());

        JsonNode replay = postExpectStatus("/api/defects/rectify",
                rectifyBody("rect-key", defectId, LocalDate.of(2026, 3, 20)), 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals("RECTIFIED", replay.get("data").get("defect").get("status").asText());

        JsonNode conflict = postExpectStatus("/api/defects/rectify",
                rectifyBody("rect-key", defectId, LocalDate.of(2026, 3, 21)), 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM defect WHERE id=? AND status='RECTIFIED'",
                Integer.class, defectId));
    }

    @Test
    void certifySameKeyDifferentParamsConflictsWithoutNewCertificate() {
        registerShip("ship-c", "IMO-CRT");
        setClockDate(LocalDate.of(2026, 3, 15));
        JsonNode s1 = postExpectStatus("/api/surveys",
                applyBody("ap-c1", "IMO-CRT", "ANNUAL"), 200);
        long survey1 = s1.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-c1", survey1, LocalDate.of(2026, 3, 15), List.of()), 200);
        JsonNode cert = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-key", survey1), 200);
        assertEquals(1, cert.get("data").get("certificate").get("version").asInt());

        // 第二个周期再发证，但复用第一个 requestId：同键异参 409，不产生新证书。
        setClockDate(LocalDate.of(2027, 6, 15));
        JsonNode s2 = postExpectStatus("/api/surveys",
                applyBody("ap-c2", "IMO-CRT", "ANNUAL"), 200);
        long survey2 = s2.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-c2", survey2, LocalDate.of(2027, 6, 15), List.of()), 200);
        JsonNode conflict = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-key", survey2), 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText());

        // 仍只有第一版证书有效，第二检验未发证。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE ship_id="
                        + "(SELECT id FROM ship WHERE imo='IMO-CRT') AND valid_flag='Y'",
                Integer.class));
        assertEquals("BOARDED", jdbc.queryForObject(
                "SELECT status FROM survey WHERE id=?", String.class, survey2));
    }
}
