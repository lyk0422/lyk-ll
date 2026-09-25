package com.example.starter.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.transfer.TransferDtos.ItemRequest;
import com.example.starter.transfer.TransferDtos.OrderDetailResponse;
import com.example.starter.transfer.TransferDtos.OrderResponse;
import com.example.starter.transfer.TransferDtos.ReleaseRequest;
import com.example.starter.transfer.TransferDtos.ReleaseResponse;
import com.example.starter.transfer.TransferDtos.VerifyItemRequest;
import com.example.starter.transfer.TransferDtos.VerifyRequest;
import com.example.starter.transfer.TransferDtos.VerifyResponse;

/**
 * 转关主流程业务测试：申请幂等、核注扣减原子性、48 小时边界、分批核销、
 * 核销不超余量、撤销、结案终态、异常解除与状态留痕。
 */
class TransferServiceTest extends BaseTransferTest {

    // ---------- 申请与幂等 ----------

    @Test
    void applyCreatesOrderAndReplaysSameResultForSameKey() {
        OrderResponse first = service.apply(applyRequest("REQ-1", "ENT-1", 10));
        assertEquals("APPLIED", first.status());
        assertEquals("REQ-1", first.requestId());
        assertEquals(1, first.items().size());
        assertEquals(10, first.items().get(0).quantity());

        OrderResponse replay = service.apply(applyRequest("REQ-1", "ENT-1", 10));
        assertEquals(first.orderId(), replay.orderId());
        assertEquals(first, replay);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM transfer_order", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void applyWithSameKeyButDifferentParamsReturns409() {
        service.apply(applyRequest("REQ-2", "ENT-1", 10));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.apply(applyRequest("REQ-2", "ENT-1", 20)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // 异参冲突不改变已落库单据
        assertEquals(10, jdbc.queryForObject(
                "SELECT quantity FROM transfer_order_item i JOIN transfer_order o ON i.order_id = o.id WHERE o.request_id = 'REQ-2'",
                Long.class));
        // 商品项顺序不同但业务字段相同，指纹一致，应返回首次结果
        var reordered = new com.example.starter.transfer.TransferDtos.ApplyRequest("REQ-3", "ENT-1",
                "WH-A", "WH-B", List.of(
                        new ItemRequest(TARIFF_B, 2, new BigDecimal("99.00")),
                        new ItemRequest(TARIFF_A, 5, new BigDecimal("12.50"))));
        OrderResponse first = service.apply(reordered);
        var sameFieldsDifferentOrder = new com.example.starter.transfer.TransferDtos.ApplyRequest(
                "REQ-3", "ENT-1", "WH-A", "WH-B", List.of(
                        new ItemRequest(TARIFF_A, 5, new BigDecimal("12.50")),
                        new ItemRequest(TARIFF_B, 2, new BigDecimal("99.00"))));
        assertEquals(first, service.apply(sameFieldsDifferentOrder));
    }

    // ---------- 核注与库存原子性 ----------

    @Test
    void endorseDeductsStockAtomicallyAndSetsDeadline() {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse order = service.apply(applyRequest("REQ-10", "ENT-1", 40));

        var result = service.endorse(order.orderId());
        assertEquals("IN_TRANSIT", result.status());
        assertEquals(60, stockOf("WH-A", TARIFF_A));

        OrderResponse after = service.getDetail(order.orderId()).order();
        assertEquals(MutableClockConfig.T0, after.endorsedAt());
        assertEquals(MutableClockConfig.T0.plus(Duration.ofHours(48)), after.verifyDeadline());
    }

    @Test
    void endorseWithInsufficientStockRollsBackCompletely() {
        seedStock("WH-A", TARIFF_A, 30);
        seedStock("WH-A", TARIFF_B, 100);
        OrderResponse order = service.apply(twoItemApplyRequest("REQ-11", "ENT-1", 40, 10));

        ApiException ex = assertThrows(ApiException.class, () -> service.endorse(order.orderId()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // 同事务回滚：任一商品项不足则全部不扣减，状态保持已申请
        assertEquals("APPLIED", statusOf(order.orderId()));
        assertEquals(30, stockOf("WH-A", TARIFF_A));
        assertEquals(100, stockOf("WH-A", TARIFF_B));
    }

    // ---------- 48 小时边界与异常冻结 ----------

    @Test
    void exactly48HoursWithoutVerifyBecomesExceptionAndFreezesEnterprise() {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse expiredOrder = service.apply(applyRequest("REQ-20", "ENT-9", 10));
        OrderResponse otherOrder = service.apply(applyRequest("REQ-21", "ENT-9", 5));
        service.endorse(expiredOrder.orderId());
        service.endorse(otherOrder.orderId());

        // 47 小时 59 分 59 秒：未到期，扫描不产生异常
        mutableClock.set(MutableClockConfig.T0.plus(Duration.ofHours(48)).minusSeconds(1));
        assertTrue(service.scanExpired().isEmpty());
        assertEquals("IN_TRANSIT", statusOf(expiredOrder.orderId()));

        // 恰满 48 小时：自动转异常，企业其余在途单冻结（同次扫描内冻结，不计入异常列表）
        mutableClock.set(MutableClockConfig.T0.plus(Duration.ofHours(48)));
        assertEquals(List.of(expiredOrder.orderId()), service.scanExpired());
        assertEquals("EXCEPTION", statusOf(expiredOrder.orderId()));
        assertEquals("FROZEN", statusOf(otherOrder.orderId()));
        assertTrue(isFrozen("ENT-9"));

        // 冻结期间：新申请拒绝、在途单核销拒绝
        ApiException applyEx = assertThrows(ApiException.class,
                () -> service.apply(applyRequest("REQ-22", "ENT-9", 1)));
        assertEquals(HttpStatus.CONFLICT, applyEx.getStatus());
        ApiException verifyEx = assertThrows(ApiException.class, () -> service.verify(
                otherOrder.orderId(), new VerifyRequest("V-20", List.of(new VerifyItemRequest(TARIFF_A, 1)))));
        assertEquals(HttpStatus.CONFLICT, verifyEx.getStatus());
    }

    @Test
    void verifyAfterDeadlineIsRejectedAndScannerMarksException() {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse order = service.apply(applyRequest("REQ-23", "ENT-8", 10));
        service.endorse(order.orderId());

        mutableClock.set(MutableClockConfig.T0.plus(Duration.ofHours(48)));
        ApiException ex = assertThrows(ApiException.class, () -> service.verify(order.orderId(),
                new VerifyRequest("V-23", List.of(new VerifyItemRequest(TARIFF_A, 1)))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(0, verifiedOf(order.orderId(), TARIFF_A));

        assertEquals(List.of(order.orderId()), service.scanExpired());
        assertEquals("EXCEPTION", statusOf(order.orderId()));
    }

    // ---------- 分批部分核销 ----------

    @Test
    void partialVerifyAccumulatesAndClosesWhenFullyVerified() {
        seedStock("WH-A", TARIFF_A, 100);
        seedStock("WH-A", TARIFF_B, 100);
        OrderResponse order = service.apply(twoItemApplyRequest("REQ-30", "ENT-1", 10, 4));
        service.endorse(order.orderId());

        VerifyResponse first = service.verify(order.orderId(),
                new VerifyRequest("V-30a", List.of(new VerifyItemRequest(TARIFF_A, 6))));
        assertFalse(first.closed());
        assertEquals("IN_TRANSIT", first.status());
        assertEquals(6, verifiedOf(order.orderId(), TARIFF_A));

        VerifyResponse second = service.verify(order.orderId(), new VerifyRequest("V-30b",
                List.of(new VerifyItemRequest(TARIFF_A, 4), new VerifyItemRequest(TARIFF_B, 4))));
        assertTrue(second.closed());
        assertEquals("CLOSED", second.status());
        assertEquals("CLOSED", statusOf(order.orderId()));
        assertEquals(10, verifiedOf(order.orderId(), TARIFF_A));
        assertEquals(4, verifiedOf(order.orderId(), TARIFF_B));
    }

    @Test
    void verifyExceedingRemainingRejectsWholeBatch() {
        seedStock("WH-A", TARIFF_A, 100);
        seedStock("WH-A", TARIFF_B, 100);
        OrderResponse order = service.apply(twoItemApplyRequest("REQ-31", "ENT-1", 10, 4));
        service.endorse(order.orderId());
        service.verify(order.orderId(),
                new VerifyRequest("V-31a", List.of(new VerifyItemRequest(TARIFF_A, 6))));

        // 同批中 TARIFF_B 超量（余量 4 请求 5），整批拒绝：TARIFF_A 的 4 也不生效
        ApiException ex = assertThrows(ApiException.class, () -> service.verify(order.orderId(),
                new VerifyRequest("V-31b", List.of(new VerifyItemRequest(TARIFF_A, 4),
                        new VerifyItemRequest(TARIFF_B, 5)))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(6, verifiedOf(order.orderId(), TARIFF_A));
        assertEquals(0, verifiedOf(order.orderId(), TARIFF_B));
        assertEquals("IN_TRANSIT", statusOf(order.orderId()));
    }

    // ---------- 核销幂等 ----------

    @Test
    void verifyReplaysSameResultAndRejectsDifferentParams() {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse order = service.apply(applyRequest("REQ-40", "ENT-1", 10));
        service.endorse(order.orderId());

        VerifyResponse first = service.verify(order.orderId(),
                new VerifyRequest("V-40", List.of(new VerifyItemRequest(TARIFF_A, 6))));
        VerifyResponse replay = service.verify(order.orderId(),
                new VerifyRequest("V-40", List.of(new VerifyItemRequest(TARIFF_A, 6))));
        assertEquals(first, replay);
        // 重放不重复扣减余量
        assertEquals(6, verifiedOf(order.orderId(), TARIFF_A));

        ApiException ex = assertThrows(ApiException.class, () -> service.verify(order.orderId(),
                new VerifyRequest("V-40", List.of(new VerifyItemRequest(TARIFF_A, 7)))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(6, verifiedOf(order.orderId(), TARIFF_A));
    }

    // ---------- 结案终态 ----------

    @Test
    void closedOrderIsTerminalAndReplaysFirstResult() {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse order = service.apply(applyRequest("REQ-50", "ENT-1", 10));
        service.endorse(order.orderId());
        VerifyResponse partial = service.verify(order.orderId(),
                new VerifyRequest("V-50a", List.of(new VerifyItemRequest(TARIFF_A, 6))));
        VerifyResponse closing = service.verify(order.orderId(),
                new VerifyRequest("V-50b", List.of(new VerifyItemRequest(TARIFF_A, 4))));
        assertTrue(closing.closed());

        // 结案后新核销请求拒绝，数量不再变动
        ApiException ex = assertThrows(ApiException.class, () -> service.verify(order.orderId(),
                new VerifyRequest("V-50c", List.of(new VerifyItemRequest(TARIFF_A, 1)))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(10, verifiedOf(order.orderId(), TARIFF_A));

        // 重复历史核销请求返回首次结果快照
        assertEquals(partial, service.verify(order.orderId(),
                new VerifyRequest("V-50a", List.of(new VerifyItemRequest(TARIFF_A, 6)))));
        assertEquals(closing, service.verify(order.orderId(),
                new VerifyRequest("V-50b", List.of(new VerifyItemRequest(TARIFF_A, 4)))));
        assertEquals(10, verifiedOf(order.orderId(), TARIFF_A));
    }

    // ---------- 撤销 ----------

    @Test
    void cancelBeforeTransitRestoresAndLocksFinalState() {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse order = service.apply(applyRequest("REQ-60", "ENT-1", 40));
        var cancelled = service.cancel(order.orderId());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(100, stockOf("WH-A", TARIFF_A));

        // 撤销为终态：再撤销、再核注均拒绝
        assertThrows(ApiException.class, () -> service.cancel(order.orderId()));
        ApiException ex = assertThrows(ApiException.class, () -> service.endorse(order.orderId()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(100, stockOf("WH-A", TARIFF_A));
    }

    @Test
    void cancelAfterEndorseIsRejected() {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse order = service.apply(applyRequest("REQ-61", "ENT-1", 40));
        service.endorse(order.orderId());
        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(order.orderId()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("IN_TRANSIT", statusOf(order.orderId()));
        assertEquals(60, stockOf("WH-A", TARIFF_A));
    }

    // ---------- 异常解除 ----------

    @Test
    void customsReviewReleaseRestoresOrdersAndResetsDeadline() {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse expired = service.apply(applyRequest("REQ-70", "ENT-7", 10));
        OrderResponse frozen = service.apply(applyRequest("REQ-71", "ENT-7", 5));
        service.endorse(expired.orderId());
        service.endorse(frozen.orderId());
        mutableClock.set(MutableClockConfig.T0.plus(Duration.ofHours(48)));
        service.scanExpired();
        assertEquals("EXCEPTION", statusOf(expired.orderId()));
        assertEquals("FROZEN", statusOf(frozen.orderId()));

        // 海关复核解除：企业解冻，两单恢复在途，异常单时限重置为解除后 48 小时
        ReleaseResponse release = service.releaseEnterprise("ENT-7",
                new ReleaseRequest("CUSTOMS-01", "复核通过"));
        assertFalse(release.frozen());
        assertEquals(List.of(expired.orderId(), frozen.orderId()),
                release.restoredOrderIds().stream().sorted().toList());
        assertFalse(isFrozen("ENT-7"));
        assertEquals("IN_TRANSIT", statusOf(expired.orderId()));
        assertEquals("IN_TRANSIT", statusOf(frozen.orderId()));
        assertEquals(MutableClockConfig.T0.plus(Duration.ofHours(96)),
                service.getDetail(expired.orderId()).order().verifyDeadline());

        // 解除后核销恢复正常
        VerifyResponse verify = service.verify(expired.orderId(),
                new VerifyRequest("V-70", List.of(new VerifyItemRequest(TARIFF_A, 10))));
        assertTrue(verify.closed());

        // 无异常时重复解除拒绝
        assertThrows(ApiException.class, () -> service.releaseEnterprise("ENT-7",
                new ReleaseRequest("CUSTOMS-01", null)));
    }

    // ---------- 状态留痕 ----------

    @Test
    void statusChangesAreTracedInOrder() {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse order = service.apply(applyRequest("REQ-80", "ENT-1", 10));
        service.endorse(order.orderId());
        service.verify(order.orderId(),
                new VerifyRequest("V-80", List.of(new VerifyItemRequest(TARIFF_A, 10))));

        OrderDetailResponse detail = service.getDetail(order.orderId());
        assertEquals("CLOSED", detail.order().status());
        assertEquals(3, detail.history().size());
        assertEquals(List.of(0, 1, 2),
                detail.history().stream().map(h -> h.seq()).toList());
        assertEquals(List.of("APPLIED", "IN_TRANSIT", "CLOSED"),
                detail.history().stream().map(h -> h.toStatus()).toList());
        assertEquals(null, detail.history().get(0).fromStatus());
        assertEquals("APPLIED", detail.history().get(1).fromStatus());
        assertEquals("IN_TRANSIT", detail.history().get(2).fromStatus());
    }
}
