package com.example.starter.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.CommandActionRequest;
import com.example.starter.api.dto.CommandResponse;
import com.example.starter.api.dto.PlanResultResponse;
import com.example.starter.api.dto.PlanStatusResponse;
import com.example.starter.api.dto.ReadingAckResponse;
import com.example.starter.service.CementService;
import com.example.starter.support.TestFixtures;

/**
 * 排放读数去重归位、小时均值恰等限值达标、超标触发降掺、30分钟恰超时升级、连续3次禁烧。
 */
class EmissionFlowTest extends AbstractIntegrationTest {

    @Autowired
    private CementService service;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    private PlanStatusResponse approvedAndExecuting(String reqId) {
        clock.travelTo(DAY.atTime(7, 55));
        var req = TestFixtures.submit(reqId, KILN, VALID_LICENSE, "0.4000",
                DAY.atTime(0, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel()));
        PlanResultResponse submit = service.submit(req);
        assertThat(submit.accepted()).isTrue();
        return service.execute(new com.example.starter.api.dto.ExecutePlanRequest(submit.planNo()));
    }

    @Test
    void hourly_average_exactly_at_limit_passes_without_command() {
        PlanStatusResponse plan = approvedAndExecuting("REQ-EQ-LIMIT");
        // 两个测点 NOx 均恰为限值 400，均值恰 400 判达标
        var ack = service.ingestReadings(TestFixtures.readings(plan.planNo(),
                TestFixtures.reading("P1", DAY.atTime(8, 10), "400", "100", "10"),
                TestFixtures.reading("P2", DAY.atTime(8, 20), "400", "100", "10")));
        assertThat(ack.inserted()).isEqualTo(2);

        var summary = service.getDailySummary(plan.planNo(), DAY);
        assertThat(summary.exceededPeriods()).isEmpty();
        assertThat(summary.reductionRecords()).isEmpty();
        assertThat(summary.effectiveHours()).hasSize(1);
        assertThat(summary.effectiveHours().get(0).exceeded()).isFalse();
    }

    @Test
    void above_limit_triggers_command_and_confirm_chain_works() {
        PlanStatusResponse plan = approvedAndExecuting("REQ-CHAIN");
        service.ingestReadings(TestFixtures.readings(plan.planNo(),
                TestFixtures.reading("P1", DAY.atTime(8, 15), "460", "100", "10")));

        CommandResponse issued = service.issueCommand(
                new CommandActionRequest("REQ-ISSUE-1", plan.planNo(), DAY.atTime(8, 0).toString()));
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.issuedAt()).isNotNull();

