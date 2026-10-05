package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.CleaningResponse;
import com.example.starter.blend.BlendDtos.CompleteCleaningRequest;
import com.example.starter.blend.BlendDtos.CompleteCleaningResponse;
import com.example.starter.blend.BlendDtos.ComponentAmount;
import com.example.starter.blend.BlendDtos.CreateCleaningRequest;
import com.example.starter.blend.BlendDtos.CreateOrderRequest;
import com.example.starter.blend.BlendDtos.OrderResponse;
import com.example.starter.blend.BlendDtos.ReleaseRequest;
import com.example.starter.blend.BlendDtos.SampleRequest;
import com.example.starter.blend.BlendDtos.TankOccupancyResponse;
import com.example.starter.blend.BlendDtos.TankOccupancyView;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 品种切换清洗控制测试（真实 H2 库，MySQL 兼容模式）。
 * 固定业务时间为 2026-09-25 18:00:00 Asia/Shanghai。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:blend_test_cleaning;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class BlendCleaningTest extends BlendServiceTestBase {

    private static final String NOW = "2026-09-25 18:00:00";

    private static CreateOrderRequest order(String requestId, String product, long blendTankId,
                                            ComponentAmount... components) {
        return new CreateOrderRequest(requestId, product, blendTankId, List.of(components));
    }

    private static ComponentAmount comp(long tankId, String volume) {
        return new ComponentAmount(tankId, new BigDecimal(volume));
    }

    private static CreateCleaningRequest cleaning(String requestId, long blendTankId, String toProduct,
                                                  String windowStart, String windowEnd) {
        return new CreateCleaningRequest(requestId, blendTankId, toProduct, windowStart, windowEnd);
    }

    /** 在指定罐上下达 GAS92 并走完双采样放行，使罐内品种登记为 GAS92。 */
    private long releaseGas92OnTank(long blendTankId, String requestPrefix) {
        OrderResponse created = service.createOrder(
                order(requestPrefix + "-O", "GAS92", blendTankId, comp(1, "50"), comp(2, "50")));
        service.submitSample(created.orderId(), new SampleRequest(requestPrefix + "-S1", 1));
        service.submitSample(created.orderId(), new SampleRequest(requestPrefix + "-S2", 2));
        service.release(created.orderId(), new ReleaseRequest(requestPrefix + "-R"));
        return created.orderId();
    }

    private TankOccupancyView tankView(TankOccupancyResponse view, long blendTankId) {
        return view.tanks().stream().filter(t -> t.blendTankId() == blendTankId).findFirst().orElseThrow();
    }

    @Test
    void crossProductOrderWithoutCleaningRejectedWithTankDetail() {
        releaseGas92OnTank(1, "R-XP-1");
        // 未清洗确认的跨品种下单直接拒绝，并给出需清洗的罐号明细
        BlendException ex = assertThrows(BlendException.class,
                () -> service.createOrder(order("R-XP-2", "GAS95", 1, comp(1, "10"))));
        assertEquals(422, ex.status());
        assertTrue(ex.getMessage().contains("需清洗罐号：B1"));
        assertTrue(ex.getMessage().contains("GAS92"));
        // 拒绝后无状态变化：不新增调合单、不占锁、不扣库存
        assertEquals(1, countRows("blend_order"));
        assertEquals(0, countRows("blend_tank_lock"));
        assertEquals(new BigDecimal("950.000"), remaining(1));
        // 空置罐不受品种切换限制
        service.createOrder(order("R-XP-3", "GAS95", 2, comp(1, "10")));
    }

    @Test
    void sameProductContinuesWithoutCleaning() {
        releaseGas92OnTank(1, "R-SP-1");
        // 同品种可直接接着用，无需清洗确认
        OrderResponse again = service.createOrder(order("R-SP-2", "GAS92", 1, comp(1, "10")));
        assertEquals(BlendService.ORDER_EXECUTING, again.status());
    }

    @Test
    void cleaningConfirmResetsTankAtomicallyAndAllowsCrossProduct() {
        releaseGas92OnTank(1, "R-CL-1");
        // 占用窗起点与放行时间端点相接，不算冲突
        CleaningResponse cleaning = service.createCleaning(
                cleaning("R-CL-2", 1, "GAS95", NOW, "2026-09-25 20:00:00"));
        assertEquals(BlendService.CLEAN_EXECUTING, cleaning.status());
        assertEquals("GAS92", cleaning.fromProduct());
        assertEquals("GAS95", cleaning.toProduct());

        // 清洗占用窗未结束：新调合单不得下达，库存无变动
        BlendException busy = assertThrows(BlendException.class,
                () -> service.createOrder(order("R-CL-3", "GAS95", 1, comp(1, "10"))));
        assertEquals(409, busy.status());
        assertEquals(new BigDecimal("950.000"), remaining(1));

        // 罐占用视图：执行中清洗占用窗可见
        TankOccupancyView executing = tankView(service.occupancyView(), 1);
        assertEquals(cleaning.cleaningId(), executing.executingCleaningId());
        assertEquals(NOW, executing.cleaningWindowStart());
        assertEquals("2026-09-25 20:00:00", executing.cleaningWindowEnd());
        assertEquals("GAS92", executing.currentProduct());

        // 清洗完成确认与罐状态复位在同一事务内完成
        CompleteCleaningResponse completed = service.completeCleaning(cleaning.cleaningId(),
                new CompleteCleaningRequest("R-CL-4"));
        assertEquals(BlendService.CLEAN_COMPLETED, completed.status());
        assertEquals(BlendService.TANK_IDLE, completed.tankState());
        assertEquals(TestClockConfig.FIXED_TIME_TEXT, completed.completedAt());
        // 事务结果一致：清洗单终态、罐级锁释放、罐状态复位空置
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM cleaning_order WHERE id = ?", String.class, cleaning.cleaningId()));
        assertEquals(0, countRows("cleaning_tank_lock"));
        TankOccupancyView reset = tankView(service.occupancyView(), 1);
        assertEquals(BlendService.TANK_IDLE, reset.state());
        assertNull(reset.currentProduct());
        assertNull(reset.executingCleaningId());

        // 复位后跨品种下单放行
        service.createOrder(order("R-CL-5", "GAS95", 1, comp(1, "10")));
    }

    @Test
    void cleaningWindowConflictsWithBlendOrderOccupancy() {
        // 执行中调合单占用窗无限延伸：任意清洗窗均冲突
        OrderResponse created = service.createOrder(
                order("R-CF-1", "GAS92", 1, comp(1, "50"), comp(2, "50")));
        BlendException executing = assertThrows(BlendException.class, () -> service.createCleaning(
                cleaning("R-CF-2", 1, "GAS95", "2026-09-25 19:00:00", "2026-09-25 20:00:00")));
        assertEquals(409, executing.status());
        assertEquals(0, countRows("cleaning_order"));

        // 放行后占用窗为[18:00, 18:00)：相交的清洗窗冲突
        service.submitSample(created.orderId(), new SampleRequest("R-CF-3", 1));
        service.submitSample(created.orderId(), new SampleRequest("R-CF-4", 2));
        service.release(created.orderId(), new ReleaseRequest("R-CF-5"));
        BlendException overlap = assertThrows(BlendException.class, () -> service.createCleaning(
                cleaning("R-CF-6", 1, "GAS95", "2026-09-25 17:00:00", "2026-09-25 19:00:00")));
        assertEquals(409, overlap.status());
        // 端点相接不算冲突：清洗窗起点等于放行时间
        service.createCleaning(cleaning("R-CF-7", 1, "GAS95", NOW, "2026-09-25 20:00:00"));
        assertEquals(1, countRows("cleaning_order"));
    }

    @Test
    void oneExecutingCleaningPerTank() {
        service.createCleaning(cleaning("R-UQ-CL-1", 1, "GAS95", NOW, "2026-09-25 20:00:00"));
        // 同罐同时刻只允许一张执行中清洗单
        BlendException ex = assertThrows(BlendException.class, () -> service.createCleaning(
                cleaning("R-UQ-CL-2", 1, "GAS95", NOW, "2026-09-25 21:00:00")));
        assertEquals(409, ex.status());
        assertEquals(1, countRows("cleaning_order"));
        assertEquals(1, countRows("cleaning_tank_lock"));
        // 另一调合罐不受影响
        service.createCleaning(cleaning("R-UQ-CL-3", 2, "GAS95", NOW, "2026-09-25 20:00:00"));
    }

    @Test
    void overdueCleaningAutoReleasesAndMarksPendingConfirm() {
        // 占用窗终点与当前时间端点相接（半开区间终点不含），登记后即逾期
        CleaningResponse cleaning = service.createCleaning(
                cleaning("R-OD-1", 1, "GAS95", "2026-09-25 16:00:00", NOW));
        assertEquals(BlendService.CLEAN_EXECUTING, cleaning.status());

        // 下次占用时自动释放占用窗并把罐标记为待确认
        BlendException ex = assertThrows(BlendException.class,
                () -> service.createOrder(order("R-OD-2", "GAS95", 1, comp(1, "10"))));
        assertEquals(422, ex.status());
        assertTrue(ex.getMessage().contains("待确认"));
        assertTrue(ex.getMessage().contains("B1"));
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM cleaning_order WHERE id = ?", String.class, cleaning.cleaningId()));
        assertEquals(0, countRows("cleaning_tank_lock"));
        TankOccupancyView view = tankView(service.occupancyView(), 1);
        assertEquals(BlendService.TANK_PENDING_CONFIRM, view.state());
        assertNull(view.executingCleaningId());

        // 逾期的清洗单不能再完成确认
        BlendException complete = assertThrows(BlendException.class, () -> service.completeCleaning(
                cleaning.cleaningId(), new CompleteCleaningRequest("R-OD-3")));
        assertEquals(409, complete.status());

        // 重新占用须重新发起清洗：新清洗单确认后罐复位，方可下单
        CleaningResponse again = service.createCleaning(
                cleaning("R-OD-4", 1, "GAS95", NOW, "2026-09-25 20:00:00"));
        service.completeCleaning(again.cleaningId(), new CompleteCleaningRequest("R-OD-5"));
        assertEquals(BlendService.TANK_IDLE, tankView(service.occupancyView(), 1).state());
        service.createOrder(order("R-OD-6", "GAS95", 1, comp(1, "10")));
    }

    @Test
    void cleaningIdempotencyReplayAndDifferentParamsConflict() {
        CleaningResponse first = service.createCleaning(
                cleaning("R-CL-IDEM-1", 1, "GAS95", NOW, "2026-09-25 20:00:00"));
        // 同键同参重放首次结果，不新增清洗单
        CleaningResponse replay = service.createCleaning(
                cleaning("R-CL-IDEM-1", 1, "GAS95", NOW, "2026-09-25 20:00:00"));
        assertEquals(first, replay);
        assertEquals(1, countRows("cleaning_order"));
        // 同键异参：409 且无状态变化
        BlendException conflict = assertThrows(BlendException.class, () -> service.createCleaning(
                cleaning("R-CL-IDEM-1", 1, "GAS95", NOW, "2026-09-25 21:00:00")));
        assertEquals(409, conflict.status());
        assertEquals(1, countRows("cleaning_order"));
        assertEquals(1, countRows("cleaning_tank_lock"));

        // 完成确认同键同参重放首次结果
        CompleteCleaningResponse done = service.completeCleaning(first.cleaningId(),
                new CompleteCleaningRequest("R-CL-IDEM-2"));
        CompleteCleaningResponse doneReplay = service.completeCleaning(first.cleaningId(),
                new CompleteCleaningRequest("R-CL-IDEM-2"));
        assertEquals(done, doneReplay);
        // 完成确认同键异参（对不同清洗单复用同一requestId）：409 且无状态变化
        CleaningResponse second = service.createCleaning(
                cleaning("R-CL-IDEM-3", 2, "GAS95", NOW, "2026-09-25 20:00:00"));
        BlendException completeConflict = assertThrows(BlendException.class, () -> service.completeCleaning(
                second.cleaningId(), new CompleteCleaningRequest("R-CL-IDEM-2")));
        assertEquals(409, completeConflict.status());
        assertEquals("EXECUTING", jdbc.queryForObject(
                "SELECT status FROM cleaning_order WHERE id = ?", String.class, second.cleaningId()));
        assertEquals(1, countRows("cleaning_tank_lock"));
    }

    @Test
    void invalidCleaningWindowRejected() {
        // 终点不晚于起点：400
        BlendException ex = assertThrows(BlendException.class, () -> service.createCleaning(
                cleaning("R-BAD-1", 1, "GAS95", "2026-09-25 20:00:00", "2026-09-25 20:00:00")));
        assertEquals(400, ex.status());
        // 时间格式非法：400
        BlendException format = assertThrows(BlendException.class, () -> service.createCleaning(
                cleaning("R-BAD-2", 1, "GAS95", "2026/09/25", "2026-09-25 20:00:00")));
        assertEquals(400, format.status());
        // 调合罐不存在：404
        BlendException notFound = assertThrows(BlendException.class, () -> service.createCleaning(
                cleaning("R-BAD-3", 99, "GAS95", NOW, "2026-09-25 20:00:00")));
        assertEquals(404, notFound.status());
        assertEquals(0, countRows("cleaning_order"));
    }
}
