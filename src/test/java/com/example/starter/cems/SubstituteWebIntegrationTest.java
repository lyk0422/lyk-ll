package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
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

import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.RegisterOutletRequest;
import com.example.starter.cems.SubstituteTypes.EffectiveAveragesView;
import com.example.starter.cems.SubstituteTypes.FreezeRequest;
import com.example.starter.cems.SubstituteTypes.FreezeView;
import com.example.starter.cems.SubstituteTypes.LabReportRequest;
import com.example.starter.cems.SubstituteTypes.LabReportView;
import com.example.starter.cems.SubstituteTypes.PermitConfirmRequest;
import com.example.starter.cems.SubstituteTypes.PermitUpsertRequest;
import com.example.starter.cems.SubstituteTypes.PermitView;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewResult;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitResult;

/**
 * 检修替代监测 HTTP 入口（随机端口）端到端测试：
 * 许可两人确认→报告登记→替代提交/复核→有效统计→补机测重算主流程，
 * 以及 400 参数非法 / 404 不存在 / 409 冲突 / 422 业务拒绝状态语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubstituteWebIntegrationTest {

    private static final String CODE = "OUT-SWEB";

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
                "web-reg", CODE, "合成排口-替代", new BigDecimal("50"), new BigDecimal("10"), "mg/L"),
                String.class);
    }

    @Test
    void fullSubstituteFlowOverHttp() {
        // 检修许可：版本 1，两名不同人员确认生效
        ResponseEntity<PermitView> permit = rest.postForEntity(url("/substitute/permits"),
                new PermitUpsertRequest("web-p1", CODE, 0,
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"),
                        "creator"), PermitView.class);
        assertEquals(HttpStatus.OK, permit.getStatusCode());
        assertEquals("PENDING_CONFIRM", permit.getBody().status());
        rest.postForEntity(url("/substitute/permits/confirm"),
                new PermitConfirmRequest("web-c1", CODE, 1, "张三"), PermitView.class);
        ResponseEntity<PermitView> active = rest.postForEntity(url("/substitute/permits/confirm"),
                new PermitConfirmRequest("web-c2", CODE, 1, "李四"), PermitView.class);
        assertEquals("ACTIVE", active.getBody().status());

        // 合成检验报告 + 08 时机测 8（本地 16 时）
        ResponseEntity<LabReportView> report = rest.postForEntity(url("/substitute/reports"),
                new LabReportRequest("web-r1", "RPT-1", CODE,
                        Instant.parse("2026-09-26T09:00:00Z")), LabReportView.class);
        assertEquals(HttpStatus.OK, report.getStatusCode());
        rest.postForEntity(url("/hourly-readings"), new HourlyReadingRequest(
                CODE, java.time.LocalDateTime.of(2026, 9, 26, 16, 0), new BigDecimal("8")), String.class);

        // 09 时替代 12 提交并复核生效
        ResponseEntity<SubstituteSubmitResult> submitted = rest.postForEntity(
                url("/substitute/substitutes"),
                new SubstituteSubmitRequest("web-s1", CODE, Instant.parse("2026-09-26T09:00:00Z"),
                        new BigDecimal("12"), "RPT-1", "submitter", 0),
                SubstituteSubmitResult.class);
        assertEquals(HttpStatus.OK, submitted.getStatusCode());
        assertEquals(1, submitted.getBody().version());
        ResponseEntity<SubstituteReviewResult> reviewed = rest.postForEntity(
                url("/substitute/substitutes/review"),
                new SubstituteReviewRequest("web-rv1", CODE,
                        Instant.parse("2026-09-26T09:00:00Z"), 1, "reviewer"),
                SubstituteReviewResult.class);
        assertEquals("EFFECTIVE", reviewed.getBody().status());
        assertTrue(reviewed.getBody().exceedanceMarked(), "替代 12 超小时限值 10 追加独立超标来源");

        // 有效统计：一机测一替代，均值 10
        ResponseEntity<EffectiveAveragesView> averages = rest.getForEntity(
                url("/substitute/outlets/" + CODE + "/averages?from=2026-09-26T08:00:00Z"
                        + "&to=2026-09-26T10:00:00Z"), EffectiveAveragesView.class);
        assertEquals(HttpStatus.OK, averages.getStatusCode());
        assertEquals(1, averages.getBody().machineCount());
        assertEquals(1, averages.getBody().substituteCount());
        assertEquals(0, averages.getBody().avgValue().compareTo(new BigDecimal("10.0000")));

        // 封账前补 09 时机测 6（本地 17 时）：替代失效，新口径均值 7
        rest.postForEntity(url("/hourly-readings"), new HourlyReadingRequest(
                CODE, java.time.LocalDateTime.of(2026, 9, 26, 17, 0), new BigDecimal("6")), String.class);
        ResponseEntity<EffectiveAveragesView> after = rest.getForEntity(
                url("/substitute/outlets/" + CODE + "/averages?from=2026-09-26T08:00:00Z"
                        + "&to=2026-09-26T10:00:00Z"), EffectiveAveragesView.class);
        assertEquals(2, after.getBody().machineCount());
        assertEquals(0, after.getBody().substituteCount());
        assertEquals(0, after.getBody().avgValue().compareTo(new BigDecimal("7.0000")));

        // 日结冻结：冻结当前选择及版本
        ResponseEntity<FreezeView> frozen = rest.postForEntity(url("/substitute/freezes"),
                new FreezeRequest("web-f1", CODE, LocalDate.of(2026, 9, 26)), FreezeView.class);
        assertEquals(HttpStatus.OK, frozen.getStatusCode());
        assertEquals(2, frozen.getBody().machineCount());
        assertEquals(0, frozen.getBody().avgValue().compareTo(new BigDecimal("7.0000")));
        assertNotNull(frozen.getBody().frozenAt());
    }

    @Test
    void httpErrorSemantics() {
        // 404 未知排口
        ResponseEntity<String> notFound = rest.postForEntity(url("/substitute/permits"),
                new PermitUpsertRequest("e-p", "OUT-NONE", 0,
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"),
                        "creator"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        // 400 窗口非法
        ResponseEntity<String> badWindow = rest.postForEntity(url("/substitute/permits"),
                new PermitUpsertRequest("e-p2", CODE, 0,
                        Instant.parse("2026-09-27T00:00:00Z"), Instant.parse("2026-09-26T00:00:00Z"),
                        "creator"), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.getStatusCode());
        // 409 版本冲突
        rest.postForEntity(url("/substitute/permits"), new PermitUpsertRequest("e-p3", CODE, 0,
                Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"),
                "creator"), PermitView.class);
        ResponseEntity<String> stale = rest.postForEntity(url("/substitute/permits"),
                new PermitUpsertRequest("e-p4", CODE, 0,
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"),
                        "creator"), String.class);
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        // 422 许可未生效不能提交替代
        rest.postForEntity(url("/substitute/reports"), new LabReportRequest("e-r1", "RPT-9", CODE,
                Instant.parse("2026-09-26T08:00:00Z")), LabReportView.class);
        ResponseEntity<String> noPermit = rest.postForEntity(url("/substitute/substitutes"),
                new SubstituteSubmitRequest("e-s1", CODE, Instant.parse("2026-09-26T08:00:00Z"),
                        new BigDecimal("5"), "RPT-9", "submitter", 0), String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noPermit.getStatusCode());
        // 404 冻结快照不存在
        ResponseEntity<String> noFreeze = rest.getForEntity(
                url("/substitute/outlets/" + CODE + "/freezes/2026-09-26"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, noFreeze.getStatusCode());
        // 空集合有效统计：均值 null（无结果而非零）
        ResponseEntity<EffectiveAveragesView> empty = rest.getForEntity(
                url("/substitute/outlets/" + CODE + "/averages?from=2026-09-26T08:00:00Z"
                        + "&to=2026-09-26T09:00:00Z"), EffectiveAveragesView.class);
        assertEquals(HttpStatus.OK, empty.getStatusCode());
        assertNull(empty.getBody().avgValue());
        assertEquals(1, empty.getBody().missingCount());
    }
}
