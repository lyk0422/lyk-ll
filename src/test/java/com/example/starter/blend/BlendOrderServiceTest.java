package com.example.starter.blend;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.blend.BlendContracts.ComponentQty;
import com.example.starter.blend.BlendContracts.CreateOrderRequest;
import com.example.starter.blend.BlendContracts.LabSampleRequest;
import com.example.starter.blend.BlendContracts.LabSampleResponse;
import com.example.starter.blend.BlendContracts.OrderDetailResponse;
import com.example.starter.blend.BlendContracts.OrderResponse;
import com.example.starter.blend.BlendContracts.ReblendRequest;
import com.example.starter.blend.BlendContracts.ReblendResponse;
import com.example.starter.blend.BlendContracts.ReleaseResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调合服务 H2 集成测试：覆盖恰等上限、卡边提示、组分不足整单拒绝（切罐扣量原子性）、
 * 双采样放行、返调一次限制、同键重放与异参 409、并发唯一执行单、封账终态与迟到化验。
 * 每个用例前清空全部业务表并重建合成夹具，用例间无顺序依赖。
 */
@SpringBootTest
class BlendOrderServiceTest {

    @Autowired
    private BlendOrderService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM lab_sample");
        jdbc.update("DELETE FROM tank_movement");
        jdbc.update("DELETE FROM blend_order_component");
        jdbc.update("DELETE FROM blend_batch");
        jdbc.update("DELETE FROM blend_tank_lock");
        jdbc.update("DELETE FROM blend_order");
        jdbc.update("DELETE FROM product_spec");
        jdbc.update("DELETE FROM component_tank");
        // 合成组分罐：TA/TB 等比例混合后辛烷值与硫含量恰等于 P1 上限
        jdbc.update("INSERT INTO component_tank (code, name, octane, sulfur, rvp, stock_kg) "
                + "VALUES ('TA','合成组分A',90.0,8.0,50.0,10000)");
        jdbc.update("INSERT INTO component_tank (code, name, octane, sulfur, rvp, stock_kg) "
                + "VALUES ('TB','合成组分B',94.0,12.0,60.0,10000)");
        jdbc.update("INSERT INTO component_tank (code, name, octane, sulfur, rvp, stock_kg) "
                + "VALUES ('TC','合成组分C',92.0,10.0,55.0,10000)");
        jdbc.update("INSERT INTO product_spec (product_code, octane_max, sulfur_max, rvp_max, "
                + "octane_margin, sulfur_margin, rvp_margin) "
                + "VALUES ('P1',92.0,10.0,60.0,1.0,2.0,5.0)");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static CreateOrderRequest order(String requestId, String tank, String product,
                                            String target, ComponentQty... components) {
        return new CreateOrderRequest(requestId, tank, product, bd(target), List.of(components));
    }

    private static ComponentQty qty(String tank, String kg) {
        return new ComponentQty(tank, bd(kg));
    }

