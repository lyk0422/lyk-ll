package com.example.starter.rolling;

import com.example.starter.rolling.api.dto.RollActualRequest;
import com.example.starter.rolling.api.dto.RollActualView;
import com.example.starter.rolling.api.dto.RollFreezeRequest;
import com.example.starter.rolling.api.dto.RollMountRequest;
import com.example.starter.rolling.api.dto.RollView;
import com.example.starter.rolling.service.BusinessException;
import com.example.starter.rolling.service.ConflictException;
import com.example.starter.rolling.service.RollLifeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轧辊寿命台账：上机、实绩回报、恰达上限禁排、超限拒收、首用标记、
 * 同辊同时刻去重、乱序归位复算、幂等/并发、日冻结迟到事件。
 */
class RollLifeIntegrationTest extends AbstractRollingIntegrationTest {

    @Autowired
    private RollLifeService rollService;

    private RollView mount(String rollNo, String limitKm) {
        return rollService.mount(new RollMountRequest("mount-" + rollNo, rollNo,
                new BigDecimal(limitKm), T0));
    }

    private RollActualRequest actual(String requestId, String rollNo, String km, int hour) {
        return new RollActualRequest(requestId, rollNo, new BigDecimal(km), T0.plusHours(hour));
    }

    @Test
    void 新辊上机后前5卷带首用标记第6卷起取消() {
        mount("R-WARM", "1000");

        for (int i = 1; i <= 6; i++) {
            RollActualView view = rollService.reportActual(actual("w-" + i, "R-WARM", "1", i));
            assertThat(view.accepted()).isTrue();
            assertThat(view.firstUse()).as("第 %d 卷首用标记", i).isEqualTo(i <= 5);
        }

        List<Boolean> flags = jdbc.queryForList(
                "SELECT first_use FROM roll_change_actual WHERE roll_no = 'R-WARM'"
                        + " ORDER BY recorded_at", Boolean.class);
        assertThat(flags).containsExactly(true, true, true, true, true, false);

        RollView roll = rollService.get("R-WARM");
        assertThat(roll.coilsSinceMount()).isEqualTo(6);
        assertThat(roll.totalKm()).isEqualByComparingTo("6");
    }

    @Test
    void 累计里程恰达周期上限即禁排且复算保持结论() {
        mount("R-EXACT", "10");

        RollActualView first = rollService.reportActual(actual("e-1", "R-EXACT", "6", 1));
        assertThat(first.banned()).isFalse();
        assertThat(first.remainingKm()).isEqualByComparingTo("4");

        RollActualView atLimit = rollService.reportActual(actual("e-2", "R-EXACT", "4", 2));
        assertThat(atLimit.accepted()).isTrue();
        assertThat(atLimit.totalKm()).isEqualByComparingTo("10");
        assertThat(atLimit.remainingKm()).isEqualByComparingTo("0");
        assertThat(atLimit.banned()).isTrue();

        RollView roll = rollService.get("R-EXACT");
        assertThat(roll.status()).isEqualTo("BANNED");

        // 恰达上限后任何正里程实绩都超限，不得入库。
        assertThatThrownBy(() -> rollService.reportActual(actual("e-3", "R-EXACT", "0.5", 3)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超限实绩不得入库");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R-EXACT'",
                Integer.class)).isEqualTo(2);

        // 复算后里程与禁排结论保持。
        RollView recomputed = rollService.recompute("R-EXACT");
        assertThat(recomputed.totalKm()).isEqualByComparingTo("10");
        assertThat(recomputed.status()).isEqualTo("BANNED");
    }

