package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.CleaningResponse;
import com.example.starter.blend.BlendDtos.CompleteCleaningRequest;
import com.example.starter.blend.BlendDtos.ComponentAmount;
import com.example.starter.blend.BlendDtos.CreateCleaningRequest;
import com.example.starter.blend.BlendDtos.CreateOrderRequest;
import com.example.starter.blend.BlendDtos.OrderResponse;
import com.example.starter.blend.BlendDtos.ReleaseRequest;
import com.example.starter.blend.BlendDtos.SampleRequest;
import com.example.starter.blend.BlendDtos.TankOccupancyView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 品种切换清洗控制测试（真实 H2 库，MySQL 兼容模式）：
 * 跨品种拦截、清洗唯一性、时间窗半开区间冲突、逾期自动释放、原子复位与幂等。
 * 固定业务时间 T = 2026-09-25 18:00:00（Asia/Shanghai）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:blend_test_cleaning;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class CleaningControlTest extends BlendServiceTestBase {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static CreateOrderRequest order(String requestId, String product, long blendTankId, long tankId,
                                            String volume) {
        return new CreateOrderRequest(requestId, product, blendTankId,
                List.of(new ComponentAmount(tankId, new BigDecimal(volume))));
    }

    /** 双组分各25方：指标92/8.5/65，GAS92双采样合格可放行。 */
    private static CreateOrderRequest releasableOrder(String requestId, long blendTankId) {
        return new CreateOrderRequest(requestId, "GAS92", blendTankId,
                List.of(new ComponentAmount(1L, new BigDecimal("25")),
                        new ComponentAmount(2L, new BigDecimal("25"))));
    }

    private static CreateCleaningRequest cleaning(String requestId, long blendTankId, String toProduct,
                                                  String windowStart, String windowEnd) {
        return new CreateCleaningRequest(requestId, blendTankId, toProduct,
                LocalDateTime.parse(windowStart.replace(' ', 'T')),
                LocalDateTime.parse(windowEnd.replace(' ', 'T')));
    }

    private void releaseOrder(long orderId, String requestIdPrefix) {
        service.submitSample(orderId, new SampleRequest(requestIdPrefix + "-S1", 1));
        service.submitSample(orderId, new SampleRequest(requestIdPrefix + "-S2", 2));
        service.release(orderId, new ReleaseRequest(requestIdPrefix + "-REL"));
    }

    @Test
    void crossProductOrderWithoutCleaningRejectedWithTankDetail() {
        OrderResponse created = service.createOrder(releasableOrder("R-CP-1", 1));
        // 罐内品种GAS92，未清洗确认直接下GAS95：422并给出需清洗罐号明细
        BlendException ex = assertThrows(BlendException.class,
                () -> service.createOrder(order("R-CP-2", "GAS95", 1, 3, "50")));
        assertEquals(422, ex.status());
        assertTrue(ex.getMessage().contains("需清洗罐号明细"));
        assertTrue(ex.getMessage().contains("B1"));
        assertTrue(ex.getMessage().contains("GAS92"));
        assertTrue(ex.getMessage().contains("GAS95"));
        // 无状态变化：仅一张调合单，无清洗单，罐状态仍为正常
        assertEquals(1, countRows("blend_order"));
        assertEquals(0, countRows("cleaning_order"));
        // 放行后罐内品种仍是GAS92，跨品种下单依旧被拒绝
        releaseOrder(created.orderId(), "R-CP-1");
        assertEquals(422, assertThrows(BlendException.class,
                () -> service.createOrder(order("R-CP-3", "GAS95", 1, 3, "50"))).status());
        assertEquals(1, countRows("blend_order"));
    }

    @Test
    void sameProductOrderNeedsNoCleaning() {
        OrderResponse first = service.createOrder(releasableOrder("R-SP-1", 1));
        releaseOrder(first.orderId(), "R-SP-1");
        // 同品种直接续用，无需清洗
        OrderResponse second = service.createOrder(order("R-SP-2", "GAS92", 1, 1, "50"));
        assertEquals(BlendService.ORDER_EXECUTING, second.status());
        assertEquals(2, countRows("blend_order"));
        assertEquals(0, countRows("cleaning_order"));
    }

    @Test
    void cleaningConfirmThenCrossProductOrderSucceeds() {
        OrderResponse first = service.createOrder(releasableOrder("R-CC-1", 1));
        releaseOrder(first.orderId(), "R-CC-1");

        CleaningResponse cleaning = cleaningService.createCleaning(
                cleaning("R-CC-2", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        assertEquals(CleaningService.CLEANING_EXECUTING, cleaning.status());
        assertEquals("GAS92", cleaning.fromProduct());
        assertEquals("GAS95", cleaning.toProduct());

        CleaningResponse completed = cleaningService.completeCleaning(cleaning.cleaningId(),
                new CompleteCleaningRequest("R-CC-3"));
        assertEquals(CleaningService.CLEANING_COMPLETED, completed.status());
        assertEquals(TestClockConfig.FIXED_TIME_TEXT, completed.completedAt());

        // 清洗确认后跨品种下单成功，确认被消费（1号罐组分95/5/60满足GAS95上限，可放行）
        OrderResponse switched = service.createOrder(order("R-CC-4", "GAS95", 1, 1, "50"));
        assertEquals(BlendService.ORDER_EXECUTING, switched.status());
        assertEquals("GAS95", jdbc.queryForObject(
                "SELECT current_product FROM blend_tank_state WHERE blend_tank_id = 1", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cleaning_order WHERE status = 'COMPLETED' AND consumed_at IS NULL",
                Integer.class));

        // 确认已消费：再切回GAS92仍需重新清洗
        releaseOrder(switched.orderId(), "R-CC-5");
        assertEquals(422, assertThrows(BlendException.class,
                () -> service.createOrder(order("R-CC-6", "GAS92", 1, 1, "50"))).status());
    }

    @Test
    void cleaningForOtherProductDoesNotConfirm() {
        OrderResponse first = service.createOrder(releasableOrder("R-NP-1", 1));
        releaseOrder(first.orderId(), "R-NP-1");
        CleaningResponse cleaning = cleaningService.createCleaning(
                cleaning("R-NP-2", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        cleaningService.completeCleaning(cleaning.cleaningId(), new CompleteCleaningRequest("R-NP-3"));
        // 清洗确认的是GAS95，下GAS98仍被拒绝
        BlendException ex = assertThrows(BlendException.class,
                () -> service.createOrder(order("R-NP-4", "GAS98", 1, 3, "50")));
        assertEquals(422, ex.status());
        assertTrue(ex.getMessage().contains("GAS98"));
        assertEquals(1, countRows("blend_order"));
    }

    @Test
    void executingCleaningUniquePerTank() {
        cleaningService.createCleaning(
                cleaning("R-UQ-1", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        // 同罐第二张执行中清洗单：409
        BlendException ex = assertThrows(BlendException.class, () -> cleaningService.createCleaning(
                cleaning("R-UQ-2", 1, "GAS98", "2026-09-25 20:00:00", "2026-09-25 21:00:00")));
        assertEquals(409, ex.status());
        assertEquals(1, countRows("cleaning_order"));
        assertEquals(1, countRows("cleaning_lock"));
        // 另一调合罐不受影响
        cleaningService.createCleaning(
                cleaning("R-UQ-3", 2, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        assertEquals(2, countRows("cleaning_order"));
    }

    @Test
    void cleaningWindowConflictsWithOrderOccupancyAndEndpointTouchingOk() {
        // 执行中调合单占用[T, +∞)：清洗窗[T+1h, T+2h)与之重叠 → 409
        OrderResponse executing = service.createOrder(releasableOrder("R-WC-1", 1));
        BlendException conflict = assertThrows(BlendException.class, () -> cleaningService.createCleaning(
                cleaning("R-WC-2", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00")));
        assertEquals(409, conflict.status());
        assertEquals(0, countRows("cleaning_order"));

        // 放行后占用窗为[T, T]：清洗窗起点与放行时间端点相接 → 不算冲突
        releaseOrder(executing.orderId(), "R-WC-1");
        CleaningResponse touching = cleaningService.createCleaning(
                cleaning("R-WC-3", 1, "GAS95", "2026-09-25 18:00:00", "2026-09-25 19:00:00"));
        assertEquals(CleaningService.CLEANING_EXECUTING, touching.status());

        // 另一罐执行中调合单：清洗窗终点与下单时间端点相接 → 不算冲突
        service.createOrder(order("R-WC-4", "GAS92", 2, 1, "10"));
        CleaningResponse beforeStart = cleaningService.createCleaning(
                cleaning("R-WC-5", 2, "GAS95", "2026-09-25 17:00:00", "2026-09-25 18:00:00"));
        assertEquals(CleaningService.CLEANING_EXECUTING, beforeStart.status());
    }

    @Test
    void orderRejectedDuringCleaningWindow() {
        cleaningService.createCleaning(
                cleaning("R-OC-1", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        // 清洗占用窗[19:00, 20:00)覆盖当前时间18:00 → 新单不得下达
        BlendException ex = assertThrows(BlendException.class,
                () -> service.createOrder(order("R-OC-2", "GAS92", 1, 1, "50")));
        assertEquals(409, ex.status());
        assertTrue(ex.getMessage().contains("清洗占用窗"));
        assertEquals(0, countRows("blend_order"));
        assertEquals(0, countRows("blend_tank_lock"));
    }

    @Test
    void overdueCleaningAutoReleasesAndMarksPendingThenReinitiate() {
        CleaningResponse cleaning = cleaningService.createCleaning(
                cleaning("R-EX-1", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        // 推进时钟越过占用窗终点，清洗未完成 → 自动释放并标记待确认
        TestClockConfig.advance(Duration.ofHours(3));
        BlendException ex = assertThrows(BlendException.class,
                () -> service.createOrder(order("R-EX-2", "GAS92", 1, 1, "50")));
        assertEquals(409, ex.status());
        assertTrue(ex.getMessage().contains("待确认"));
        assertEquals(CleaningService.CLEANING_EXPIRED, jdbc.queryForObject(
                "SELECT status FROM cleaning_order WHERE id = " + cleaning.cleaningId(), String.class));
        assertEquals(0, countRows("cleaning_lock"));
        assertEquals(CleaningService.TANK_PENDING_CONFIRMATION, jdbc.queryForObject(
                "SELECT status FROM blend_tank_state WHERE blend_tank_id = 1", String.class));

        // 重新占用须重新发起清洗：新清洗完成确认后罐复位，可正常下单
        CleaningResponse retry = cleaningService.createCleaning(
                cleaning("R-EX-3", 1, "GAS92", "2026-09-25 22:00:00", "2026-09-25 23:00:00"));
        cleaningService.completeCleaning(retry.cleaningId(), new CompleteCleaningRequest("R-EX-4"));
        assertEquals(CleaningService.TANK_NORMAL, jdbc.queryForObject(
                "SELECT status FROM blend_tank_state WHERE blend_tank_id = 1", String.class));
        OrderResponse after = service.createOrder(order("R-EX-5", "GAS92", 1, 1, "50"));
        assertEquals(BlendService.ORDER_EXECUTING, after.status());
    }

    @Test
    void completeExpiredCleaningRejected() {
        CleaningResponse cleaning = cleaningService.createCleaning(
                cleaning("R-CE-1", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        TestClockConfig.advance(Duration.ofHours(3));
        BlendException ex = assertThrows(BlendException.class, () -> cleaningService.completeCleaning(
                cleaning.cleaningId(), new CompleteCleaningRequest("R-CE-2")));
        assertEquals(409, ex.status());
        assertEquals(CleaningService.CLEANING_EXPIRED, jdbc.queryForObject(
                "SELECT status FROM cleaning_order WHERE id = " + cleaning.cleaningId(), String.class));
        assertEquals(CleaningService.TANK_PENDING_CONFIRMATION, jdbc.queryForObject(
                "SELECT status FROM blend_tank_state WHERE blend_tank_id = 1", String.class));
    }

    @Test
    void cleaningIdempotencyReplayAndConflict() {
        CleaningResponse first = cleaningService.createCleaning(
                cleaning("R-IM-1", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        // 同键同参重放首次结果
        CleaningResponse replay = cleaningService.createCleaning(
                cleaning("R-IM-1", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        assertEquals(first, replay);
        assertEquals(1, countRows("cleaning_order"));
        assertEquals(1, countRows("cleaning_lock"));
        // 同键异参：409且无状态变化
        BlendException conflict = assertThrows(BlendException.class, () -> cleaningService.createCleaning(
                cleaning("R-IM-1", 1, "GAS95", "2026-09-25 20:00:00", "2026-09-25 21:00:00")));
        assertEquals(409, conflict.status());
        assertEquals(1, countRows("cleaning_order"));
        assertEquals(1, countRows("cleaning_lock"));

        // 完成确认同样幂等：同键重放首次结果，异参拒绝
        CleaningResponse completed = cleaningService.completeCleaning(first.cleaningId(),
                new CompleteCleaningRequest("R-IM-2"));
        CleaningResponse replayComplete = cleaningService.completeCleaning(first.cleaningId(),
                new CompleteCleaningRequest("R-IM-2"));
        assertEquals(completed, replayComplete);
        assertEquals(409, assertThrows(BlendException.class, () -> cleaningService.completeCleaning(
                first.cleaningId(), new CompleteCleaningRequest("R-IM-3"))).status());
        assertEquals(1, countRows("cleaning_order"));
        assertEquals(0, countRows("cleaning_lock"));
    }

    @Test
    void completeRollbackRestoresNoPartialState() {
        CleaningResponse cleaning = cleaningService.createCleaning(
                cleaning("R-TX-1", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));
        // 完成确认与罐状态复位在同一事务内：事务回滚后无任何部分状态
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            cleaningService.doCompleteCleaning(cleaning.cleaningId(),
                    new CompleteCleaningRequest("R-TX-2"));
            throw new IllegalStateException("模拟确认后失败");
        }));
        assertEquals(CleaningService.CLEANING_EXECUTING, jdbc.queryForObject(
                "SELECT status FROM cleaning_order WHERE id = " + cleaning.cleaningId(), String.class));
        assertEquals(1, countRows("cleaning_lock"));
        assertEquals(CleaningService.TANK_CLEANING, jdbc.queryForObject(
                "SELECT status FROM blend_tank_state WHERE blend_tank_id = 1", String.class));
        assertNull(jdbc.queryForObject(
                "SELECT clean_for_product FROM blend_tank_state WHERE blend_tank_id = 1", String.class));

        // 正常完成：清洗单、锁、罐状态一并复位
        cleaningService.completeCleaning(cleaning.cleaningId(), new CompleteCleaningRequest("R-TX-3"));
        assertEquals(CleaningService.CLEANING_COMPLETED, jdbc.queryForObject(
                "SELECT status FROM cleaning_order WHERE id = " + cleaning.cleaningId(), String.class));
        assertEquals(0, countRows("cleaning_lock"));
        assertEquals(CleaningService.TANK_NORMAL, jdbc.queryForObject(
                "SELECT status FROM blend_tank_state WHERE blend_tank_id = 1", String.class));
        assertEquals("GAS95", jdbc.queryForObject(
                "SELECT clean_for_product FROM blend_tank_state WHERE blend_tank_id = 1", String.class));
    }

    @Test
    void occupancyViewShowsOrderAndCleaningAndAutoExpiry() {
        OrderResponse order = service.createOrder(order("R-VW-1", "GAS92", 1, 1, "50"));
        cleaningService.createCleaning(
                cleaning("R-VW-2", 2, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00"));

        List<TankOccupancyView> views = cleaningService.occupancyView();
        assertEquals(2, views.size());
        TankOccupancyView tank1 = views.get(0);
        assertEquals("B1", tank1.code());
        assertEquals(CleaningService.TANK_NORMAL, tank1.tankStatus());
        assertEquals("GAS92", tank1.currentProduct());
        assertEquals(order.orderId(), tank1.orderOccupancy().orderId());
        assertEquals(TestClockConfig.FIXED_TIME_TEXT, tank1.orderOccupancy().occupiedFrom());
        assertNull(tank1.orderOccupancy().occupiedTo());
        assertNull(tank1.cleaningOccupancy());
        TankOccupancyView tank2 = views.get(1);
        assertEquals("B2", tank2.code());
        assertEquals(CleaningService.TANK_CLEANING, tank2.tankStatus());
        assertNull(tank2.orderOccupancy());
        assertEquals("2026-09-25 19:00:00", tank2.cleaningOccupancy().windowStart());
        assertEquals("2026-09-25 20:00:00", tank2.cleaningOccupancy().windowEnd());

        // 清洗逾期后占用视图自动释放占用窗并标记待确认
        TestClockConfig.advance(Duration.ofHours(3));
        TankOccupancyView expired = cleaningService.occupancyView().get(1);
        assertEquals(CleaningService.TANK_PENDING_CONFIRMATION, expired.tankStatus());
        assertNull(expired.cleaningOccupancy());
        assertEquals(CleaningService.CLEANING_EXPIRED, jdbc.queryForObject(
                "SELECT status FROM cleaning_order WHERE blend_tank_id = 2", String.class));
    }
}
