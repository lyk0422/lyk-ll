package com.example.starter.strategy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.repo.StrategyRepository;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下发：变更间隔恰 30 分钟允许、新旧策略同事务原子切换、requestId 幂等与并发唯一生效。
 */
class StrategyDispatchTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired
    private StrategyRepository strategyRepository;

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
    }

    @Test
    @DisplayName("相邻生效间隔恰满 30 分钟允许，少 1 秒被拒绝")
    void intervalExactlyThirtyMinutesAllowed() {
        draftStrategy("S1", "24.00", "18.00", "70.00", "0.5", "5");
        dispatch("S1", "REQ-D1", T0);

        draftStrategy("S2", "25.00", "17.00", "70.00", "0.5", "5");
        assertThatThrownBy(() -> dispatch("S2", "REQ-D2-EARLY", T0.plusSeconds(29 * 60 + 59)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("30 分钟");

        // 同键异参一律 409：失败请求的 requestId 换时刻重提仍冲突，须换新键。
        assertThatThrownBy(() -> dispatch("S2", "REQ-D2-EARLY", T0.plusSeconds(30 * 60)))
                .isInstanceOf(ConflictException.class);

        StrategyDtos.StrategyResponse exact = dispatch("S2", "REQ-D2", T0.plusSeconds(30 * 60));
        assertThat(exact.status()).isEqualTo("DISPATCHED");
        assertThat(exact.effectiveTime()).isEqualTo(T0.plusSeconds(30 * 60));
    }

    @Test
    @DisplayName("新策略生效与旧策略失效在同一事务完成：旧 EXPIRED、新 DISPATCHED")
    void switchIsAtomic() {
        draftStrategy("S1", "24.00", "18.00", "70.00", "0.5", "5");
        draftStrategy("S2", "25.00", "17.00", "70.00", "0.5", "5");
        dispatch("S1", "REQ-D1", T0);
        dispatch("S2", "REQ-D2", T0.plusSeconds(30 * 60));

        StrategyRepository.Strategy oldOne = strategyRepository.findByCode("S1").orElseThrow();
        StrategyRepository.Strategy newOne = strategyRepository.findByCode("S2").orElseThrow();
        assertThat(oldOne.status()).isEqualTo("EXPIRED");
        assertThat(oldOne.expiredAt()).isEqualTo(T0.plusSeconds(30 * 60));
        assertThat(newOne.status()).isEqualTo("DISPATCHED");
        assertThat(strategyRepository.findActiveByZone(ZONE)).isPresent()
                .get().extracting(StrategyRepository.Strategy::strategyCode).isEqualTo("S2");
    }

    @Test
    @DisplayName("同 requestId 同参数重放返回首次结果，不重复失效其他策略")
    void sameRequestReplayReturnsFirstResult() {
        draftStrategy("S1", "24.00", "18.00", "70.00", "0.5", "5");
        StrategyDtos.StrategyResponse first = dispatch("S1", "REQ-R", T0);
        StrategyDtos.StrategyResponse replay = dispatch("S1", "REQ-R", T0);
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(replay.effectiveTime()).isEqualTo(first.effectiveTime());
        assertThat(strategyRepository.findByCode("S1").orElseThrow().status()).isEqualTo("DISPATCHED");
    }

    @Test
    @DisplayName("同 requestId 不同参数（生效时刻不同）返回 409")
    void sameRequestDifferentParamsConflicts() {
        draftStrategy("S1", "24.00", "18.00", "70.00", "0.5", "5");
        dispatch("S1", "REQ-C", T0);
        assertThatThrownBy(() -> dispatch("S1", "REQ-C", T0.plusSeconds(30 * 60)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("并发下发不同策略到同分区：仅一个 DISPATCHED 生效，另一个被原子替换或拒绝")
    void concurrentDispatchSameZoneOnlyOneWins() throws Exception {
        draftStrategy("S1", "24.00", "18.00", "70.00", "0.5", "5");
        draftStrategy("S2", "25.00", "17.00", "70.00", "0.5", "5");
        // 先让 S1 生效，再并发下发 S2/S3（间隔均满足 30 分钟，唯一约束在生效时刻上兜底）。
        dispatch("S1", "REQ-INIT", T0);
        draftStrategy("S3", "26.00", "18.00", "70.00", "0.5", "5");

        Instant target = T0.plusSeconds(30 * 60);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return dispatch("S2", "REQ-C2", target);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return dispatch("S3", "REQ-C3", target);
                    }));
            start.countDown();
            int success = 0;
            int conflict = 0;
            for (Future<?> f : futures) {
                try {
                    f.get(15, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    conflict++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflict).isEqualTo(1);
            // 同时刻最终只有一个生效策略，且 S1 已失效。
            Integer dispatched = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM climate_strategy WHERE zone_code = ? AND status = 'DISPATCHED'",
                    Integer.class, ZONE);
            assertThat(dispatched).isEqualTo(1);
            Integer expiredS1 = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM climate_strategy WHERE strategy_code = 'S1' AND status = 'EXPIRED'",
                    Integer.class);
            assertThat(expiredS1).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
