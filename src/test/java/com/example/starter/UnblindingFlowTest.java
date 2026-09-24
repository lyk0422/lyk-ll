package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.audit.AuditEventView;
import com.example.starter.audit.AuditRepository;
import com.example.starter.common.ApiException;
import com.example.starter.enrollment.EnrollmentRepository;
import com.example.starter.enrollment.EnrollmentRequest;
import com.example.starter.enrollment.EnrollmentService;
import com.example.starter.enrollment.EnrollmentView;
import com.example.starter.unblinding.UnblindRequest;
import com.example.starter.unblinding.UnblindResponse;
import com.example.starter.unblinding.UnblindingRepository;
import com.example.starter.unblinding.UnblindingService;
import com.example.starter.visit.VisitRequest;
import com.example.starter.visit.VisitService;
import com.example.starter.visit.VisitView;

/**
 * 应急揭盲测试：双人确认、单人确认不生效、揭盲终态幂等、访视排除标记、审计事件顺序。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class UnblindingFlowTest {

    private static final String TRIAL = "TRIAL-001";
    private static final Instant CONSENT = Instant.parse("2025-12-01T08:30:00Z");

    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private UnblindingService unblindingService;
    @Autowired
    private VisitService visitService;
    @Autowired
    private EnrollmentRepository enrollmentRepository;
    @Autowired
    private UnblindingRepository unblindingRepository;
    @Autowired
    private AuditRepository auditRepository;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanData() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM visit_record");
        jdbc.update("DELETE FROM unblinding_record");
        jdbc.update("DELETE FROM enrollment");
        jdbc.update("DELETE FROM randomization_block");
        jdbc.update("DELETE FROM random_counter");
        clock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void singleApproverConfirmationHasNoEffect() {
        enroll("SCR-U1");
        UnblindRequest single = new UnblindRequest("SCR-U1", "PI-001", "PI-001", "SAE 应急揭盲");

        assertThatThrownBy(() -> unblindingService.unblind(TRIAL, single))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("SINGLE_APPROVER_NOT_ALLOWED");
                });

        long trialId = trialId();
        assertThat(unblindingRepository.findByScreening(trialId, "SCR-U1")).isNull();
        assertThat(enrollmentRepository.findByScreening(trialId, "SCR-U1").status()).isEqualTo("ENROLLED");
        assertThat(auditRepository.list(trialId, "SCR-U1"))
                .extracting(AuditEventView::eventType)
                .containsExactly("ENROLLED");
    }

    @Test
    void unauthorizedApproverIsRejected() {
        enroll("SCR-U2");
        UnblindRequest request = new UnblindRequest("SCR-U2", "PI-001", "PI-999", "SAE 应急揭盲");

        assertThatThrownBy(() -> unblindingService.unblind(TRIAL, request))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("APPROVER_NOT_AUTHORIZED");
                });
        assertThat(unblindingRepository.findByScreening(trialId(), "SCR-U2")).isNull();
    }

    @Test
    void twoDistinctApproversConfirmInOneTransaction() {
        enroll("SCR-U3");
        char actualArm = enrollmentRepository.findByScreening(trialId(), "SCR-U3").actualArm();

        UnblindResponse response = unblindingService.unblind(TRIAL,
                new UnblindRequest("SCR-U3", "PI-001", "PI-002", "受试者发生 SAE"));

        assertThat(response.repeated()).isFalse();
        assertThat(response.revealedArm()).isEqualTo(String.valueOf(actualArm));
        assertThat(enrollmentRepository.findByScreening(trialId(), "SCR-U3").status()).isEqualTo("UNBLINDED");
        // 审计事件按发生顺序可查询：入组 → 第一确认 → 第二确认 → 揭盲生效
        assertThat(auditRepository.list(trialId(), "SCR-U3"))
                .extracting(AuditEventView::eventType)
                .containsExactly("ENROLLED", "UNBLIND_CONFIRM_1", "UNBLIND_CONFIRM_2", "UNBLINDED");
        List<AuditEventView> events = auditRepository.list(trialId(), "SCR-U3");
        assertThat(events.get(1).actor()).isEqualTo("PI-001");
        assertThat(events.get(2).actor()).isEqualTo("PI-002");
    }

    @Test
    void unblindingIsTerminalAndIdempotent() {
        enroll("SCR-U4");
        UnblindResponse first = unblindingService.unblind(TRIAL,
                new UnblindRequest("SCR-U4", "PI-001", "PI-002", "首次揭盲"));

        // 重复揭盲请求（即使换确认人与原因）返回首次结果，不新增事件
        UnblindResponse repeated = unblindingService.unblind(TRIAL,
                new UnblindRequest("SCR-U4", "PI-002", "DM-001", "重复请求"));

        assertThat(repeated.repeated()).isTrue();
        assertThat(repeated.effectiveAt()).isEqualTo(first.effectiveAt());
        assertThat(repeated.approver1()).isEqualTo("PI-001");
        assertThat(repeated.approver2()).isEqualTo("PI-002");
        assertThat(repeated.revealedArm()).isEqualTo(first.revealedArm());
        assertThat(auditRepository.list(trialId(), "SCR-U4")).hasSize(4);
        assertThat(countRows("unblinding_record")).isEqualTo(1);
    }

    @Test
    void subsequentVisitsAreExcludedFromPrimaryAnalysisSet() {
        enroll("SCR-U5");
        Instant beforeUnblind = Instant.parse("2026-01-02T00:00:00Z");
        Instant futureVisit = Instant.parse("2026-02-01T00:00:00Z");
        clock.setInstant(beforeUnblind);
        // 揭盲前的历史访视：不排除
        visitService.addVisit(TRIAL, new VisitRequest("SCR-U5", beforeUnblind, "基线访视"));
        // 揭盲前录入但访视时刻晚于揭盲生效时刻：揭盲生效时应被标记排除
        visitService.addVisit(TRIAL, new VisitRequest("SCR-U5", futureVisit, "计划访视"));

        Instant unblindAt = Instant.parse("2026-01-10T00:00:00Z");
        clock.setInstant(unblindAt);
        unblindingService.unblind(TRIAL, new UnblindRequest("SCR-U5", "PI-001", "PI-002", "SAE"));

        // 揭盲后的新访视：自动排除
        VisitView after = visitService.addVisit(TRIAL,
                new VisitRequest("SCR-U5", Instant.parse("2026-01-20T00:00:00Z"), "揭盲后访视"));
        assertThat(after.excludedFromPas()).isTrue();

        List<VisitView> visits = visitService.listVisits(TRIAL, "SCR-U5");
        assertThat(visits).hasSize(3);
        assertThat(visits.get(0).excludedFromPas()).isFalse();
        assertThat(visits.get(1).excludedFromPas()).isTrue();
        assertThat(visits.get(2).excludedFromPas()).isTrue();
    }

    @Test
    void blindedViewHidesActualArmUntilUnblinded() {
        enroll("SCR-U6");
        EnrollmentView blinded = enrollmentService.findBlindedView(TRIAL, "SCR-U6");
        assertThat(blinded.unblinded()).isFalse();
        assertThat(blinded.actualArm()).isNull();
        assertThat(blinded.armCode()).isIn("A", "B");

        unblindingService.unblind(TRIAL, new UnblindRequest("SCR-U6", "PI-001", "DM-001", "SAE"));

        EnrollmentView unblinded = enrollmentService.findBlindedView(TRIAL, "SCR-U6");
        assertThat(unblinded.unblinded()).isTrue();
        assertThat(unblinded.actualArm()).isIn("T", "C");
    }

    @Test
    void unblindingUnknownSubjectReturns404() {
        assertThatThrownBy(() -> unblindingService.unblind(TRIAL,
                new UnblindRequest("SCR-NONE", "PI-001", "PI-002", "SAE")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("SUBJECT_NOT_FOUND");
                });
    }

    private void enroll(String screeningNo) {
        enrollmentService.enroll(TRIAL,
                new EnrollmentRequest("REQ-" + screeningNo, screeningNo, "C01", CONSENT));
    }

    private long trialId() {
        return enrollmentRepository.findTrialByCode(TRIAL).id();
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
