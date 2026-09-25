package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 展期规则：仅年度检验可展期、仅一次、最长恰满 3 个月；
 * 批准与原证书有效期、检验窗口末日在同一事务内原子更新；中间/特别检验禁展期。
 */
class ExtensionFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);

    private void registerShip(String reqId, String imo) {
        postExpectStatus("/api/ships",
                shipBody(reqId, imo, ANNIVERSARY, DELIVERY), 200);
    }

    /** 完成一整轮年度检验并发证，返回 (surveyId, certId)。 */
    private long[] completeAnnualCycle(String imo, String tag, LocalDate day) {
        setClockDate(day);
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("ap-" + tag, imo, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-" + tag, surveyId, day, List.of()), 200);
        JsonNode cert = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-" + tag, surveyId), 200);
        return new long[]{surveyId, cert.get("data").get("certificate").get("id").asLong()};
    }

    @Test
    void annualExtensionExactlyThreeMonthsApprovedAtomically() {
        registerShip("ship-ext", "IMO-EXT");
        // 第一周期 2026 窗口首日完成并发证：旧证有效期至 2027-09-15。
        long[] first = completeAnnualCycle("IMO-EXT", "e1", LocalDate.of(2026, 3, 15));
        long oldCertId = first[1];

        // 第二周期在窗口末日（2027-09-15）申请新检验并登轮。
        setClockDate(LocalDate.of(2027, 9, 15));
        JsonNode applied2 = postExpectStatus("/api/surveys",
                applyBody("ap-e2", "IMO-EXT", "ANNUAL"), 200);
        long survey2 = applied2.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-e2", survey2, LocalDate.of(2027, 9, 15), List.of()), 200);

        // 申请恰满 3 个月展期。
        JsonNode ext = postExpectStatus("/api/extensions",
                extensionApplyBody("ext-apply", survey2, 3), 200);
        long extensionId = ext.get("data").get("id").asLong();
        assertEquals("PENDING", ext.get("data").get("status").asText());
        assertEquals("2027-09-15", ext.get("data").get("oldValidUntil").asText());

        // 批准：旧证有效期与窗口末日同事务延后恰 3 个月至 2027-12-15。
        JsonNode approved = postExpectStatus("/api/extensions/approve",
                extensionApproveBody("ext-approve", extensionId), 200);
        assertEquals("APPROVED", approved.get("data").get("status").asText());
        assertEquals("2027-12-15", approved.get("data").get("newValidUntil").asText());

        assertEquals("2027-12-15", jdbc.queryForObject(
                "SELECT valid_until FROM certificate WHERE id=?", String.class, oldCertId));
        assertEquals("2027-12-15", jdbc.queryForObject(
                "SELECT extension_until FROM survey WHERE id=?", String.class, survey2));
        assertEquals("VALID", jdbc.queryForObject(
                "SELECT status FROM certificate WHERE id=?", String.class, oldCertId));
    }

    @Test
    void extensionAllowedOnlyOncePerSurvey() {
        registerShip("ship-once", "IMO-ONCE");
        completeAnnualCycle("IMO-ONCE", "o1", LocalDate.of(2026, 3, 15));
        setClockDate(LocalDate.of(2027, 6, 15));
        JsonNode applied2 = postExpectStatus("/api/surveys",
                applyBody("ap-o2", "IMO-ONCE", "ANNUAL"), 200);
        long survey2 = applied2.get("data").get("id").asLong();

        postExpectStatus("/api/extensions",
                extensionApplyBody("ext-once-1", survey2, 1), 200);
        JsonNode again = postExpectStatus("/api/extensions",
                extensionApplyBody("ext-once-2", survey2, 1), 409);
        assertEquals("EXTENSION_EXISTS", again.get("code").asText());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM extension_request WHERE survey_id=?",
                Integer.class, survey2);
        assertEquals(1, count);
    }

    @Test
    void specialSurveyCannotBeExtended() {
        registerShip("ship-sp", "IMO-SPEXT");
        // 特别检验窗口：交付日 2020-03-01 起每 5 年，2025-03-01 前后各 3 个月。
        setClockDate(LocalDate.of(2025, 3, 1));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("ap-sp", "IMO-SPEXT", "SPECIAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-sp", surveyId, LocalDate.of(2025, 3, 1), List.of()), 200);

        JsonNode err = postExpectStatus("/api/extensions",
                extensionApplyBody("ext-sp", surveyId, 3), 422);
        assertEquals("EXTENSION_NOT_ALLOWED", err.get("code").asText());

        // 中间检验同样禁展期：2022 年第二个周年窗口。
        registerShip("ship-im", "IMO-IMEXT");
        setClockDate(LocalDate.of(2022, 3, 1));
        JsonNode appliedIm = postExpectStatus("/api/surveys",
                applyBody("ap-im", "IMO-IMEXT", "INTERMEDIATE"), 200);
        JsonNode errIm = postExpectStatus("/api/extensions",
                extensionApplyBody("ext-im", appliedIm.get("data").get("id").asLong(), 2), 422);
        assertEquals("EXTENSION_NOT_ALLOWED", errIm.get("code").asText());
    }

    @Test
    void extensionBeyondThreeMonthsRejected() {
        registerShip("ship-4m", "IMO-4M");
        completeAnnualCycle("IMO-4M", "x1", LocalDate.of(2026, 3, 15));
        setClockDate(LocalDate.of(2027, 6, 15));
        JsonNode applied2 = postExpectStatus("/api/surveys",
                applyBody("ap-x2", "IMO-4M", "ANNUAL"), 200);
        long survey2 = applied2.get("data").get("id").asLong();

        // Bean Validation @Max(3) → 400。
        ResponseEntityLike raw = postStatus("/api/extensions",
                extensionApplyBody("ext-4m", survey2, 4));
        assertEquals(400, raw.status);
    }

    @Test
    void approveRollsBackWhenCertificateNoLongerValid() {
        registerShip("ship-rb", "IMO-RB");
        long[] first = completeAnnualCycle("IMO-RB", "rb1", LocalDate.of(2026, 3, 15));
        long oldCertId = first[1];
        setClockDate(LocalDate.of(2027, 6, 15));
        JsonNode applied2 = postExpectStatus("/api/surveys",
                applyBody("ap-rb2", "IMO-RB", "ANNUAL"), 200);
        long survey2 = applied2.get("data").get("id").asLong();
        JsonNode ext = postExpectStatus("/api/extensions",
                extensionApplyBody("ext-rb", survey2, 2), 200);
        long extensionId = ext.get("data").get("id").asLong();

        // 证书在批准前已失效：批准必须整体失败，展期状态不得停留在“已批准而证书未更新”。
        jdbc.update("UPDATE certificate SET status='EXPIRED', valid_flag=NULL WHERE id=?",
                oldCertId);
        JsonNode err = postExpectStatus("/api/extensions/approve",
                extensionApproveBody("ext-rb-approve", extensionId), 422);
        assertEquals("CERTIFICATE_NOT_VALID", err.get("code").asText());

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM extension_request WHERE id=?", String.class, extensionId));
        // 未写入新有效期、未延后窗口：不存在“已批准而证书未更新”的中间态。
        assertTrue(jdbc.queryForObject(
                "SELECT new_valid_until IS NULL FROM extension_request WHERE id=?",
                Boolean.class, extensionId));
        assertTrue(jdbc.queryForObject(
                "SELECT extension_until IS NULL FROM survey WHERE id=?",
                Boolean.class, survey2));
    }

    /** 轻量状态包装，避免在断言辅助中引入静态导入冲突。 */
    private record ResponseEntityLike(int status) {
    }

    private ResponseEntityLike postStatus(String path, Object body) {
        return new ResponseEntityLike(postRaw(path, body).getStatusCode().value());
    }
}
