package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.FeedRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.RecycleQuotaView;
import com.example.starter.transition.dto.ReduceRecycleQuotaRequest;
import com.example.starter.transition.dto.ReexamRequest;
import com.example.starter.transition.dto.RegisterRecycleQuotaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收额度登记与调减 H2 集成测试：登记约束、版本化调减、改判失效与幂等重放。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class RecycleQuotaWorkflowTest extends AbstractIntegrationTest {

    @Autowired
    private TransitionService transitionService;
    @Autowired
    private RecycleService recycleService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("登记成功：视图勾稽原额度=当前额度、消费为0、版本1、状态有效")
    void registerQuotaSuccessAndViewReconciles() {
        prepareRoll("q-roll-1", "PM-Q1", "q1");

        IdempotentResult<RecycleQuotaView> result = recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q1-reg", "q-roll-1", new BigDecimal("25.500")));

        assertFalse(result.replayed());
        RecycleQuotaView view = result.data();
        assertEquals("q-roll-1", view.rollNo());
        assertEquals(0, view.registeredKg().compareTo(new BigDecimal("25.500")));
        assertEquals(0, view.currentKg().compareTo(new BigDecimal("25.500")));
        assertEquals(0, view.consumedKg().compareTo(BigDecimal.ZERO));
        assertEquals(0, view.availableKg().compareTo(new BigDecimal("25.500")));
        assertEquals(1, view.version());
        assertEquals("ACTIVE", view.status());
        assertNull(view.invalidatedReason());
        assertTrue(view.consumptions().isEmpty());
    }

    @Test
    @DisplayName("登记校验：非正数与超三位小数400，失败不占键可同键重试成功")
    void registerQuotaValidationAndFailureReleasesKey() {
        prepareRoll("q-roll-2", "PM-Q2", "q2");

        ApiException zero = assertThrows(ApiException.class, () -> recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q2-reg", "q-roll-2", BigDecimal.ZERO)));
        assertEquals(400, zero.getStatus());
        ApiException tooPrecise = assertThrows(ApiException.class, () -> recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q2-reg2", "q-roll-2", new BigDecimal("10.0005"))));
        assertEquals(400, tooPrecise.getStatus());

        // 失败不占键：同一 requestId 换合法参数可成功登记。
        IdempotentResult<RecycleQuotaView> ok = recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q2-reg", "q-roll-2", new BigDecimal("10.000")));
        assertFalse(ok.replayed());
        assertEquals(0, ok.data().registeredKg().compareTo(new BigDecimal("10.000")));
    }

    @Test
    @DisplayName("登记幂等：同键同参重放冻结响应，同键异参409，每卷仅一次登记")
    void registerQuotaIdempotencyAndUniqueness() {
        prepareRoll("q-roll-3", "PM-Q3", "q3");

        IdempotentResult<RecycleQuotaView> first = recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q3-reg", "q-roll-3", new BigDecimal("12.500")));
        IdempotentResult<RecycleQuotaView> replay = recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q3-reg", "q-roll-3", new BigDecimal("12.500")));
        assertTrue(replay.replayed());
        assertEquals(first.data().registeredKg(), replay.data().registeredKg());
        assertEquals(first.data().createdAt(), replay.data().createdAt());

        ApiException differentParams = assertThrows(ApiException.class, () -> recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q3-reg", "q-roll-3", new BigDecimal("9.999"))));
        assertEquals(409, differentParams.getStatus());

        ApiException duplicate = assertThrows(ApiException.class, () -> recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q3-reg-again", "q-roll-3", new BigDecimal("1.000"))));
        assertEquals(409, duplicate.getStatus());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM recycle_quota WHERE roll_no = 'q-roll-3'", Long.class));
    }

    @Test
    @DisplayName("已改判正品的卷不能新增回收登记")
    void registerQuotaOnUpgradedRollRejected() {
        prepareRoll("q-roll-4", "PM-Q4", "q4");
        upgradeRoll("q-roll-4", "q4");

        ApiException ex = assertThrows(ApiException.class, () -> recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q4-reg", "q-roll-4", new BigDecimal("5.000"))));
        assertEquals(409, ex.getStatus());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM recycle_quota WHERE roll_no = 'q-roll-4'", Long.class));
    }

    @Test
    @DisplayName("调减：版本1起成功加1，过期409，只允许减少且不得低于累计消费")
    void reduceQuotaVersionAndConsumptionGuards() {
        prepareRoll("q-roll-5", "PM-Q5", "q5");
        recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q5-reg", "q-roll-5", new BigDecimal("20.000")));
        // 消费 8kg：另一执行中订单按比例投料（50kg×16%=8kg）。
        String feeder = prepareExecutingOrder("PM-Q5F", "q5f", "16.000");
        recycleService.submitFeed(new FeedRequest("q5-feed", feeder, 1,
                new BigDecimal("50.000"), List.of(
                new FeedRequest.FeedItem("q-roll-5", new BigDecimal("8.000")))));

        // 版本过期：当前版本已被消费推到 2。
        ApiException stale = assertThrows(ApiException.class, () -> recycleService.reduceQuota("q-roll-5",
                new ReduceRecycleQuotaRequest("q5-red-stale", 1, new BigDecimal("15.000"))));
        assertEquals(409, stale.getStatus());
        // 不得低于累计消费 8kg。
        ApiException belowConsumed = assertThrows(ApiException.class, () -> recycleService.reduceQuota("q-roll-5",
                new ReduceRecycleQuotaRequest("q5-red-low", 2, new BigDecimal("7.999"))));
        assertEquals(409, belowConsumed.getStatus());
        // 只允许减少：等于当前额度也拒绝。
        ApiException notReduced = assertThrows(ApiException.class, () -> recycleService.reduceQuota("q-roll-5",
                new ReduceRecycleQuotaRequest("q5-red-eq", 2, new BigDecimal("20.000"))));
        assertEquals(409, notReduced.getStatus());

        // 合法调减到恰等于累计消费：版本 2→3，可用额度归零。
        IdempotentResult<RecycleQuotaView> reduced = recycleService.reduceQuota("q-roll-5",
                new ReduceRecycleQuotaRequest("q5-red-ok", 2, new BigDecimal("8.000")));
        assertFalse(reduced.replayed());
        assertEquals(3, reduced.data().version());
        assertEquals(0, reduced.data().currentKg().compareTo(new BigDecimal("8.000")));
        assertEquals(0, reduced.data().consumedKg().compareTo(new BigDecimal("8.000")));
        assertEquals(0, reduced.data().availableKg().compareTo(BigDecimal.ZERO));

        // 同键重放不重复扣版本。
        IdempotentResult<RecycleQuotaView> replay = recycleService.reduceQuota("q-roll-5",
                new ReduceRecycleQuotaRequest("q5-red-ok", 2, new BigDecimal("8.000")));
        assertTrue(replay.replayed());
        assertEquals(3, recycleService.getQuota("q-roll-5").version());
    }

    @Test
    @DisplayName("复议改判正品：未消费额度同步失效，已消费凭据保留，失效后调减409")
    void upgradeInvalidatesUnconsumedQuota() {
        prepareRoll("q-roll-6", "PM-Q6", "q6");
        recycleService.registerQuota(
                new RegisterRecycleQuotaRequest("q6-reg", "q-roll-6", new BigDecimal("20.000")));
        String feeder = prepareExecutingOrder("PM-Q6F", "q6f", "16.000");
        recycleService.submitFeed(new FeedRequest("q6-feed", feeder, 1,
                new BigDecimal("50.000"), List.of(
                new FeedRequest.FeedItem("q-roll-6", new BigDecimal("8.000")))));

        upgradeRoll("q-roll-6", "q6");

        RecycleQuotaView view = recycleService.getQuota("q-roll-6");
        assertEquals("INVALIDATED", view.status());
        assertEquals("REEXAM_UPGRADED", view.invalidatedReason());
        assertEquals(0, view.currentKg().compareTo(new BigDecimal("8.000")), "未消费额度失效，当前额度降为累计消费");
        assertEquals(0, view.consumedKg().compareTo(new BigDecimal("8.000")), "累计消费不变");
        assertEquals(0, view.availableKg().compareTo(BigDecimal.ZERO));
        assertEquals(0, view.registeredKg().compareTo(new BigDecimal("20.000")), "原额度保留可勾稽");
        assertEquals(1, view.consumptions().size(), "已消费凭据保留");
        assertTrue(view.consumptions().get(0).feedNo().startsWith("FE-"));
        assertEquals(0, view.consumptions().get(0).kg().compareTo(new BigDecimal("8.000")));

        ApiException reduceAfterInvalidation = assertThrows(ApiException.class,
                () -> recycleService.reduceQuota("q-roll-6",
                        new ReduceRecycleQuotaRequest("q6-red", view.version(), new BigDecimal("5.000"))));
        assertEquals(409, reduceAfterInvalidation.getStatus());
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 登记一张过渡卷：申请→判定→执行→处置（强制降级）。 */
    private void prepareRoll(String rollNo, String machine, String prefix) {
        OrderView applied = transitionService.apply(new ApplyOrderRequest(
                prefix + "-apply", machine, "G-A4-70", "G-A4-77")).data();
        transitionService.decide(applied.orderNo());
        transitionService.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        transitionService.dispose(applied.orderNo(), new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem(rollNo, new BigDecimal("900.000")))));
    }

    /** 准备一张执行中且携带掺配比例的订单（申请携带比例即自动判定）。 */
    private String prepareExecutingOrder(String machine, String prefix, String ratioPct) {
        OrderView applied = transitionService.apply(new ApplyOrderRequest(
                prefix + "-apply", machine, "G-A4-70", "G-A4-77", new BigDecimal(ratioPct))).data();
        transitionService.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        return applied.orderNo();
    }

    /** 首次检验达门槛后复议改判正品。 */
    private void upgradeRoll(String rollNo, String prefix) {
        transitionService.submitInspection(new InspectionRequest(
                prefix + "-insp", rollNo, new BigDecimal("18.000"), new BigDecimal("4.000"),
                new BigDecimal("45.000"), new BigDecimal("77.00")));
        transitionService.submitReexam(new ReexamRequest(
                prefix + "-rex", rollNo, new BigDecimal("17.000"), new BigDecimal("6.000"),
                new BigDecimal("47.000"), new BigDecimal("77.00")));
        assertEquals("UPGRADED", transitionService.getRoll(rollNo).verdict());
    }
}
