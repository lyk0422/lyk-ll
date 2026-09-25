package com.example.starter.greenhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.greenhouse.dto.BudgetAllocateRequest;
import com.example.starter.greenhouse.dto.CommandIssueRequest;
import com.example.starter.greenhouse.dto.CommandResponse;
import com.example.starter.greenhouse.service.EnergyService;
import com.example.starter.greenhouse.web.ApiException;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 能耗预算：恰用尽后指令限制、同事务扣减、并发不超预算、requestId 幂等。
 */
class EnergyBudgetTest extends BaseGreenhouseTest {

    private static final String DAY = "2026-09-25";

    @Autowired
    private EnergyService energyService;

    private void setup(String zone) {
        createCrop("C-" + zone);
        createZone(zone);
        energyService.allocate(new BudgetAllocateRequest(zone, DAY, new BigDecimal("10.000")));
    }

    private CommandResponse command(String zone, String type, String kwh, String reqId) {
        return energyService.issue(new CommandIssueRequest(
                zone, type, new BigDecimal(kwh), "2026-09-25T10:00:00Z", reqId));
    }

    @Test
    void 耗能指令同事务扣减预算() {
        setup("ZE1");
        CommandResponse r1 = command("ZE1", "HEATING", "4.000", "E1");
        assertEquals(new BigDecimal("6.000"), r1.remainingKwh());
        CommandResponse r2 = command("ZE1", "LIGHTING", "6.000", "E2");
        assertEquals(new BigDecimal("0.000"), r2.remainingKwh(), "恰用尽合法");
        BigDecimal remaining = jdbc.queryForObject(
                "SELECT remaining_kwh FROM energy_budget WHERE zone_id = (SELECT id FROM zone WHERE zone_code='ZE1')",
                BigDecimal.class);
        assertEquals(new BigDecimal("0.000"), remaining);
    }

    @Test
    void 预算恰尽后只允许零能耗通风指令() {
        setup("ZE2");
        command("ZE2", "HEATING", "10.000", "E3");
        ApiException ex = assertThrows(ApiException.class,
                () -> command("ZE2", "LIGHTING", "0.001", "E4"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        // 失败指令不得入账，预算保持 0。
        BigDecimal remaining = jdbc.queryForObject(
                "SELECT remaining_kwh FROM energy_budget WHERE zone_id = (SELECT id FROM zone WHERE zone_code='ZE2')",
                BigDecimal.class);
        assertEquals(new BigDecimal("0.000"), remaining);
        Long failedCommands = jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_command WHERE request_id = 'E4'", Long.class);
        assertEquals(0L, failedCommands);
        // 通风类零能耗仍允许。
        CommandResponse vent = command("ZE2", "VENTILATION", "0.000", "E5");
        assertEquals("VENTILATION", vent.commandType());
    }

    @Test
    void 通风指令必须零能耗() {
        setup("ZE3");
        ApiException ex = assertThrows(ApiException.class,
                () -> command("ZE3", "VENTILATION", "0.001", "E6"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void 指令同键同参重放不重复扣预算() {
        setup("ZE4");
        CommandResponse first = command("ZE4", "HEATING", "3.000", "E7");
        CommandResponse replay = command("ZE4", "HEATING", "3.000", "E7");
        assertEquals(first.id(), replay.id());
        assertEquals(new BigDecimal("7.000"), replay.remainingKwh());
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM energy_command WHERE request_id = 'E7'", Long.class);
        assertEquals(1L, rows);
    }

    @Test
    void 指令同键异参返回409() {
        setup("ZE5");
        command("ZE5", "HEATING", "3.000", "E8");
        ApiException ex = assertThrows(ApiException.class,
                () -> command("ZE5", "LIGHTING", "2.000", "E8"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void 并发耗能指令不超预算且唯一串行结果() throws Exception {
        setup("ZE6");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                start.await();
                try {
                    command("ZE6", "HEATING", "2.000", "EC-" + idx);
                    success.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY) {
                        rejected.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        // 预算 10、每笔 2，恰 5 笔成功，其余被拒。
        assertEquals(5, success.get());
        assertEquals(3, rejected.get());
        BigDecimal remaining = jdbc.queryForObject(
                "SELECT remaining_kwh FROM energy_budget WHERE zone_id = (SELECT id FROM zone WHERE zone_code='ZE6')",
                BigDecimal.class);
        assertEquals(new BigDecimal("0.000"), remaining);
        BigDecimal consumed = jdbc.queryForObject(
                "SELECT COALESCE(SUM(energy_kwh),0) FROM energy_command WHERE zone_id = "
                        + "(SELECT id FROM zone WHERE zone_code='ZE6')", BigDecimal.class);
        assertEquals(new BigDecimal("10.000"), consumed, "并发下总入账绝不超预算");
    }

    @Test
    void 未分配预算时耗能指令被拒() {
        createCrop("C-ZX");
        createZone("ZX");
        ApiException ex = assertThrows(ApiException.class,
                () -> command("ZX", "HEATING", "1.000", "E9"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }
}
