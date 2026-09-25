package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可复算性：窗口台账清空重建结果一致；缺陷链按序可查；
 * 证书版本链可复算；特别检验证书有效期按 5 年周期；展期重复批准幂等。
 */
class RecomputationFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);

    private void registerShip(String reqId, String imo) {
        postExpectStatus("/api/ships",
                shipBody(reqId, imo, ANNIVERSARY, DELIVERY), 200);
    }

    @Test
    void windowLedgerRecomputesDeterministically() {
        registerShip("ship-led", "IMO-LED");
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("ap-led", "IMO-LED", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();

        JsonNode first = postExpectStatus("/api/ledger/recompute",
                java.util.Map.of(), 200);
        int rows1 = first.get("rows").asInt();
        assertTrue(rows1 >= 3, "三类窗口至少各有一行");

        // 2026 年度窗口行锚点/首尾日复算正确，并关联到已申请检验。
        JsonNode row2026 = findRow(get("/api/ledger"), "ANNUAL", "2026-06-15");
        assertEquals("2026-03-15", row2026.get("windowStart").asText());
        assertEquals("2026-09-15", row2026.get("windowEnd").asText());
        assertEquals(surveyId, row2026.get("surveyId").asLong());
        assertTrue(row2026.get("extensionUntil").isNull());

        // 批准展期后重建，该行展期末日复算为窗口末日 +3 个月。
        postExpectStatus("/api/surveys/board",
                boardBody("bd-led", surveyId, LocalDate.of(2026, 6, 15), List.of()), 200);
        postExpectStatus("/api/certificates/certify",
                certifyBody("cert-led", surveyId), 200);
        setClockDate(LocalDate.of(2027, 6, 15));
        JsonNode applied2 = postExpectStatus("/api/surveys",
                applyBody("ap-led2", "IMO-LED", "ANNUAL"), 200);
        long survey2 = applied2.get("data").get("id").asLong();
        JsonNode ext = postExpectStatus("/api/extensions",
                extensionApplyBody("ext-led", survey2, 3), 200);
        postExpectStatus("/api/extensions/approve",
                extensionApproveBody("ext-led-ap", ext.get("data").get("id").asLong()), 200);

        postExpectStatus("/api/ledger/recompute", java.util.Map.of(), 200);
        JsonNode row2027 = findRow(get("/api/ledger"), "ANNUAL", "2027-06-15");
        assertEquals("2027-12-15", row2027.get("extensionUntil").asText());
        assertEquals(survey2, row2027.get("surveyId").asLong());

        // 再重建一次：先清空再复算，展期末日保持确定一致。
        postExpectStatus("/api/ledger/recompute", java.util.Map.of(), 200);
        assertEquals("2027-12-15",
                findRow(get("/api/ledger"), "ANNUAL", "2027-06-15")
                        .get("extensionUntil").asText());
    }

    private JsonNode findRow(JsonNode array, String type, String anchor) {
        if (!array.isArray()) {
            throw new AssertionError("台账响应不是数组: " + array);
        }
        for (JsonNode row : array) {
            if (type.equals(row.path("type").asText(null))
                    && anchor.equals(row.path("cycleAnchor").asText(null))) {
                return row;
            }
        }
        throw new AssertionError("未找到台账行: " + type + " " + anchor
                + "，实际内容: " + array);
    }

    @Test
    void defectChainAndCertificateVersionChainAreQueryable() {
        registerShip("ship-chain2", "IMO-CH2");
        setClockDate(LocalDate.of(2026, 3, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("ap-ch2", "IMO-CH2", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board", boardBody("bd-ch2", surveyId,
                LocalDate.of(2026, 3, 15),
                List.of(defectInput("MAJOR", "缺陷一", LocalDate.of(2026, 3, 10)),
                        defectInput("MINOR", "缺陷二", LocalDate.of(2026, 3, 11)))), 200);

        // 缺陷链按发现顺序返回，截止日复算（重大无固定期限，一般 +30 日）。
        JsonNode chain = readTree(getDefects(surveyId));
        assertEquals(2, chain.size());
        assertEquals("缺陷一", chain.get(0).get("description").asText());
        assertTrue(chain.get(0).get("deadline").isNull());
        assertEquals("2026-04-10", chain.get(1).get("deadline").asText());

        // 整改重大缺陷并发证，版本链第一版可查。
        postExpectStatus("/api/defects/rectify",
                rectifyBody("rect-ch2", chain.get(0).get("id").asLong(),
                        LocalDate.of(2026, 3, 20)), 200);
        postExpectStatus("/api/certificates/certify",
                certifyBody("cert-ch2", surveyId), 200);
        JsonNode versions = readTree(getVersions("IMO-CH2", "ANNUAL"));
        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0).get("version").asInt());
        assertEquals("VALID", versions.get(0).get("status").asText());
    }

    private String getDefects(long surveyId) {
        return rest.getRestTemplate().getForEntity(
                url("/api/surveys/" + surveyId + "/defects"), String.class).getBody();
    }

    private String getVersions(String imo, String type) {
        return rest.getRestTemplate().getForEntity(
                url("/api/certificates/versions?shipImo=" + imo + "&type=" + type),
                String.class).getBody();
    }

    private JsonNode readTree(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void specialCertificateValiditySpansFiveYears() {
        registerShip("ship-spv", "IMO-SPV");
        setClockDate(LocalDate.of(2025, 3, 1));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("ap-spv", "IMO-SPV", "SPECIAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        assertEquals("2025-03-01", applied.get("data").get("cycleAnchor").asText());
        postExpectStatus("/api/surveys/board",
                boardBody("bd-spv", surveyId, LocalDate.of(2025, 3, 1), List.of()), 200);
        JsonNode cert = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-spv", surveyId), 200);
        // 特别检验：锚点 2025-03-01 +5 年 +3 个月 = 2030-06-01（含当日）。
        assertEquals("2030-06-01", cert.get("data").get("certificate").get("validUntil").asText());
    }

    @Test
    void repeatedApproveIsIdempotent() {
        registerShip("ship-apr", "IMO-APR");
        setClockDate(LocalDate.of(2026, 3, 15));
        JsonNode a1 = postExpectStatus("/api/surveys",
                applyBody("ap-apr1", "IMO-APR", "ANNUAL"), 200);
        long s1 = a1.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-apr1", s1, LocalDate.of(2026, 3, 15), List.of()), 200);
        postExpectStatus("/api/certificates/certify",
                certifyBody("cert-apr1", s1), 200);

        setClockDate(LocalDate.of(2027, 6, 15));
        JsonNode a2 = postExpectStatus("/api/surveys",
                applyBody("ap-apr2", "IMO-APR", "ANNUAL"), 200);
        long s2 = a2.get("data").get("id").asLong();
        JsonNode ext = postExpectStatus("/api/extensions",
                extensionApplyBody("ext-apr", s2, 2), 200);
        long extensionId = ext.get("data").get("id").asLong();

        JsonNode ok = postExpectStatus("/api/extensions/approve",
                extensionApproveBody("apv-apr", extensionId), 200);
        assertEquals("APPROVED", ok.get("data").get("status").asText());

        // 同键重放。
        JsonNode replay = postExpectStatus("/api/extensions/approve",
                extensionApproveBody("apv-apr", extensionId), 200);
        assertTrue(replay.get("replayed").asBoolean());
        // 换键重复批准：幂等成功，状态仍 APPROVED，有效期只延后一次。
        JsonNode again = postExpectStatus("/api/extensions/approve",
                extensionApproveBody("apv-apr-2", extensionId), 200);
        assertEquals("APPROVED", again.get("data").get("status").asText());
        assertEquals("2027-11-15", again.get("data").get("newValidUntil").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM extension_request WHERE survey_id=?", Integer.class, s2));
    }
}
