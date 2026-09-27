package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 互认并发边界（H2 行锁/唯一索引验证，非 mock）：
 * 同检验同项目并发互认唯一受理；并发更正唯一新修订；
 * 并发更正与发证串行化——不以旧互认可用标志先发证。
 */
class RecognitionConcurrencyTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    private static final Instant COMPLETED =
            LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

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

    private void setUpRule() {
        postExpectStatus("/api/flag/rules",
                ruleBody("rule-cc", "SYN", 0, 90,
                        List.of(mapping("FLAG-HULL", "CLASS-HULL"))), 200);
    }

    private long setUpSurveyWithBinding(String reqPrefix, String imo, String reportNo) {
        postExpectStatus("/api/ships",
                shipBody(reqPrefix + "-ship", imo, ANNIVERSARY, DELIVERY), 200);
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody(reqPrefix + "-ap", imo, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody(reqPrefix + "-bd", surveyId, LocalDate.of(2026, 6, 15), List.of()),
                200);
        postExpectStatus("/api/flag/reports",
                reportBody(reqPrefix + "-imp", reportNo, imo, "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 200);
        postExpectStatus("/api/flag/recognitions",
                recognitionBody(reqPrefix + "-rec", reportNo, surveyId, 1,
                        List.of("CLASS-HULL")), 200);
        return surveyId;
    }

    @Test
    void parallelRecognitionsForSameItemOnlyOneActive() throws Exception {
        setUpRule();
        postExpectStatus("/api/ships",
                shipBody("ship-pr", "IMO-PR", ANNIVERSARY, DELIVERY), 200);
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("pr-ap", "IMO-PR", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/flag/reports",
                reportBody("pr-imp", "FR-PR1", "IMO-PR", "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 200);

        List<HttpResult> results = runParallel(6, () ->
                postSync("/api/flag/recognitions",
                        recognitionBody("pr-rec-" + java.util.UUID.randomUUID(),
                                "FR-PR1", surveyId, 1, List.of("CLASS-HULL"))));

        long ok = results.stream().filter(r -> r.status == 200).count();
        long rejected = results.stream()
                .filter(r -> (r.status == 422
                        && "BINDING_ALREADY_ACTIVE".equals(r.body.get("code").asText()))
                        || (r.status == 409
                        && "BINDING_RACE_LOST".equals(r.body.get("code").asText())))
                .count();
        assertEquals(1, ok, "恰好一个互认受理");
        assertEquals(5, rejected, "其余并发互认全部明确拒绝，不存在 5xx");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=? AND active_flag='Y'",
                Integer.class, surveyId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=?",
                Integer.class, surveyId));
    }

    @Test
    void parallelCorrectionsProduceExactlyOneNewRevision() throws Exception {
        setUpRule();
        long surveyId = setUpSurveyWithBinding("pc", "IMO-PCC", "FR-PC1");

        List<HttpResult> results = runParallel(4, () ->
                postSync("/api/flag/reports/FR-PC1/corrections",
                        correctBody("pc-cor-" + java.util.UUID.randomUUID(), 1, COMPLETED,
                                List.of(reportItem("FLAG-HULL", "MINOR_DEFECT")))));

        long ok = results.stream().filter(r -> r.status == 200).count();
        long conflict = results.stream()
                .filter(r -> r.status == 409
                        && "REPORT_REVISION_CONFLICT".equals(r.body.get("code").asText()))
                .count();
        assertEquals(1, ok, "恰好一个更正受理");
        assertEquals(3, conflict, "其余并发更正全部 409");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_inspection_report WHERE report_no='FR-PC1'",
                Integer.class), "原修订 + 唯一新修订");
        // 未签发检验的旧互认被唯一一次失效处理。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=?"
                        + " AND status='INVALIDATED'",
                Integer.class, surveyId));
    }

    @Test
    void parallelCorrectionAndCertifySerializeConsistently() throws Exception {
        setUpRule();
        long surveyId = setUpSurveyWithBinding("rc", "IMO-RCC", "FR-RC1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<HttpResult> certifyFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return postSync("/api/certificates/certify", certifyBody("rc-cert", surveyId));
            });
            Future<HttpResult> correctFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return postSync("/api/flag/reports/FR-RC1/corrections",
                        correctBody("rc-cor", 1, COMPLETED,
                                List.of(reportItem("FLAG-HULL", "MAJOR_DEFECT"))));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            HttpResult certify = certifyFuture.get(30, TimeUnit.SECONDS);
            HttpResult correct = correctFuture.get(30, TimeUnit.SECONDS);

            assertEquals(200, correct.status, "更正始终受理（失效或登记风险）");
            String surveyStatus = jdbc.queryForObject(
                    "SELECT status FROM survey WHERE id=?", String.class, surveyId);
            if (certify.status == 200) {
                // 发证先行：检验终态，历史绑定保留 ACTIVE，更正另记风险。
                assertEquals("CERTIFIED", surveyStatus);
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=?"
                                + " AND active_flag='Y'", Integer.class, surveyId));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM recognition_risk", Integer.class));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM certificate WHERE survey_id=?",
                        Integer.class, surveyId));
            } else {
                // 更正先行：旧互认已失效，发证被门禁拒绝，不得以旧可用标志发证。
                assertEquals(422, certify.status);
                assertEquals("RECOGNITION_INVALIDATED", certify.body.get("code").asText());
                assertEquals("BOARDED", surveyStatus);
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=?"
                                + " AND active_flag='Y'", Integer.class, surveyId));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=?"
                                + " AND status='INVALIDATED'", Integer.class, surveyId));
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM certificate WHERE survey_id=?",
                        Integer.class, surveyId));
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM recognition_risk", Integer.class));
            }
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flag_inspection_report WHERE report_no='FR-RC1'",
                    Integer.class), "更正恰好生成一个新修订");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelSameRequestRecognitionAcceptedOnce() throws Exception {
        setUpRule();
        postExpectStatus("/api/ships",
                shipBody("ship-sr", "IMO-SR", ANNIVERSARY, DELIVERY), 200);
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("sr-ap", "IMO-SR", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/flag/reports",
                reportBody("sr-imp", "FR-SR1", "IMO-SR", "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 200);

        List<HttpResult> results = runParallel(5, () ->
                postSync("/api/flag/recognitions",
                        recognitionBody("sr-rec-key", "FR-SR1", surveyId, 1,
                                List.of("CLASS-HULL"))));

        long first = results.stream().filter(r -> r.status == 200
                && !r.body.get("replayed").asBoolean()).count();
        long replay = results.stream().filter(r -> r.status == 200
                && r.body.get("replayed").asBoolean()).count();
        long inFlight = results.stream()
                .filter(r -> r.status == 409
                        && "IDEMPOTENCY_IN_FLIGHT".equals(r.body.get("code").asText()))
                .count();
        assertEquals(1, first, "唯一首次受理");
        assertEquals(4, replay + inFlight, "其余请求重放或提示处理中");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=?",
                Integer.class, surveyId), "同键并发不产生重复绑定");
    }
}
