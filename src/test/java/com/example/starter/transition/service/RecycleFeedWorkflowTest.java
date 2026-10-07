package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.TransitionStatus;
import com.example.starter.transition.dto.AdjustBlendRatioRequest;
import com.example.starter.transition.dto.AdjustRecycleQuotaRequest;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.BlendReadingRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.FeedView;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.RecycleQuotaView;
import com.example.starter.transition.dto.ReexamRequest;
import com.example.starter.transition.dto.RegisterRecycleQuotaRequest;
import com.example.starter.transition.dto.SubmitFeedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收重量登记、额度调减与投料申请主流程及失败分支的 H2 集成测试。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class RecycleFeedWorkflowTest extends AbstractIntegrationTest {

    private static final LocalDateTime SAMPLED_AT = LocalDateTime.of(2026, 9, 25, 11, 0, 0);

    @Autowired
    private TransitionService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("回收登记：正数三位小数登记成功，版本1起，可用额度=登记额度")
    void registerQuotaHappyPath() {
        prepareDisposedRolls("rq-dis-1", "PM-RQ1", "R-Q1");
        IdempotentResult<RecycleQuotaView> result = service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-1", "R-Q1", new BigDecimal("10.500")));

        assertFalse(result.replayed());
        RecycleQuotaView view = result.data();
        assertEquals("R-Q1", view.rollNo());
        assertKg("10.500", view.initialKg());
        assertKg("10.500", view.registeredKg());
        assertKg("0", view.consumedKg());
        assertKg("10.500", view.availableKg());
        assertKg("0", view.invalidatedKg());
        assertEquals(1, view.version());
        assertEquals("ACTIVE", view.status());
        assertTrue(view.consumptions().isEmpty());
    }

    @Test
    @DisplayName("回收登记：非正数或超过三位小数拒绝400，未登记卷404，重复登记409")
    void registerQuotaValidation() {
        prepareDisposedRolls("rq-dis-2", "PM-RQ2", "R-Q2", "R-Q3");

        assertStatus(400, () -> service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-bad-1", "R-Q2", new BigDecimal("0"))));
        assertStatus(400, () -> service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-bad-2", "R-Q2", new BigDecimal("-1.5"))));
        assertStatus(400, () -> service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-bad-3", "R-Q2", new BigDecimal("10.0001"))));
        assertStatus(404, () -> service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-bad-4", "R-NOPE", new BigDecimal("10"))));

        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("rq-2", "R-Q2", new BigDecimal("10")));
        assertStatus(409, () -> service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-2-again", "R-Q2", new BigDecimal("8"))));
    }

    @Test
    @DisplayName("回收登记：失败不占幂等键，同键修正参数后可重新登记")
    void failedRegisterDoesNotOccupyKey() {
        prepareDisposedRolls("rq-dis-3", "PM-RQ3", "R-Q4");
        assertStatus(400, () -> service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-retry", "R-Q4", new BigDecimal("10.0001"))));

        IdempotentResult<RecycleQuotaView> result = service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-retry", "R-Q4", new BigDecimal("10")));
        assertFalse(result.replayed(), "失败请求不占键，修正参数后应正常受理");
        assertKg("10", result.data().registeredKg());
    }

    @Test
    @DisplayName("回收登记重放：同键同参返回冻结响应，同键异参409")
    void registerQuotaIdempotentReplay() {
        prepareDisposedRolls("rq-dis-4", "PM-RQ4", "R-Q5");
        IdempotentResult<RecycleQuotaView> first = service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-replay", "R-Q5", new BigDecimal("10")));

        IdempotentResult<RecycleQuotaView> replayed = service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-replay", "R-Q5", new BigDecimal("10")));
        assertTrue(replayed.replayed());
        assertEquals(first.data(), replayed.data(), "重放返回首次冻结响应");

        assertStatus(409, () -> service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-replay", "R-Q5", new BigDecimal("9"))));
    }

    @Test
    @DisplayName("已改判正品的卷不能新增回收登记")
    void registerQuotaOnUpgradedRollRejected() {
        prepareDisposedRolls("rq-dis-5", "PM-RQ5", "R-Q6");
        upgradeRoll("R-Q6", "insp-q6", "rex-q6");

        assertStatus(409, () -> service.registerRecycleQuota(
                new RegisterRecycleQuotaRequest("rq-upgraded", "R-Q6", new BigDecimal("10"))));
    }

    @Test
    @DisplayName("额度调减：只允许减少未消费额度，版本成功+1，过期409，不得减掉累计消费")
    void adjustQuotaRules() {
        prepareDisposedRolls("aq-dis-1", "PM-AQ1", "R-A1", "R-A2");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("aq-1", "R-A1", new BigDecimal("10")));
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("aq-2", "R-A2", new BigDecimal("10")));
        // R-A2 先消费 4 kg（总浆 20 kg × 20% = 4 kg）
        String feedOrder = prepareExecutedOrder("aq-feed-order", "PM-AQ2", "20.000");
        submitFeed(feedOrder, 1, "aq-feed-1", "20", item("R-A2", "4"));

        // 调减 10 → 6：成功，版本 1→2
        IdempotentResult<RecycleQuotaView> adjusted = service.adjustRecycleQuota("R-A1",
                new AdjustRecycleQuotaRequest("aq-adj-1", 1, new BigDecimal("6")));
        assertFalse(adjusted.replayed());
        assertEquals(2, adjusted.data().version());
        assertKg("6", adjusted.data().registeredKg());
        assertKg("6", adjusted.data().availableKg());

        // 过期版本 409
        assertStatus(409, () -> service.adjustRecycleQuota("R-A1",
                new AdjustRecycleQuotaRequest("aq-adj-2", 1, new BigDecimal("5"))));
        // 增加额度 400（只允许减少）
        assertStatus(400, () -> service.adjustRecycleQuota("R-A1",
                new AdjustRecycleQuotaRequest("aq-adj-3", 2, new BigDecimal("8"))));
        // 不得把累计消费减掉：R-A2 已消费 4，调到 3 拒绝 409
        assertStatus(409, () -> service.adjustRecycleQuota("R-A2",
                new AdjustRecycleQuotaRequest("aq-adj-4", 2, new BigDecimal("3"))));
        // 调到恰等于累计消费：允许
        IdempotentResult<RecycleQuotaView> toConsumed = service.adjustRecycleQuota("R-A2",
                new AdjustRecycleQuotaRequest("aq-adj-5", 2, new BigDecimal("4")));
        assertKg("4", toConsumed.data().registeredKg());
        assertKg("0", toConsumed.data().availableKg());
    }

    @Test
    @DisplayName("复议改判正品：未消费额度同步失效，已消费凭据保留，失效后不得投料与调减")
    void upgradeInvalidatesUnconsumedQuota() {
        prepareDisposedRolls("up-dis-1", "PM-UP1", "R-U1");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("up-rq", "R-U1", new BigDecimal("10")));
        // 先消费 4 kg
        String feedOrder = prepareExecutedOrder("up-feed-order", "PM-UP2", "20.000");
        FeedView consumed = submitFeed(feedOrder, 1, "up-feed-1", "20", item("R-U1", "4")).data();

        upgradeRoll("R-U1", "up-insp", "up-rex");

        RecycleQuotaView view = service.getRecycleQuota("R-U1");
        assertEquals("INVALIDATED", view.status());
        assertEquals("REEXAM_UPGRADED", view.invalidReason());
        assertKg("10", view.registeredKg());
        assertKg("4", view.consumedKg());
        assertKg("6", view.invalidatedKg());
        assertKg("0", view.availableKg());
        assertEquals(1, view.consumptions().size(), "已消费凭据保留");
        assertEquals(consumed.feedNo(), view.consumptions().get(0).feedNo());
        assertKg("4", view.consumptions().get(0).weightKg());

        // 失效后不得再投料、不得调减
        assertStatus(409, () -> submitFeed(feedOrder, 1, "up-feed-2", "20", item("R-U1", "4")));
        assertStatus(409, () -> service.adjustRecycleQuota("R-U1",
                new AdjustRecycleQuotaRequest("up-adj", view.version(), new BigDecimal("9"))));
    }

    @Test
    @DisplayName("投料主流程：总浆100kg×比例20%=应投20kg，两卷各10kg，额度同步扣减")
    void feedHappyPath() {
        prepareDisposedRolls("fd-dis-1", "PM-FD1", "R-F1", "R-F2");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("fd-rq-1", "R-F1", new BigDecimal("10")));
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("fd-rq-2", "R-F2", new BigDecimal("10")));
        String orderNo = prepareExecutedOrder("fd-order", "PM-FD2", "20.000");

        IdempotentResult<FeedView> result = submitFeed(orderNo, 1, "fd-1", "100",
                item("R-F1", "10"), item("R-F2", "10"));

        assertFalse(result.replayed());
        FeedView view = result.data();
        assertEquals(orderNo, view.orderNo());
        assertEquals(1, view.versionNo());
        assertKg("100", view.totalPulpKg());
        assertKg("20.000", view.targetRatioPct());
        assertKg("20", view.requiredKg());
        assertKg("20", view.actualKg());
        assertEquals(2, view.items().size());

        RecycleQuotaView q1 = service.getRecycleQuota("R-F1");
        assertKg("10", q1.consumedKg());
        assertKg("0", q1.availableKg());
        assertEquals(2, q1.version(), "消费后版本 +1");
        assertEquals(1, q1.consumptions().size());
        assertEquals(view.feedNo(), q1.consumptions().get(0).feedNo());
        assertEquals(orderNo, q1.consumptions().get(0).orderNo());

        // 批查询与勾稽一致
        FeedView fetched = service.getFeed(view.feedNo());
        assertEquals(view, fetched);
    }

    @Test
    @DisplayName("投料比例核算：合计不等于应投量400，乘积超三位小数精度400")
    void feedRatioAccounting() {
        prepareDisposedRolls("ra-dis-1", "PM-RA1", "R-C1", "R-C2");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("ra-rq-1", "R-C1", new BigDecimal("10")));
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("ra-rq-2", "R-C2", new BigDecimal("10")));
        String orderNo = prepareExecutedOrder("ra-order", "PM-RA2", "20.000");

        // 合计 19.999 ≠ 应投 20 → 400
        assertStatus(400, () -> submitFeed(orderNo, 1, "ra-bad-1", "100",
                item("R-C1", "10"), item("R-C2", "9.999")));
        // 乘积 0.001 × 20.000 / 100 = 0.0002 超过三位小数精度 → 400
        assertStatus(400, () -> submitFeed(orderNo, 1, "ra-bad-2", "0.001",
                item("R-C1", "0.001")));
        // 批内卷号重复 → 400
        assertStatus(400, () -> submitFeed(orderNo, 1, "ra-bad-3", "100",
                item("R-C1", "10"), item("R-C1", "10")));
        // 未登记回收重量的卷 → 409；不存在的卷 → 404
        prepareDisposedRolls("ra-dis-2", "PM-RA3", "R-C3");
        assertStatus(409, () -> submitFeed(orderNo, 1, "ra-bad-4", "100",
                item("R-C1", "10"), item("R-C3", "10")));
        assertStatus(404, () -> submitFeed(orderNo, 1, "ra-bad-5", "100",
                item("R-C1", "10"), item("R-NOPE", "10")));
    }

    @Test
    @DisplayName("投料精度边界：应投量恰三位小数合法，超精度组合拒绝且额度不受影响")
    void feedPrecisionBoundary() {
        prepareDisposedRolls("pr-dis-1", "PM-PR1", "R-P1");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("pr-rq", "R-P1", new BigDecimal("0.5")));
        String orderNo = prepareExecutedOrder("pr-order", "PM-PR2", "20.000");

        // 总浆 2.5 × 20% = 0.5 恰三位小数 → 合法
        IdempotentResult<FeedView> ok = submitFeed(orderNo, 1, "pr-ok", "2.5", item("R-P1", "0.5"));
        assertKg("0.5", ok.data().requiredKg());

        // 总浆 0.001 × 20% = 0.0002 超三位小数 → 400，额度不变
        assertStatus(400, () -> submitFeed(orderNo, 1, "pr-bad", "0.001", item("R-P1", "0.001")));
        assertKg("0.5", service.getRecycleQuota("R-P1").consumedKg());
    }

    @Test
    @DisplayName("投料超领回滚：任一卷额度不足整单回滚，其他卷额度与批次均不留痕")
    void feedOverClaimRollback() {
        prepareDisposedRolls("oc-dis-1", "PM-OC1", "R-O1", "R-O2");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("oc-rq-1", "R-O1", new BigDecimal("10")));
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("oc-rq-2", "R-O2", new BigDecimal("5")));
        String orderNo = prepareExecutedOrder("oc-order", "PM-OC2", "20.000");

        // R-O2 仅有 5 kg，整批需各 10 kg → 整单回滚
        assertStatus(409, () -> submitFeed(orderNo, 1, "oc-feed", "100",
                item("R-O1", "10"), item("R-O2", "10")));

        assertKg("0", service.getRecycleQuota("R-O1").consumedKg());
        assertKg("0", service.getRecycleQuota("R-O2").consumedKg());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_batch", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_batch_item", Long.class));
    }

    @Test
    @DisplayName("投料状态守卫：版本已换、订单暂停、订单归档均拒绝")
    void feedStateGuards() {
        prepareDisposedRolls("sg-dis-1", "PM-SG1", "R-S1", "R-S2", "R-S3");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("sg-rq-1", "R-S1", new BigDecimal("20")));
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("sg-rq-2", "R-S2", new BigDecimal("20")));
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("sg-rq-3", "R-S3", new BigDecimal("20")));

        // 版本已换：调整比例后旧版本号投料 → 409
        String orderNo = prepareExecutedOrder("sg-order-1", "PM-SG2", "20.000");
        service.adjustBlendRatio(orderNo, new AdjustBlendRatioRequest("sg-adj", new BigDecimal("15.000")));
        assertStatus(409, () -> submitFeed(orderNo, 1, "sg-feed-1", "100", item("R-S1", "20")));
        // 当前版本 2、比例 15%：100×15%=15 → 合法
        submitFeed(orderNo, 2, "sg-feed-2", "100", item("R-S1", "15"));

        // 暂停：连续 3 个超差读数触发失控暂停 → 409
        String pausedOrder = prepareExecutedOrder("sg-order-2", "PM-SG3", "20.000");
        for (int i = 0; i < 3; i++) {
            service.submitBlendReading(new BlendReadingRequest("sg-rd-" + i, pausedOrder,
                    SAMPLED_AT.plusMinutes(i), new BigDecimal("28.000")));
        }
        assertEquals(TransitionStatus.PAUSED, service.getOrder(pausedOrder).status());
        assertStatus(409, () -> submitFeed(pausedOrder, 1, "sg-feed-3", "100", item("R-S2", "20")));

        // 归档：完成并归档后 → 409
        String archivedOrder = prepareExecutedOrder("sg-order-3", "PM-SG4", "20.000");
        service.dispose(archivedOrder, new DisposeRollsRequest(
                List.of(new DisposeRollsRequest.RollItem("R-S9", new BigDecimal("1")))));
        service.complete(archivedOrder);
        service.archive(archivedOrder);
        assertStatus(409, () -> submitFeed(archivedOrder, 1, "sg-feed-4", "100", item("R-S3", "20")));
    }

    @Test
    @DisplayName("投料幂等：同键同参重放冻结响应（同一批号），同键异参409，失败不占键")
    void feedIdempotency() {
        prepareDisposedRolls("fi-dis-1", "PM-FI1", "R-I1", "R-I2");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("fi-rq-1", "R-I1", new BigDecimal("30")));
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("fi-rq-2", "R-I2", new BigDecimal("30")));
        String orderNo = prepareExecutedOrder("fi-order", "PM-FI2", "20.000");

        // 失败不占键：合计不符 400 后同键修正参数可成功
        assertStatus(400, () -> submitFeed(orderNo, 1, "fi-retry", "100",
                item("R-I1", "10"), item("R-I2", "9")));
        IdempotentResult<FeedView> first = submitFeed(orderNo, 1, "fi-retry", "100",
                item("R-I1", "10"), item("R-I2", "10"));
        assertFalse(first.replayed());

        // 同键同参重放：冻结响应，批号一致，额度不重复扣减
        IdempotentResult<FeedView> replayed = submitFeed(orderNo, 1, "fi-retry", "100",
                item("R-I1", "10"), item("R-I2", "10"));
        assertTrue(replayed.replayed());
        assertEquals(first.data(), replayed.data());
        assertKg("10", service.getRecycleQuota("R-I1").consumedKg());

        // 同键异参 → 409
        assertStatus(409, () -> submitFeed(orderNo, 1, "fi-retry", "100",
                item("R-I1", "11"), item("R-I2", "9")));
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 申请（携带比例，同事务判定）并执行，返回 EXECUTED 状态单号。 */
    private String prepareExecutedOrder(String requestId, String machine, String ratioPct) {
        OrderView applied = service.apply(new ApplyOrderRequest(
                requestId, machine, "G-A4-70", "G-A4-77", new BigDecimal(ratioPct))).data();
        return service.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60)).orderNo();
    }

    /** 申请→判定→执行→处置，登记过渡卷（强制降级），返回单号。 */
    private String prepareDisposedRolls(String requestId, String machine, String... rollNos) {
        OrderView applied = service.apply(new ApplyOrderRequest(
                requestId, machine, "G-A4-70", "G-A4-77")).data();
        service.decide(applied.orderNo());
        service.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        List<DisposeRollsRequest.RollItem> items = java.util.Arrays.stream(rollNos)
                .map(r -> new DisposeRollsRequest.RollItem(r, new BigDecimal("100.5")))
                .toList();
        return service.dispose(applied.orderNo(), new DisposeRollsRequest(items)).orderNo();
    }

    /** 首次检验达门槛后复议改判正品。 */
    private void upgradeRoll(String rollNo, String inspectionRequestId, String reexamRequestId) {
        service.submitInspection(new InspectionRequest(inspectionRequestId, rollNo,
                new BigDecimal("18.000"), new BigDecimal("4.000"),
                new BigDecimal("45.000"), new BigDecimal("77.00")));
        service.submitReexam(new ReexamRequest(reexamRequestId, rollNo,
                new BigDecimal("18.000"), new BigDecimal("4.000"),
                new BigDecimal("45.000"), new BigDecimal("77.00")));
        assertEquals("UPGRADED", service.getRoll(rollNo).verdict());
    }

    private SubmitFeedRequest.FeedItem item(String rollNo, String weightKg) {
        return new SubmitFeedRequest.FeedItem(rollNo, new BigDecimal(weightKg));
    }

    private IdempotentResult<FeedView> submitFeed(String orderNo, int versionNo, String requestId,
                                                  String totalPulpKg,
                                                  SubmitFeedRequest.FeedItem... items) {
        return service.submitFeed(new SubmitFeedRequest(requestId, orderNo, versionNo,
                new BigDecimal(totalPulpKg), List.of(items)));
    }

    private void assertKg(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " kg，实际 " + actual + " kg");
    }

    private void assertStatus(int status, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(status, ex.getStatus(), ex.getMessage());
    }
}
