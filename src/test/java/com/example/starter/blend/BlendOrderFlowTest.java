package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.ComponentAmount;
import com.example.starter.blend.BlendDtos.CreateOrderRequest;
import com.example.starter.blend.BlendDtos.OrderDetail;
import com.example.starter.blend.BlendDtos.OrderResponse;
import com.example.starter.blend.BlendDtos.ReblendRequest;
import com.example.starter.blend.BlendDtos.ReblendResponse;
import com.example.starter.blend.BlendDtos.ReleaseRequest;
import com.example.starter.blend.BlendDtos.ReleaseResponse;
import com.example.starter.blend.BlendDtos.SampleRequest;
import com.example.starter.blend.BlendDtos.SampleResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调合主流程与失败分支测试（真实 H2 库，MySQL 兼容模式）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:blend_test_flow;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class BlendOrderFlowTest extends BlendServiceTestBase {

    private static CreateOrderRequest order(String requestId, long blendTankId, ComponentAmount... components) {
        return new CreateOrderRequest(requestId, "GAS92", blendTankId, List.of(components));
    }

    private static ComponentAmount comp(long tankId, String volume) {
        return new ComponentAmount(tankId, new BigDecimal(volume));
    }

    @Test
    void doubleSamplePassThenReleaseWithExactLimitAndEdgeHint() {
        // 50/50 混合：辛烷值恰等上限92.000，蒸气压恰等上限65.000，硫含量8.500触发卡边提示
        OrderResponse created = service.createOrder(order("R-FLOW-1", 1, comp(1, "50"), comp(2, "50")));
        assertEquals(BlendService.ORDER_EXECUTING, created.status());
        assertEquals(new BigDecimal("92.000"), created.metrics().octane());
        assertEquals(new BigDecimal("65.000"), created.metrics().vaporPressure());
        assertEquals(1, created.marginHints().size());
        assertTrue(created.marginHints().get(0).contains("硫含量"));
        assertEquals(new BigDecimal("950.000"), remaining(1));
        assertEquals(new BigDecimal("950.000"), remaining(2));

        SampleResponse first = service.submitSample(created.orderId(), new SampleRequest("R-FLOW-2", 1));
        assertTrue(first.qualified());
        assertFalse(first.late());

        SampleResponse second = service.submitSample(created.orderId(), new SampleRequest("R-FLOW-3", 2));
        assertTrue(second.qualified());

        ReleaseResponse released = service.release(created.orderId(), new ReleaseRequest("R-FLOW-4"));
        assertEquals(BlendService.ORDER_RELEASED, released.status());
        assertEquals(TestClockConfig.FIXED_TIME_TEXT, released.releasedAt());

        OrderDetail detail = service.getOrder(created.orderId());
        assertEquals(BlendService.ORDER_RELEASED, detail.status());
        assertEquals(1, detail.batches().size());
        assertEquals(2, detail.batches().get(0).samples().size());
        // 重复查询结果稳定
        OrderDetail again = service.getOrder(created.orderId());
        assertEquals(detail, again);
    }

    @Test
    void insufficientComponentRejectsWholeOrderWithoutInventoryChange() {
        // 3号罐余量500，申请600：整单拒绝，所有罐余量不变，不落任何调合单/批次/锁
        BlendException ex = assertThrows(BlendException.class, () -> service.createOrder(
                order("R-REJ-1", 1, comp(1, "600"), comp(3, "600"))));
        assertEquals(422, ex.status());
        assertEquals(new BigDecimal("1000.000"), remaining(1));
        assertEquals(new BigDecimal("500.000"), remaining(3));
        assertEquals(0, countRows("blend_order"));
        assertEquals(0, countRows("blend_batch"));
        assertEquals(0, countRows("blend_tank_lock"));
    }

    @Test
    void sameKeyReplayReturnsFirstResultAndDifferentParamsConflict() {
        OrderResponse first = service.createOrder(order("R-IDEM-1", 1, comp(1, "100")));
        OrderResponse replay = service.createOrder(order("R-IDEM-1", 1, comp(1, "100")));
        assertEquals(first, replay);
        assertEquals(1, countRows("blend_order"));
        assertEquals(new BigDecimal("900.000"), remaining(1));

        // 同键异参：409，且不占用罐量、不新增调合单
        BlendException conflict = assertThrows(BlendException.class, () -> service.createOrder(
                order("R-IDEM-1", 1, comp(1, "200"))));
        assertEquals(409, conflict.status());
        assertEquals(1, countRows("blend_order"));
        assertEquals(1, countRows("blend_tank_lock"));
        assertEquals(new BigDecimal("900.000"), remaining(1));
    }

    @Test
    void failedBusinessResultIsAlsoIdempotent() {
        // 首次业务失败（组分不足）同样按 requestId 幂等：重放同参返回同样的422，异参返回409
        BlendException first = assertThrows(BlendException.class, () -> service.createOrder(
                order("R-IDEM-F1", 1, comp(3, "600"))));
        assertEquals(422, first.status());
        BlendException replay = assertThrows(BlendException.class, () -> service.createOrder(
                order("R-IDEM-F1", 1, comp(3, "600"))));
        assertEquals(422, replay.status());
        assertEquals(first.getMessage(), replay.getMessage());
        BlendException conflict = assertThrows(BlendException.class, () -> service.createOrder(
                order("R-IDEM-F1", 1, comp(3, "100"))));
        assertEquals(409, conflict.status());
        assertEquals(0, countRows("blend_order"));
    }

    @Test
    void firstSampleFailAllowsOneReblendWithBatchChain() {
        // 80/20混合：辛烷值93.800超限，首样不合格
        OrderResponse created = service.createOrder(order("R-RB-1", 1, comp(1, "80"), comp(2, "20")));
        SampleResponse first = service.submitSample(created.orderId(), new SampleRequest("R-RB-2", 1));
        assertFalse(first.qualified());

        // 返调追加60方2号组分：新批次引用原批次，辛烷值回到92.000恰等上限
        ReblendResponse reblend = service.reblend(created.orderId(),
                new ReblendRequest("R-RB-3", List.of(comp(2, "60"))));
        assertEquals(created.batchId(), reblend.originBatchId());
        assertNotEquals(created.batchId(), reblend.batchId());
        assertEquals(2, reblend.seq());
        assertEquals(new BigDecimal("92.000"), reblend.metrics().octane());
        assertEquals(new BigDecimal("920.000"), remaining(2));

        // 返调仅限一次
        BlendException second = assertThrows(BlendException.class, () -> service.reblend(created.orderId(),
                new ReblendRequest("R-RB-4", List.of(comp(2, "10")))));
        assertEquals(409, second.status());

        // 返调后重走双采样，均合格放行
        assertTrue(service.submitSample(created.orderId(), new SampleRequest("R-RB-5", 1)).qualified());
        assertTrue(service.submitSample(created.orderId(), new SampleRequest("R-RB-6", 2)).qualified());
        assertEquals(BlendService.ORDER_RELEASED,
                service.release(created.orderId(), new ReleaseRequest("R-RB-7")).status());

        // 批次链可复算：两个批次，第二批次引用第一批次
        OrderDetail detail = service.getOrder(created.orderId());
        assertEquals(2, detail.batches().size());
        assertEquals(1, detail.reblendCount());
        assertEquals(detail.batches().get(0).batchId(), detail.batches().get(1).originBatchId());
        assertEquals(new BigDecimal("160.000"), detail.totalVolume());
    }

    @Test
    void reblendStillFailingTurnsUnqualifiedTerminal() {
        // 90/10混合：辛烷值94.400超限，首样不合格
        OrderResponse created = service.createOrder(order("R-UQ-1", 1, comp(1, "90"), comp(2, "10")));
        assertFalse(service.submitSample(created.orderId(), new SampleRequest("R-UQ-2", 1)).qualified());

        // 返调仅追加5方2号组分：辛烷值94.143仍超限
        service.reblend(created.orderId(), new ReblendRequest("R-UQ-3", List.of(comp(2, "5"))));
        SampleResponse again = service.submitSample(created.orderId(), new SampleRequest("R-UQ-4", 1));
        assertFalse(again.qualified());
        assertEquals(BlendService.ORDER_UNQUALIFIED, again.orderStatus());

        // 终态：不得再次返调、不能放行、不再接受化验
        assertEquals(409, assertThrows(BlendException.class, () -> service.reblend(created.orderId(),
                new ReblendRequest("R-UQ-5", List.of(comp(2, "50"))))).status());
        assertEquals(409, assertThrows(BlendException.class, () -> service.release(created.orderId(),
                new ReleaseRequest("R-UQ-6"))).status());
        assertEquals(409, assertThrows(BlendException.class, () -> service.submitSample(created.orderId(),
                new SampleRequest("R-UQ-7", 1))).status());
        assertEquals(BlendService.ORDER_UNQUALIFIED, service.getOrder(created.orderId()).status());
        // 终态后调合罐锁已释放，可下达新单
        service.createOrder(order("R-UQ-8", 1, comp(1, "10")));
    }

    @Test
    void releaseIsTerminalAndLateSampleOnlyRecorded() {
        OrderResponse created = service.createOrder(order("R-REL-1", 1, comp(1, "50"), comp(2, "50")));
        service.submitSample(created.orderId(), new SampleRequest("R-REL-2", 1));
        service.submitSample(created.orderId(), new SampleRequest("R-REL-3", 2));
        ReleaseResponse released = service.release(created.orderId(), new ReleaseRequest("R-REL-4"));
        assertEquals(BlendService.ORDER_RELEASED, released.status());

        // 重复封账（新requestId）返回幂等成功，时间不变
        ReleaseResponse again = service.release(created.orderId(), new ReleaseRequest("R-REL-5"));
        assertEquals(BlendService.ORDER_RELEASED, again.status());
        assertEquals(released.releasedAt(), again.releasedAt());

        // 封账后迟到化验：只记迟到事件，不改判定结论
        SampleResponse late = service.submitSample(created.orderId(), new SampleRequest("R-REL-6", 1));
        assertTrue(late.late());
        assertEquals(BlendService.ORDER_RELEASED, late.orderStatus());

        OrderDetail detail = service.getOrder(created.orderId());
        assertEquals(BlendService.ORDER_RELEASED, detail.status());
        assertEquals(1, detail.lateEvents().size());
        assertTrue(detail.lateEvents().get(0).contains("迟到"));
        // 判定结论不变：仍只有2条采样记录且均合格
        assertEquals(2, detail.batches().get(0).samples().size());
        assertTrue(detail.batches().get(0).samples().stream().allMatch(s -> s.qualified()));
    }

    @Test
    void samplingOrderAndPrematureReleaseRejected() {
        OrderResponse created = service.createOrder(order("R-ORD-1", 1, comp(1, "50"), comp(2, "50")));
        // 次样先于首样：409
        assertEquals(409, assertThrows(BlendException.class, () -> service.submitSample(created.orderId(),
                new SampleRequest("R-ORD-2", 2))).status());
        // 双采样未合格提前放行：409
        assertEquals(409, assertThrows(BlendException.class, () -> service.release(created.orderId(),
                new ReleaseRequest("R-ORD-3"))).status());
        // 首样重复提交（不同requestId）：409
        service.submitSample(created.orderId(), new SampleRequest("R-ORD-4", 1));
        assertEquals(409, assertThrows(BlendException.class, () -> service.submitSample(created.orderId(),
                new SampleRequest("R-ORD-5", 1))).status());
    }

    @Test
    void sameBlendTankRejectsSecondExecutingOrder() {
        service.createOrder(order("R-BUSY-1", 1, comp(1, "10")));
        BlendException ex = assertThrows(BlendException.class,
                () -> service.createOrder(order("R-BUSY-2", 1, comp(1, "10"))));
        assertEquals(409, ex.status());
        // 另一调合罐不受影响
        service.createOrder(order("R-BUSY-3", 2, comp(1, "10")));
    }
}
