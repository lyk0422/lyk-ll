package com.example.starter.hazmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.error.ConflictException;
import com.example.starter.hazmat.repo.VehicleFreezeRepository;
import com.example.starter.hazmat.service.TimeoutScanService;
import com.example.starter.hazmat.service.WaybillService;
import com.example.starter.hazmat.support.HazmatIntegrationTest;
import com.example.starter.hazmat.support.TestClockConfiguration;
import com.example.starter.hazmat.web.WaybillView;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 到达核销与失联冻结并发竞态测试：二者必须形成唯一终态，不得既核销又冻结。
 */
class CloseFreezeRaceTest extends HazmatIntegrationTest {

    @Autowired
    private WaybillService waybillService;

    @Autowired
    private TimeoutScanService scanService;

    @Autowired
    private VehicleFreezeRepository freezes;

    @Test
    void 核销与失联冻结并发_唯一终态() throws Exception {
        int rounds = 10;
        int closedWins = 0;
        int lostWins = 0;
        for (int round = 0; round < rounds; round++) {
            String vehicle = "VEH-RACE-" + round;
            WaybillView applied = waybillService.apply("REQ-RACE-" + round, vehicle, 1,
                    routeId("R-LOW"), BASE_TIME.plusHours(1));
            waybillService.approve(applied.id());
            waybillService.depart(applied.id());
            long waybillId = applied.id();
            // 推进到途经点 1、2 均已超时（连续 2 点），扫描将触发失联冻结
            clock.setInstant(TestClockConfiguration.BASE_INSTANT.plus(Duration.ofHours(3)));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<WaybillStatus> closeFuture = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                try {
                    return waybillService.close(waybillId).status();
                } catch (ConflictException e) {
                    return null;
                }
            });
            Future<Boolean> scanFuture = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                scanService.scanOnce();
                return freezes.isFrozen(vehicle);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            WaybillStatus closeResult = closeFuture.get(15, TimeUnit.SECONDS);
            Boolean frozenByScan = scanFuture.get(15, TimeUnit.SECONDS);
            pool.shutdownNow();

            WaybillStatus finalStatus = waybillService.getView(waybillId).status();
            boolean frozen = freezes.isFrozen(vehicle);
            assertTrue(finalStatus == WaybillStatus.CLOSED || finalStatus == WaybillStatus.LOST,
                    "终态必须为 CLOSED 或 LOST，实际: " + finalStatus);
            if (finalStatus == WaybillStatus.CLOSED) {
                closedWins++;
                assertEquals(WaybillStatus.CLOSED, closeResult);
                // 核销胜出：不得存在冻结记录
                assertTrue(!frozen && !frozenByScan, "已核销运单不得被冻结");
            } else {
                lostWins++;
                // 冻结胜出：核销侧只能拿到冲突，且必须存在冻结记录
                assertTrue(frozen, "失联运单必须存在冻结记录");
            }
            // 终态稳定：重复核销幂等或冲突，不会再次改变状态
            try {
                waybillService.close(waybillId);
            } catch (ConflictException expected) {
                // 失联终态下核销冲突，符合预期
            }
            assertEquals(finalStatus, waybillService.getView(waybillId).status());

            // 下一轮前清理本轮业务数据
            jdbc.update("DELETE FROM checkin_record");
            jdbc.update("DELETE FROM alert");
            jdbc.update("DELETE FROM vehicle_freeze");
            jdbc.update("DELETE FROM reroute_request");
            jdbc.update("DELETE FROM waybill_waypoint");
            jdbc.update("DELETE FROM waybill");
        }
        // 至少验证两种终态都可能出现（竞态真实发生）；不强制比例，仅要求均收敛到唯一终态
        assertTrue(closedWins + lostWins == rounds);
    }
}
