package com.example.starter.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.FeedResponse;
import com.example.starter.api.dto.StageConfirmRequest;
import com.example.starter.api.dto.StageConfirmResponse;
import com.example.starter.api.dto.WarmUpTicketCreateRequest;
import com.example.starter.api.dto.WarmUpTicketResponse;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.RejectCategory;
import com.example.starter.service.WarmUpService;

/**
 * 冷修后升温操作票：合成曲线分阶段执行、恰满最长用时转保温、双人复核、
 * 曲线未走完禁投料、保温恰满4小时告警、requestId 幂等与同窑唯一票并发。
 */
class WarmUpFlowTest extends AbstractIntegrationTest {

    @Autowired
    private WarmUpService warmUpService;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 8, 0);

    private WarmUpTicketResponse createTicket(String requestId, String kilnId) {
        return warmUpService.createTicket(new WarmUpTicketCreateRequest(requestId, kilnId));
    }

    private StageConfirmResponse confirm(String ticketNo, String requestId, String operator,
                                         String temp) {
        return warmUpService.confirmStage(ticketNo,
                new StageConfirmRequest(requestId, operator, new java.math.BigDecimal(temp)));
    }

    /** 两名操作人按序完成当前阶段确认。 */
    private StageConfirmResponse confirmByTwo(String ticketNo, String reqPrefix, String temp) {
        StageConfirmResponse first = confirm(ticketNo, reqPrefix + "-A", "OP-A", temp);
        assertThat(first.confirmStatus()).isEqualTo("PENDING_SECOND");
        return confirm(ticketNo, reqPrefix + "-B", "OP-B", temp);
    }

    // ---------------------------------------------------------------------
    // 主流程：升温→走完曲线→允许投料
    // ---------------------------------------------------------------------

    @Test
    void full_curve_then_feed_allowed() {
        clock.travelTo(T0);
        WarmUpTicketResponse ticket = createTicket("REQ-WU-1", KILN);
        assertThat(ticket.status()).isEqualTo("EXECUTING");
        assertThat(ticket.curveVersion()).isEqualTo("CURVE-2026A");
        assertThat(ticket.currentStageNo()).isEqualTo(1);
        assertThat(ticket.currentStageType()).isEqualTo("RAMP");
        assertThat(ticket.targetTemp()).isEqualByComparingTo("600.0");

        // 曲线未走完禁止投料
        BusinessException denied = assertThrows(BusinessException.class,
                () -> warmUpService.feed(new FeedRequest("REQ-FEED-EARLY", KILN)));
        assertThat(denied.getHttpStatus().value()).isEqualTo(422);
        assertThat(denied.getCategory()).isEqualTo(RejectCategory.CURVE_NOT_FINISHED);

        // 三段升温均达标推进（恰等于目标温度判达标）
        assertThat(confirmByTwo(ticket.ticketNo(), "REQ-C1", "600.0").confirmStatus())
                .isEqualTo("ADVANCED");
        assertThat(confirmByTwo(ticket.ticketNo(), "REQ-C2", "1000.0").confirmStatus())
                .isEqualTo("ADVANCED");
        StageConfirmResponse done = confirmByTwo(ticket.ticketNo(), "REQ-C3", "1450.0");
        assertThat(done.confirmStatus()).isEqualTo("COMPLETED");
        assertThat(done.ticketStatus()).isEqualTo("COMPLETED");

        // 阶段推进与曲线版本记录原子完成：阶段行与票当前序号一致，曲线版本随阶段落库
        Integer stageRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM warm_up_stage WHERE ticket_id = ? AND curve_version = 'CURVE-2026A'",
                Integer.class, ticket.ticketId());
        assertThat(stageRows).isEqualTo(3);
        Integer currentStage = jdbc.queryForObject(
                "SELECT current_stage_no FROM warm_up_ticket WHERE id = ?",
                Integer.class, ticket.ticketId());
        Integer maxStage = jdbc.queryForObject(
                "SELECT MAX(stage_no) FROM warm_up_stage WHERE ticket_id = ?",
                Integer.class, ticket.ticketId());
        assertThat(currentStage).isEqualTo(maxStage);
        // 曲线走完释放窑升温槽
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_active_warmup WHERE kiln_id = ?",
                Integer.class, KILN)).isZero();

        // 曲线走完允许投料
        FeedResponse feed = warmUpService.feed(new FeedRequest("REQ-FEED-1", KILN));
        assertThat(feed.ticketNo()).isEqualTo(ticket.ticketNo());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM feed_record WHERE request_id = 'REQ-FEED-1'",
                Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // 恰满最长用时未达目标 → 自动转保温段；保温达标后继续升温
    // ---------------------------------------------------------------------

    @Test
    void exactly_max_duration_without_target_enters_hold() {
        clock.travelTo(T0);
        WarmUpTicketResponse ticket = createTicket("REQ-WU-2", KILN);

        // 未满最长用时且未达目标：第二人确认评估时拒绝推进（第一人确认已登记保留）
        clock.travelTo(T0.plusMinutes(119));
        assertThat(confirm(ticket.ticketNo(), "REQ-C1-A", "OP-A", "500.0").confirmStatus())
                .isEqualTo("PENDING_SECOND");
        BusinessException tooEarly = assertThrows(BusinessException.class,
                () -> confirm(ticket.ticketNo(), "REQ-C1-B", "OP-B", "500.0"));
        assertThat(tooEarly.getHttpStatus().value()).isEqualTo(422);
        assertThat(warmUpService.status(ticket.ticketNo()).currentStageNo()).isEqualTo(1);

        // 恰满最长用时（120 分钟）未达目标：双人复核齐全后自动转入保温段
        clock.travelTo(T0.plusMinutes(120));
        StageConfirmResponse hold = confirm(ticket.ticketNo(), "REQ-C2", "OP-B", "500.0");
        assertThat(hold.confirmStatus()).isEqualTo("HOLD_ENTERED");
        assertThat(hold.ticketStatus()).isEqualTo("HOLDING");

        WarmUpTicketResponse holding = warmUpService.status(ticket.ticketNo());
        assertThat(holding.status()).isEqualTo("HOLDING");
        assertThat(holding.currentStageType()).isEqualTo("HOLD");
        assertThat(holding.curveStageNo()).isEqualTo(1);
        assertThat(holding.holdStartedAt()).isEqualTo(T0.plusMinutes(120));
        assertThat(holding.targetTemp()).isEqualByComparingTo("600.0");

        // 保温段温度未达承接目标：拒绝结束保温，继续保温
        clock.travelTo(T0.plusMinutes(150));
        assertThat(confirm(ticket.ticketNo(), "REQ-C3-A", "OP-A", "550.0").confirmStatus())
                .isEqualTo("PENDING_SECOND");
        BusinessException stillCold = assertThrows(BusinessException.class,
                () -> confirm(ticket.ticketNo(), "REQ-C3-B", "OP-B", "550.0"));
        assertThat(stillCold.getHttpStatus().value()).isEqualTo(422);
        assertThat(warmUpService.status(ticket.ticketNo()).status()).isEqualTo("HOLDING");

        // 保温达标：双人复核后推进到下一升温段
        StageConfirmResponse resumed = confirm(ticket.ticketNo(), "REQ-C4", "OP-B", "600.0");
        assertThat(resumed.confirmStatus()).isEqualTo("ADVANCED");
        WarmUpTicketResponse executing = warmUpService.status(ticket.ticketNo());
        assertThat(executing.status()).isEqualTo("EXECUTING");
        assertThat(executing.currentStageType()).isEqualTo("RAMP");
        assertThat(executing.curveStageNo()).isEqualTo(2);
        assertThat(executing.holdStartedAt()).isNull();
    }

    // ---------------------------------------------------------------------
    // 保温段恰满 4 小时升级告警
    // ---------------------------------------------------------------------

    @Test
    void hold_exactly_four_hours_escalates_alarm_once() {
        clock.travelTo(T0);
        WarmUpTicketResponse ticket = createTicket("REQ-WU-3", KILN);
        clock.travelTo(T0.plusMinutes(120));
        assertThat(confirmByTwo(ticket.ticketNo(), "REQ-C1", "500.0").confirmStatus())
                .isEqualTo("HOLD_ENTERED");
        LocalDateTime holdStart = T0.plusMinutes(120);

        // 差 1 分钟满 4 小时：不告警
        clock.travelTo(holdStart.plusHours(4).minusMinutes(1));
        assertThat(warmUpService.scanHoldTimeouts()).isZero();

        // 恰满 4 小时：升级告警，且重复扫描不重复告警
        clock.travelTo(holdStart.plusHours(4));
        assertThat(warmUpService.scanHoldTimeouts()).isEqualTo(1);
        assertThat(warmUpService.scanHoldTimeouts()).isZero();
        Integer alarms = jdbc.queryForObject(
                "SELECT COUNT(*) FROM warm_up_alarm WHERE ticket_id = ? AND alarm_type = 'HOLD_TIMEOUT'",
                Integer.class, ticket.ticketId());
        assertThat(alarms).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // 双人复核：单人确认不生效，同一操作人不可重复确认
    // ---------------------------------------------------------------------

    @Test
    void stage_confirm_requires_two_distinct_operators() {
        clock.travelTo(T0);
        WarmUpTicketResponse ticket = createTicket("REQ-WU-4", KILN);

        StageConfirmResponse first = confirm(ticket.ticketNo(), "REQ-C1-A", "OP-A", "600.0");
        assertThat(first.confirmStatus()).isEqualTo("PENDING_SECOND");
        assertThat(first.confirmedOperators()).containsExactly("OP-A");
        // 单人确认不生效：阶段未推进
        WarmUpTicketResponse afterFirst = warmUpService.status(ticket.ticketNo());
        assertThat(afterFirst.currentStageNo()).isEqualTo(1);
        assertThat(afterFirst.confirmedOperators()).containsExactly("OP-A");

        // 同一操作人重复确认（新 requestId）：拒绝，不计为第二人
        BusinessException sameOperator = assertThrows(BusinessException.class, () -> confirm(
                ticket.ticketNo(), "REQ-C1-A2", "OP-A", "600.0"));
        assertThat(sameOperator.getHttpStatus().value()).isEqualTo(422);
        assertThat(warmUpService.status(ticket.ticketNo()).confirmedOperators())
                .containsExactly("OP-A");

        // 第二名操作人确认后阶段才推进
        StageConfirmResponse second = confirm(ticket.ticketNo(), "REQ-C1-B", "OP-B", "600.0");
        assertThat(second.confirmStatus()).isEqualTo("ADVANCED");
        assertThat(second.confirmedOperators()).containsExactly("OP-A", "OP-B");
        assertThat(warmUpService.status(ticket.ticketNo()).currentStageNo()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // 幂等：同键同参重放首次结果，同键异参 409
    // ---------------------------------------------------------------------

    @Test
    void idempotent_replay_and_conflict() {
        clock.travelTo(T0);
        WarmUpTicketResponse ticket = createTicket("REQ-WU-5", KILN);
        WarmUpTicketResponse replay = createTicket("REQ-WU-5", KILN);
        assertThat(replay.ticketNo()).isEqualTo(ticket.ticketNo());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warm_up_ticket WHERE request_id = 'REQ-WU-5'",
                Integer.class)).isEqualTo(1);

        // 同键异参：409
        BusinessException conflict = assertThrows(BusinessException.class,
                () -> createTicket("REQ-WU-5", "KILN-OTHER"));
        assertThat(conflict.getHttpStatus().value()).isEqualTo(409);
        assertThat(conflict.getCategory()).isEqualTo(RejectCategory.IDEMPOTENT_CONFLICT);

        // 阶段确认重放：不重复登记确认人
        StageConfirmResponse first = confirm(ticket.ticketNo(), "REQ-C5-A", "OP-A", "600.0");
        StageConfirmResponse firstReplay = confirm(ticket.ticketNo(), "REQ-C5-A", "OP-A", "600.0");
        assertThat(firstReplay.confirmStatus()).isEqualTo(first.confirmStatus());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warm_up_stage_confirm WHERE ticket_id = ?",
                Integer.class, ticket.ticketId())).isEqualTo(1);
        BusinessException confirmConflict = assertThrows(BusinessException.class, () -> confirm(
                ticket.ticketNo(), "REQ-C5-A", "OP-A", "700.0"));
        assertThat(confirmConflict.getHttpStatus().value()).isEqualTo(409);

        // 走完曲线后投料幂等重放
        confirm(ticket.ticketNo(), "REQ-C5-B", "OP-B", "600.0");
        confirmByTwo(ticket.ticketNo(), "REQ-C5-2", "1000.0");
        confirmByTwo(ticket.ticketNo(), "REQ-C5-3", "1450.0");
        FeedResponse feed = warmUpService.feed(new FeedRequest("REQ-FEED-5", KILN));
        FeedResponse feedReplay = warmUpService.feed(new FeedRequest("REQ-FEED-5", KILN));
        assertThat(feedReplay.feedId()).isEqualTo(feed.feedId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM feed_record WHERE request_id = 'REQ-FEED-5'",
                Integer.class)).isEqualTo(1);
        BusinessException feedConflict = assertThrows(BusinessException.class,
                () -> warmUpService.feed(new FeedRequest("REQ-FEED-5", "KILN-OTHER")));
        assertThat(feedConflict.getHttpStatus().value()).isEqualTo(409);
    }

    // ---------------------------------------------------------------------
    // 并发：同窑同时刻只允许一张执行中升温票
    // ---------------------------------------------------------------------

    @Test
    void concurrent_create_allows_only_one_active_ticket_per_kiln() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String reqId = String.format("REQ-CONC-WU-%03d", i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    createTicket(reqId, KILN);
                    accepted.incrementAndGet();
                } catch (BusinessException e) {
                    assertThat(e.getCategory()).isEqualTo(RejectCategory.WARMUP_TICKET_ACTIVE);
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_active_warmup WHERE kiln_id = ?",
                Integer.class, KILN)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warm_up_ticket WHERE kiln_id = ? AND status = 'EXECUTING'",
                Integer.class, KILN)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // 无升温票的窑禁止投料
    // ---------------------------------------------------------------------

    @Test
    void feed_without_any_ticket_is_denied() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> warmUpService.feed(new FeedRequest("REQ-FEED-NONE", "KILN-Z")));
        assertThat(ex.getHttpStatus().value()).isEqualTo(422);
        assertThat(ex.getCategory()).isEqualTo(RejectCategory.CURVE_NOT_FINISHED);
    }
}
