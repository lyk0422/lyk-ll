package com.example.starter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.starter.error.ApiException;
import com.example.starter.repo.ShipmentRepository;
import com.example.starter.service.RiskService;
import com.example.starter.service.ShipmentService;
import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.RiskBindRequest;
import com.example.starter.web.dto.RiskResultRequest;
import com.example.starter.web.dto.ShipmentProgressView;
import com.example.starter.web.dto.VehicleCreateRequest;
import com.example.starter.web.dto.VehicleDepartRequest;
import com.example.starter.web.dto.VehicleRevokeRequest;
import com.example.starter.web.dto.VehicleView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分车出厂并发边界（真实线程 + H2 行锁/唯一约束）：同头只争车只一方成功、
 * 撤销与发车不能既释放又出厂、阳性风险阻断发车、两车并发逐车核销末车恰好收口。
 * 断言最终状态与落库数据，不靠打印或休眠。
 */
class ShipmentConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private ShipmentService shipmentService;

    @Autowired
    private RiskService riskService;

    @Autowired
    private ShipmentRepository shipmentRepo;

    private static List<String> heads(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(String.format("H%02d", i));
        }
        return list;
    }

    private void readyAllQualified(String batchNo, int n) throws Exception {
        List<String> hs = heads(n);
        readyJudgedBatch(batchNo, hs, hs.stream().map(h -> "QUALIFIED").toList());
        shipmentService.enableSplit(batchNo,
                new com.example.starter.web.dto.SplitEnableRequest(
                        "se-" + batchNo, "分车员甲", clock.instant().toString()));
    }

    @Test
    @DisplayName("同头只争车并发建单：只一方成功，失败方整单不占（其另一头也不落库）")
    void concurrentCreateSameHeadOnlyOneWins() throws Exception {
        readyAllQualified("B-CRACE", 4);

        VehicleCreateRequest a = new VehicleCreateRequest("vc-a", "京A-1",
                List.of("H01", "H02"), 0, clock.instant().toString());
        VehicleCreateRequest b = new VehicleCreateRequest("vc-b", "京A-2",
                List.of("H01", "H03"), 0, clock.instant().toString());

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> shipmentService.createVehicle("B-CRACE", a),
                () -> shipmentService.createVehicle("B-CRACE", b));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream().filter(o -> o.error() != null && o.error().status() == 409)
                .count();
        assertEquals(1, successes, "同头只争车只一方成功");
        assertEquals(1, conflicts, "争车失败方返回 409");

        // 成功车全量预占 2 头；失败方整单回滚，其“另一头”也不得残留占用
        ShipmentProgressView view = getShipment("B-CRACE");
        assertEquals(1, view.vehicles().size());
        assertEquals(2, view.reservedHeads().size());
        assertTrue(view.reservedHeads().contains("H01"));
        // 唯一预占：H01 在预占台账中只出现一次（数据库唯一约束兜底）
        Integer h01 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipment_head WHERE batch_id ="
                        + " (SELECT id FROM slaughter_batch WHERE batch_no = 'B-CRACE') AND head_no = 'H01'",
                Integer.class);
        assertEquals(1, h01);
    }

    @Test
    @DisplayName("撤销与发车并发：互斥序列化，绝不既释放又出厂")
    void revokeVersusDepartNeverBothReleaseAndShip() throws Exception {
        readyAllQualified("B-RD", 2);
        shipmentService.createVehicle("B-RD", new VehicleCreateRequest("vc-1", "京A-1",
                List.of("H01", "H02"), 0, clock.instant().toString()));

        VehicleRevokeRequest revoke = new VehicleRevokeRequest("vr-1", 1,
                clock.instant().toString());
        VehicleDepartRequest depart = new VehicleDepartRequest("vd-1", 1,
                clock.instant().toString(), "核销员甲");

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> shipmentService.revokeVehicle("B-RD", "京A-1", revoke),
                () -> shipmentService.departVehicle("B-RD", "京A-1", depart));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream().filter(o -> o.error() != null && o.error().status() == 409)
                .count();
        assertEquals(1, successes, "撤销与发车恰好一方成功");
        assertEquals(1, conflicts);

        VehicleView finalVehicle = getShipment("B-RD").vehicles().get(0);
        long batchId = jdbc.queryForObject(
                "SELECT id FROM slaughter_batch WHERE batch_no = 'B-RD'", Long.class);
        long reserved = shipmentRepo.findShipmentHeads(batchId).stream()
                .filter(h -> "RESERVED".equals(h.status())).count();
        long departed = shipmentRepo.findShipmentHeads(batchId).stream()
                .filter(h -> "DEPARTED".equals(h.status())).count();

        if ("REVOKED".equals(finalVehicle.status())) {
            // 撤销先成功：全部释放，无任何出厂
            assertEquals(2, finalVehicle.version());
            assertEquals(0, reserved + departed, "撤销车不得残留或出厂任何头只");
        } else {
            // 发车先成功：两头全部出厂，不存在被释放的缺口
            assertEquals("DEPARTED", finalVehicle.status());
            assertEquals(2, departed);
            assertEquals(0, reserved);
        }
    }

    @Test
    @DisplayName("阳性抽检结果与发车并发：车辆绝不出厂，批次留在专项隔离")
    void positiveResultVersusDepartNeverShips() throws Exception {
        readyAllQualified("B-CPOS", 10);
        // 受控批 NORMAL：首份清单仅抽 H01，尚无结果
        riskService.bind("B-CPOS", new RiskBindRequest("rb-1", "NORMAL", "RULE-1", heads(10), 0,
                clock.instant().toString()));
        shipmentService.createVehicle("B-CPOS", new VehicleCreateRequest("vc-1", "京A-1",
                heads(10), 0, clock.instant().toString()));

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> riskService.submitResult("B-CPOS",
                        new RiskResultRequest("rr-pos", "H01", "POSITIVE", 1,
                                clock.instant().toString())),
                () -> shipmentService.departVehicle("B-CPOS", "京A-1",
                        new VehicleDepartRequest("vd-1", 1, clock.instant().toString(), "核销员甲")));

        long departSuccess = outcomes.stream()
                .filter(o -> o.error() == null && o.value() instanceof VehicleView).count();
        assertEquals(0, departSuccess, "阳性/未完成抽检门禁下发车必须失败");

        BatchView batch = getView("B-CPOS");
        assertEquals("SPECIAL_QUARANTINE", batch.status(), "阳性证据落库后批次专项隔离");
        ShipmentProgressView view = getShipment("B-CPOS");
        assertEquals(0, view.departedCount(), "不得有任何头只出厂");
        assertEquals("RESERVED", view.vehicles().get(0).status(), "失败保留原预占");
    }

    @Test
    @DisplayName("两车并发逐车核销：全部成功，末车恰好收口一次，批次转最终出厂")
    void twoVehiclesDepartConcurrentlyLastOneCloses() throws Exception {
        readyAllQualified("B-CLOSE", 4);
        shipmentService.createVehicle("B-CLOSE", new VehicleCreateRequest("vc-a", "京A-1",
                List.of("H01", "H02"), 0, clock.instant().toString()));
        shipmentService.createVehicle("B-CLOSE", new VehicleCreateRequest("vc-b", "京A-2",
                List.of("H03", "H04"), 0, clock.instant().toString()));

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> shipmentService.departVehicle("B-CLOSE", "京A-1",
                        new VehicleDepartRequest("vd-a", 1, clock.instant().toString(), "核销员甲")),
                () -> shipmentService.departVehicle("B-CLOSE", "京A-2",
                        new VehicleDepartRequest("vd-b", 1, clock.instant().toString(), "核销员甲")));

        assertTrue(outcomes.stream().allMatch(o -> o.error() == null), "两车各含不相交合格头只，应都核销成功");
        long released = outcomes.stream()
                .filter(o -> o.value() instanceof VehicleView v && v.batchReleased()).count();
        assertEquals(1, released, "只有末车收口把批次转最终出厂，且仅一次");

        BatchView batch = getView("B-CLOSE");
        assertEquals("RELEASED", batch.status());
        assertNotNull(batch.releasedAt());
        ShipmentProgressView view = getShipment("B-CLOSE");
        assertEquals(4, view.departedCount(), "按头只勾稽：4 头全部出厂");
        assertEquals(0, view.unshippedCount());
        assertTrue(view.allShipped());
        assertTrue(view.vehicles().stream().allMatch(v -> "DEPARTED".equals(v.status())));
    }

    // ---------- 并发辅助（与现有并发测试同构） ----------

    private record Outcome<T>(T value, ApiException error) {
    }

    private <T> List<Outcome<T>> runConcurrently(Supplier<T> first, Supplier<T> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Outcome<T>> taskFirst = () -> awaitAndRun(ready, start, first);
            Callable<Outcome<T>> taskSecond = () -> awaitAndRun(ready, start, second);
            Future<Outcome<T>> futureFirst = pool.submit(taskFirst);
            Future<Outcome<T>> futureSecond = pool.submit(taskSecond);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
            start.countDown();
            Outcome<T> outcomeFirst = futureFirst.get(15, TimeUnit.SECONDS);
            Outcome<T> outcomeSecond = futureSecond.get(15, TimeUnit.SECONDS);
            assertNotNull(outcomeFirst);
            assertNotNull(outcomeSecond);
            return List.of(outcomeFirst, outcomeSecond);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发线程未在超时内结束");
        }
    }

    private static <T> Outcome<T> awaitAndRun(CountDownLatch ready, CountDownLatch start,
                                              Supplier<T> action) throws Exception {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            return new Outcome<>(action.get(), null);
        } catch (ApiException e) {
            return new Outcome<>(null, e);
        }
    }
}