        clock.advanceMinutes(10);
        CommandResponse confirmed = service.confirmCommand(
                new CommandActionRequest("REQ-CONF-1", plan.planNo(), DAY.atTime(8, 0).toString()));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(confirmed.targetRate()).isEqualByComparingTo("0.1000");
    }

    @Test
    void exactly_30_minutes_without_confirm_escalates_and_forces_floor_rate() {
        PlanStatusResponse plan = approvedAndExecuting("REQ-TIMEOUT");
        service.ingestReadings(TestFixtures.readings(plan.planNo(),
                TestFixtures.reading("P1", DAY.atTime(8, 15), "460", null, null)));
        CommandResponse issued = service.issueCommand(
                new CommandActionRequest("REQ-ISSUE-T", plan.planNo(), DAY.atTime(8, 0).toString()));
        LocalDateTime issuedAt = issued.issuedAt();

        // 29 分钟：未恰满，不升级
        clock.travelTo(issuedAt.plusMinutes(29));
        assertThat(service.scanCommandTimeouts()).isZero();
        String statusAt29 = jdbc.queryForObject(
                "SELECT status FROM reduction_command WHERE plan_id = ?",
                String.class, plan.planId());
        assertThat(statusAt29).isEqualTo("ISSUED");

        // 恰满 30 分钟：自动升级，强制降至保底比例
        clock.travelTo(issuedAt.plusMinutes(30));
        assertThat(service.scanCommandTimeouts()).isEqualTo(1);
        CommandResponse escalated = service.issueCommand(
                new CommandActionRequest("REQ-ISSUE-T-REPLAY", plan.planNo(),
                        DAY.atTime(8, 0).toString()));
        // 指令已非 TRIGGERED：重复下发回显当前状态
        assertThat(escalated.status()).isEqualTo("ESCALATED");
        assertThat(escalated.escalatedAt()).isEqualTo(issuedAt.plusMinutes(30));
        assertThat(escalated.targetRate()).isEqualByComparingTo("0.1000");
    }

    @Test
    void three_consecutive_exceeded_hours_bans_co_firing_for_the_day() {
        PlanStatusResponse plan = approvedAndExecuting("REQ-BAN");

        ingestHour(plan.planNo(), 8, "460");
        ingestHour(plan.planNo(), 9, "460");
        // 恰连续 3 次
        ingestHour(plan.planNo(), 10, "460");

        PlanStatusResponse after = service.status(plan.planNo());
        assertThat(after.status()).isEqualTo("BANNED");
        Integer banCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ban_record WHERE kiln_id = ? AND ban_date = ?",
                Integer.class, KILN, java.sql.Date.valueOf(DAY));
        assertThat(banCount).isEqualTo(1);
        // 窑已释放
        assertThat(jdbc.queryForList("SELECT kiln_id FROM kiln_active_plan WHERE kiln_id = ?",
                String.class, KILN)).isEmpty();

        // 第 4 个超标小时不再重复禁烧落单
        ingestHour(plan.planNo(), 11, "460");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ban_record WHERE kiln_id = ?",
                Integer.class, KILN)).isEqualTo(1);
    }

    @Test
    void one_compliant_hour_breaks_consecutive_count() {
        PlanStatusResponse plan = approvedAndExecuting("REQ-BREAK");
        ingestHour(plan.planNo(), 8, "460");
        ingestHour(plan.planNo(), 9, "400"); // 恰达标，连续中断
        ingestHour(plan.planNo(), 10, "460");
        ingestHour(plan.planNo(), 11, "460");
        assertThat(service.status(plan.planNo()).status()).isEqualTo("EXECUTING");
    }

    @Test
    void readings_are_deduplicated_and_out_of_order_hours_are_aligned() {
        PlanStatusResponse plan = approvedAndExecuting("REQ-DEDUP");
        // 先报 10 点、再乱序补 9 点
        service.ingestReadings(TestFixtures.readings(plan.planNo(),
                TestFixtures.reading("P1", DAY.atTime(10, 5), "460", null, null)));
        service.ingestReadings(TestFixtures.readings(plan.planNo(),
                TestFixtures.reading("P1", DAY.atTime(9, 5), "460", null, null)));
        // 重复上报同测点同时刻
        ReadingAckResponse dup = service.ingestReadings(TestFixtures.readings(plan.planNo(),
                TestFixtures.reading("P1", DAY.atTime(10, 5), "999", null, null)));
        assertThat(dup.inserted()).isZero();
        assertThat(dup.duplicated()).isEqualTo(1);

        // 乱序归位后连续序号仍按采样时刻：9点=1,10点=2，未触发禁烧
        assertThat(service.status(plan.planNo()).status()).isEqualTo("EXECUTING");
        var summary = service.getDailySummary(plan.planNo(), DAY);
        assertThat(summary.exceededPeriods()).extracting(p -> p.hourStart().getHour())
                .containsExactly(9, 10);
    }

    private void ingestHour(String planNo, int hour, String nox) {
        service.ingestReadings(TestFixtures.readings(planNo,
                TestFixtures.reading("P1", DAY.atTime(hour, 15), nox, "100", "10"),
                TestFixtures.reading("P2", DAY.atTime(hour, 45), nox, "100", "10")));
    }
}