    private BigDecimal stock(String tankCode) {
        return jdbc.queryForObject("SELECT stock_kg FROM component_tank WHERE code = ?",
                BigDecimal.class, tankCode);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void 下达成功切罐扣量同事务完成且指标恰等上限判合格() {
        OrderResponse res = service.createOrder(order("R-OK-1", "BT1", "P1", "1000",
                qty("TA", "500"), qty("TB", "500")));
        assertEquals("EXECUTING", res.status());
        assertFalse(res.replayed());
        // 恰等上限：辛烷值 92.000、硫含量 10.0000
        assertEquals(0, bd("92.000").compareTo(res.indicators().octane()));
        assertEquals(0, bd("10.0000").compareTo(res.indicators().sulfur()));
        // 库存与流水一致：不得出现已切罐未扣量
        assertEquals(0, bd("9500.000").compareTo(stock("TA")));
        assertEquals(0, bd("9500.000").compareTo(stock("TB")));
        assertEquals(2, count("tank_movement"));
        assertEquals(2, count("blend_order_component"));

        LabSampleResponse s1 = service.submitLabSample(res.orderId(), new LabSampleRequest("S-OK-1", 1));
        assertTrue(s1.passed());
        LabSampleResponse s2 = service.submitLabSample(res.orderId(), new LabSampleRequest("S-OK-2", 2));
        assertTrue(s2.passed());
        assertEquals("PASSED", s2.batchStatus());

        ReleaseResponse rel = service.release(res.orderId());
        assertEquals("RELEASED", rel.status());
        assertFalse(rel.replayed());
    }

    @Test
    void 卡边提示不判不合格() {
        // 仅用 TA：辛烷值 90 < 92-1，蒸气压 50 < 60-5，触发卡边提示但仍合格
        OrderResponse res = service.createOrder(order("R-EDGE-1", "BT1", "P1", "1000",
                qty("TA", "1000")));
        assertEquals(2, res.edgeHints().size());
        assertTrue(res.edgeHints().stream().anyMatch(h -> h.indicator().equals("octane")));
        assertTrue(res.edgeHints().stream().anyMatch(h -> h.indicator().equals("rvp")));

        LabSampleResponse s1 = service.submitLabSample(res.orderId(), new LabSampleRequest("S-EDGE-1", 1));
        assertTrue(s1.passed());
        assertEquals(2, s1.edgeHints().size());
    }

    @Test
    void 任一组分不足则整单不下达且库存无变动() {
        // TA 充足、TB 不足：整单拒绝，TA 不得出现已切罐未扣量
        ApiException ex = assertThrows(ApiException.class, () -> service.createOrder(
                order("R-SHORT-1", "BT1", "P1", "26000", qty("TA", "6000"), qty("TB", "20000"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals(0, bd("10000.000").compareTo(stock("TA")));
        assertEquals(0, bd("10000.000").compareTo(stock("TB")));
        assertEquals(0, count("blend_order"));
        assertEquals(0, count("tank_movement"));
        assertEquals(0, count("blend_tank_lock"));
    }

    @Test
    void 同键同参重放返回首次结果且只扣量一次() {
        CreateOrderRequest req = order("R-IDEM-1", "BT1", "P1", "1000", qty("TA", "1000"));
        OrderResponse first = service.createOrder(req);
        OrderResponse replay = service.createOrder(req);
        assertEquals(first.orderId(), replay.orderId());
        assertTrue(replay.replayed());
        assertEquals(0, bd("9000.000").compareTo(stock("TA")));
        assertEquals(1, count("blend_order"));
    }

    @Test
    void 同键异参返回409且不占用罐量() {
        OrderResponse created = service.createOrder(order("R-IDEM-2", "BT1", "P1", "1000", qty("TA", "1000")));
        ApiException ex = assertThrows(ApiException.class, () -> service.createOrder(
                order("R-IDEM-2", "BT1", "P1", "2000", qty("TA", "2000"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals(0, bd("9000.000").compareTo(stock("TA")));
        assertEquals(1, count("blend_order"));

        long orderId = created.orderId();
        LabSampleResponse s1 = service.submitLabSample(orderId, new LabSampleRequest("S-IDEM-2", 1));
        ApiException sampleEx = assertThrows(ApiException.class,
                () -> service.submitLabSample(orderId, new LabSampleRequest("S-IDEM-2", 2)));
        assertEquals(HttpStatus.CONFLICT, sampleEx.status());
        LabSampleResponse replay = service.submitLabSample(orderId, new LabSampleRequest("S-IDEM-2", 1));
        assertTrue(replay.replayed());
        assertEquals(s1.sampleId(), replay.sampleId());
    }

    @Test
    void 双采样均合格方可放行且封账为幂等终态() {
        OrderResponse res = service.createOrder(order("R-REL-1", "BT1", "P1", "1000",
                qty("TA", "500"), qty("TB", "500")));
        service.submitLabSample(res.orderId(), new LabSampleRequest("S-REL-1", 1));
        // 仅首样合格不得放行
        ApiException early = assertThrows(ApiException.class, () -> service.release(res.orderId()));
        assertEquals(HttpStatus.CONFLICT, early.status());

        service.submitLabSample(res.orderId(), new LabSampleRequest("S-REL-2", 2));
        ReleaseResponse rel = service.release(res.orderId());
        assertEquals("RELEASED", rel.status());
        // 重复封账返回幂等成功
        ReleaseResponse again = service.release(res.orderId());
        assertTrue(again.replayed());
        assertEquals(rel.releasedAt(), again.releasedAt());

        // 封账后迟到化验数据只记迟到事件，不改判定结论
        LabSampleResponse late = service.submitLabSample(res.orderId(), new LabSampleRequest("S-REL-3", 1));
        assertTrue(late.late());
        assertEquals("RELEASED", late.orderStatus());
        OrderDetailResponse detail = service.getDetail(res.orderId());
        assertEquals("RELEASED", detail.status());
        assertEquals(3, detail.samples().size());
        assertEquals(1, detail.samples().stream().filter(s -> s.late()).count());
    }

    @Test
    void 首样不合格允许返调一次并重走双采样判定() {
        // 纯 TB：辛烷值 94 > 92 上限，首样不合格
        OrderResponse res = service.createOrder(order("R-RB-1", "BT1", "P1", "1000",
                qty("TB", "1000")));
        LabSampleResponse fail = service.submitLabSample(res.orderId(), new LabSampleRequest("S-RB-1", 1));
        assertFalse(fail.passed());
        assertEquals("FAILED", fail.batchStatus());
        // 不合格批次不得继续采样，须先返调
        assertThrows(ApiException.class,
                () -> service.submitLabSample(res.orderId(), new LabSampleRequest("S-RB-2", 2)));

        // 返调：追加 TA 1000，累计辛烷值 (94+90)/2=92.0 恰等上限
        ReblendResponse rb = service.reblend(res.orderId(),
                new ReblendRequest("RB-1", List.of(qty("TA", "1000"))));
        assertEquals(2, rb.batchNo());
        assertEquals(0, bd("92.000").compareTo(rb.indicators().octane()));
        assertEquals(0, bd("9000.000").compareTo(stock("TA")));
        // 返调幂等重放
        ReblendResponse rbReplay = service.reblend(res.orderId(),
                new ReblendRequest("RB-1", List.of(qty("TA", "1000"))));
        assertTrue(rbReplay.replayed());
        assertEquals(rb.newBatchId(), rbReplay.newBatchId());

        // 返调后重走双采样，均合格可放行
        assertTrue(service.submitLabSample(res.orderId(), new LabSampleRequest("S-RB-3", 1)).passed());
        assertTrue(service.submitLabSample(res.orderId(), new LabSampleRequest("S-RB-4", 2)).passed());
        assertEquals("RELEASED", service.release(res.orderId()).status());

        // 批次链可复算：批次1被取代，批次2引用批次1
        OrderDetailResponse detail = service.getDetail(res.orderId());
        assertEquals(2, detail.batches().size());
        assertEquals("SUPERSEDED", detail.batches().get(0).status());
        assertEquals(detail.batches().get(0).batchId(), detail.batches().get(1).parentBatchId());
    }

    @Test
    void 返调后仍不合格转不合格品终态且不得再次返调() {
        OrderResponse res = service.createOrder(order("R-OFF-1", "BT1", "P1", "1000",
                qty("TB", "1000")));
        assertFalse(service.submitLabSample(res.orderId(), new LabSampleRequest("S-OFF-1", 1)).passed());
        // 返调仍用高辛烷组分，指标依然超限
        service.reblend(res.orderId(), new ReblendRequest("RB-OFF-1", List.of(qty("TB", "100"))));
        LabSampleResponse fail2 = service.submitLabSample(res.orderId(), new LabSampleRequest("S-OFF-2", 1));
        assertFalse(fail2.passed());
        assertEquals("OFF_SPEC", fail2.orderStatus());

        // 终态：不得再次返调、不再接收化验、不得放行，调合罐锁已释放
        assertThrows(ApiException.class, () -> service.reblend(res.orderId(),
                new ReblendRequest("RB-OFF-2", List.of(qty("TA", "5000")))));
        assertThrows(ApiException.class,
                () -> service.submitLabSample(res.orderId(), new LabSampleRequest("S-OFF-3", 2)));
        assertThrows(ApiException.class, () -> service.release(res.orderId()));
        assertEquals(0, count("blend_tank_lock"));
        assertEquals("OFF_SPEC", service.getDetail(res.orderId()).status());
    }

    @Test
    void 同一调合罐并发下达仅唯一成功() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    gate.await();
                    service.createOrder(order("R-CC-" + idx, "BT1", "P1", "100", qty("TA", "100")));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    conflict.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, count("blend_order"));
        assertEquals(1, count("blend_tank_lock"));
        // 仅唯一成功单扣量 100kg
        assertEquals(0, bd("9900.000").compareTo(stock("TA")));
    }

    @Test
    void 不同调合罐可并行下达且详情复算结果稳定() {
        OrderResponse a = service.createOrder(order("R-PAR-1", "BT1", "P1", "100", qty("TA", "100")));
        OrderResponse b = service.createOrder(order("R-PAR-2", "BT2", "P1", "100", qty("TB", "100")));
        assertNotEquals(a.orderId(), b.orderId());
        OrderDetailResponse d1 = service.getDetail(a.orderId());
        OrderDetailResponse d2 = service.getDetail(a.orderId());
        assertEquals(d1, d2);
        assertEquals(1, d1.batches().size());
    }
}
