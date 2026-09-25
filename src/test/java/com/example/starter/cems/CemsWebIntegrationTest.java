package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.ConfirmOrderRequest;
import com.example.starter.cems.CemsTypes.DailyEvalRequest;
import com.example.starter.cems.CemsTypes.DailyEvalResult;
import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.HourlyReadingResult;
import com.example.starter.cems.CemsTypes.RegisterOutletRequest;
import com.example.starter.cems.CemsTypes.ReleaseRestrictionRequest;
import com.example.starter.cems.CemsTypes.ReleaseResult;

/**
 * 真实 HTTP 入口（随机端口）端到端测试：超标标记→限排执行→复测解除，以及 409/404 状态语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CemsWebIntegrationTest {

    private static final String CODE = "OUT-WEB";

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
    }

    @Test
    void fullFlowOverHttpMarkRestrictConfirmRetestAndRelease() {
        // 许可登记
        rest.postForEntity(url("/outlets"), new RegisterOutletRequest(
                "w-reg", CODE, "合成排口-WEB", new BigDecimal("50"), new BigDecimal("10"), "mg/L"),
                String.class);

        // 超标标记：日均值 50.5 > 50
        ResponseEntity<DailyEvalResult> daily = rest.postForEntity(url("/daily-eval"),
                new DailyEvalRequest("w-daily", CODE, java.time.LocalDate.of(2026, 9, 25),
                        new BigDecimal("50.5")), DailyEvalResult.class);
        assertEquals(HttpStatus.OK, daily.getStatusCode());
        assertTrue(daily.getBody().exceeded());

        // 恰等限值判达标
        ResponseEntity<DailyEvalResult> equal = rest.postForEntity(url("/daily-eval"),
                new DailyEvalRequest("w-equal", CODE, java.time.LocalDate.of(2026, 9, 26),
                        new BigDecimal("50")), DailyEvalResult.class);
        assertFalse(equal.getBody().exceeded());

        // 连续 3 次小时超标→自动下达限排令
        String orderNo = null;
        for (int h = 8; h <= 10; h++) {
            ResponseEntity<HourlyReadingResult> resp = rest.postForEntity(url("/hourly-readings"),
                    new HourlyReadingRequest(CODE, LocalDateTime.of(2026, 9, 26, h, 0),
                            new BigDecimal(String.valueOf(10 + h - 7))),
                    HourlyReadingResult.class);
            if (h == 10) {
                assertTrue(resp.getBody().restrictionTriggered());
                orderNo = resp.getBody().orderNo();
            }
        }

        // 限排执行（确认），生效同时冻结基数
        ResponseEntity<String> confirm = rest.postForEntity(url("/orders/confirm"),
                new ConfirmOrderRequest("w-confirm", orderNo), String.class);
        assertEquals(HttpStatus.OK, confirm.getStatusCode());

        // 复测合格解除（9.0 恰含本值达标）
        clock.setFixed(CemsTestSupport.T0.plusHours(3));
        ResponseEntity<ReleaseResult> release = rest.postForEntity(url("/orders/release"),
                new ReleaseRestrictionRequest("w-release", orderNo,
                        LocalDateTime.of(2026, 9, 26, 14, 0), new BigDecimal("9.0")),
                ReleaseResult.class);
        assertEquals(HttpStatus.OK, release.getStatusCode());
        assertEquals("CLOSED", release.getBody().status());
    }

    @Test
    void sameKeyDifferentParamsReturns409AndUnknownOutletReturns404() {
        rest.postForEntity(url("/outlets"), new RegisterOutletRequest(
                "w2-reg", CODE, "合成排口-WEB2", new BigDecimal("50"), new BigDecimal("10"), "mg/L"),
                String.class);
        rest.postForEntity(url("/daily-eval"),
                new DailyEvalRequest("w2-idem", CODE, java.time.LocalDate.of(2026, 9, 25),
                        new BigDecimal("60")), DailyEvalResult.class);
        ResponseEntity<String> conflict = rest.postForEntity(url("/daily-eval"),
                new DailyEvalRequest("w2-idem", CODE, java.time.LocalDate.of(2026, 9, 25),
                        new BigDecimal("30")), String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getBody().contains("不一致"));

        ResponseEntity<String> notFound = rest.getForEntity(url("/outlets/NO-SUCH"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        // 缺参数 400
        ResponseEntity<String> badRequest = rest.postForEntity(url("/daily-eval"),
                new DailyEvalRequest("w2-bad", CODE, null, new BigDecimal("60")), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode());
    }
}
