package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
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

import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.RegisterOutletRequest;
import com.example.starter.cems.MaintenanceTypes.EffectiveAveragesView;
import com.example.starter.cems.MaintenanceTypes.LabReportRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenanceAdjustmentView;
import com.example.starter.cems.MaintenanceTypes.MaintenancePermitRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenancePermitView;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewView;
import com.example.starter.cems.MaintenanceTypes.ReplacementConfirmRequest;
import com.example.starter.cems.MaintenanceTypes.ReplacementSubmitRequest;
import com.example.starter.cems.MaintenanceTypes.ReplacementView;
import com.example.starter.cems.MaintenanceTypes.SubstituteExceedanceView;

/**
 * 检修替代 HTTP 入口（随机端口）端到端测试：双人许可→检验报告→替代提交/另一人复核→
 * 有效监测均值（机测优先）→封账→封账后补机测独立调差的主流程，
 * 以及 400 参数非法 / 404 不存在 / 409 冲突 / 422 业务拒绝状态语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MaintenanceWebIntegrationTest {

    private static final String CODE = "OUT-MWEB";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CemsService cemsService;

    private String url(String path) {
        return "http://localhost:" + port + "/api/cems/maintenance" + path;
    }

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.setFixed(CemsTestSupport.T0);
        rest.postForEntity("/api/cems/outlets", new RegisterOutletRequest(
                "mweb-reg", CODE, "合成排口-检修", new BigDecimal("50"), new BigDecimal("10"), "mg/L"),
                String.class);
    }

    @Test
    void fullMaintenanceFlowOverHttpIncludingBackfillAdjustment() {
        // 检修许可：双人确认，版本从 1 起
        ResponseEntity<MaintenancePermitView> permit = rest.postForEntity(url("/permits"),
                new MaintenancePermitRequest("mweb-p1", CODE, 0,
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"),
                        "zhang", "wang"), MaintenancePermitView.class);
        assertEquals(HttpStatus.OK, permit.getStatusCode());
        assertEquals(1, permit.getBody().version());

        // 检验报告（09 时窗口）
        ResponseEntity<String> report = rest.postForEntity(url("/lab-reports"),
                new LabReportRequest("mweb-r9", CODE, "RPT-MWEB-9",
                        Instant.parse("2026-09-26T09:00:00Z"), Instant.parse("2026-09-26T10:00:00Z")),
                String.class);
        assertEquals(HttpStatus.OK, report.getStatusCode());

        // 08 时合法机测 12
        cemsService.reportHourly(new HourlyReadingRequest(CODE,
                LocalDateTime.of(2026, 9, 26, 8, 0), new BigDecimal("12")));

        // 09 时缺机测：提交替代 20（待复核）
        ResponseEntity<ReplacementView> submitted = rest.postForEntity(url("/replacements"),
                new ReplacementSubmitRequest("mweb-s9", CODE, Instant.parse("2026-09-26T09:00:00Z"),
                        new BigDecimal("20"), "RPT-MWEB-9", "li", 0), ReplacementView.class);
        assertEquals(HttpStatus.OK, submitted.getStatusCode());
        assertEquals("PENDING", submitted.getBody().status());

        // 提交人自己复核 → 422
        ResponseEntity<String> selfConfirm = rest.postForEntity(url("/replacements/confirm"),
                new ReplacementConfirmRequest("mweb-cbad", CODE,
                        Instant.parse("2026-09-26T09:00:00Z"), "li"), String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, selfConfirm.getStatusCode());

        // 另一人复核 → 生效
        ResponseEntity<ReplacementView> confirmed = rest.postForEntity(url("/replacements/confirm"),
                new ReplacementConfirmRequest("mweb-c9", CODE,
                        Instant.parse("2026-09-26T09:00:00Z"), "zhao"), ReplacementView.class);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode());
        assertEquals("EFFECTIVE", confirmed.getBody().status());

        // 两小时：一机测 12、一替代 20 → 有效均值 16，来源不双计
        ResponseEntity<EffectiveAveragesView> avg = rest.getForEntity(
                url("/outlets/" + CODE + "/effective-averages?from=2026-09-26T08:00:00Z&to=2026-09-26T10:00:00Z"),
                EffectiveAveragesView.class);
        assertEquals(HttpStatus.OK, avg.getStatusCode());
        assertEquals(2, avg.getBody().countedCount());
        assertEquals(0, avg.getBody().missingCount());
        assertEquals(0, new BigDecimal("16.0000").compareTo(avg.getBody().effectiveAvg()));
        assertEquals("MACHINE", avg.getBody().hours().get(0).source());
        assertEquals("REPLACEMENT", avg.getBody().hours().get(1).source());

        // 替代 20 超原小时限值 10：追加独立来源
        ResponseEntity<SubstituteExceedanceView[]> marks = rest.getForEntity(
                url("/outlets/" + CODE + "/substitute-exceedances"), SubstituteExceedanceView[].class);
        assertEquals(HttpStatus.OK, marks.getStatusCode());
        assertEquals(1, marks.getBody().length);
        assertEquals("ACTIVE", marks.getBody()[0].status());

        // 封账：冻结选择与版本
        ResponseEntity<MaintenanceReviewView> review = rest.postForEntity(url("/reviews"),
                new MaintenanceReviewRequest("mweb-fz", CODE, DAY), MaintenanceReviewView.class);
        assertEquals(HttpStatus.OK, review.getStatusCode());
        assertEquals("FROZEN", review.getBody().status());
        assertEquals(2, review.getBody().items().size());

        // 同键同参回放封账
        ResponseEntity<MaintenanceReviewView> replay = rest.postForEntity(url("/reviews"),
                new MaintenanceReviewRequest("mweb-fz", CODE, DAY), MaintenanceReviewView.class);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals(review.getBody().frozenAt(), replay.getBody().frozenAt());

        // 封账后补 09 时合法机测 30：冻结均值不改写，仅形成独立调差；实时口径改用机测
        cemsService.reportHourly(new HourlyReadingRequest(CODE,
                LocalDateTime.of(2026, 9, 26, 9, 0), new BigDecimal("30")));
        ResponseEntity<MaintenanceReviewView> after = rest.getForEntity(
                url("/outlets/" + CODE + "/reviews/2026-09-26"), MaintenanceReviewView.class);
        assertEquals(0, new BigDecimal("16.0000").compareTo(after.getBody().effectiveAvg()),
                "冻结均值不被封账后补机测重写");

        ResponseEntity<MaintenanceAdjustmentView[]> adjustments = rest.getForEntity(
                url("/outlets/" + CODE + "/adjustments?day=2026-09-26"), MaintenanceAdjustmentView[].class);
        assertEquals(HttpStatus.OK, adjustments.getStatusCode());
        assertEquals(1, adjustments.getBody().length);
        assertEquals("MACHINE_BACKFILL", adjustments.getBody()[0].changeType());
        assertEquals("REPLACEMENT", adjustments.getBody()[0].oldSource());
        assertEquals(0, new BigDecimal("20").compareTo(adjustments.getBody()[0].oldValue()));
        assertEquals("MACHINE", adjustments.getBody()[0].newSource());
        assertEquals(0, new BigDecimal("30").compareTo(adjustments.getBody()[0].newValue()));

        ResponseEntity<EffectiveAveragesView> live = rest.getForEntity(
                url("/outlets/" + CODE + "/effective-averages?from=2026-09-26T08:00:00Z&to=2026-09-26T10:00:00Z"),
                EffectiveAveragesView.class);
        assertEquals(0, new BigDecimal("21.0000").compareTo(live.getBody().effectiveAvg()),
                "实时口径机测优先：(12+30)/2=21");
        assertEquals("MACHINE", live.getBody().hours().get(1).source());
    }

    @Test
    void httpStatusSemanticsForMaintenanceEndpoints() {
        // 400：同一人确认
        ResponseEntity<String> sameConfirmer = rest.postForEntity(url("/permits"),
                new MaintenancePermitRequest("mweb-400", CODE, 0,
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"),
                        "li", "li"), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, sameConfirmer.getStatusCode());

        // 正常许可
        rest.postForEntity(url("/permits"),
                new MaintenancePermitRequest("mweb-p0", CODE, 0,
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"),
                        "zhang", "wang"), MaintenancePermitView.class);

        // 409：expectedVersion 不匹配
        ResponseEntity<String> conflict = rest.postForEntity(url("/permits"),
                new MaintenancePermitRequest("mweb-409", CODE, 0,
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-28T00:00:00Z"),
                        "zhang", "wang"), String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // 404：未知检验报告提交替代
        ResponseEntity<String> noReport = rest.postForEntity(url("/replacements"),
                new ReplacementSubmitRequest("mweb-noreport", CODE, Instant.parse("2026-09-26T09:00:00Z"),
                        new BigDecimal("20"), "RPT-NOPE", "li", 0), String.class);
        assertEquals(HttpStatus.NOT_FOUND, noReport.getStatusCode());

        // 404：未知排口
        ResponseEntity<String> noOutlet = rest.getForEntity(
                url("/outlets/NO-SUCH/permit"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, noOutlet.getStatusCode());

        // 422：替代小时越窗（报告在 10 时窗口，却用于次日 10 时）
        rest.postForEntity(url("/lab-reports"),
                new LabReportRequest("mweb-r10", CODE, "RPT-MWEB-10",
                        Instant.parse("2026-09-26T10:00:00Z"), Instant.parse("2026-09-26T11:00:00Z")),
                String.class);
        ResponseEntity<String> outsideWindow = rest.postForEntity(url("/replacements"),
                new ReplacementSubmitRequest("mweb-outwin", CODE, Instant.parse("2026-09-27T10:00:00Z"),
                        new BigDecimal("20"), "RPT-MWEB-10", "li", 0), String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, outsideWindow.getStatusCode());
        assertNotNull(outsideWindow.getBody());
        assertTrue(outsideWindow.getBody().contains("检修许可"));
    }

    @Test
    void emptyRangeOverHttpReturnsNoResult() {
        rest.postForEntity(url("/permits"),
                new MaintenancePermitRequest("mweb-pe", CODE, 0,
                        Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"),
                        "zhang", "wang"), MaintenancePermitView.class);
        ResponseEntity<EffectiveAveragesView> view = rest.getForEntity(
                url("/outlets/" + CODE + "/effective-averages?from=2027-01-01T00:00:00Z&to=2027-01-01T02:00:00Z"),
                EffectiveAveragesView.class);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertEquals(0, view.getBody().countedCount());
        assertEquals(2, view.getBody().missingCount());
        assertEquals(null, view.getBody().effectiveAvg());
        assertTrue(view.getBody().hours().isEmpty());
    }
}