    @Test
    void 超限实绩拒绝入库且里程不变() {
        mount("R-OVER", "10");
        rollService.reportActual(actual("ov-1", "R-OVER", "6", 1));

        assertThatThrownBy(() -> rollService.reportActual(actual("ov-2", "R-OVER", "5", 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过周期上限")
                .hasMessageContaining("剩余里程 4");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R-OVER'",
                Integer.class)).isEqualTo(1);
        RollView roll = rollService.get("R-OVER");
        assertThat(roll.totalKm()).isEqualByComparingTo("6");
        assertThat(roll.status()).isEqualTo("IN_SERVICE");
    }

    @Test
    void 寿命到期轧辊申请上机拒绝并给出剩余里程() {
        mount("R-EXPIRED", "10");
        rollService.reportActual(actual("x-1", "R-EXPIRED", "10", 1));

        assertThatThrownBy(() -> rollService.mount(
                new RollMountRequest("mount-R-EXPIRED-2", "R-EXPIRED", new BigDecimal("10"), T0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("寿命到期")
                .hasMessageContaining("禁止上机")
                .hasMessageContaining("剩余里程 0");

        // 拒绝不产生台账写入：仍只有一次上机记录，里程不变。
        RollView roll = rollService.get("R-EXPIRED");
        assertThat(roll.totalKm()).isEqualByComparingTo("10");
        assertThat(roll.status()).isEqualTo("BANNED");
    }

    @Test
    void 实绩缺轧辊号直接拒绝提示补录且不增加里程() {
        mount("R-NOROLL", "100");

        assertThatThrownBy(() -> rollService.reportActual(
                new RollActualRequest("nr-1", null, new BigDecimal("3"), T0.plusHours(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少轧辊号")
                .hasMessageContaining("补录");

        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM roll_change_actual",
                Integer.class)).isZero();
        assertThat(rollService.get("R-NOROLL").totalKm()).isEqualByComparingTo("0");

        // 拒绝未登记幂等记录：补录轧辊号后可用同 requestId 重报成功。
        RollActualView corrected = rollService.reportActual(
                new RollActualRequest("nr-1", "R-NOROLL", new BigDecimal("3"), T0.plusHours(1)));
        assertThat(corrected.accepted()).isTrue();
        assertThat(rollService.get("R-NOROLL").totalKm()).isEqualByComparingTo("3");
    }

    @Test
    void 同一轧辊同时刻实绩去重只入库一张() {
        mount("R-DEDUP", "100");

        RollActualView first = rollService.reportActual(actual("d-1", "R-DEDUP", "3", 1));
        RollActualView dup = rollService.reportActual(actual("d-2", "R-DEDUP", "3", 1));

        assertThat(first.accepted()).isTrue();
        assertThat(dup.accepted()).isFalse();
        assertThat(dup.rollingKm()).isEqualByComparingTo("3");
        assertThat(dup.totalKm()).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R-DEDUP'",
                Integer.class)).isEqualTo(1);
        assertThat(rollService.get("R-DEDUP").totalKm()).isEqualByComparingTo("3");
    }

    @Test
    void 乱序回传按时刻归位台账可复算且重复查询稳定() {
        mount("R-ORDER", "100");

        // 先回报晚时刻，再乱序回报早时刻，均按 recorded_at 归位。
        RollActualView later = rollService.reportActual(actual("o-1", "R-ORDER", "3", 12));
        RollActualView earlier = rollService.reportActual(actual("o-2", "R-ORDER", "2", 9));

        assertThat(later.accepted()).isTrue();
        assertThat(earlier.accepted()).isTrue();
        assertThat(earlier.firstUse()).isTrue();

        RollView q1 = rollService.get("R-ORDER");
        RollView q2 = rollService.get("R-ORDER");
        assertThat(q1).isEqualTo(q2);
        assertThat(q1.totalKm()).isEqualByComparingTo("5");

        // 复算由实绩记录重算，结果与台账一致且两卷均在首用烫辊位。
        RollView recomputed = rollService.recompute("R-ORDER");
        assertThat(recomputed.totalKm()).isEqualByComparingTo("5");
        assertThat(recomputed.coilsSinceMount()).isEqualTo(2);
        List<Boolean> flags = jdbc.queryForList(
                "SELECT first_use FROM roll_change_actual WHERE roll_no = 'R-ORDER'"
                        + " ORDER BY recorded_at", Boolean.class);
        assertThat(flags).containsExactly(true, true);
        assertThat(rollService.get("R-ORDER")).isEqualTo(recomputed);
    }

    @Test
    void 同键同参返回首次结果同键异参409() {
        mount("R-IDEM", "100");

        RollActualRequest req = actual("i-1", "R-IDEM", "3", 1);
        RollActualView first = rollService.reportActual(req);
        RollActualView replay = rollService.reportActual(req);

        assertThat(first.accepted()).isTrue();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R-IDEM'",
                Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> rollService.reportActual(actual("i-1", "R-IDEM", "4", 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("i-1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R-IDEM'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void 并发回报同一轧辊里程原子累计() throws Exception {
        mount("R-CONC", "1000");
        int n = 8;

        List<Outcome> outcomes = runConcurrent(n, i -> actual("c-" + i, "R-CONC", "1", i + 1));

        assertThat(outcomes).allMatch(o -> o.view != null && o.view.accepted(),
                "全部并发回报成功");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R-CONC'",
                Integer.class)).isEqualTo(n);
        RollView roll = rollService.get("R-CONC");
        assertThat(roll.totalKm()).isEqualByComparingTo(String.valueOf(n));
        assertThat(roll.coilsSinceMount()).isEqualTo(n);
    }

    @Test
    void 并发同辊同时刻同键回报只入库一张实绩() throws Exception {
        mount("R-CONCID", "100");
        int n = 4;

        List<Outcome> outcomes = runConcurrent(n, i -> actual("cc-1", "R-CONCID", "2", 1));

        for (Outcome outcome : outcomes) {
            assertThat(outcome.view != null || outcome.conflict)
                    .as("并发同键只允许首次结果或 409，实际：%s", outcome.error).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R-CONCID'",
                Integer.class)).isEqualTo(1);
        assertThat(rollService.get("R-CONCID").totalKm()).isEqualByComparingTo("2");

        // 并发落定的首次结果可用同键同参稳定重放。
        RollActualView replay = rollService.reportActual(actual("cc-1", "R-CONCID", "2", 1));
        assertThat(replay.rollingKm()).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R-CONCID'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void 日冻结后迟到实绩只记事件不改已冻结里程() {
        mount("R-LATE", "100");
        rollService.reportActual(actual("lt-1", "R-LATE", "3", 1));
        RollView frozen = rollService.freeze("R-LATE", new RollFreezeRequest("fz-1"));
        assertThat(frozen.frozenAt()).isNotNull();
        assertThat(frozen.totalKm()).isEqualByComparingTo("3");

        RollActualRequest lateReq = actual("lt-2", "R-LATE", "5", 2);
        RollActualView late = rollService.reportActual(lateReq);

        assertThat(late.late()).isTrue();
        assertThat(late.accepted()).isFalse();
        assertThat(late.rollingKm()).isNull();
        assertThat(late.totalKm()).isEqualByComparingTo("3");

        // 已冻结里程不变，实绩不入库，只登记一条迟到事件。
        assertThat(rollService.get("R-LATE").totalKm()).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R-LATE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_late_event WHERE roll_no = 'R-LATE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT rolling_km FROM roll_late_event WHERE roll_no = 'R-LATE'",
                BigDecimal.class)).isEqualByComparingTo("5");

        // 迟到回报同键重放幂等，不重复登记事件。
        RollActualView replay = rollService.reportActual(lateReq);
        assertThat(replay.late()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_late_event WHERE roll_no = 'R-LATE'",
                Integer.class)).isEqualTo(1);
    }

    // ---------------- 并发辅助 ----------------

    private record Outcome(RollActualView view, boolean conflict, String error) {
    }

    private interface ActualTask {
        RollActualRequest request(int index);
    }

    private List<Outcome> runConcurrent(int n, ActualTask task) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                    try {
                        return new Outcome(rollService.reportActual(task.request(index)),
                                false, null);
                    } catch (ConflictException e) {
                        return new Outcome(null, true, e.getMessage());
                    } catch (Exception e) {
                        return new Outcome(null, false,
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
