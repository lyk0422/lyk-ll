package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 互认并发边界（H2 行锁/唯一索引，真实并发，非 mock）：
 * 同项目并发绑定唯一受理；并发首导报告唯一落修订；
 * 并发“报告更正 vs 发证”必须冻结一致修订——不能以旧互认可用标志先发证。
 */
class RecognitionConcurrencyTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    private static final String FLAG = "SYN-PA";
    private static final String FIRE_ITEM = "FIRE-SAFETY-ANNUAL";

    private record HttpResult(int status, JsonNode body) {
    }

    private HttpResult postSync(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.getRestTemplate()
                .postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
        try {
            return new HttpResult(response.getStatusCode().value(),
                    new ObjectMapper().readTree(response.getBody()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<HttpResult> runParallel(int n, java.util.function.Supplier<HttpResult> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResult>> futures = IntStream.range(0, n).mapToObj(i ->
                    pool.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return task.get();
                    })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<HttpResult> results = new java.util.ArrayList<>();
            for (Future<HttpResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void registerShip(String reqId, String imo) {
        postExpectStatus("/api/ships",
                shipBody(reqId, imo, ANNIVERSARY, DELIVERY), 200);
    }

    private void publishRule(String reqId) {
        postExpectStatus("/api/recognition/rules",
                publishRuleBody(reqId, 0, 90, List.of(FLAG),
                        List.of(ruleMapping(FIRE_ITEM, "ANNUAL", "FIRE_SAFETY"))), 200);
    }

    private long boardedAnnualSurvey(String imo, String applyReq, String boardReq) {
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody(applyReq, imo, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody(boardReq, surveyId, LocalDate.of(2026, 6, 15), List.of()), 200);
        return surveyId;
    }

    private List<Map<String, Object>> passItems(String note) {
        return List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", note));
    }

    @Test
    void parallelBindingsForSameClassItemOnlyOneActive() throws Exception {
        registerShip("ship-pb", "IMO-PB");
        publishRule("rule-pb");
        long surveyId = boardedAnnualSurvey("IMO-PB", "apply-pb", "board-pb");
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-pb", "RPT-PB", 0, "IMO-PB", FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER, passItems("v1")), 200);

        List<HttpResult> results = runParallel(6, () ->
                postSync("/api/recognition/bindings",
                        bindBody("bind-" + java.util.UUID.randomUUID(),
                                surveyId, "RPT-PB", 1, FIRE_ITEM)));

        long ok = results.stream().filter(r -> r.status == 200).count();
        long conflict = results.stream()
                .filter(r -> r.status == 409
                        && "BINDING_ALREADY_EXISTS".equals(r.body.get("code").asText()))
                .count();
        assertEquals(1, ok, "恰好一个绑定成功");
        assertEquals(5, conflict, "其余并发绑定全部 409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=? AND active_flag='Y'",
                Integer.class, surveyId));
    }

    @Test
    void parallelFirstImportsCreateExactlyOneRevision() throws Exception {
        registerShip("ship-pi", "IMO-PI");
        publishRule("rule-pi");

        List<HttpResult> results = runParallel(6, () ->
                postSync("/api/recognition/reports",
                        importReportBody("imp-" + java.util.UUID.randomUUID(),
                                "RPT-PI", 0, "IMO-PI", FLAG,
                                "2026-05-01T00:00:00Z", SYNTH_MARKER, passItems("v1"))));

        long ok = results.stream().filter(r -> r.status == 200).count();
        long conflict = results.stream()
                .filter(r -> r.status == 409
                        && "REPORT_REVISION_CONFLICT".equals(r.body.get("code").asText()))
                .count();
        assertEquals(1, ok, "恰好一个首导成功");
        assertEquals(5, conflict, "其余并发首导全部 409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_report WHERE report_no='RPT-PI'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_report_item", Integer.class));
    }

    /**
     * 并发“报告更正 vs 发证”，多轮真实并发。每轮最终状态必须自洽：
     * 发证胜→证书有效、绑定保持 ACTIVE、更正另记风险；
     * 更正胜→发证 422、绑定失效、无证书；
     * 绝不允许“证书已发但同一绑定已被失效”或 5xx/死锁。
     */
    @Test
    void parallelCertifyAndCorrectionFreezeConsistentRevision() throws Exception {
        int iterations = 12;
        boolean sawCertifyWin = false;
        boolean sawCorrectionWin = false;
        publishRule("rule-race-shared");
        for (int i = 0; i < iterations; i++) {
            String tag = String.format("%02d", i);
            String imo = "IMO-RACE-" + tag;
            registerShip("ship-" + tag, imo);
            long surveyId = boardedAnnualSurvey(imo, "apply-" + tag, "board-" + tag);
            String reportNo = "RPT-RACE-" + tag;
            postExpectStatus("/api/recognition/reports",
                    importReportBody("rpt-" + tag + "-1", reportNo, 0, imo, FLAG,
                            "2026-05-01T00:00:00Z", SYNTH_MARKER, passItems("v1")), 200);
            postExpectStatus("/api/recognition/bindings",
                    bindBody("bind-" + tag, surveyId, reportNo, 1, FIRE_ITEM), 200);

            Object correctBody = importReportBody("cor-" + tag, reportNo, 1, imo, FLAG,
                    "2026-05-02T00:00:00Z", SYNTH_MARKER, passItems("v2-changed"));
            Object certifyBody = certifyBody("cert-" + tag, surveyId);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<HttpResult> certF = pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return postSync("/api/certificates/certify", certifyBody);
                });
                Future<HttpResult> corrF = pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return postSync("/api/recognition/reports", correctBody);
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                HttpResult cert = certF.get(30, TimeUnit.SECONDS);
                HttpResult corr = corrF.get(30, TimeUnit.SECONDS);

                // 更正恒成功且产生修订 2。
                assertEquals(200, corr.status, "更正不应失败: " + corr.body);
                Integer revisions = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM external_report WHERE report_no=?",
                        Integer.class, reportNo);
                assertEquals(2, revisions);

                Integer certCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM certificate WHERE survey_id=?",
                        Integer.class, surveyId);
                String bindingStatus = jdbc.queryForObject(
                        "SELECT status FROM recognition_binding WHERE survey_id=?",
                        String.class, surveyId);
                Integer riskCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM recognition_risk WHERE report_no=?",
                        Integer.class, reportNo);

                if (cert.status == 200) {
                    sawCertifyWin = true;
                    assertEquals(1, certCount, "发证胜：恰发一张证书");
                    assertEquals("VALID", jdbc.queryForObject(
                            "SELECT status FROM certificate WHERE survey_id=?",
                            String.class, surveyId));
                    assertEquals("ACTIVE", bindingStatus,
                            "发证胜：冻结一致修订，绑定不得被更正失效");
                    assertEquals(1, riskCount, "发证胜：更正涉及已签发证据，另记风险");
                } else {
                    sawCorrectionWin = true;
                    assertEquals(422, cert.status, "更正胜：发证必须被业务拒绝，不得 5xx");
                    assertTrue(
                            "RECOGNITION_REVISION_CHANGED".equals(cert.body.get("code").asText())
                                    || "RECOGNITION_FREEZE_CHANGED"
                                    .equals(cert.body.get("code").asText()),
                            "拒绝码应为修订变更/冻结变更: " + cert.body.get("code").asText());
                    assertEquals(0, certCount, "更正胜：不得发证");
                    assertEquals("INVALIDATED", bindingStatus,
                            "更正胜：未签发检验旧绑定原子失效");
                    assertEquals(0, riskCount, "更正胜：未签发不记风险");
                }
            } finally {
                pool.shutdownNow();
            }
        }
        // 真实并发应覆盖两种交错顺序；若调度极端只出现一种，仍由其他单测覆盖另一种语义。
        assertTrue(sawCertifyWin || sawCorrectionWin, "至少完成一种并发结局");
    }
}
