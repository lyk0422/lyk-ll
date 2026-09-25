package com.example.starter.greenhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.greenhouse.domain.StrategyStatus;
import com.example.starter.greenhouse.dto.ActivationResponse;
import com.example.starter.greenhouse.dto.StrategyDraftRequest;
import com.example.starter.greenhouse.dto.StrategyResponse;
import com.example.starter.greenhouse.repo.StrategyRepository;
import com.example.starter.greenhouse.repo.ZoneRepository;
import com.example.starter.greenhouse.web.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 策略校验边界、30 分钟变更间隔、原子切换与并发唯一生效、requestId 幂等。
 */
class StrategyBoundaryTest extends BaseGreenhouseTest {

    @Autowired
    private StrategyRepository strategyRepository;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void 设定点恰在作物窗口边界合法() {
        createCrop("C1");
        createZone("Z1");
        // 白天温 20/28、湿度 60/80；夜间温 14/20、湿度 65/85 全部恰在边界，容差 0。
        draftValidate("S-BOUND", "C1", "Z1",
                "20", "0", "60", "0", "20", "0", "65", "0");
        StrategyResponse resp = strategyService.get("S-BOUND");
        assertEquals(StrategyStatus.VALIDATED.name(), resp.status());
    }

    @Test
    void 设定点超出窗口边界被拒绝() {
        createCrop("C1");
        createZone("Z1");
        strategyService.draft(new StrategyDraftRequest(
                "S-OUT", "C1", "Z1",
                bd("19.99"), bd("0"), bd("60"), bd("0"),
                bd("20"), bd("0"), bd("65"), bd("0")));
        ApiException ex = assertThrows(ApiException.class, () -> strategyService.validate("S-OUT"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getMessage().contains("白天温度"));
    }

    @Test
    void 昼夜温差恰为8度合法() {
        createCrop("C1");
        createZone("Z1");
        // 白天 22℃、夜间 14℃，差值恰为 8.00℃。
        draftValidate("S-DELTA8", "C1", "Z1",
                "22", "0", "70", "5", "14", "0", "70", "5");
        assertEquals(StrategyStatus.VALIDATED.name(), strategyService.get("S-DELTA8").status());
    }

    @Test
    void 昼夜温差超过8度被拒() {
        createCrop("C1");
        createZone("Z1");
        strategyService.draft(new StrategyDraftRequest(
                "S-DELTA81", "C1", "Z1",
                bd("22.01"), bd("0"), bd("70"), bd("5"),
                bd("14"), bd("0"), bd("70"), bd("5")));
        ApiException ex = assertThrows(ApiException.class, () -> strategyService.validate("S-DELTA81"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getMessage().contains("昼夜温度设定差"));
    }

    @Test
    void 变更间隔恰满30分钟允许() {
        createCrop("C1");
        createZone("Z1");
        draftValidate("S-A", "C1", "Z1",
                "22", "1", "70", "5", "16", "1", "70", "5");
        draftValidate("S-B", "C1", "Z1",
                "23", "1", "70", "5", "15", "1", "70", "5");
        ActivationResponse first = strategyService.activate("S-A", "REQ-A");
        assertNull(first.previousStrategyCode());
        clock.advance(Duration.ofMinutes(30));
        ActivationResponse second = strategyService.activate("S-B", "REQ-B");
        assertEquals("S-A", second.previousStrategyCode());
        // 旧策略失效与新策略生效同事务完成。
        assertEquals(StrategyStatus.SUPERSEDED, strategyRepository.findByCode("S-A").orElseThrow().getStatus());
        assertEquals(StrategyStatus.ACTIVE, strategyRepository.findByCode("S-B").orElseThrow().getStatus());
        Long zoneId = zoneRepository.findByCode("Z1").orElseThrow().getId();
        assertEquals(strategyRepository.findByCode("S-B").orElseThrow().getId(),
                strategyRepository.findActiveByZone(zoneId).orElseThrow().getStrategyId());
    }

    @Test
    void 变更间隔不足30分钟被拒且不改变生效策略() {
        createCrop("C1");
        createZone("Z1");
        draftValidate("S-A2", "C1", "Z1",
                "22", "1", "70", "5", "16", "1", "70", "5");
        draftValidate("S-B2", "C1", "Z1",
                "23", "1", "70", "5", "15", "1", "70", "5");
        strategyService.activate("S-A2", "REQ-A2");
        clock.advance(Duration.ofMinutes(29).plusSeconds(59));
        ApiException ex = assertThrows(ApiException.class, () -> strategyService.activate("S-B2", "REQ-B2"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        // 被拒后新策略仍为 VALIDATED，生效指针不变。
        assertEquals(StrategyStatus.VALIDATED, strategyRepository.findByCode("S-B2").orElseThrow().getStatus());
        assertEquals(StrategyStatus.ACTIVE, strategyRepository.findByCode("S-A2").orElseThrow().getStatus());
    }

    @Test
    void 同分区并发生效仅一个成功() throws Exception {
        createCrop("C1");
        createZone("Z1");
        draftValidate("S-P1", "C1", "Z1",
                "22", "1", "70", "5", "16", "1", "70", "5");
        draftValidate("S-P2", "C1", "Z1",
                "24", "1", "70", "5", "17", "1", "70", "5");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            start.await();
            return strategyService.activate("S-P1", "REQ-P1");
        });
        Future<?> f2 = pool.submit(() -> {
            start.await();
            return strategyService.activate("S-P2", "REQ-P2");
        });
        start.countDown();
        int success = 0;
        int conflict = 0;
        for (Future<?> f : List.of(f1, f2)) {
            try {
                f.get(10, TimeUnit.SECONDS);
                success++;
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof ApiException api && api.getStatus() == HttpStatus.CONFLICT) {
                    conflict++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdown();
        assertEquals(1, success, "恰一个策略并发首笔下发成功");
        assertEquals(1, conflict, "另一个必须冲突失败");
        Long zoneId = zoneRepository.findByCode("Z1").orElseThrow().getId();
        long activeCount = jdbc.queryForObject("SELECT COUNT(*) FROM zone_active_strategy WHERE zone_id = ?",
                Long.class, zoneId);
        assertEquals(1L, activeCount);
    }

    @Test
    void 下发同键同参重放返回首次结果() {
        createCrop("C1");
        createZone("Z1");
        draftValidate("S-R1", "C1", "Z1",
                "22", "1", "70", "5", "16", "1", "70", "5");
        ActivationResponse first = strategyService.activate("S-R1", "REQ-R");
        clock.advance(Duration.ofMinutes(10));
        ActivationResponse replay = strategyService.activate("S-R1", "REQ-R");
        assertEquals(first.effectiveAt(), replay.effectiveAt());
        long logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM strategy_change_log WHERE request_id = 'REQ-R'", Long.class);
        assertEquals(1L, logs, "重放不得重复写入变更流水");
    }

    @Test
    void 下发同键异参返回409() {
        createCrop("C1");
        createZone("Z1");
        draftValidate("S-X1", "C1", "Z1",
                "22", "1", "70", "5", "16", "1", "70", "5");
        draftValidate("S-X2", "C1", "Z1",
                "24", "1", "70", "5", "17", "1", "70", "5");
        strategyService.activate("S-X1", "REQ-X");
        clock.advance(Duration.ofMinutes(40));
        ApiException ex = assertThrows(ApiException.class,
                () -> strategyService.activate("S-X2", "REQ-X"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }
}
