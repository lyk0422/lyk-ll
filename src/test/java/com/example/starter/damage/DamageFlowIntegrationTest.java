package com.example.starter.damage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.api.CaseStatus;
import com.example.starter.damage.api.Responsibility;
import com.example.starter.damage.api.dto.ApproveRepairRequest;
import com.example.starter.damage.api.dto.CaseViews.CaseResponse;
import com.example.starter.damage.api.dto.CaseViews.RegisterResponse;
import com.example.starter.damage.api.dto.CompleteRepairRequest;
import com.example.starter.damage.api.dto.EstimateRequest;
import com.example.starter.damage.api.dto.RegisterRequest;
import com.example.starter.damage.api.dto.ReinspectRequest;
import com.example.starter.damage.api.dto.ReleaseRequest;
import com.example.starter.damage.api.dto.SurveyItemDto;
import com.example.starter.damage.api.dto.SurveyRequest;
import com.example.starter.damage.service.DamageCaseService;

/** H2（MODE=MySQL）全流程与边界：真实事务、唯一约束与估价复算，无 mock。 */
@SpringBootTest
class DamageFlowIntegrationTest {

    private static final long DAY = 86_400_000L;

    @Autowired
    private DamageCaseService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        for (String table : List.of("case_event", "repair_cycle", "estimate_item",
                "estimate_version", "damage_item", "survey", "idempotent_request",
                "damage_case")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
    }

