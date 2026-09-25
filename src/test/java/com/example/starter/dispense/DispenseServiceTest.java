package com.example.starter.dispense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.error.ApiExceptionHandler.BusinessRejectionException;
import com.example.starter.error.ApiExceptionHandler.IdempotencyConflictException;
import com.example.starter.error.ApiExceptionHandler.StateConflictException;
import com.example.starter.motherliquor.MotherLiquorService;
import com.example.starter.support.BaseIntegrationTest;

/**
 * 分装计算、双人核对与发药勾稽集成测试（H2 事务与并发边界）。
 * 场景统一使用 TEST-1H（半衰期 3600 秒）：母液 1000 MBq @ T0，给药时刻 T0+1h 推算活度 500 MBq。
 */
class DispenseServiceTest extends BaseIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");
    private static final Instant ADMINISTER_AT = Instant.parse("2026-09-26T09:00:00Z");

    @Autowired
    private DispenseService dispenseService;
    @Autowired
    private MotherLiquorService motherLiquorService;

    private long motherLiquorId;

    @BeforeEach
    void setUp() {
        motherLiquorId = motherLiquorService.register(testNuclideId(), 1000.0, T0).id();
    }

    @Test
    void 分装活度按衰变公式推算至给药时刻() {
        var view = dispenseService.calculate("dp-req-1", motherLiquorId, 500.0, ADMINISTER_AT);
        assertThat(view.calculatedActivityMbq()).isEqualTo(500.0);
        assertThat(view.deviation()).isEqualTo(0.0);
        assertThat(view.status()).isEqualTo("PENDING_CHECK");
    }

    @Test
    void 分装偏差恰为正10百分比合法() {
        // 处方 = 500 / 1.1，推算/处方 - 1 = 0.1
        double prescription = 500.0 / 1.1;
        var view = dispenseService.calculate("dp-req-2", motherLiquorId, prescription, ADMINISTER_AT);
        assertThat(view.deviation()).isCloseTo(0.1, within(1e-9));
        assertThat(view.status()).isEqualTo("PENDING_CHECK");
    }

    @Test
    void 分装偏差恰为负10百分比合法() {
        // 处方 = 500 / 0.9，推算/处方 - 1 = -0.1
        double prescription = 500.0 / 0.9;
        var view = dispenseService.calculate("dp-req-3", motherLiquorId, prescription, ADMINISTER_AT);
        assertThat(view.deviation()).isCloseTo(-0.1, within(1e-9));
        assertThat(view.status()).isEqualTo("PENDING_CHECK");
    }

    @Test
    void 分装偏差超过10百分比拒绝并记终态() {
        double prescription = 500.0 / 1.10001;
        var view = dispenseService.calculate("dp-req-4", motherLiquorId, prescription, ADMINISTER_AT);
        assertThat(view.status()).isEqualTo("REJECTED");
        // REJECTED 为终态：不允许核对
        assertThatThrownBy(() -> dispenseService.check("dp-req-4-c1", view.id(), "checker-a"))
                .isInstanceOf(StateConflictException.class);
    }

    @Test
    void 分装同键重放与同键异参() {
        var first = dispenseService.calculate("dp-req-5", motherLiquorId, 500.0, ADMINISTER_AT);
        var replay = dispenseService.calculate("dp-req-5", motherLiquorId, 500.0, ADMINISTER_AT);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> dispenseService.calculate("dp-req-5", motherLiquorId, 501.0,
                ADMINISTER_AT))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM dispense").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void 双人核对须两名不同人员先后核对() {
        var dispense = dispenseService.calculate("dp-req-6", motherLiquorId, 500.0, ADMINISTER_AT);
        var first = dispenseService.check("dp-req-6-c1", dispense.id(), "checker-a");
        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.dispenseStatus()).isEqualTo("FIRST_CHECKED");
        // 单人重复核对不生效
        assertThatThrownBy(() -> dispenseService.check("dp-req-6-c2", dispense.id(), "checker-a"))
                .isInstanceOf(StateConflictException.class);
        var second = dispenseService.check("dp-req-6-c3", dispense.id(), "checker-b");
        assertThat(second.seq()).isEqualTo(2);
        assertThat(second.dispenseStatus()).isEqualTo("CHECKED");
        // 核对链可复算
        var detail = dispenseService.detail(dispense.id());
        assertThat(detail.checks()).extracting(DispenseService.CheckView::checkerId)
                .containsExactly("checker-a", "checker-b");
    }

    @Test
    void 未完成双人核对不允许发药() {
        var dispense = dispenseService.calculate("dp-req-7", motherLiquorId, 500.0, ADMINISTER_AT);
        dispenseService.check("dp-req-7-c1", dispense.id(), "checker-a");
        assertThatThrownBy(() -> dispenseService.deliver("dp-req-7-d1", dispense.id(), 0.0,
                ADMINISTER_AT))
                .isInstanceOf(StateConflictException.class);
    }

    /**
     * 构造一个已完成双人核对、处方 460 MBq 的批次。
     * 给药时刻 T0+1h：母液推算 500 MBq，偏差 (500-460)/460 ≈ 8.7% 合法；
     * 勾稽基准时刻取给药时刻：初始推算 500，分装总量 460，剩余须为 40。
     */
    private long checkedDispense(String keyPrefix) {
        var dispense = dispenseService.calculate(keyPrefix + "-calc", motherLiquorId, 460.0,
                ADMINISTER_AT);
        dispenseService.check(keyPrefix + "-c1", dispense.id(), "checker-a");
        dispenseService.check(keyPrefix + "-c2", dispense.id(), "checker-b");
        return dispense.id();
    }

    @Test
    void 发药勾稽成立且扣减原子生效() {
        long dispenseId = checkedDispense("dp-req-8");
        var delivery = dispenseService.deliver("dp-req-8-d1", dispenseId, 40.0, ADMINISTER_AT);
        assertThat(delivery.initialAtRefMbq()).isEqualTo(500.0);
        assertThat(delivery.dispensedAtRefMbq()).isEqualTo(460.0);
        assertThat(delivery.decayResidualMbq()).isEqualTo(0.0);
        // 发药与母液活度扣减同事务：库存由 1000 扣减为实测剩余 40
        var mother = motherLiquorService.require(motherLiquorId);
        assertThat(mother.remainingActivityMbq()).isEqualTo(40.0);
        assertThat(mother.remainingMeasuredAt()).isEqualTo(ADMINISTER_AT);
        assertThat(dispenseService.detail(dispenseId).dispense().status()).isEqualTo("DELIVERED");
        // 勾稽台账可复算：初始=分装+剩余+衰变残量
        var ledger = motherLiquorService.ledger(motherLiquorId);
        assertThat(ledger).hasSize(1);
        var entry = ledger.get(0);
        assertThat(entry.initialActivityMbq()).isEqualTo(entry.dispensedActivityMbq()
                + entry.remainingActivityMbq() + entry.decayResidualMbq());
    }

    @Test
    void 发药勾稽不等拒绝且库存不变() {
        long dispenseId = checkedDispense("dp-req-9");
        assertThatThrownBy(() -> dispenseService.deliver("dp-req-9-d1", dispenseId, 41.0,
                ADMINISTER_AT))
                .isInstanceOf(BusinessRejectionException.class);
        // 拒绝发药：库存与批次状态均不变
        assertThat(motherLiquorService.require(motherLiquorId).remainingActivityMbq())
                .isEqualTo(1000.0);
        assertThat(dispenseService.detail(dispenseId).dispense().status()).isEqualTo("CHECKED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM delivery").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger").query(Long.class).single()).isZero();
    }

    @Test
    void 发药同键重放返回首次结果且不重复扣减() {
        long dispenseId = checkedDispense("dp-req-10");
        var first = dispenseService.deliver("dp-req-10-d1", dispenseId, 40.0, ADMINISTER_AT);
        var replay = dispenseService.deliver("dp-req-10-d1", dispenseId, 40.0, ADMINISTER_AT);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(motherLiquorService.require(motherLiquorId).remainingActivityMbq()).isEqualTo(40.0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM delivery").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger").query(Long.class).single()).isEqualTo(1);
        // 同键异参返回 409
        assertThatThrownBy(() -> dispenseService.deliver("dp-req-10-d1", dispenseId, 39.0,
                ADMINISTER_AT))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void 发药完成为终态且迟到测量只记迟到事件() {
        long dispenseId = checkedDispense("dp-req-11");
        // 完成前测量为正常事件
        var before = dispenseService.recordMeasurement("dp-req-11-m1", dispenseId, 460.0,
                ADMINISTER_AT);
        assertThat(before.late()).isFalse();
        dispenseService.deliver("dp-req-11-d1", dispenseId, 40.0, ADMINISTER_AT);
        // 终态后重复完成（异键）冲突
        assertThatThrownBy(() -> dispenseService.deliver("dp-req-11-d2", dispenseId, 40.0,
                ADMINISTER_AT))
                .isInstanceOf(StateConflictException.class);
        // 迟到测量数据只记迟到事件
        var late = dispenseService.recordMeasurement("dp-req-11-m2", dispenseId, 39.5,
                Instant.parse("2026-09-26T09:05:00Z"));
        assertThat(late.late()).isTrue();
        // 迟到事件不改变库存与台账
        assertThat(motherLiquorService.require(motherLiquorId).remainingActivityMbq()).isEqualTo(40.0);
        assertThat(motherLiquorService.ledger(motherLiquorId)).hasSize(1);
    }

    @Test
    void 并发发药唯一受理且库存只扣减一次() throws Exception {
        long dispenseId = checkedDispense("dp-req-12");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        try {
                            dispenseService.deliver("dp-req-12-d" + i, dispenseId, 40.0,
                                    ADMINISTER_AT);
                            return "OK";
                        } catch (StateConflictException e) {
                            return "CONFLICT";
                        }
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new java.util.ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn("OK"::equals).hasSize(1);
            assertThat(results).filteredOn("CONFLICT"::equals).hasSize(threads - 1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM delivery WHERE dispense_id = ?")
                    .param(dispenseId).query(Long.class).single()).isEqualTo(1);
            assertThat(motherLiquorService.require(motherLiquorId).remainingActivityMbq())
                    .isEqualTo(40.0);
        } finally {
            pool.shutdownNow();
        }
    }
}
