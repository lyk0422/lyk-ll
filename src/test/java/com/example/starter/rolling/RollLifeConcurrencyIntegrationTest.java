package com.example.starter.rolling;

import com.example.starter.rolling.api.dto.RollActualView;
import com.example.starter.rolling.service.ConflictException;
import com.example.starter.rolling.service.RollLifeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一轧辊并发实绩回报：同时刻只允许一张实绩入库（唯一约束裁决），
 * 里程不重复累计；不同时刻并发回报全部入库且里程等于总和。
 */
class RollLifeConcurrencyIntegrationTest extends AbstractRollingIntegrationTest {

    @Autowired
    private RollLifeService rollService;

    @Test
    void 同辊同时刻并发回报只允许一张实绩入库() throws Exception {
        seedRoll("RC1", "10", "0", 0, true, false);

        List<ReportOutcome> outcomes = runConcurrentReports(
                new ReportTask("rc-1-a", "RC1", "3", 1),
                new ReportTask("rc-1-b", "RC1", "3", 1));

        // 一张入库（accepted=true），另一张按首次结果幂等返回（accepted=false）或 409。
        long accepted = outcomes.stream().filter(o -> o.view != null && o.view.accepted()).count();
        long dedup = outcomes.stream().filter(o -> o.view != null && !o.view.accepted()).count();
        long conflict = outcomes.stream().filter(o -> o.conflict).count();
        assertThat(accepted).as("同时刻只允许一张实绩入库").isEqualTo(1);
        assertThat(dedup + conflict).as("另一张去重或 409").isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'RC1'", Integer.class))
                .isEqualTo(1);
        // 里程不重复累计。
        assertThat(jdbc.queryForObject(
                "SELECT total_km FROM roll_ledger WHERE roll_no = 'RC1'", BigDecimal.class))
                .isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject(
                "SELECT coils_used FROM roll_ledger WHERE roll_no = 'RC1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 同辊异时刻并发回报全部入库且里程为总和() throws Exception {
        seedRoll("RC2", "10", "0", 0, true, false);

        List<ReportOutcome> outcomes = runConcurrentReports(
                new ReportTask("rc-2-a", "RC2", "2", 1),
                new ReportTask("rc-2-b", "RC2", "3", 2),
                new ReportTask("rc-2-c", "RC2", "4", 3));

        for (ReportOutcome outcome : outcomes) {
            assertThat(outcome.view).as("异时刻并发回报均应成功：%s", outcome.error).isNotNull();
            assertThat(outcome.view.accepted()).isTrue();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'RC2'", Integer.class))
                .isEqualTo(3);
        // 台账行锁串行化并发累加，最终里程等于三笔总和。
        assertThat(jdbc.queryForObject(
                "SELECT total_km FROM roll_ledger WHERE roll_no = 'RC2'", BigDecimal.class))
                .isEqualByComparingTo("9");
        assertThat(rollService.getLedger("RC2").recomputedTotalKm()).isEqualByComparingTo("9");
    }

    @Test
    void 同键并发首调竞争后重放取得首次结果() throws Exception {
        seedRoll("RC3", "10", "0", 0, true, false);

        List<ReportOutcome> outcomes = runConcurrentReports(
                new ReportTask("rc-3", "RC3", "2", 1),
                new ReportTask("rc-3", "RC3", "2", 1));

        long success = outcomes.stream().filter(o -> o.view != null).count();
        assertThat(success).as("同键并发至少一个成功").isGreaterThanOrEqualTo(1);

        // 用相同 requestId 重放：无论竞争结果如何都取得首次结果。
        RollActualView replay = rollService.reportActual(rollActual("rc-3", "RC3", "2", 1));
        assertThat(replay.rollingKm()).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'RC3'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT total_km FROM roll_ledger WHERE roll_no = 'RC3'", BigDecimal.class))
                .isEqualByComparingTo("2");
    }

    private record ReportTask(String requestId, String rollNo, String km, int hourOffset) {
    }

    private record ReportOutcome(RollActualView view, boolean conflict, String error) {
    }

    private List<ReportOutcome> runConcurrentReports(ReportTask... tasks) throws Exception {
        int n = tasks.length;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<ReportOutcome>> futures = new ArrayList<>();
            for (ReportTask task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                    try {
                        return new ReportOutcome(rollService.reportActual(
                                rollActual(task.requestId(), task.rollNo(), task.km(),
                                        task.hourOffset())), false, null);
                    } catch (ConflictException e) {
                        return new ReportOutcome(null, true, e.getMessage());
                    } catch (Exception e) {
                        return new ReportOutcome(null, false,
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ReportOutcome> outcomes = new ArrayList<>();
            for (Future<ReportOutcome> f : futures) {
                ReportOutcome outcome = f.get(30, TimeUnit.SECONDS);
                assertThat(outcome.view() != null || outcome.conflict())
                        .as("并发回报只允许成功或 409，实际：%s", outcome.error()).isTrue();
                outcomes.add(outcome);
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
