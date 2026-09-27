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
 * 互认真实并发边界（H2 行锁/唯一索引验证，非 mock）：
 * 并发导入同编号报告唯一受理；同项目并发互认唯一有效绑定；
 * 并发更正与发证冻结一致修订，最终状态满足不变量。
 */
class FlagRecognitionConcurrencyTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    private static final String COMPLETED_AT = "2026-06-10T00:00:00Z";

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

    private Map<String, Object> importBody(String requestId, String reportNo, String imo) {
        return Map.of("requestId", requestId, "reportNo", reportNo, "shipImo", imo,
                "flagCode", "SYN-FLAG", "completedAt", COMPLETED_AT,
                "attestation", "SYNTHETIC-ATTEST",
                "items", List.of(Map.of("itemCode", "FLAG-HULL", "conclusion", "PASS")));
    }

    private Map<String, Object> recognitionBody(String requestId, String reportNo, long surveyId) {
        return Map.of("requestId", requestId, "reportNo", reportNo,
                "surveyId", surveyId, "ruleVersion", 1, "itemCode", "FLAG-HULL");
    }

    private Map<String, Object> correctBody(String requestId, String reportNo, int expected) {
        return Map.of("requestId", requestId, "reportNo", reportNo,
                "expectedVersion", expected, "completedAt", "2026-06-12T00:00:00Z",
                "attestation", "SYNTHETIC-ATTEST",
                "items", List.of(Map.of("itemCode", "FLAG-HULL", "conclusion", "PASS")));
    }

    private long registerAndApply(String suffix, String imo) {
        postExpectStatus("/api/ships", shipBody("ship-" + suffix, imo, ANNIVERSARY, DELIVERY), 200);
        return postExpectStatus("/api/surveys", applyBody("apply-" + suffix, imo, "ANNUAL"), 200)
                .get("data").get("id").asLong();
    }

    @Test
    void parallelImportSameReportNoCreatesExactlyOneReport() throws Exception {
        List<HttpResult> results = runParallel(6, () ->
                postSync("/api/flag/reports",
                        importBody("imp-p" + java.util.UUID.randomUUID(), "FR-P1", "IMO-FP1")));

        long ok = results.stream().filter(r -> r.status == 200).count();
        assertEquals(6, ok, "同参并发导入全部 200（一个首插，其余按重复处理）");
        long created = results.stream()
                .filter(r -> !r.body.get("data").get("existing").asBoolean()).count();
        assertEquals(1, created, "恰好一个真实新建");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_report WHERE report_no='FR-P1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_report_revision r JOIN flag_report p ON r.report_id=p.id"
                        + " WHERE p.report_no='FR-P1'", Integer.class),
                "并发导入不产生重复修订");
    }

    @Test
    void parallelRecognitionSameItemOnlyOneActive() throws Exception {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("pr", "IMO-FPR");
        postExpectStatus("/api/flag/reports", importBody("imp-pr", "FR-PR", "IMO-FPR"), 200);

        List<HttpResult> results = runParallel(6, () ->
                postSync("/api/flag/recognitions",
                        recognitionBody("rec-p" + java.util.UUID.randomUUID(), "FR-PR", surveyId)));

        long ok = results.stream().filter(r -> r.status == 200).count();
        long exists = results.stream()
                .filter(r -> r.status == 409
                        && "RECOGNITION_EXISTS".equals(r.body.get("code").asText()))
                .count();
        assertEquals(1, ok, "恰好一个互认受理");
        assertEquals(5, exists, "其余并发申请全部 409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_recognition WHERE survey_id=" + surveyId
                        + " AND active_flag='Y'", Integer.class),
                "每个项目只一个有效绑定");
    }

    @Test
    void parallelCorrectAndCertifyFreezeConsistentRevision() throws Exception {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("cc", "IMO-FCC");
        postExpectStatus("/api/flag/reports", importBody("imp-cc", "FR-CC", "IMO-FCC"), 200);
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-cc", "FR-CC", surveyId), 200);
        postExpectStatus("/api/surveys/board",
                boardBody("bd-cc", surveyId, LocalDate.of(2026, 6, 15), List.of()), 200);

        // 并发：报告更正 vs 发证（各 4 个请求交错）
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        List<HttpResult> results = runParallel(8, () -> {
            if (seq.getAndIncrement() % 2 == 0) {
                return postSync("/api/flag/reports/correct",
                        correctBody("cor-cc-" + java.util.UUID.randomUUID(), "FR-CC", 1));
            }
            return postSync("/api/certificates/certify",
                    certifyBody("cert-cc-" + java.util.UUID.randomUUID(), surveyId));
        });

        for (HttpResult r : results) {
            assertTrue(r.status == 200 || r.status == 409 || r.status == 422,
                    "不允许 5xx: " + r.status + " " + r.body);
        }
        // 更正：恰好一个成功推进版本，其余 409 VERSION_CONFLICT（或重放）
        long correctOk = results.stream().filter(r -> r.status == 200
                && r.body.get("data") != null && r.body.get("data").has("currentRevision")).count();
        assertEquals(1, correctOk, "并发更正恰好一个成功");
        assertEquals(2, jdbc.queryForObject(
                "SELECT current_revision FROM flag_report WHERE report_no='FR-CC'", Integer.class),
                "报告最终修订为 2");

        // 最终一致性不变量：检验若已发证且互认仍 ACTIVE，必须另有风险记录；
        // 互认若 INVALIDATED，失效时刻必须存在。随后补发证必须成功（失效互认不阻断门禁）。
        JsonNode chain = get("/api/flag/surveys/" + surveyId + "/evidence-chain");
        String surveyStatus = chain.get("survey").get("status").asText();
        String recStatus = chain.get("recognitions").get(0).get("status").asText();
        int riskCount = chain.get("riskNotes").size();
        if ("CERTIFIED".equals(surveyStatus) && "ACTIVE".equals(recStatus)) {
            assertEquals(1, riskCount,
                    "发证先于更正生效：历史互认保留且另记风险");
        } else if ("INVALIDATED".equals(recStatus)) {
            assertTrue(chain.get("recognitions").get(0).get("invalidatedAt") != null
                    && !chain.get("recognitions").get(0).get("invalidatedAt").isNull());
            assertEquals(0, riskCount, "未签发时失效不记风险");
            if (!"CERTIFIED".equals(surveyStatus)) {
                postExpectStatus("/api/certificates/certify",
                        certifyBody("cert-cc-final", surveyId), 200);
            }
        } else {
            // 更正后检验仍未签发且互认仍 ACTIVE 不可能：更正原子失效未签发检验的互认。
            throw new AssertionError("不一致终态: survey=" + surveyStatus + " rec=" + recStatus);
        }
    }
}
