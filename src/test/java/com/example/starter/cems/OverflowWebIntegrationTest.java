package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.OverflowDailySummaryView;
import com.example.starter.cems.CemsTypes.OverflowHourlyRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitCreateRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitUpdateRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.RainfallAdjustRequest;
import com.example.starter.cems.CemsTypes.RainfallReportRequest;
import com.example.starter.cems.CemsTypes.RegisterOutletRequest;

/**
 * 雨季溢流真实 HTTP 入口（随机端口）端到端测试：
 * 许可版本→雨量→溢流小时→复核冻结双口径→冻结后调差，以及 400/404/409/422 状态语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OverflowWebIntegrationTest {

    private static final String CODE = "OUT-OVWEB";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private String url(String path) {
        return "http://localhost:" + port + "/api/cems/overflow" + path;
    }

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.setFixed(CemsTestSupport.T0);
        rest.postForEntity("/api/cems/outlets", new RegisterOutletRequest(
                "ovw-reg", CODE, "合成排口-OVWEB", new BigDecimal("50"), new BigDecimal("10"), "mg/L"),
                String.class);
    }

    @Test
    void fullOverflowFlowOverHttpPermitRainfallHourReviewAndAdjust() {
        ResponseEntity<String> create = rest.postForEntity(url("/permits"), new OverflowPermitCreateRequest(
                "ovw-p1", CODE, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(),
                new BigDecimal("5.0"), 10), String.class);
        assertEquals(HttpStatus.OK, create.getStatusCode());

        // 雨量恰等门槛合法
        assertEquals(HttpStatus.OK, rest.postForEntity(url("/rainfall"), new RainfallReportRequest(
                "ovw-r1", CODE, DAY.atTime(1, 0), new BigDecimal("5.0"), null), String.class).getStatusCode());
        assertEquals(HttpStatus.OK, rest.postForEntity(url("/hourly"), new OverflowHourlyRequest(
                "ovw-h1", CODE, DAY.atTime(1, 0), new BigDecimal("200"), true), String.class).getStatusCode());
        // 一个正常排放小时
        assertEquals(HttpStatus.OK, rest.postForEntity(url("/hourly"), new OverflowHourlyRequest(
                "ovw-h0", CODE, DAY.atTime(0, 0), new BigDecimal("100"), false), String.class).getStatusCode());

        ResponseEntity<OverflowReviewResult> review = rest.postForEntity(url("/review"),
                new OverflowReviewRequest("ovw-rev", CODE, DAY), OverflowReviewResult.class);
        assertEquals(HttpStatus.OK, review.getStatusCode());
        assertEquals(1, review.getBody().frozenCompliantHours());
        // NORMAL 排除 1 个合规溢流小时，分母 1；ALL 分母 2。
        assertEquals(1, review.getBody().summary().normal().denominator());
        assertEquals(2, review.getBody().summary().all().denominator());
        assertEquals(0, new BigDecimal("100.0000").compareTo(
                review.getBody().summary().normal().averageValue()));

        // 双口径查询
        ResponseEntity<OverflowDailySummaryView> summary = rest.getForEntity(
                url("/daily-summary/" + CODE + "?dayUtc=2026-10-03"), OverflowDailySummaryView.class);
        assertEquals(HttpStatus.OK, summary.getStatusCode());
        assertEquals(2, summary.getBody().all().denominator());

        // 冻结后独立调差
        ResponseEntity<String> adjust = rest.postForEntity(url("/rainfall/adjust"), new RainfallAdjustRequest(
                "ovw-adj", CODE, DAY.atTime(1, 0), new BigDecimal("2.0"), "雨后校准修正"), String.class);
        assertEquals(HttpStatus.OK, adjust.getStatusCode());
        // 快照不变
        assertEquals(1, rest.getForEntity(
                url("/daily-summary/" + CODE + "?dayUtc=2026-10-03"), OverflowDailySummaryView.class)
                .getBody().normal().excludedHours());
    }

    @Test
    void overflowHttpErrorSemantics400_404_409_422() {
        // 400：非整点窗口
        ResponseEntity<String> badWindow = rest.postForEntity(url("/permits"), new OverflowPermitCreateRequest(
                "e-bad", CODE, DAY.atTime(0, 30), DAY.atTime(12, 0), new BigDecimal("5"), 3), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.getStatusCode());

        // 404：排口不存在
        ResponseEntity<String> notFound = rest.postForEntity(url("/permits"), new OverflowPermitCreateRequest(
                "e-404", "NO-OUT", DAY.atStartOfDay(), DAY.atTime(12, 0), new BigDecimal("5"), 3), String.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        rest.postForEntity(url("/permits"), new OverflowPermitCreateRequest(
                "e-p1", CODE, DAY.atStartOfDay(), DAY.atTime(12, 0), new BigDecimal("5"), 3), String.class);

        // 422：窗口与版本 1 重叠
        ResponseEntity<String> overlap = rest.postForEntity(url("/permits/update"), new OverflowPermitUpdateRequest(
                "e-p2", CODE, 1, DAY.atTime(11, 0), DAY.atTime(18, 0), new BigDecimal("5"), 3), String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, overlap.getStatusCode());

        // 409：expectedVersion 不匹配
        ResponseEntity<String> versionConflict = rest.postForEntity(url("/permits/update"),
                new OverflowPermitUpdateRequest("e-p3", CODE, 99, DAY.atTime(12, 0),
                        DAY.plusDays(1).atStartOfDay(), new BigDecimal("5"), 3), String.class);
        assertEquals(HttpStatus.CONFLICT, versionConflict.getStatusCode());

        // 422：缺雨量待核时复核被拒
        rest.postForEntity(url("/hourly"), new OverflowHourlyRequest(
                "e-h", CODE, DAY.atTime(2, 0), new BigDecimal("200"), true), String.class);
        ResponseEntity<String> pendingReview = rest.postForEntity(url("/review"),
                new OverflowReviewRequest("e-rev", CODE, DAY), String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, pendingReview.getStatusCode());

        // 未冻结日查询日结：404（无结果而非零）
        ResponseEntity<String> noSummary = rest.getForEntity(
                url("/daily-summary/" + CODE + "?dayUtc=2026-10-04"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, noSummary.getStatusCode());
        assertFalse(noSummary.getBody().isBlank());
    }
}
