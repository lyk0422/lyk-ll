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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实并发边界（H2 行锁/唯一索引验证，非 mock）：
 * 同船同类型并发申请唯一受理；同键并发同参唯一受理且另一请求重放；
 * 同一检验并发发证只产生一张有效证书。
 */
class ConcurrencyFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);

    private void registerShip(String reqId, String imo) {
        postExpectStatus("/api/ships",
                shipBody(reqId, imo, ANNIVERSARY, DELIVERY), 200);
    }

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

    @Test
    void parallelApplicationsForSameShipAndTypeOnlyOneAccepted() throws Exception {
        registerShip("ship-pa", "IMO-PA");
        setClockDate(LocalDate.of(2026, 6, 15));

        List<HttpResult> results = runParallel(8, () ->
                postSync("/api/surveys",
                        applyBody("apply-" + java.util.UUID.randomUUID(),
                                "IMO-PA", "ANNUAL")));

        long ok = results.stream().filter(r -> r.status == 200).count();
        long conflict = results.stream()
                .filter(r -> r.status == 409
                        && "SURVEY_IN_PROGRESS".equals(r.body.get("code").asText()))
                .count();
        assertEquals(1, ok, "恰好一个申请受理");
        assertEquals(7, conflict, "其余并发申请全部 409");

        Integer surveys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM survey WHERE ship_id="
                        + "(SELECT id FROM ship WHERE imo='IMO-PA')", Integer.class);
        assertEquals(1, surveys);
        Integer inProgress = jdbc.queryForObject(
                "SELECT COUNT(*) FROM survey WHERE ship_id="
                        + "(SELECT id FROM ship WHERE imo='IMO-PA') AND active_flag='Y'",
                Integer.class);
        assertEquals(1, inProgress);
    }

    @Test
    void parallelSameRequestSameParamsAcceptedOnceOtherReplays() throws Exception {
        registerShip("ship-pr", "IMO-PR");
        setClockDate(LocalDate.of(2026, 6, 15));

        List<HttpResult> results = runParallel(6, () ->
                postSync("/api/surveys",
                        applyBody("same-key", "IMO-PR", "ANNUAL")));

        long first = results.stream().filter(r -> r.status == 200
                && !r.body.get("replayed").asBoolean()).count();
        long replay = results.stream().filter(r -> r.status == 200
                && r.body.get("replayed").asBoolean()).count();
        long inFlight = results.stream()
                .filter(r -> r.status == 409
                        && "IDEMPOTENCY_IN_FLIGHT".equals(r.body.get("code").asText()))
                .count();
        assertEquals(1, first, "唯一首次受理");
        assertEquals(5, replay + inFlight, "其余请求重放首次结果或得到处理中冲突");

        Integer surveys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM survey WHERE request_id='same-key'", Integer.class);
        assertEquals(1, surveys, "同键并发不产生多条检验");
        // 处理中冲突的请求随后用同参数重放，必须拿到首次结果。
        HttpResult later = postSync("/api/surveys",
                applyBody("same-key", "IMO-PR", "ANNUAL"));
        assertEquals(200, later.status);
        assertTrue(later.body.get("replayed").asBoolean());
    }

    @Test
    void parallelCertifyProducesExactlyOneValidCertificate() throws Exception {
        registerShip("ship-pc", "IMO-PC");
        setClockDate(LocalDate.of(2026, 3, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("ap-pc", "IMO-PC", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody("bd-pc", surveyId, LocalDate.of(2026, 3, 15), List.of()), 200);

        List<HttpResult> results = runParallel(8, () ->
                postSync("/api/certificates/certify",
                        certifyBody("cert-" + java.util.UUID.randomUUID(), surveyId)));

        long ok = results.stream().filter(r -> r.status == 200).count();
        long raceLost = results.stream()
                .filter(r -> r.status == 409
                        && "CERTIFY_RACE_LOST".equals(r.body.get("code").asText()))
                .count();
        assertTrue(ok >= 1);
        assertEquals(8, ok + raceLost, "不存在 5xx，落败方得到明确 409");

        Integer validCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE ship_id="
                        + "(SELECT id FROM ship WHERE imo='IMO-PC') AND valid_flag='Y'",
                Integer.class);
        assertEquals(1, validCount, "恰好一张有效证书");
        // 落败方随后用新 requestId 重发：终态幂等返回同一张证书，不新增版本。
        HttpResult after = postSync("/api/certificates/certify",
                certifyBody("cert-after", surveyId));
        assertEquals(200, after.status);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE ship_id="
                        + "(SELECT id FROM ship WHERE imo='IMO-PC')", Integer.class));
    }
}
