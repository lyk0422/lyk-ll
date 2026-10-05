package com.example.starter.strategy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.AlarmHandlingDtos;
import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.repo.RehearsalRepository;
import com.example.starter.repo.StrategyRepository;
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

/**
 * 策略冲突仲裁：同分区存在进行中告警处置时，新策略不兼容则挂起（无副作用），
 * 处置完结后可重新下发；兼容（含边界恰等）则放行。
 */
class StrategyArbitrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-08T00:00:00Z");
    private static final String DAY = "2026-01-08";

    @Autowired
    private StrategyRepository strategyRepository;

    private Long alarmId;

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
        // 基线策略 S1 已生效，连续 3 点 27℃ 偏离触发 ACTIVE 告警。
        alarmId = dispatchBaselineAndTriggerAlarm(T0, T0.plusSeconds(60));
    }

    private void startHandling(String code, String tempMin, String tempMax,
                               String humMin, String humMax) {
        alarmHandlingService.start(new AlarmHandlingDtos.StartRequest(
                code, alarmId, "REQ-H-" + code, bd(tempMin), bd(tempMax), bd(humMin), bd(humMax)));
    }

    @Test
    @DisplayName("与进行中处置不兼容：下发挂起 SUSPENDED，旧策略、预算、生效时刻均无副作用")
    void incompatibleHandlingSuspendsWithoutSideEffects() {
        allocateBudget(DAY, "10.000");
        // 处置窗口温度 [25,26]：S2 白天设定 24℃ 不在窗口内，不兼容。
        startHandling("H1", "25.00", "26.00", "65.00", "75.00");
        draftStrategy("S2", "24.00", "18.00", "70.00", "0.5", "5");

        StrategyDtos.StrategyResponse response = dispatch("S2", "REQ-S2", T0.plusSeconds(30 * 60));
        assertThat(response.status()).isEqualTo("SUSPENDED");
        assertThat(response.effectiveTime()).isNull();
        assertThat(response.dispatchedAt()).isNull();

        // 无副作用：旧策略 S1 仍生效未失效，预算未扣减，无环控指令。
        assertThat(strategyRepository.findByCode("S1").orElseThrow().status()).isEqualTo("DISPATCHED");
        assertThat(strategyRepository.findByCode("S1").orElseThrow().expiredAt()).isNull();
        assertThat(energyService.getBudget(ZONE, java.time.LocalDate.parse(DAY)).remainingKwh())
                .isEqualByComparingTo("10.000");
        Integer commands = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM control_command WHERE zone_code = ?", Integer.class, ZONE);
        assertThat(commands).isZero();

        // 仲裁结论与状态推进同事务留档：SUSPENDED 且记录冲突处置编码。
        RehearsalRepository.Rehearsal rehearsal = rehearsalService.latest("S2");
        assertThat(rehearsal.verdict()).isEqualTo("SUSPENDED");
        assertThat(rehearsal.handlingConflict()).isEqualTo("H1");
    }

    @Test
    @DisplayName("挂起的下发按 requestId 幂等：重放返回首次挂起结果，留档不重复")
    void suspendedDispatchIsIdempotent() {
        startHandling("H1", "25.00", "26.00", "65.00", "75.00");
        draftStrategy("S2", "24.00", "18.00", "70.00", "0.5", "5");

        StrategyDtos.StrategyResponse first = dispatch("S2", "REQ-S2", T0.plusSeconds(30 * 60));
        StrategyDtos.StrategyResponse replay = dispatch("S2", "REQ-S2", T0.plusSeconds(30 * 60));
        assertThat(first.status()).isEqualTo("SUSPENDED");
        assertThat(replay.status()).isEqualTo("SUSPENDED");

        Integer rehearsals = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM strategy_rehearsal WHERE strategy_code = 'S2'", Integer.class);
        assertThat(rehearsals).isEqualTo(1);
    }

    @Test
    @DisplayName("处置完结后挂起策略可重新下发：新策略生效、旧策略同事务失效")
    void dispatchSucceedsAfterHandlingCompleted() {
        startHandling("H1", "25.00", "26.00", "65.00", "75.00");
        draftStrategy("S2", "24.00", "18.00", "70.00", "0.5", "5");
        assertThat(dispatch("S2", "REQ-S2", T0.plusSeconds(30 * 60)).status()).isEqualTo("SUSPENDED");

        alarmHandlingService.complete("H1", new AlarmHandlingDtos.CompleteRequest("REQ-H1-DONE"));

        // 挂起请求的 requestId 已绑定首次结果，重新下发须换新键。
        StrategyDtos.StrategyResponse redispatched =
                dispatch("S2", "REQ-S2B", T0.plusSeconds(30 * 60));
        assertThat(redispatched.status()).isEqualTo("DISPATCHED");
        assertThat(redispatched.effectiveTime()).isEqualTo(T0.plusSeconds(30 * 60));
        assertThat(strategyRepository.findByCode("S1").orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(rehearsalService.latest("S2").verdict()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("设定点恰在处置窗口边界视为兼容，直接放行下发")
    void boundaryOfHandlingWindowIsCompatible() {
        // S2 设定 24/18/70：温度窗口 [18,24] 两端恰等、湿度窗口恰等 70，均视为兼容。
        startHandling("H1", "18.00", "24.00", "70.00", "70.00");
        draftStrategy("S2", "24.00", "18.00", "70.00", "0.5", "5");

        StrategyDtos.StrategyResponse response = dispatch("S2", "REQ-S2", T0.plusSeconds(30 * 60));
        assertThat(response.status()).isEqualTo("DISPATCHED");
        assertThat(rehearsalService.latest("S2").verdict()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("并发下发撞上进行中处置：两个策略都挂起，旧策略保持生效，留档各一条")
    void concurrentDispatchWithInProgressHandlingBothSuspend() throws Exception {
        startHandling("H1", "25.00", "26.00", "65.00", "75.00");
        draftStrategy("S2", "24.00", "18.00", "70.00", "0.5", "5");
        draftStrategy("S3", "23.00", "17.00", "70.00", "0.5", "5");

        Instant target = T0.plusSeconds(30 * 60);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<StrategyDtos.StrategyResponse>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return dispatch("S2", "REQ-C2", target);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return dispatch("S3", "REQ-C3", target);
                    }));
            start.countDown();
            for (Future<StrategyDtos.StrategyResponse> f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS).status()).isEqualTo("SUSPENDED");
            }
        } finally {
            pool.shutdownNow();
        }
        // 挂起无副作用：S1 仍是唯一生效策略，S2/S3 均为挂起且各留档一条。
        Integer dispatched = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM climate_strategy WHERE zone_code = ? AND status = 'DISPATCHED'",
                Integer.class, ZONE);
        assertThat(dispatched).isEqualTo(1);
        assertThat(strategyRepository.findByCode("S1").orElseThrow().status()).isEqualTo("DISPATCHED");
        Integer suspended = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM climate_strategy WHERE zone_code = ? AND status = 'SUSPENDED'",
                Integer.class, ZONE);
        assertThat(suspended).isEqualTo(2);
        Integer rehearsals = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM strategy_rehearsal WHERE verdict = 'SUSPENDED'", Integer.class);
        assertThat(rehearsals).isEqualTo(2);
    }
}
