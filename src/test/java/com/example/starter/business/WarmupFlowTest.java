package com.example.starter.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.FeedResponse;
import com.example.starter.api.dto.WarmupConfirmRequest;
import com.example.starter.api.dto.WarmupStageSpec;
import com.example.starter.api.dto.WarmupStageResponse;
import com.example.starter.api.dto.WarmupTicketCreateRequest;
import com.example.starter.api.dto.WarmupTicketResponse;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.RejectCategory;
import com.example.starter.service.WarmupService;

/**
 * 冷修后升温操作票主流程与边界：恰满最长用时转保温、保温恰满4小时告警、
 * 双人复核、曲线未走完禁投料、幂等重放。
 */
class WarmupFlowTest extends AbstractIntegrationTest {

    @Autowired
    private WarmupService service;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 5, 8, 0);

    private WarmupTicketCreateRequest twoStageTicket(String requestId, String kilnId) {
        return new WarmupTicketCreateRequest(requestId, kilnId, "CURVE-V1", List.of(
                new WarmupStageSpec(new BigDecimal("600.00"), 60),
                new WarmupStageSpec(new BigDecimal("1150.00"), 120)));
    }

    private WarmupConfirmRequest confirm(String requestId, String operator, String temp) {
        return new WarmupConfirmRequest(requestId, operator, new BigDecimal(temp));
    }

    @Test
    void cold_repair_full_curve_to_feed_flow() {
        clock.travelTo(T0);
        WarmupTicketResponse ticket = service.create(twoStageTicket("REQ-WT-1", KILN));
        assertThat(ticket.status()).isEqualTo("EXECUTING");
        assertThat(ticket.curveVersion()).isEqualTo("CURVE-V1");
        assertThat(ticket.currentStageSeq()).isEqualTo(1);
        assertThat(ticket.stages()).hasSize(2);
        assertThat(ticket.stages().get(0).status()).isEqualTo("ACTIVE");
        assertThat(ticket.stages().get(0).stageType()).isEqualTo("HEATING");
        assertThat(ticket.stages().get(1).status()).isEqualTo("PENDING");

        // 曲线未走完禁止投料
        BusinessException feedBlocked = assertThrows(BusinessException.class,
                () -> service.feed(new FeedRequest("REQ-FEED-EARLY", KILN, "op-feed")));
        assertThat(feedBlocked.getHttpStatus().value()).isEqualTo(422);
        assertThat(feedBlocked.getCategory()).isEqualTo(RejectCategory.CURVE_NOT_FINISHED);

        // 单人确认不生效：阶段仍 ACTIVE，未推进
        WarmupTicketResponse afterFirst = service.confirm(ticket.ticketNo(),
                confirm("REQ-CF-1A", "operator-1", "620"));
        assertThat(afterFirst.stages().get(0).status()).isEqualTo("ACTIVE");
        assertThat(afterFirst.stages().get(0).confirmUser1()).isEqualTo("operator-1");
        assertThat(afterFirst.stages().get(0).confirmUser2()).isNull();
        assertThat(afterFirst.currentStageSeq()).isEqualTo(1);

        // 同一操作人再次确认不算复核
        BusinessException samePerson = assertThrows(BusinessException.class,
                () -> service.confirm(ticket.ticketNo(), confirm("REQ-CF-1B", "operator-1", "620")));
        assertThat(samePerson.getHttpStatus().value()).isEqualTo(422);

        // 第二名不同操作人复核：阶段1完成，原子推进到阶段2
        WarmupTicketResponse afterSecond = service.confirm(ticket.ticketNo(),
                confirm("REQ-CF-1C", "operator-2", "620"));
        assertThat(afterSecond.stages().get(0).status()).isEqualTo("CONFIRMED");
        assertThat(afterSecond.stages().get(0).confirmUser2()).isEqualTo("operator-2");
        assertThat(afterSecond.stages().get(0).confirmedAt()).isNotNull();
        assertThat(afterSecond.stages().get(1).status()).isEqualTo("ACTIVE");
        assertThat(afterSecond.stages().get(1).enteredAt()).isNotNull();
        assertThat(afterSecond.currentStageSeq()).isEqualTo(2);

        // 阶段2 双人复核后曲线走完
        service.confirm(ticket.ticketNo(), confirm("REQ-CF-2A", "operator-3", "1200"));
        WarmupTicketResponse done = service.confirm(ticket.ticketNo(),
                confirm("REQ-CF-2B", "operator-4", "1200"));
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.completedAt()).isNotNull();
        assertThat(done.stages().get(1).status()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_active_warmup WHERE kiln_id = ?",
                Integer.class, KILN)).isZero();

        // 曲线走完允许投料
        FeedResponse feed = service.feed(new FeedRequest("REQ-FEED-1", KILN, "op-feed"));
        assertThat(feed.accepted()).isTrue();
        assertThat(feed.fedAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM feed_record WHERE kiln_id = ?",
                Integer.class, KILN)).isEqualTo(1);
    }

    @Test
    void heating_stage_exactly_max_duration_converts_to_holding() {
        clock.travelTo(T0);
        WarmupTicketResponse ticket = service.create(twoStageTicket("REQ-WT-2", KILN));

        // 未满最长用时：不转保温
        clock.travelTo(T0.plusMinutes(59));
        assertThat(service.scanWarmupTimeouts()).isZero();
        WarmupStageResponse before = service.status(ticket.ticketNo()).stages().get(0);
        assertThat(before.stageType()).isEqualTo("HEATING");
        assertThat(before.holdingSince()).isNull();

        // 恰满最长用时 60 分钟：自动转保温段
        clock.travelTo(T0.plusMinutes(60));
        assertThat(service.scanWarmupTimeouts()).isEqualTo(1);
        WarmupStageResponse after = service.status(ticket.ticketNo()).stages().get(0);
        assertThat(after.stageType()).isEqualTo("HOLDING");
        assertThat(after.holdingSince()).isEqualTo(T0.plusMinutes(60));
        assertThat(after.status()).isEqualTo("ACTIVE");

        // 已转保温，重复扫描不再变更
        assertThat(service.scanWarmupTimeouts()).isZero();
    }

    @Test
    void holding_stage_exactly_4_hours_escalates_alarm() {
        clock.travelTo(T0);
        WarmupTicketResponse ticket = service.create(twoStageTicket("REQ-WT-3", KILN));
        // 恰满 60 分钟转保温，holding_since = T0+60min
        clock.travelTo(T0.plusMinutes(60));
        service.scanWarmupTimeouts();
        LocalDateTime holdingSince = T0.plusMinutes(60);

        // 保温未满 4 小时：不告警
        clock.travelTo(holdingSince.plusHours(4).minusMinutes(1));
        assertThat(service.scanWarmupTimeouts()).isZero();
        assertThat(service.status(ticket.ticketNo()).stages().get(0).alarm()).isFalse();

        // 恰满 4 小时：升级告警
        clock.travelTo(holdingSince.plusHours(4));
        assertThat(service.scanWarmupTimeouts()).isEqualTo(1);
        WarmupStageResponse alarmed = service.status(ticket.ticketNo()).stages().get(0);
        assertThat(alarmed.alarm()).isTrue();
        assertThat(alarmed.alarmedAt()).isEqualTo(holdingSince.plusHours(4));

        // 告警只升级一次
        assertThat(service.scanWarmupTimeouts()).isZero();
    }

    @Test
    void confirm_below_target_temp_is_rejected() {
        clock.travelTo(T0);
        WarmupTicketResponse ticket = service.create(twoStageTicket("REQ-WT-4", KILN));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(ticket.ticketNo(), confirm("REQ-CF-LOW", "operator-1", "599")));
        assertThat(ex.getHttpStatus().value()).isEqualTo(422);
        assertThat(ex.getCategory()).isEqualTo(RejectCategory.TARGET_NOT_REACHED);

        // 未达目标的确认不留痕：阶段仍无确认人
        WarmupStageResponse stage = service.status(ticket.ticketNo()).stages().get(0);
        assertThat(stage.status()).isEqualTo("ACTIVE");
        assertThat(stage.confirmUser1()).isNull();
    }

    @Test
    void create_idempotent_replay_and_conflict() {
        clock.travelTo(T0);
        WarmupTicketResponse first = service.create(twoStageTicket("REQ-WT-5", KILN));
        WarmupTicketResponse replay = service.create(twoStageTicket("REQ-WT-5", KILN));
        assertThat(replay.ticketNo()).isEqualTo(first.ticketNo());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warmup_ticket WHERE request_id = 'REQ-WT-5'",
                Integer.class)).isEqualTo(1);

        // 同键异参 409
        WarmupTicketCreateRequest mutated = new WarmupTicketCreateRequest("REQ-WT-5", KILN,
                "CURVE-V2", List.of(new WarmupStageSpec(new BigDecimal("700.00"), 30)));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(mutated));
        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
        assertThat(ex.getCategory()).isEqualTo(RejectCategory.IDEMPOTENT_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warmup_ticket WHERE request_id = 'REQ-WT-5'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void confirm_idempotent_replay_does_not_double_advance() {
        clock.travelTo(T0);
        WarmupTicketResponse ticket = service.create(twoStageTicket("REQ-WT-6", KILN));
        WarmupTicketResponse first = service.confirm(ticket.ticketNo(),
                confirm("REQ-CF-RP", "operator-1", "620"));
        WarmupTicketResponse replay = service.confirm(ticket.ticketNo(),
                confirm("REQ-CF-RP", "operator-1", "620"));
        assertThat(replay.stages().get(0).confirmUser1()).isEqualTo("operator-1");
        assertThat(replay.currentStageSeq()).isEqualTo(first.currentStageSeq());
        // 重放不推进阶段、不重复登记
        WarmupStageResponse stage = service.status(ticket.ticketNo()).stages().get(0);
        assertThat(stage.status()).isEqualTo("ACTIVE");
        assertThat(stage.confirmUser2()).isNull();

        // 同键异参 409
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(ticket.ticketNo(), confirm("REQ-CF-RP", "operator-9", "620")));
        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
        assertThat(ex.getCategory()).isEqualTo(RejectCategory.IDEMPOTENT_CONFLICT);
    }

    @Test
    void feed_idempotent_replay_and_conflict() {
        clock.travelTo(T0);
        WarmupTicketResponse ticket = service.create(twoStageTicket("REQ-WT-7", KILN));
        service.confirm(ticket.ticketNo(), confirm("REQ-CF-7A", "operator-1", "620"));
        service.confirm(ticket.ticketNo(), confirm("REQ-CF-7B", "operator-2", "620"));
        service.confirm(ticket.ticketNo(), confirm("REQ-CF-7C", "operator-3", "1200"));
        service.confirm(ticket.ticketNo(), confirm("REQ-CF-7D", "operator-4", "1200"));

        FeedResponse first = service.feed(new FeedRequest("REQ-FEED-7", KILN, "op-feed"));
        FeedResponse replay = service.feed(new FeedRequest("REQ-FEED-7", KILN, "op-feed"));
        assertThat(replay.accepted()).isTrue();
        assertThat(replay.fedAt()).isEqualTo(first.fedAt());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM feed_record WHERE request_id = 'REQ-FEED-7'",
                Integer.class)).isEqualTo(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.feed(new FeedRequest("REQ-FEED-7", KILN, "op-other")));
        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
        assertThat(ex.getCategory()).isEqualTo(RejectCategory.IDEMPOTENT_CONFLICT);
    }

    @Test
    void second_ticket_same_kiln_rejected_while_executing() {
        clock.travelTo(T0);
        service.create(twoStageTicket("REQ-WT-8A", KILN));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(twoStageTicket("REQ-WT-8B", KILN)));
        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
        assertThat(ex.getCategory()).isEqualTo(RejectCategory.WARMUP_TICKET_ACTIVE);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warmup_ticket WHERE kiln_id = ? AND status = 'EXECUTING'",
                Integer.class, KILN)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_active_warmup WHERE kiln_id = ?",
                Integer.class, KILN)).isEqualTo(1);
    }
}
