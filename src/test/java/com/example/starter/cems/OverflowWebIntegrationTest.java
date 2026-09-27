package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import com.example.starter.cems.CemsTypes.RegisterOutletRequest;
import com.example.starter.cems.OverflowTypes.CorrectRainfallRequest;
import com.example.starter.cems.OverflowTypes.CreatePermitRequest;
import com.example.starter.cems.OverflowTypes.DualAverageResult;
import com.example.starter.cems.OverflowTypes.FreezeResult;
import com.example.starter.cems.OverflowTypes.HourDataRequest;
import com.example.starter.cems.OverflowTypes.HourView;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;

/**
 * 雨季溢流真实 HTTP 入口（随机端口）端到端测试：
 * 许可→小时标记（缺雨量待核→补雨量重算）→复核冻结→双口径查询；以及 400/404/409/422 状态语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OverflowWebIntegrationTest {

    private static final String CODE = "OUT-OVWEB";
    private static final LocalDateTime WIN_START = LocalDateTime.of(2026, 9, 26, 0, 0);
    private static final LocalDateTime WIN_END = LocalDateTime.of(2026, 9, 27, 0, 0);

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

    private String cemsUrl(String path) {
        return "http://localhost:" + port + "/api/cems" + path;
    }

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.resetUtc();
        clock.setFixedUtc(LocalDateTime.of(2026, 9, 26, 12, 0));
        rest.postForEntity(cemsUrl("/outlets"), new RegisterOutletRequest(
                "ovw-reg", CODE, "合成排口-OVWEB", new BigDecimal("50"), new BigDecimal("10"), "mg/L"),
                String.class);
    }

    @Test
    void fullOverflowFlowOverHttpPermitPendingCorrectFreezeAndDualAverage() {
        // 首版许可：门槛 10mm，最大连续 2 小时。
        ResponseEntity<String> permit = rest.postForEntity(url("/permits"), new CreatePermitRequest(
                "ovw-permit", CODE, WIN_START, WIN_END, new BigDecimal("10.00"), 2), String.class);
        assertEquals(HttpStatus.OK, permit.getStatusCode());

        // 缺雨量观测溢流：PENDING。
        ResponseEntity<HourView> pending = rest.postForEntity(url("/hours"), new HourDataRequest(
                "ovw-h2", CODE, LocalDateTime.of(2026, 9, 26, 2, 0),
                new BigDecimal("100"), null, true), HourView.class);
        assertEquals(HttpStatus.OK, pending.getStatusCode());
        assertEquals("PENDING", pending.getBody().overflowStatus());
        assertNull(pending.getBody().rainfallMm());

        // 冻结前补录恰等门槛雨量：重算为 COMPLIANT。
        ResponseEntity<String> corrected = rest.postForEntity(url("/rainfall/correct"),
                new CorrectRainfallRequest("ovw-fix", CODE,
                        LocalDateTime.of(2026, 9, 26, 2, 0), new BigDecimal("10.00")), String.class);
        assertEquals(HttpStatus.OK, corrected.getStatusCode());

        // 另一个正常排放小时。
        rest.postForEntity(url("/hours"), new HourDataRequest(
                "ovw-h3", CODE, LocalDateTime.of(2026, 9, 26, 3, 0),
                new BigDecimal("10"), new BigDecimal("0"), false), HourView.class);

        // 复核冻结。
        ResponseEntity<FreezeResult> frozen = rest.postForEntity(url("/review/freeze"),
                new ReviewFreezeRequest("ovw-freeze", CODE,
                        LocalDateTime.of(2026, 9, 26, 2, 0),
                        LocalDateTime.of(2026, 9, 26, 12, 0)), FreezeResult.class);
        assertEquals(HttpStatus.OK, frozen.getStatusCode());
        assertEquals("COMPLIANT", frozen.getBody().overflowStatus());
        assertEquals(1, frozen.getBody().permitVersionFrozen());

        // 冻结后再更正雨量：422。
        ResponseEntity<String> rejected = rest.postForEntity(url("/rainfall/correct"),
                new CorrectRainfallRequest("ovw-fix-late", CODE,
                        LocalDateTime.of(2026, 9, 26, 2, 0), new BigDecimal("99")), String.class);
        assertEquals(422, rejected.getStatusCode().value());

        // 双口径：正常均值只排除证据齐全的合规溢流小时。
        ResponseEntity<DualAverageResult> avg = rest.getForEntity(
                url("/averages/" + CODE + "?fromUtc=2026-09-26T02:00:00&toUtc=2026-09-26T04:00:00&basis=NORMAL"),
                DualAverageResult.class);
        assertEquals(HttpStatus.OK, avg.getStatusCode());
        assertFalse(avg.getBody().noResult());
        assertEquals(0, new BigDecimal("55.0000").compareTo(avg.getBody().allAverage()));
        assertEquals(0, new BigDecimal("10.0000").compareTo(avg.getBody().normalAverage()));
        assertEquals(1, avg.getBody().normalDenominator());
        assertEquals(2, avg.getBody().allDenominator());
    }

    @Test
    void httpStatusSemantics400And404And409() {
        rest.postForEntity(url("/permits"), new CreatePermitRequest(
                "ovw2-permit", CODE, WIN_START, WIN_END, new BigDecimal("10.00"), 2), String.class);

        // 非 UTC 整点：400。
        ResponseEntity<String> badRequest = rest.postForEntity(url("/hours"), new HourDataRequest(
                "ovw2-bad", CODE, LocalDateTime.of(2026, 9, 26, 2, 30),
                new BigDecimal("9"), new BigDecimal("11"), true), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode());

        // 排口不存在：404。
        ResponseEntity<String> notFound = rest.getForEntity(url("/permits/NO-SUCH"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        // 重复建许可（不同业务参数但许可已存在）：409。
        ResponseEntity<String> conflict = rest.postForEntity(url("/permits"), new CreatePermitRequest(
                "ovw2-dup", CODE, WIN_START, WIN_END, new BigDecimal("8"), 2), String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // 空窗口双口径：无结果而非零（200，均值字段为 null）。
        ResponseEntity<DualAverageResult> empty = rest.getForEntity(
                url("/averages/" + CODE + "?fromUtc=2026-09-26T05:00:00&toUtc=2026-09-26T06:00:00"),
                DualAverageResult.class);
        assertEquals(HttpStatus.OK, empty.getStatusCode());
        assertTrue(empty.getBody().noResult());
        assertNull(empty.getBody().allAverage());
    }
}
