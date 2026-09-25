package com.example.starter.web;

import com.example.starter.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 层测试：经真实 REST 入口验证 200/201/409/422/400 状态码与幂等冲突不产生副作用。
 */
class BalanceControllerHttpTest extends AbstractIntegrationTest {

    private static final String STATION = "ST-H";
    private static final String U1 = "ST-H-U01";

    @Autowired
    private TestRestTemplate rest;

    @Test
    void httpEndpoints_coverSuccess_conflict_conflictReplay_andValidation() {
        createStation(STATION, 3);

        // 首条调节令：201 Created
        String adjustBody = """
                {
                  "requestId": "http-adj-1",
                  "stationCode": "ST-H",
                  "steps": [{"unitCode": "ST-H-U01", "action": "OPEN", "delta": 10.000}]
                }
                """;
        ResponseEntity<String> created = post("/api/adjustments", adjustBody);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).contains("\"taskCreated\":true");

        // 同键同参重放：200，replay=true
        ResponseEntity<String> replay = post("/api/adjustments", adjustBody);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).contains("\"replay\":true");

        // 同键异参：409，错误码 IDEMPOTENT_CONFLICT
        String conflictBody = """
                {
                  "requestId": "http-adj-1",
                  "stationCode": "ST-H",
                  "steps": [{"unitCode": "ST-H-U01", "action": "OPEN", "delta": 9.000}]
                }
                """;
        ResponseEntity<String> conflict = post("/api/adjustments", conflictBody);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("IDEMPOTENT_CONFLICT");
        // 开度仍为首令后的 60%
        assertThat(valveOpening(STATION, U1)).isEqualByComparingTo("60.000");

        // 参数非法（步进 11%）：400 校验失败
        String invalidBody = """
                {
                  "requestId": "http-adj-bad",
                  "stationCode": "ST-H",
                  "steps": [{"unitCode": "ST-H-U01", "action": "OPEN", "delta": 11.000}]
                }
                """;
        ResponseEntity<String> invalid = post("/api/adjustments", invalidBody);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).contains("VALIDATION_FAILED");

        // 稳定不足评估：422
        String earlyEval = """
                {"requestId":"http-eval-early","stationCode":"ST-H","phase":"RE_EVAL"}
                """;
        ResponseEntity<String> unprocessable = post("/api/evaluations", earlyEval);
        assertThat(unprocessable.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unprocessable.getBody()).contains("STABILIZING");

        // 不存在的换热站：404
        String missing = """
                {"requestId":"http-eval-missing","stationCode":"NO-SUCH","phase":"JUDGMENT"}
                """;
        ResponseEntity<String> notFound = post("/api/evaluations", missing);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void httpTemperatureReport_andRecomputeEndpoints() {
        createStation(STATION, 3);
        String tempBody = """
                {
                  "requestId": "http-temp-1",
                  "stationCode": "ST-H",
                  "readings": [
                    {"unitCode": "ST-H-U01", "supplyTemp": 60.0, "returnTemp": 44.0, "observedAt": "2026-09-01T00:00:00Z"},
                    {"unitCode": "ST-H-U02", "supplyTemp": 60.0, "returnTemp": 45.0, "observedAt": "2026-09-01T00:00:00Z"},
                    {"unitCode": "ST-H-U03", "supplyTemp": 60.0, "returnTemp": 46.0, "observedAt": "2026-09-01T00:00:00Z"}
                  ]
                }
                """;
        ResponseEntity<String> tempResp = post("/api/temperatures", tempBody);
        assertThat(tempResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tempResp.getBody()).contains("\"accepted\":3", "\"late\":false");

        ResponseEntity<String> distribution = rest.getForEntity(
                "/api/stations/ST-H/distribution", String.class);
        assertThat(distribution.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(distribution.getBody()).contains("ST-H-U01", "44.000");
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
