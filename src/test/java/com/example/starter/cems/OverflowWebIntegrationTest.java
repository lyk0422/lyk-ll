package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.DischargeStatsView;
import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.OverflowAdjustmentRequest;
import com.example.starter.cems.CemsTypes.OverflowHourEntry;
import com.example.starter.cems.CemsTypes.OverflowHoursRequest;
import com.example.starter.cems.CemsTypes.OverflowHoursResult;
import com.example.starter.cems.CemsTypes.OverflowPermitRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitView;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.RegisterOutletRequest;

/**
 * 雨季溢流真实 HTTP 入口（随机端口）端到端测试：
 * 许可版本→小时上报→双口径查询→复核冻结→独立调差，以及 400/404/409/422 状态语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OverflowWebIntegrationTest {

    private static final String CODE = "OV-WEB";

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
                "w-reg", CODE, "合成排口-OV-WEB", new BigDecimal("50"), new BigDecimal("10"), "mg/L"),
                String.class);
    }

    private static LocalDateTime utc(int hour) {
        return LocalDateTime.of(2026, 9, 26, hour, 0);
    }

    @Test
    void fullOverflowFlowOverHttp() {
        // 许可版本新增（expectedVersion=0 → 版本 1）
        ResponseEntity<OverflowPermitView> permit = rest.postForEntity(url("/overflow/permits"),
                new OverflowPermitRequest("wp-1", CODE, 0, utc(6), utc(12),
                        new BigDecimal("5"), 3), OverflowPermitView.class);
        assertEquals(HttpStatus.OK, permit.getStatusCode());
        assertEquals(1, permit.getBody().version());

        // 小时溢流数据：07 合规（恰等雨量）、08 缺雨量待核
        ResponseEntity<OverflowHoursResult> hours = rest.postForEntity(url("/overflow/hours"),
                new OverflowHoursRequest("wh-1", CODE, List.of(
                        new OverflowHourEntry(utc(7), new BigDecimal("5"), true),
                        new OverflowHourEntry(utc(8), null, true))),
                OverflowHoursResult.class);
        assertEquals(HttpStatus.OK, hours.getStatusCode());
        assertEquals("COMPLIANT", hours.getBody().hours().get(0).evalStatus());
        assertEquals("PENDING_REVIEW", hours.getBody().hours().get(1).evalStatus());

        // 浓度读数（旧入口，行为不变）
        rest.postForEntity(url("/hourly-readings"),
                new HourlyReadingRequest(CODE, utc(7), new BigDecimal("8")), String.class);
        rest.postForEntity(url("/hourly-readings"),
                new HourlyReadingRequest(CODE, utc(8), new BigDecimal("9")), String.class);

        // 双口径查询：全口径 8.5；正常口径排除 07 合规溢流 → 9
        ResponseEntity<DischargeStatsView> all = rest.getForEntity(
                url("/overflow/stats/" + CODE + "?from=2026-09-26T06:00:00&to=2026-09-26T12:00:00&caliber=ALL"),
                DischargeStatsView.class);
        assertEquals(HttpStatus.OK, all.getStatusCode());
        assertEquals(2, all.getBody().denominator());
        assertEquals(0, new BigDecimal("8.5").compareTo(all.getBody().average()));
        assertEquals(4, all.getBody().missingHours().size(), "缺点明示：06/09/10/11 无读数");

        ResponseEntity<DischargeStatsView> normal = rest.getForEntity(
                url("/overflow/stats/" + CODE + "?from=2026-09-26T06:00:00&to=2026-09-26T12:00:00&caliber=NORMAL"),
                DischargeStatsView.class);
        assertEquals(1, normal.getBody().denominator());
        assertEquals(0, new BigDecimal("9").compareTo(normal.getBody().average()));
        assertEquals(List.of(utc(7)), normal.getBody().excludedCompliantOverflowHours());

        // 复核冻结
        ResponseEntity<OverflowReviewResult> review = rest.postForEntity(url("/overflow/reviews"),
                new OverflowReviewRequest("wr-1", CODE, LocalDate.of(2026, 9, 26)),
                OverflowReviewResult.class);
        assertEquals(HttpStatus.OK, review.getStatusCode());
        assertEquals("REVIEW", review.getBody().snapshot().frozenBy());

        // 冻结后更正雨量：422；独立调差：200
        ResponseEntity<String> correct = rest.postForEntity(url("/overflow/hours"),
                new OverflowHoursRequest("wh-2", CODE, List.of(
                        new OverflowHourEntry(utc(7), new BigDecimal("9"), true))), String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, correct.getStatusCode());

        ResponseEntity<String> adjust = rest.postForEntity(url("/overflow/adjustments"),
                new OverflowAdjustmentRequest("wa-1", CODE, utc(7), new BigDecimal("-0.5"), "人工核减"),
                String.class);
        assertEquals(HttpStatus.OK, adjust.getStatusCode());
    }

    @Test
    void errorSemanticsOverHttp() {
        // 400：窗口非法
        ResponseEntity<String> badWindow = rest.postForEntity(url("/overflow/permits"),
                new OverflowPermitRequest("e-1", CODE, 0, utc(12), utc(6), new BigDecimal("5"), 3),
                String.class);
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.getStatusCode());

        // 404：排口不存在
        ResponseEntity<String> notFound = rest.postForEntity(url("/overflow/permits"),
                new OverflowPermitRequest("e-2", "NO-SUCH", 0, utc(6), utc(12), new BigDecimal("5"), 3),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        // 409：同键异参幂等冲突
        rest.postForEntity(url("/overflow/permits"),
                new OverflowPermitRequest("e-3", CODE, 0, utc(6), utc(12), new BigDecimal("5"), 3),
                String.class);
        ResponseEntity<String> conflict = rest.postForEntity(url("/overflow/permits"),
                new OverflowPermitRequest("e-3", CODE, 0, utc(6), utc(13), new BigDecimal("5"), 3),
                String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // 409：expectedVersion 版本冲突
        ResponseEntity<String> stale = rest.postForEntity(url("/overflow/permits"),
                new OverflowPermitRequest("e-4", CODE, 0, utc(6), utc(14), new BigDecimal("5"), 3),
                String.class);
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());

        // 422：未冻结时独立调差
        ResponseEntity<String> unprocessable = rest.postForEntity(url("/overflow/adjustments"),
                new OverflowAdjustmentRequest("e-5", CODE, utc(7), new BigDecimal("1"), "冻结前调差"),
                String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, unprocessable.getStatusCode());

        // 400：非 UTC 整点
        ResponseEntity<String> nonHour = rest.postForEntity(url("/overflow/hours"),
                new OverflowHoursRequest("e-6", CODE, List.of(
                        new OverflowHourEntry(LocalDateTime.of(2026, 9, 26, 7, 30),
                                new BigDecimal("5"), true))), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, nonHour.getStatusCode());

        // 400：caliber 非法
        ResponseEntity<String> badCaliber = rest.getForEntity(
                url("/overflow/stats/" + CODE + "?from=2026-09-26T06:00:00&to=2026-09-26T12:00:00&caliber=X"),
                String.class);
        assertEquals(HttpStatus.BAD_REQUEST, badCaliber.getStatusCode());
    }

    @Test
    void emptyStatsReturnsNullAverageNotZero() {
        ResponseEntity<DischargeStatsView> empty = rest.getForEntity(
                url("/overflow/stats/" + CODE + "?from=2026-09-26T06:00:00&to=2026-09-26T12:00:00&caliber=ALL"),
                DischargeStatsView.class);
        assertEquals(HttpStatus.OK, empty.getStatusCode());
        assertNull(empty.getBody().average(), "空集合返回无结果而非零");
        assertEquals(0, empty.getBody().denominator());
        assertEquals(6, empty.getBody().missingHours().size());
    }
}
