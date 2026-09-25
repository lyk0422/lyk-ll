package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 真实 HTTP/JSON 端到端测试：预警→读数→演算→方案→签收超时升级→执行→回执偏差边界→归档→迟到读数，
 * 并验证同键重放 200 与同键异参 409 的 HTTP 语义。
 */
class RestEndToEndTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> resp) {
        return resp.getBody();
    }

    @Test
    @DisplayName("全流程 HTTP：恰等临界、恰满时限升级、回执恰 10% 一致、归档终态、409 冲突")
    void fullChainOverHttp() {
        String pond = "TP-DEMO-01";

        // 1. 预警
        Map<String, Object> warningReq = Map.of(
                "requestId", "http-w-1",
                "pondCode", pond,
                "title", "HTTP 合成暴雨预警",
                "rainSummary", "6 小时 120mm");
        ResponseEntity<Map> created = restTemplate.postForEntity(url("/api/warnings"),
                new HttpEntity<>(warningReq), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String warningNo = (String) body(created).get("warningNo");

        // 2. 降雨/水位读数
        ResponseEntity<Map> rain = restTemplate.postForEntity(
                url("/api/ponds/" + pond + "/readings/rainfall"),
                new HttpEntity<>(Map.of(
                        "stationCode", "ST-HTTP",
                        "sampleTime", "2026-09-25T10:00:00",
                        "rainfallDepth", "32.5",
                        "durationMinutes", 60)),
                Map.class);
        assertThat(rain.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body(rain).get("accepted")).isEqualTo(true);
        // 重复读数去重
        ResponseEntity<Map> rainDup = restTemplate.postForEntity(
                url("/api/ponds/" + pond + "/readings/rainfall"),
                new HttpEntity<>(Map.of(
                        "stationCode", "ST-HTTP",
                        "sampleTime", "2026-09-25T10:00:00",
                        "rainfallDepth", "99.9",
                        "durationMinutes", 60)),
                Map.class);
        assertThat(body(rainDup).get("accepted")).isEqualTo(false);

        // 3. 演算：库容恰等临界（水位200→可用400000）
        Map<String, Object> criticalReq = Map.of(
                "requestId", "http-r-1",
                "pondCode", pond,
                "warningNo", warningNo,
                "currentWaterLevel", "200.0000",
                "designFloodVolume", "400000",
                "dryBeachLength", "150",
                "phreaticDepth", "10");
        ResponseEntity<Map> critical = restTemplate.postForEntity(url("/api/routings"),
                new HttpEntity<>(criticalReq), Map.class);
        assertThat(critical.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(critical).get("capacityJudgment")).isEqualTo("CRITICAL");
        assertThat(body(critical).get("dangerJudgment")).isEqualTo("CRITICAL");

        // 4. 临界态演算不允许泄流方案（须须泄流/险）
        String criticalRoutingNo = (String) body(critical).get("routingNo");
        Map<String, Object> badPlan = Map.of(
                "requestId", "http-p-bad",
                "warningNo", warningNo,
                "routingNo", criticalRoutingNo,
                "openings", List.of(Map.of("name", "1#闸门", "opening", "1.0",
                        "release", "10.000")));
        ResponseEntity<Map> badPlanResp = restTemplate.postForEntity(url("/api/plans"),
                new HttpEntity<>(badPlan), Map.class);
        assertThat(badPlanResp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 5. 再做一次须泄流演算并制定恰等安全泄量 20 的方案
        Map<String, Object> releaseReq = Map.of(
                "requestId", "http-r-2",
                "pondCode", pond,
                "warningNo", warningNo,
                "currentWaterLevel", "200.0000",
                "designFloodVolume", "400001",
                "dryBeachLength", "150",
                "phreaticDepth", "10");
        ResponseEntity<Map> release = restTemplate.postForEntity(url("/api/routings"),
                new HttpEntity<>(releaseReq), Map.class);
        String routingNo = (String) body(release).get("routingNo");

        Map<String, Object> planReq = Map.of(
                "requestId", "http-p-1",
                "warningNo", warningNo,
                "routingNo", routingNo,
                "openings", List.of(Map.of("name", "1#排洪隧洞工作闸门",
                        "opening", "1.200", "release", "20.000")));
        ResponseEntity<Map> planResp = restTemplate.postForEntity(url("/api/plans"),
                new HttpEntity<>(planReq), Map.class);
        assertThat(planResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(planResp).get("status")).isEqualTo("EFFECTIVE");
        String planNo = (String) body(planResp).get("planNo");
        String orderNo = (String) body(planResp).get("orderNo");

        // 方案同键重放：200 返回首次方案
        ResponseEntity<Map> planReplay = restTemplate.postForEntity(url("/api/plans"),
                new HttpEntity<>(planReq), Map.class);
        assertThat(planReplay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(planReplay).get("planNo")).isEqualTo(planNo);

        // 6. 恰满 15 分钟后签收 → 自动升级值班领导
        clock.advanceMinutes(15);
        ResponseEntity<Map> signResp = restTemplate.postForEntity(
                url("/api/orders/" + orderNo + "/sign"),
                new HttpEntity<>(Map.of("operator", "值班员乙")), Map.class);
        assertThat(signResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(signResp).get("status")).isEqualTo("ESCALATED");
        assertThat(body(signResp).get("escalatedTo")).isEqualTo("值班领导");

        // 7. 升级态执行（已补签）
        ResponseEntity<Map> execResp = restTemplate.postForEntity(
                url("/api/orders/" + orderNo + "/execute"),
                new HttpEntity<>(Map.of("operator", "值班员乙")), Map.class);
        assertThat(execResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 8. 回执偏差恰 10%（22 vs 20）：无原因也受理
        Map<String, Object> receiptReq = Map.of(
                "requestId", "http-rc-1",
                "operator", "值班员乙",
                "actualOpenings", List.of(Map.of("name", "1#排洪隧洞工作闸门",
                        "opening", "1.300", "release", "22.000")));
        ResponseEntity<Map> receiptResp = restTemplate.postForEntity(
                url("/api/orders/" + orderNo + "/receipt"),
                new HttpEntity<>(receiptReq), Map.class);
        assertThat(receiptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(body(receiptResp).get("deviationRatio").toString()))
                .isEqualByComparingTo("0.100000");

        // 回执同键异参：409
        Map<String, Object> receiptConflict = Map.of(
                "requestId", "http-rc-1",
                "operator", "值班员乙",
                "actualOpenings", List.of(Map.of("name", "1#排洪隧洞工作闸门",
                        "opening", "1.300", "release", "19.000")));
        ResponseEntity<Map> conflictResp = restTemplate.postForEntity(
                url("/api/orders/" + orderNo + "/receipt"),
                new HttpEntity<>(receiptConflict), Map.class);
        assertThat(conflictResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(conflictResp).get("code")).isEqualTo("IDEMPOTENT_CONFLICT");

        // 9. 归档终态 + 重复归档幂等
        Map<String, Object> reviewReq = Map.of(
                "requestId", "http-rv-1",
                "warningNo", warningNo,
                "note", "HTTP 全流程复盘");
        ResponseEntity<Map> reviewResp = restTemplate.postForEntity(
                url("/api/reviews/archive"), new HttpEntity<>(reviewReq), Map.class);
        assertThat(reviewResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(reviewResp).get("status")).isEqualTo("ARCHIVED");

        ResponseEntity<Map> reviewAgain = restTemplate.postForEntity(
                url("/api/reviews/archive"),
                new HttpEntity<>(Map.of("requestId", "http-rv-2", "warningNo", warningNo)),
                Map.class);
        assertThat(body(reviewAgain).get("replay")).isEqualTo(true);

        // 10. 归档后读数：只记迟到事件
        ResponseEntity<Map> late = restTemplate.postForEntity(
                url("/api/ponds/" + pond + "/readings/water"),
                new HttpEntity<>(Map.of(
                        "stationCode", "ST-LATE",
                        "sampleTime", "2026-09-25T12:00:00",
                        "waterLevel", "201.0000")),
                Map.class);
        assertThat(body(late).get("late")).isEqualTo(true);
    }
}
