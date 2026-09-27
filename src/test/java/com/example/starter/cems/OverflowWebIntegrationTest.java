package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.RegisterOutletRequest;
import com.example.starter.cems.OverflowTypes.AveragesView;
import com.example.starter.cems.OverflowTypes.HourReportRequest;
import com.example.starter.cems.OverflowTypes.HourReportResult;
import com.example.starter.cems.OverflowTypes.OverflowHourItem;
import com.example.starter.cems.OverflowTypes.PermitUpsertRequest;
import com.example.starter.cems.OverflowTypes.PermitView;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;
import com.example.starter.cems.OverflowTypes.ReviewView;

/**
 * 溢流 HTTP 入口（随机端口）端到端测试：许可→小时上报→双口径查询→复核冻结主流程，
 * 以及 400 参数非法 / 404 不存在 / 409 冲突 / 422 业务拒绝状态语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OverflowWebIntegrationTest {

    private static final String CODE = "OUT-OWEB";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private String url(String path) {
        return "http://localhost:" + port + "/api/cems" + path;
    }

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.setFixed(CemsTestSupport.T0);
        rest.postForEntity(url("/outlets"), new RegisterOutletRequest(
                "web-reg", CODE, "合成排口-溢流", new BigDecimal("50"), new BigDecimal("10"), "mg/L"),
                String.class);
    }

    private static PermitUpsertRequest permitReq(String requestId, String code, int expectedVersion) {
        return new PermitUpsertRequest(requestId, code, expectedVersion,
                Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"),
                new BigDecimal("5"), 10);
    }

    @Test
    void fullOverflowFlowOverHttp() {
        // 新增溢流许可版本（版本从 1 开始）
        ResponseEntity<PermitView> permit = rest.postForEntity(url("/overflow/permits"),
                permitReq("web-p1", CODE, 0), PermitView.class);
        assertEquals(HttpStatus.OK, permit.getStatusCode());
        assertEquals(1, permit.getBody().version());

        // 小时数据上报：08 普通、09 合规溢流（恰等雨量门槛）
        ResponseEntity<HourReportResult> hours = rest.postForEntity(url("/overflow/hours"),
                new HourReportRequest("web-h1", CODE, List.of(
                        new OverflowHourItem(Instant.parse("2026-09-26T08:00:00Z"),
                                new BigDecimal("1"), false, new BigDecimal("10")),
                        new OverflowHourItem(Instant.parse("2026-09-26T09:00:00Z"),
                                new BigDecimal("5"), true, new BigDecimal("20")))),
                HourReportResult.class);
        assertEquals(HttpStatus.OK, hours.getStatusCode());
        assertEquals(2, hours.getBody().reported());
        assertEquals("COMPLIANT", hours.getBody().hours().get(1).markStatus());

        // 双口径均值查询：正常口径排除 09 时合规溢流
        ResponseEntity<AveragesView> averages = rest.getForEntity(
                url("/overflow/outlets/" + CODE
                        + "/averages?from=2026-09-26T08:00:00Z&to=2026-09-26T11:00:00Z"),
                AveragesView.class);
        assertEquals(HttpStatus.OK, averages.getStatusCode());
        assertEquals(2, averages.getBody().allCount());
        assertEquals(1, averages.getBody().normalCount());
        assertEquals(1, averages.getBody().missingCount());
        assertEquals(0, averages.getBody().normalAvg().compareTo(new BigDecimal("10.0000")));

        // 日结复核冻结
        ResponseEntity<ReviewView> review = rest.postForEntity(url("/overflow/reviews"),
                new ReviewFreezeRequest("web-r1", CODE, LocalDate.of(2026, 9, 26)), ReviewView.class);
        assertEquals(HttpStatus.OK, review.getStatusCode());
        assertEquals("FROZEN", review.getBody().status());
        assertEquals(1, review.getBody().permitVersion());

        // 同键同参回放首次冻结结果
        ResponseEntity<ReviewView> replay = rest.postForEntity(url("/overflow/reviews"),
                new ReviewFreezeRequest("web-r1", CODE, LocalDate.of(2026, 9, 26)), ReviewView.class);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals(review.getBody().frozenAt(), replay.getBody().frozenAt());
    }

    @Test
    void httpStatusSemanticsForOverflowEndpoints() {
        // 404：排口不存在
        ResponseEntity<String> notFound = rest.postForEntity(url("/overflow/permits"),
                permitReq("web-404", "NO-SUCH", 0), String.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        // 400：窗口终点不晚于起点
        ResponseEntity<String> badRequest = rest.postForEntity(url("/overflow/permits"),
                new PermitUpsertRequest("web-400", CODE, 0,
                        Instant.parse("2026-09-26T12:00:00Z"), Instant.parse("2026-09-26T12:00:00Z"),
                        new BigDecimal("5"), 3),
                String.class);
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode());

        // 409：expectedVersion 与当前版本不匹配
        rest.postForEntity(url("/overflow/permits"), permitReq("web-p0", CODE, 0), PermitView.class);
        ResponseEntity<String> conflict = rest.postForEntity(url("/overflow/permits"),
                permitReq("web-409", CODE, 0), String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // 422：雨量低于门槛仍请求合规溢流标记
        ResponseEntity<String> unprocessable = rest.postForEntity(url("/overflow/hours"),
                new HourReportRequest("web-422", CODE, List.of(
                        new OverflowHourItem(Instant.parse("2026-09-26T09:00:00Z"),
                                new BigDecimal("1"), true, new BigDecimal("20")))),
                String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, unprocessable.getStatusCode());
        assertNotNull(unprocessable.getBody());
        assertTrue(unprocessable.getBody().contains("低于许可门槛"));
    }

    @Test
    void sameKeyDifferentParamsReturns409OverHttp() {
        rest.postForEntity(url("/overflow/permits"), permitReq("web-idem", CODE, 0), PermitView.class);
        ResponseEntity<String> conflict = rest.postForEntity(url("/overflow/permits"),
                new PermitUpsertRequest("web-idem", CODE, 0,
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-28T00:00:00Z"),
                        new BigDecimal("5"), 10),
                String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        Map<String, Object> permit = jdbc.queryForMap(
                "SELECT version FROM overflow_permit WHERE outlet_code = ?", CODE);
        assertEquals(1, ((Number) permit.get("version")).intValue(), "异参 409 不得改变许可版本");
    }
}