    private static long utcDay(int month, int day) {
        return LocalDate.of(2026, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private RegisterResponse register(String reqId, String container, String value, long at) {
        return service.register(new RegisterRequest(reqId, container, new BigDecimal(value), at));
    }

    private SurveyItemDto item(String code, String location, Responsibility resp, int qty) {
        return new SurveyItemDto(code, location, resp, qty);
    }

    private CaseResponse survey(String reqId, String caseNo, long at, SurveyItemDto... items) {
        return service.survey(new SurveyRequest(reqId, caseNo, "检验员-张", at, List.of(items)));
    }

    @Test
    @DisplayName("主流程：恰30%修理→批复第7日完工→复检合格→放行，放箱与结案同事务")
    void exactlyThirtyPercentRepairThenReleaseOnSeventhDay() {
        long d1 = utcDay(9, 1);
        RegisterResponse reg = register("REQ-REG-1", "CSQU3001001", "1000.00", d1);
        String caseNo = reg.caseNo();

        // 单项 RP-POST 300.00，恰为箱值 1000 的 30% → 修理。
        CaseResponse surveyed = survey("REQ-SUR-1", caseNo, d1 + 3_600_000L,
                item("IICL-4010", "左前角柱", Responsibility.OWNER, 1));
        assertThat(surveyed.status()).isEqualTo(CaseStatus.SURVEYED.name());
        assertThat(surveyed.items()).hasSize(1);

        CaseResponse estimated = service.estimate(new EstimateRequest("REQ-EST-1", caseNo));
        assertThat(estimated.status()).isEqualTo(CaseStatus.ESTIMATED.name());
        assertThat(estimated.currentEstimate().decision()).isEqualTo("REPAIR");
        assertThat(estimated.currentEstimate().ratio()).isEqualByComparingTo("0.300000");
        assertThat(estimated.currentEstimate().maxItemAmount()).isEqualByComparingTo("300.00");

        CaseResponse approved = service.approveRepair(
                new ApproveRepairRequest("REQ-APV-1", caseNo, d1 + 4 * 3_600_000L));
        assertThat(approved.status()).isEqualTo(CaseStatus.REPAIR_APPROVED.name());
        assertThat(approved.repairCycles()).hasSize(1);
        // 恰第 7 个自然日（UTC）末刻完工，仍在有效期。
        long endOfDay7 = utcDay(9, 8) + DAY - 1;
        CaseResponse completed = service.completeRepair(
                new CompleteRepairRequest("REQ-CMP-1", caseNo, endOfDay7));
        assertThat(completed.status()).isEqualTo(CaseStatus.REPAIR_COMPLETED.name());
        assertThat(completed.repairCycles().get(0).status()).isEqualTo("COMPLETED");

        CaseResponse reinspected = service.reinspect(
                new ReinspectRequest("REQ-RIN-1", caseNo, true, utcDay(9, 8) + 10 * 3_600_000L));
        assertThat(reinspected.status()).isEqualTo(CaseStatus.REINSPECT_PASSED.name());

        CaseResponse released = service.release(
                new ReleaseRequest("REQ-REL-1", caseNo, utcDay(9, 9)));
        assertThat(released.status()).isEqualTo(CaseStatus.RELEASED.name());
        assertThat(released.closeOutcome()).isEqualTo("RELEASED");
        assertThat(released.closedAtUtc()).isNotNull();
        // 结案后 active_flag 释放，同箱可重新登记。
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM damage_case WHERE active_flag = 'CSQU3001001'", Integer.class);
        assertThat(activeCount).isZero();
    }

    @Test
    @DisplayName("单项修理费超过箱值30%：估价即判全损并唯一结案，不能再修理或放行")
    void overThirtyPercentDeclaresTotalLoss() {
        long d1 = utcDay(9, 1);
        String caseNo = register("REQ-REG-2", "CSQU3001002", "1000.00", d1).caseNo();
        survey("REQ-SUR-2", caseNo, d1,
                item("IICL-4010", "左前角柱", Responsibility.OWNER, 2)); // 300*2=600 > 300

        CaseResponse estimated = service.estimate(new EstimateRequest("REQ-EST-2", caseNo));
        assertThat(estimated.status()).isEqualTo(CaseStatus.TOTAL_LOSS.name());
        assertThat(estimated.closeOutcome()).isEqualTo("TOTAL_LOSS");
        assertThat(estimated.currentEstimate().decision()).isEqualTo("TOTAL_LOSS");
        assertThat(estimated.currentEstimate().ratio()).isGreaterThan(new BigDecimal("0.300000"));

        assertThatThrownBy(() -> service.approveRepair(
                new ApproveRepairRequest("REQ-APV-2", caseNo, d1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("REPAIR_NOT_ALLOWED");
        assertThatThrownBy(() -> service.release(new ReleaseRequest("REQ-REL-2", caseNo, d1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("CASE_ALREADY_CLOSED");
        Integer cycleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_cycle WHERE case_id IS NOT NULL", Integer.class);
        // 全损与修理互斥：不存在任何修理链。
        Integer repairChains = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_cycle rc JOIN damage_case dc ON dc.id = rc.case_id "
                        + "WHERE dc.case_no = ?", Integer.class, caseNo);
        assertThat(repairChains).isZero();
        assertThat(cycleCount).isZero();
    }

    @Test
    @DisplayName("批复第8自然日完工逾期：422且标记EXPIRED；重新估价产生v2后再次修理放行")
    void expiredApprovalRequiresReEstimate() {
        long d1 = utcDay(9, 1);
        String caseNo = register("REQ-REG-3", "CSQU3001003", "1000.00", d1).caseNo();
        survey("REQ-SUR-3", caseNo, d1,
                item("IICL-4010", "角柱", Responsibility.OWNER, 1)); // 300/1000 = 30%
        service.estimate(new EstimateRequest("REQ-EST-3A", caseNo));
        service.approveRepair(new ApproveRepairRequest("REQ-APV-3A", caseNo, d1 + 3_600_000L));

        long startOfDay8 = utcDay(9, 9);
        assertThatThrownBy(() -> service.completeRepair(
                new CompleteRepairRequest("REQ-CMP-3A", caseNo, startOfDay8)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("APPROVAL_EXPIRED");

        // 逾期事实独立落库：修理链为 EXPIRED，案件回到 REPAIR_APPROVED 待重新估价。
        String cycleStatus = jdbc.queryForObject(
                "SELECT rc.status FROM repair_cycle rc JOIN damage_case dc ON dc.id = rc.case_id "
                        + "WHERE dc.case_no = ?", String.class, caseNo);
        assertThat(cycleStatus).isEqualTo("EXPIRED");

        CaseResponse reEstimated = service.estimate(new EstimateRequest("REQ-EST-3B", caseNo));
        assertThat(reEstimated.estimates()).hasSize(2);
        assertThat(reEstimated.estimates().get(1).versionNo()).isEqualTo(2);
        assertThat(reEstimated.status()).isEqualTo(CaseStatus.ESTIMATED.name());

        service.approveRepair(new ApproveRepairRequest("REQ-APV-3B", caseNo,
                utcDay(9, 10) + 3_600_000L));
        service.completeRepair(new CompleteRepairRequest("REQ-CMP-3B", caseNo,
                utcDay(9, 17) + DAY - 1));
        service.reinspect(new ReinspectRequest("REQ-RIN-3", caseNo, true, utcDay(9, 17) + 12 * 3_600_000L));
        CaseResponse released = service.release(new ReleaseRequest("REQ-REL-3", caseNo, utcDay(9, 18)));
        assertThat(released.status()).isEqualTo(CaseStatus.RELEASED.name());
        // 逾期重做不占返修名额：共 2 个修理链，FAIL 数为 0。
        assertThat(released.repairCycles()).hasSize(2);
    }

    @Test
    @DisplayName("复检不合格允许返修一次；返修后仍不合格转全损")
    void reworkOnceThenSecondFailureDeclaresTotalLoss() {
        long d1 = utcDay(10, 1);
        String caseNo = register("REQ-REG-4", "CSQU3001004", "1000.00", d1).caseNo();
        survey("REQ-SUR-4", caseNo, d1,
                item("IICL-4010", "角柱", Responsibility.OWNER, 1));
        service.estimate(new EstimateRequest("REQ-EST-4", caseNo));
        service.approveRepair(new ApproveRepairRequest("REQ-APV-4A", caseNo, d1));
        service.completeRepair(new CompleteRepairRequest("REQ-CMP-4A", caseNo, d1 + DAY));

        CaseResponse firstFail = service.reinspect(
                new ReinspectRequest("REQ-RIN-4A", caseNo, false, d1 + 2 * DAY));
        assertThat(firstFail.status()).isEqualTo(CaseStatus.REWORK_REQUIRED.name());

        // 返修批复与施工（复用当前估价版本，修理链第 2 环）。
        service.approveRepair(new ApproveRepairRequest("REQ-APV-4B", caseNo, d1 + 3 * DAY));
        service.completeRepair(new CompleteRepairRequest("REQ-CMP-4B", caseNo, d1 + 4 * DAY));
        CaseResponse secondFail = service.reinspect(
                new ReinspectRequest("REQ-RIN-4B", caseNo, false, d1 + 5 * DAY));
        assertThat(secondFail.status()).isEqualTo(CaseStatus.TOTAL_LOSS.name());
        assertThat(secondFail.closeOutcome()).isEqualTo("TOTAL_LOSS");
        Integer failCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_cycle rc JOIN damage_case dc ON dc.id = rc.case_id "
                        + "WHERE dc.case_no = ? AND rc.reinspect_result = 'FAIL'", Integer.class, caseNo);
        assertThat(failCount).isEqualTo(2);
    }

    @Test
    @DisplayName("requestId 幂等：同键同参返回首次结果且不产生新估价版本；同键异参409且无副作用")
    void idempotentReplayAndConflict() {
        long d1 = utcDay(10, 1);
        String caseNo = register("REQ-REG-5", "CSQU3001005", "2000.00", d1).caseNo();
        survey("REQ-SUR-5", caseNo, d1,
                item("IICL-4010", "角柱", Responsibility.OWNER, 1),
                item("IICL-3010", "门板", Responsibility.TERMINAL, 2));

        CaseResponse first = service.estimate(new EstimateRequest("REQ-EST-5", caseNo));
        CaseResponse replay = service.estimate(new EstimateRequest("REQ-EST-5", caseNo));
        assertThat(replay.currentEstimate().estimateId())
                .isEqualTo(first.currentEstimate().estimateId());
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM estimate_version ev JOIN damage_case dc ON dc.id = ev.case_id "
                        + "WHERE dc.case_no = ?", Integer.class, caseNo);
        assertThat(versionCount).isEqualTo(1);
        assertThat(replay.currentEstimate().subtotalOwner()).isEqualByComparingTo("300.00");
        assertThat(replay.currentEstimate().subtotalTerminal()).isEqualByComparingTo("300.00");

        assertThatThrownBy(() -> service.estimate(new EstimateRequest("REQ-EST-5", "CSQU9999999")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        Integer versionCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM estimate_version ev JOIN damage_case dc ON dc.id = ev.case_id "
                        + "WHERE dc.case_no = ?", Integer.class, caseNo);
        assertThat(versionCountAfter).isEqualTo(1);
    }

    @Test
    @DisplayName("结案终态：重复放行幂等成功；结案后迟到检验只记迟到事件")
    void closedCaseIsTerminalAndLateSurveyOnlyRecordsEvent() {
        long d1 = utcDay(11, 1);
        String caseNo = register("REQ-REG-6", "CSQU3001006", "1000.00", d1).caseNo();
        survey("REQ-SUR-6", caseNo, d1,
                item("IICL-4010", "角柱", Responsibility.OWNER, 1));
        service.estimate(new EstimateRequest("REQ-EST-6", caseNo));
        service.approveRepair(new ApproveRepairRequest("REQ-APV-6", caseNo, d1));
        service.completeRepair(new CompleteRepairRequest("REQ-CMP-6", caseNo, d1 + DAY));
        service.reinspect(new ReinspectRequest("REQ-RIN-6", caseNo, true, d1 + 2 * DAY));
        CaseResponse released = service.release(new ReleaseRequest("REQ-REL-6", caseNo, d1 + 3 * DAY));
        long closedAt = released.closedAtUtc();

        // 同键同参重复结案：幂等返回首次结果。
        CaseResponse replay = service.release(new ReleaseRequest("REQ-REL-6", caseNo, d1 + 3 * DAY));
        assertThat(replay.status()).isEqualTo(CaseStatus.RELEASED.name());
        assertThat(replay.closedAtUtc()).isEqualTo(closedAt);

        // 新 requestId 重复结案：终态幂等成功。
        CaseResponse replayNewKey = service.release(new ReleaseRequest("REQ-REL-6-AGAIN", caseNo, d1 + 9 * DAY));
        assertThat(replayNewKey.status()).isEqualTo(CaseStatus.RELEASED.name());
        assertThat(replayNewKey.closedAtUtc()).isEqualTo(closedAt);

        CaseResponse late = survey("REQ-SUR-6-LATE", caseNo, d1 + 10 * DAY,
                item("IICL-2010", "顶板", Responsibility.TERMINAL, 1));
        assertThat(late.status()).isEqualTo(CaseStatus.RELEASED.name());
        assertThat(late.closedAtUtc()).isEqualTo(closedAt);
        Boolean lateFlag = jdbc.queryForObject(
                "SELECT late_event FROM survey WHERE request_id = 'REQ-SUR-6-LATE'", Boolean.class);
        assertThat(lateFlag).isTrue();
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_event ce JOIN damage_case dc ON dc.id = ce.case_id "
                        + "WHERE dc.case_no = ? AND ce.event_type = 'LATE_SURVEY'", Integer.class, caseNo);
        assertThat(eventCount).isEqualTo(1);
        // 迟到残损项不得写入残损清单。
        Integer itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM damage_item di JOIN damage_case dc ON dc.id = di.case_id "
                        + "WHERE dc.case_no = ? AND di.code = 'IICL-2010'", Integer.class, caseNo);
        assertThat(itemCount).isZero();
    }

    @Test
    @DisplayName("失败分支：重复登记409、未知代码400、非法状态422、案件不存在404")
    void failureBranches() {
        long d1 = utcDay(11, 1);
        register("REQ-REG-7", "CSQU3001007", "1000.00", d1);
        assertThatThrownBy(() -> register("REQ-REG-7B", "CSQU3001007", "1000.00", d1))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("ACTIVE_CASE_EXISTS");
        String caseNo = service.getCase(jdbc.queryForObject(
                "SELECT case_no FROM damage_case WHERE container_no = 'CSQU3001007'", String.class)).caseNo();

        assertThatThrownBy(() -> survey("REQ-SUR-7X", caseNo, d1,
                item("IICL-9999", "未知位置", Responsibility.OWNER, 1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("UNKNOWN_IICL_CODE");

        // 未检验直接估价 → 422。
        assertThatThrownBy(() -> service.estimate(new EstimateRequest("REQ-EST-7X", caseNo)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("INVALID_STATE");

        assertThatThrownBy(() -> service.getCase("DC-NOT-EXIST"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(404);
    }
}
