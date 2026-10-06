package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.domain.Circuit;
import com.example.starter.dto.BrightnessRequest;
import com.example.starter.dto.DimRequest;
import com.example.starter.dto.DimResult;
import com.example.starter.dto.TrafficRequest;
import com.example.starter.exception.BizException;
import com.example.starter.repo.RecordRepository;
import com.example.starter.repo.TrafficRepository;
import com.example.starter.service.DimmingService;

/**
 * 调光规则测试：稳定时限、恰等阈值不触发、过渡期间禁止下调、车流量保底。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tl_test_rules;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DimmingRuleTest extends BaseIntegrationTest {

    @Autowired
    private DimmingService service;

    @Autowired
    private RecordRepository recordRepo;

    @Autowired
    private TrafficRepository trafficRepo;

    @Test
    void 稳定时限_未满10分钟拒绝_恰满10分钟允许() {
        DimResult first = service.dimManually(1L, new DimRequest("r-stab-1", 4));
        assertEquals(DimResult.STATUS_EXECUTED, first.status());
        assertEquals(3, first.fromLevel());
        assertEquals(4, first.toLevel());

        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        BizException ex = assertThrows(BizException.class,
                () -> service.dimManually(1L, new DimRequest("r-stab-2", 3)));
        assertEquals("STABILITY_NOT_MET", ex.code());
        assertEquals(409, ex.status().value());
        assertEquals(4, service.getCircuit(1L).currentLevel());

        // 恰满 10 分钟允许
        clock.advance(Duration.ofSeconds(1));
        DimResult ok = service.dimManually(1L, new DimRequest("r-stab-3", 3));
        assertEquals(DimResult.STATUS_EXECUTED, ok.status());
        assertEquals(4, ok.fromLevel());
        assertEquals(3, ok.toLevel());
    }

    @Test
    void 恰等阈值不触发_低于阈值立即升档_过渡期间禁止下调() {
        // 恰等阈值（300 lux）不触发
        DimResult equal = service.checkBrightness(1L, new BrightnessRequest("r-br-1", 300));
        assertEquals(DimResult.STATUS_NOT_TRIGGERED, equal.status());
        assertEquals(3, service.getCircuit(1L).currentLevel());
        assertEquals(0, recordRepo.countByCircuit(1L));

        // 低于阈值立即升一档（不受稳定期限制），并按过渡曲线执行
        DimResult up = service.checkBrightness(1L, new BrightnessRequest("r-br-2", 299));
        assertEquals(DimResult.STATUS_EXECUTED, up.status());
        assertEquals(3, up.fromLevel());
        assertEquals(4, up.toLevel());
        Circuit circuit = service.getCircuit(1L);
        assertNotNull(circuit.transitionUntil());
        assertEquals(TestClockConfig.BASE_TIME.plus(DimmingService.TRANSITION), circuit.transitionUntil());

        // 稳定期满（恰 10 分钟）但过渡曲线未结束：禁止下调
        clock.advance(Duration.ofMinutes(10));
        BizException ex = assertThrows(BizException.class,
                () -> service.dimManually(1L, new DimRequest("r-br-3", 2)));
        assertEquals("TRANSITION_NO_DOWNGRADE", ex.code());
        assertEquals(4, service.getCircuit(1L).currentLevel());

        // 过渡结束后允许下调
        clock.advance(Duration.ofMinutes(5));
        DimResult down = service.dimManually(1L, new DimRequest("r-br-4", 2));
        assertEquals(DimResult.STATUS_EXECUTED, down.status());
        assertEquals(4, down.fromLevel());
        assertEquals(2, down.toLevel());
    }

    @Test
    void 车流量恰等为0时降至保底亮度() {
        DimResult nonZero = service.trafficUpdate(1L, new TrafficRequest("r-tr-1", 12, null));
        assertEquals(DimResult.STATUS_NOT_TRIGGERED, nonZero.status());
        assertEquals(3, service.getCircuit(1L).currentLevel());

        DimResult zero = service.trafficUpdate(1L, new TrafficRequest("r-tr-2", 0, null));
        assertEquals(DimResult.STATUS_EXECUTED, zero.status());
        assertEquals(3, zero.fromLevel());
        assertEquals(1, zero.toLevel());
        assertEquals("TRAFFIC", zero.source());
        assertEquals(1, service.getCircuit(1L).currentLevel());
        // 采样与调光留痕均入库
        assertEquals(2, trafficRepo.countByCircuit(1L));
        assertEquals(1, recordRepo.countByCircuit(1L));
    }

    @Test
    void 车流量为0但稳定期未满仅记录采样不调光() {
        service.dimManually(1L, new DimRequest("r-trs-1", 4));
        clock.advance(Duration.ofMinutes(5));
        DimResult result = service.trafficUpdate(1L, new TrafficRequest("r-trs-2", 0, null));
        assertEquals(DimResult.STATUS_NOT_TRIGGERED, result.status());
        assertEquals(4, service.getCircuit(1L).currentLevel());
        // 采样仍按滚动更新入库
        assertEquals(1, trafficRepo.countByCircuit(1L));
        assertEquals(1, recordRepo.countByCircuit(1L));
    }
}
