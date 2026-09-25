package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷链与发证：重大缺陷拒发证、一般缺陷恰第 30 日受理、逾期证书失效、
 * 证书原子交接、签发终态幂等、迟到登轮只记事件。
 */
class DefectAndCertificateFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);

    private void registerShip(String reqId, String imo) {
        postExpectStatus("/api/ships",
                shipBody(reqId, imo, ANNIVERSARY, DELIVERY), 200);
    }

    /** 在窗口首日申请并登轮，返回 surveyId。 */
    private long applyAndBoard(String imo, String applyReq, String boardReq,
                               List<java.util.Map<String, Object>> defects) {
        setClockDate(LocalDate.of(2026, 3, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody(applyReq, imo, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        JsonNode boarded = postExpectStatus("/api/surveys/board",
                boardBody(boardReq, surveyId, LocalDate.of(2026, 3, 15), defects), 200);
        assertEquals("BOARDED", boarded.get("data").get("survey").get("status").asText());
        return surveyId;
    }

    @Test
    void majorDefectBlocksCertifyUntilRectified() {
        registerShip("ship-m", "IMO-MAJOR");
        long surveyId = applyAndBoard("IMO-MAJOR", "ap-m", "bd-m",
                List.of(defectInput("MAJOR", "主机底座裂纹", LocalDate.of(2026, 3, 15))));

        JsonNode blocked = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-m-1", surveyId), 422);
        assertEquals("MAJOR_DEFECT_OPEN", blocked.get("code").asText());

        long defectId = jdbc.queryForObject(
                "SELECT id FROM defect WHERE survey_id = ?", Long.class, surveyId);
        JsonNode rectified = postExpectStatus("/api/defects/rectify",
                rectifyBody("rect-m", defectId, LocalDate.of(2026, 3, 20)), 200);
        assertTrue(rectified.get("data").get("accepted").asBoolean());

        JsonNode certified = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-m-2", surveyId), 200);
        assertEquals("CERTIFIED",
                certified.get("data").get("survey").get("status").asText());
    }

    @Test
    void minorDefectRectifiedExactlyOnDay30IsAccepted() {
        registerShip("ship-d30", "IMO-D30");
        long surveyId = applyAndBoard("IMO-D30", "ap-d30", "bd-d30",
                List.of(defectInput("MINOR", "救生圈过期", LocalDate.of(2026, 3, 15))));

        JsonNode certified = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-d30", surveyId), 200);
        long certId = certified.get("data").get("certificate").get("id").asLong();

        long defectId = jdbc.queryForObject(
                "SELECT id FROM defect WHERE survey_id = ?", Long.class, surveyId);
        // 发现日 3/15 + 30 日 = 4/14，恰第 30 日提交受理。
        JsonNode rectified = postExpectStatus("/api/defects/rectify",
                rectifyBody("rect-d30", defectId, LocalDate.of(2026, 4, 14)), 200);
        assertTrue(rectified.get("data").get("accepted").asBoolean());
        assertEquals("RECTIFIED",
                rectified.get("data").get("defect").get("status").asText());
        assertTrue(rectified.get("data").get("invalidatedCertificateId").isNull());

        String status = jdbc.queryForObject(
                "SELECT status FROM certificate WHERE id = ?", String.class, certId);
        assertEquals("VALID", status);
    }

    @Test
    void minorDefectRectifiedOnDay31ExpiresCertificate() {
        registerShip("ship-d31", "IMO-D31");
        long surveyId = applyAndBoard("IMO-D31", "ap-d31", "bd-d31",
                List.of(defectInput("MINOR", "信号灯故障", LocalDate.of(2026, 3, 15))));
        JsonNode certified = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-d31", surveyId), 200);
        long certId = certified.get("data").get("certificate").get("id").asLong();

        long defectId = jdbc.queryForObject(
                "SELECT id FROM defect WHERE survey_id = ?", Long.class, surveyId);
        JsonNode late = postExpectStatus("/api/defects/rectify",
                rectifyBody("rect-d31", defectId, LocalDate.of(2026, 4, 15)), 200);
        assertFalse(late.get("data").get("accepted").asBoolean());
        assertEquals(certId, late.get("data").get("invalidatedCertificateId").asLong());

        String certStatus = jdbc.queryForObject(
                "SELECT status FROM certificate WHERE id = ?", String.class, certId);
        assertEquals("EXPIRED", certStatus);
        Integer validCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE ship_id = "
                        + "(SELECT id FROM ship WHERE imo='IMO-D31') AND valid_flag='Y'",
                Integer.class);
        assertEquals(0, validCount);
    }

    @Test
    void newCertificateSupersedesOldAtomically() {
        registerShip("ship-chain", "IMO-CHAIN");
        // 第一周期：2026 窗口首日用可控时钟完成并发证。
        long survey1 = applyAndBoard("IMO-CHAIN", "ap-c1", "bd-c1", List.of());
        JsonNode cert1 = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-c1", survey1), 200);
        long certId1 = cert1.get("data").get("certificate").get("id").asLong();
        assertEquals(1, cert1.get("data").get("certificate").get("version").asInt());

        // 第二周期：2027 周年日窗口再次申请、登轮、发证。
        setClockDate(LocalDate.of(2027, 6, 15));
        JsonNode applied2 = postExpectStatus("/api/surveys",
                applyBody("ap-c2", "IMO-CHAIN", "ANNUAL"), 200);
        long survey2 = applied2.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-c2", survey2, LocalDate.of(2027, 6, 15), List.of()), 200);
        JsonNode cert2 = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-c2", survey2), 200);
        long certId2 = cert2.get("data").get("certificate").get("id").asLong();
        assertEquals(2, cert2.get("data").get("certificate").get("version").asInt());
        assertEquals(certId1, cert2.get("data").get("supersededCertificateId").asLong());

        // 同一事务完成：旧证 SUPERSEDED 且记录被谁替代；新证 VALID；有效证书唯一。
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM certificate WHERE id=?", String.class, certId1));
        assertEquals(certId2, jdbc.queryForObject(
                "SELECT superseded_by FROM certificate WHERE id=?", Long.class, certId1));
        assertEquals("VALID", jdbc.queryForObject(
                "SELECT status FROM certificate WHERE id=?", String.class, certId2));
        Integer validCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE ship_id = "
                        + "(SELECT id FROM ship WHERE imo='IMO-CHAIN') AND valid_flag='Y'",
                Integer.class);
        assertEquals(1, validCount);
    }

    @Test
    void certifyIsTerminalAndRepeatedCertifyIsIdempotent() {
        registerShip("ship-term", "IMO-TERM");
        long surveyId = applyAndBoard("IMO-TERM", "ap-t", "bd-t", List.of());
        JsonNode first = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-t-1", surveyId), 200);
        long certId = first.get("data").get("certificate").get("id").asLong();

        // 换新 requestId 重复签发：幂等成功但不产生新版本（replayed 针对 requestId 为 false，
        // 业务结果仍是同一张证书）。
        JsonNode again = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-t-2", surveyId), 200);
        assertEquals(certId, again.get("data").get("certificate").get("id").asLong());
        assertEquals("CERTIFIED", again.get("data").get("survey").get("status").asText());

        // 同 requestId 重放：明确 replayed=true。
        JsonNode replay = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-t-2", surveyId), 200);
        assertTrue(replay.get("replayed").asBoolean());

        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE survey_id = ?", Integer.class, surveyId);
        assertEquals(1, versions);
    }

    @Test
    void lateBoardRecordAfterCertifyIsStoredAsEventOnly() {
        registerShip("ship-late", "IMO-LATE");
        long surveyId = applyAndBoard("IMO-LATE", "ap-l", "bd-l", List.of());
        postExpectStatus("/api/certificates/certify",
                certifyBody("cert-l", surveyId), 200);

        JsonNode late = postExpectStatus("/api/surveys/board",
                boardBody("board-late", surveyId, LocalDate.of(2026, 3, 16),
                        List.of(defectInput("MINOR", "迟到记录", LocalDate.of(2026, 3, 16)))),
                200);
        assertTrue(late.get("data").get("acceptedAsLate").asBoolean());
        assertEquals("CERTIFIED", late.get("data").get("survey").get("status").asText());

        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM survey_event WHERE survey_id=? AND event_type='LATE_BOARD_RECORD'",
                Integer.class, surveyId);
        assertEquals(1, events);
        // 迟到缺陷不进入缺陷链、不产生新证书。
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM defect WHERE survey_id=?", Integer.class, surveyId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE survey_id=?", Integer.class, surveyId));
    }
}
