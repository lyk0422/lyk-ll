package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.dto.AdjustBlendRatioRequest;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.BlendReadingRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.FeedRequest;
import com.example.starter.transition.dto.FeedView;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.RecycleQuotaView;
import com.example.starter.transition.dto.RegisterRecycleQuotaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投料申请 H2 集成测试：比例精确核算、超领整单回滚、状态/版本拒绝与幂等重放。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class FeedWorkflowTest extends AbstractIntegrationTest {

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
    @DisplayName("投料主流程：总浆100kg、比例20%、两卷各10kg，应投20kg精确勾稽")
    void feedHappyPath() {
        prepareRollWithQuota("f-roll-1", "PM-F1", "f1", "10.000");
        prepareRollWithQuota("f-roll-2", "PM-F2", "f2", "10.000");
        String orderNo = prepareExecutingOrder("PM-FF1", "ff1", "20.000");

        IdempotentResult<FeedView> result = recycleService.submitFeed(new FeedRequest(
                "ff1-feed", orderNo, 1, new BigDecimal("100.000"), List.of(
                new FeedRequest.FeedItem("f-roll-1", new BigDecimal("10.000")),
                new FeedRequest.FeedItem("f-roll-2", new BigDecimal("10.000")))));

        assertFalse(result.replayed());
        FeedView view = result.data();
        assertTrue(view.feedNo().startsWith("FE-"));
        assertEquals(orderNo, view.orderNo());
        assertEquals(1, view.blendVersionNo());
        assertEquals(0, view.totalPulpKg().compareTo(new BigDecimal("100.000")));
        assertEquals(0, view.requiredRecycleKg().compareTo(new BigDecimal("20.00")),
                "应投回收量=100kg×20%=20kg");
        assertEquals(0, view.actualRecycleKg().compareTo(new BigDecimal("20.000")));
        assertEquals(2, view.items().size());

        RecycleQuotaView quota1 = recycleService.getQuota("f-roll-1");
        assertEquals(0, quota1.consumedKg().compareTo(new BigDecimal("10.000")));
        assertEquals(0, quota1.availableKg().compareTo(BigDecimal.ZERO));
        assertEquals(1, quota1.consumptions().size());
        assertEquals(view.feedNo(), quota1.consumptions().get(0).feedNo());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_application", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_consumption", Long.class));
    }

    @Test
    @DisplayName("回收合计与应投量差额不以舍入掩盖：19.999≠20 拒绝，20.001≠20 拒绝")
    void feedSumMustExactlyMatch() {
        prepareRollWithQuota("f-roll-3", "PM-F3", "f3", "30.000");
        String orderNo = prepareExecutingOrder("PM-FF2", "ff2", "20.000");

        ApiException less = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff2-feed-a", orderNo, 1, new BigDecimal("100.000"), List.of(
                        new FeedRequest.FeedItem("f-roll-3", new BigDecimal("19.999"))))));
        assertEquals(409, less.getStatus());
        ApiException more = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff2-feed-b", orderNo, 1, new BigDecimal("100.000"), List.of(
                        new FeedRequest.FeedItem("f-roll-3", new BigDecimal("20.001"))))));
        assertEquals(409, more.getStatus());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_application", Long.class));
        assertEquals(0, recycleService.getQuota("f-roll-3").consumedKg().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("计算结果超过三位小数精度：拒绝该组合")
    void feedPrecisionExceededRejected() {
        prepareRollWithQuota("f-roll-4", "PM-F4", "f4", "30.000");
        String orderNo = prepareExecutingOrder("PM-FF3", "ff3", "20.000");

        // 33.333 × 20% = 6.6666，超过三位小数。
        ApiException ex = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff3-feed", orderNo, 1, new BigDecimal("33.333"), List.of(
                        new FeedRequest.FeedItem("f-roll-4", new BigDecimal("6.667"))))));
        assertEquals(409, ex.getStatus());
        assertTrue(ex.getMessage().contains("精度"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_application", Long.class));
    }

    @Test
    @DisplayName("任一卷余额不足整单回滚：已扣卷额度恢复，无投料单与消费凭据")
    void feedInsufficientQuotaRollsBackWholeOrder() {
        prepareRollWithQuota("f-roll-5", "PM-F5", "f5", "10.000");
        prepareRollWithQuota("f-roll-6", "PM-F6", "f6", "5.000");
        String orderNo = prepareExecutingOrder("PM-FF4", "ff4", "20.000");

        ApiException ex = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff4-feed", orderNo, 1, new BigDecimal("100.000"), List.of(
                        new FeedRequest.FeedItem("f-roll-5", new BigDecimal("10.000")),
                        new FeedRequest.FeedItem("f-roll-6", new BigDecimal("10.000"))))));
        assertEquals(409, ex.getStatus());

        RecycleQuotaView quota5 = recycleService.getQuota("f-roll-5");
        assertEquals(0, quota5.consumedKg().compareTo(BigDecimal.ZERO), "整单回滚：先扣的卷额度恢复");
        assertEquals(0, quota5.availableKg().compareTo(new BigDecimal("10.000")));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_application", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_consumption", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = 'ff4-feed'", Long.class),
                "失败不占键");
    }

    @Test
    @DisplayName("订单暂停、归档或版本已换时拒绝投料")
    void feedRejectedOnPausedArchivedOrStaleVersion() {
        prepareRollWithQuota("f-roll-7", "PM-F7", "f7", "30.000");
        String paused = prepareExecutingOrder("PM-FF5", "ff5", "20.000");
        // 连续 3 个超差读数使订单失控暂停。
        for (int i = 0; i < 3; i++) {
            transitionService.submitBlendReading(new BlendReadingRequest(
                    "ff5-reading-" + i, paused, LocalDateTime.of(2026, 9, 25, 10, 0, 0).plusMinutes(i),
                    new BigDecimal("24.000")));
        }
        assertEquals("PAUSED", transitionService.getOrder(paused).status());
        ApiException onPaused = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff5-feed", paused, 1, new BigDecimal("100.000"), List.of(
                        new FeedRequest.FeedItem("f-roll-7", new BigDecimal("20.000"))))));
        assertEquals(409, onPaused.getStatus());

        // 版本已换：调整后当前版本为 2，引用旧版本 1 拒绝。
        String staleVersion = prepareExecutingOrder("PM-FF6", "ff6", "20.000");
        transitionService.adjustBlendRatio(staleVersion,
                new AdjustBlendRatioRequest("ff6-adj", new BigDecimal("22.000")));
        ApiException onStale = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff6-feed", staleVersion, 1, new BigDecimal("100.000"), List.of(
                        new FeedRequest.FeedItem("f-roll-7", new BigDecimal("20.000"))))));
        assertEquals(409, onStale.getStatus());

        // 归档：执行→处置→完成→归档后拒绝。
        String archived = prepareExecutingOrder("PM-FF7", "ff7", "20.000");
        transitionService.dispose(archived, new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem("f-roll-7b", new BigDecimal("100.000")))));
        transitionService.complete(archived);
        transitionService.archive(archived);
        ApiException onArchived = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff7-feed", archived, 1, new BigDecimal("100.000"), List.of(
                        new FeedRequest.FeedItem("f-roll-7", new BigDecimal("20.000"))))));
        assertEquals(409, onArchived.getStatus());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_application", Long.class));
    }

    @Test
    @DisplayName("投料校验：卷号重复409，1至10个卷之外400，未登记卷409")
    void feedValidation() {
        prepareRollWithQuota("f-roll-8", "PM-F8", "f8", "30.000");
        String orderNo = prepareExecutingOrder("PM-FF8", "ff8", "20.000");

        ApiException duplicated = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff8-feed-a", orderNo, 1, new BigDecimal("100.000"), List.of(
                        new FeedRequest.FeedItem("f-roll-8", new BigDecimal("10.000")),
                        new FeedRequest.FeedItem("f-roll-8", new BigDecimal("10.000"))))));
        assertEquals(409, duplicated.getStatus());

        List<FeedRequest.FeedItem> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(new FeedRequest.FeedItem("f-roll-x" + i, new BigDecimal("1.000")));
        }
        ApiException tooMany = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff8-feed-b", orderNo, 1, new BigDecimal("55.000"), eleven)));
        assertEquals(400, tooMany.getStatus());

        ApiException unregistered = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff8-feed-c", orderNo, 1, new BigDecimal("100.000"), List.of(
                        new FeedRequest.FeedItem("f-roll-absent", new BigDecimal("20.000"))))));
        assertEquals(409, unregistered.getStatus());
    }

    @Test
    @DisplayName("投料幂等：同键同参重放不重复扣量，同键异参409")
    void feedIdempotentReplay() {
        prepareRollWithQuota("f-roll-9", "PM-F9", "f9", "30.000");
        String orderNo = prepareExecutingOrder("PM-FF9", "ff9", "20.000");
        FeedRequest request = new FeedRequest("ff9-feed", orderNo, 1, new BigDecimal("100.000"),
                List.of(new FeedRequest.FeedItem("f-roll-9", new BigDecimal("20.000"))));

        IdempotentResult<FeedView> first = recycleService.submitFeed(request);
        IdempotentResult<FeedView> replay = recycleService.submitFeed(request);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.data().feedNo(), replay.data().feedNo());
        assertEquals(0, recycleService.getQuota("f-roll-9").consumedKg()
                .compareTo(new BigDecimal("20.000")), "重放不重复扣量");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_application", Long.class));

        ApiException differentParams = assertThrows(ApiException.class, () -> recycleService.submitFeed(
                new FeedRequest("ff9-feed", orderNo, 1, new BigDecimal("50.000"),
                        List.of(new FeedRequest.FeedItem("f-roll-9", new BigDecimal("10.000"))))));
        assertEquals(409, differentParams.getStatus());
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 登记一张过渡卷并登记回收额度。 */
    private void prepareRollWithQuota(String rollNo, String machine, String prefix, String kg) {
        OrderView applied = transitionService.apply(new ApplyOrderRequest(
                prefix + "-apply", machine, "G-A4-70", "G-A4-77")).data();
        transitionService.decide(applied.orderNo());
        transitionService.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        transitionService.dispose(applied.orderNo(), new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem(rollNo, new BigDecimal("900.000")))));
        recycleService.registerQuota(new RegisterRecycleQuotaRequest(
                prefix + "-reg", rollNo, new BigDecimal(kg)));
    }

    /** 准备一张执行中且携带掺配比例的订单（申请携带比例即自动判定）。 */
    private String prepareExecutingOrder(String machine, String prefix, String ratioPct) {
        OrderView applied = transitionService.apply(new ApplyOrderRequest(
                prefix + "-apply", machine, "G-A4-70", "G-A4-77", new BigDecimal(ratioPct))).data();
        transitionService.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        return applied.orderNo();
    }
}
