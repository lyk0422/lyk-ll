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
 * 互认并发边界（H2 行锁/唯一索引真实并发验证）：
 * 同检验并发申请互认唯一受理；并发更正与发证冻结一致修订，不得以旧互认发证。
 */
class RecognitionConcurrencyTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    private static final List<String> RULE_ITEMS =
            List.of("HULL_STRUCTURE", "MACHINERY", "SAFETY_EQUIPMENT");
    private static final Instant COMPLETED_AT =
            LocalDate.of(2026, 6, 10).atTime(10, 0).toInstant(ZoneOffset.UTC);

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

    private List<HttpResult> runParallel(List<java.util.function.Supplier<HttpResult>> tasks)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResult>> futures = tasks.stream().map(task ->
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

    private long prepareRecognizedSurvey(String suffix) {
        postExpectStatus("/api/ships",
                shipBody("ship-" + suffix, "IMO-" + suffix, ANNIVERSARY, DELIVERY), 200);
        postExpectStatus("/api/recognition/rules",
                ruleBody("rule-" + suffix, 1, "SYN-FL", 90, RULE_ITEMS), 200);
        postExpectStatus("/api/recognition/reports",
                importReportBody("imp-" + suffix, "RPT-" + suffix, "IMO-" + suffix, "SYN-FL",
                        COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 200);
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("ap-" + suffix, "IMO-" + suffix, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-" + suffix, surveyId, LocalDate.of(2026, 6, 15), List.of(),
                        List.of()), 200);
        return surveyId;
    }

    @Test
    void parallelRecognitionApplyOnlyOneAcceptedPerItem() throws Exception {
        long surveyId = prepareRecognizedSurvey("PC1");

        List<HttpResult> results = runParallel(IntStream.range(0, 6)
                .mapToObj(i -> (java.util.function.Supplier<HttpResult>) () ->
                        postSync("/api/recognition/apply",
                                applyRecognitionBody("rec-pc1-" + i, surveyId, "RPT-PC1", 1)))
                .toList());

        long ok = results.stream().filter(r -> r.status == 200).count();
        long bindingExists = results.stream()
                .filter(r -> r.status == 409
                        && "BINDING_EXISTS".equals(r.body.get("code").asText()))
                .count();
        assertEquals(1, ok, "恰好一个互认申请受理");
        assertEquals(5, bindingExists, "其余并发申请全部 409");

        Integer activeBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id="
                        + surveyId + " AND active_flag='Y'", Integer.class);
        assertEquals(3, activeBindings, "每项目恰好一个有效绑定");
    }

    @Test
    void parallelSameRequestRecognitionApplyAcceptedOnce() throws Exception {
        long surveyId = prepareRecognizedSurvey("PC2");

        List<HttpResult> results = runParallel(IntStream.range(0, 5)
                .mapToObj(i -> (java.util.function.Supplier<HttpResult>) () ->
                        postSync("/api/recognition/apply",
                                applyRecognitionBody("rec-same", surveyId, "RPT-PC2", 1)))
                .toList());

        long first = results.stream().filter(r -> r.status == 200
                && !r.body.get("replayed").asBoolean()).count();
        long replay = results.stream().filter(r -> r.status == 200
                && r.body.get("replayed").asBoolean()).count();
        long inFlight = results.stream()
                .filter(r -> r.status == 409
                        && "IDEMPOTENCY_IN_FLIGHT".equals(r.body.get("code").asText()))
                .count();
        assertEquals(1, first, "唯一首次受理");
        assertEquals(4, replay + inFlight, "其余请求重放或处理中冲突");

        Integer activeBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id="
                        + surveyId + " AND active_flag='Y'", Integer.class);
        assertEquals(3, activeBindings, "同键并发不产生重复绑定");
    }

    @Test
    void parallelCorrectionAndCertifyFreezeConsistentRevision() throws Exception {
        long surveyId = prepareRecognizedSurvey("PC3");
        postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-pc3", surveyId, "RPT-PC3", 1), 200);

        // 1 个更正 + 4 个发证并发：终态必须一致——
        // 要么发证先于更正生效（证书发出、历史互认保留并记风险），
        // 要么更正先生效（互认失效、发证全部 422），不得出现凭失效互认发证。
        List<java.util.function.Supplier<HttpResult>> tasks = new java.util.ArrayList<>();
        tasks.add(() -> postSync("/api/recognition/reports/correct",
                correctReportBody("cor-pc3", "RPT-PC3", 1, COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS")))));
        IntStream.range(0, 4).forEach(i ->
                tasks.add(() -> postSync("/api/certificates/certify",
                        certifyBody("cert-pc3-" + i, surveyId))));
        List<HttpResult> results = runParallel(tasks);

        HttpResult correction = results.get(0);
        assertEquals(200, correction.status, "更正应成功");
        List<HttpResult> certifies = results.subList(1, results.size());
        long certified = certifies.stream().filter(r -> r.status == 200).count();
        long invalidated = certifies.stream()
                .filter(r -> r.status == 422
                        && "RECOGNITION_INVALIDATED".equals(r.body.get("code").asText()))
                .count();
        assertEquals(4, certified + invalidated,
                "发证只能成功或得到互认失效 422，不存在其他结果: " + certifies);

        Integer validCerts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE survey_id=" + surveyId
                        + " AND valid_flag='Y'", Integer.class);
        Integer activeBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id="
                        + surveyId + " AND active_flag='Y'", Integer.class);
        Integer risks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_risk WHERE survey_id=" + surveyId,
                Integer.class);
        if (certified > 0) {
            assertEquals(1, validCerts, "发证先生效：恰好一张有效证书");
            assertEquals(3, activeBindings, "已签发检验的历史互认保留");
            assertEquals(3, risks, "已签发检验的历史互认另记风险");
        } else {
            assertEquals(0, validCerts, "更正先生效：不得发出证书");
            assertEquals(0, activeBindings, "未签发检验的旧互认全部失效");
            assertEquals(0, risks, "未签发检验不记风险");
        }
    }
}
