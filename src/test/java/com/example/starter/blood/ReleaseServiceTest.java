package com.example.starter.blood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.blood.domain.TestOutcome;
import com.example.starter.blood.domain.TestType;
import com.example.starter.blood.domain.UnitStatus;
import com.example.starter.blood.repo.IssueRecordRepository;
import com.example.starter.blood.repo.LateEventRepository;
import com.example.starter.blood.service.ApiException;
import com.example.starter.blood.service.BloodUnitService;
import com.example.starter.blood.service.ReleaseResult;
import com.example.starter.blood.service.ReleaseService;

/**
 * 放行服务测试：幂等、效期边界、终态不复活、并发唯一放行、原子性与回传/过期竞态。
 */
class ReleaseServiceTest extends BloodServiceTestBase {

    private static final Instant WINDOW_START = Instant.parse("2026-09-24T08:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-09-24T12:00:00Z");
    private static final LocalDate EXPIRE_DATE = LocalDate.of(2026, 9, 25);

    @Autowired
    private BloodUnitService unitService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private IssueRecordRepository issueRepository;

    @Autowired
    private LateEventRepository lateEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private void checkInReleasable(String barcode) {
        unitService.checkIn(barcode, "红细胞", EXPIRE_DATE);
        // 乱序回传：复检先到，初检后到
        unitService.applyTestResult(barcode, TestType.RECHECK, TestOutcome.QUALIFIED);
        unitService.applyTestResult(barcode, TestType.INITIAL, TestOutcome.QUALIFIED);
        assertThat(unitService.getUnit(barcode).status()).isEqualTo(UnitStatus.RELEASABLE);
    }

    @Test
    void releaseSucceedsAtomicallyAndSameKeySameParamsReplays() {
        checkInReleasable("RL-001");

        ReleaseResult first = releaseService.release("REQ-1", "RL-001", WINDOW_START, WINDOW_END);
        assertThat(first.outcome()).isEqualTo(ReleaseService.OUTCOME_RELEASED);
        assertThat(first.unitStatus()).isEqualTo(UnitStatus.RELEASED);
        assertThat(first.replayed()).isFalse();
        // 库存扣减与发血记录同生
        assertThat(unitService.getUnit("RL-001").status()).isEqualTo(UnitStatus.RELEASED);
        assertThat(issueRepository.countByBarcode("RL-001")).isEqualTo(1);

        // 同键同参重放：返回首次结果，不重复扣减、不重复发血
        ReleaseResult replay = releaseService.release("REQ-1", "RL-001", WINDOW_START, WINDOW_END);
        assertThat(replayed(replay)).isTrue();
        assertThat(replay.outcome()).isEqualTo(ReleaseService.OUTCOME_RELEASED);
        assertThat(issueRepository.countByBarcode("RL-001")).isEqualTo(1);
    }

    private static boolean replayed(ReleaseResult result) {
        return result.replayed();
    }

    @Test
    void sameKeyDifferentParamsReturns409AndKeepsState() {
        checkInReleasable("RL-002");
        releaseService.release("REQ-2", "RL-002", WINDOW_START, WINDOW_END);
        assertThat(unitService.getUnit("RL-002").status()).isEqualTo(UnitStatus.RELEASED);

        // 同键异参：409，且不改变库存状态
        assertThatThrownBy(() -> releaseService.release("REQ-2", "RL-002", WINDOW_START,
                Instant.parse("2026-09-24T18:00:00Z")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(unitService.getUnit("RL-002").status()).isEqualTo(UnitStatus.RELEASED);
        assertThat(issueRepository.countByBarcode("RL-002")).isEqualTo(1);
    }

    @Test
    void releaseOnNonReleasableUnitRejectedWithRealStatus() {
        unitService.checkIn("RL-003", "血浆", EXPIRE_DATE);
        ReleaseResult result = releaseService.release("REQ-3", "RL-003", WINDOW_START, WINDOW_END);
        assertThat(result.outcome()).isEqualTo(ReleaseService.OUTCOME_REJECTED);
        assertThat(result.unitStatus()).isEqualTo(UnitStatus.PENDING);
        assertThat(issueRepository.countByBarcode("RL-003")).isZero();
    }

    @Test
    void releaseAtExactExpiryBoundaryIsAllowed() {
        checkInReleasable("RL-004");
        // 恰在效期边界时刻（效期当日 23:59:59 UTC）仍可放行
        clock.setInstant(Instant.parse("2026-09-25T23:59:59Z"));
        ReleaseResult result = releaseService.release("REQ-4", "RL-004", WINDOW_START, WINDOW_END);
        assertThat(result.outcome()).isEqualTo(ReleaseService.OUTCOME_RELEASED);
        assertThat(unitService.getUnit("RL-004").status()).isEqualTo(UnitStatus.RELEASED);
    }

    @Test
    void releaseAfterExpiryBoundaryRejectedAndMarkedExpiredDiscarded() {
        checkInReleasable("RL-005");
        // 效期当日 23:59:59 之后禁止放行
        clock.setInstant(Instant.parse("2026-09-25T23:59:59.001Z"));
        ReleaseResult result = releaseService.release("REQ-5", "RL-005", WINDOW_START, WINDOW_END);
        assertThat(result.outcome()).isEqualTo(ReleaseService.OUTCOME_REJECTED);
        assertThat(result.unitStatus()).isEqualTo(UnitStatus.EXPIRED_DISCARDED);
        assertThat(unitService.getUnit("RL-005").status()).isEqualTo(UnitStatus.EXPIRED_DISCARDED);
        assertThat(issueRepository.countByBarcode("RL-005")).isZero();

        // 过期报废为终态：后续合格回传不复活，只记迟到事件
        var late = unitService.applyTestResult("RL-005", TestType.RECHECK, TestOutcome.QUALIFIED);
        assertThat(late.late()).isTrue();
        assertThat(unitService.getUnit("RL-005").status()).isEqualTo(UnitStatus.EXPIRED_DISCARDED);
        assertThat(lateEventRepository.countByBarcode("RL-005")).isEqualTo(1);
    }

    @Test
    void concurrentReleaseOnSameBarcodeSucceedsOnlyOnce() throws Exception {
        checkInReleasable("RL-006");
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        Callable<ReleaseResult> taskA = () -> guardedRelease(ready, go, "REQ-6A", "RL-006");
        Callable<ReleaseResult> taskB = () -> guardedRelease(ready, go, "REQ-6B", "RL-006");
        try {
            Future<ReleaseResult> futureA = pool.submit(taskA);
            Future<ReleaseResult> futureB = pool.submit(taskB);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<ReleaseResult> results = List.of(futureA.get(30, TimeUnit.SECONDS),
                    futureB.get(30, TimeUnit.SECONDS));
            // 只允许成功一次，另一请求得到该单位当前真实状态
            assertThat(results.stream().filter(r -> r.outcome().equals(ReleaseService.OUTCOME_RELEASED)).count())
                    .isEqualTo(1);
            ReleaseResult loser = results.stream()
                    .filter(r -> r.outcome().equals(ReleaseService.OUTCOME_REJECTED)).findFirst().orElseThrow();
            assertThat(loser.unitStatus()).isEqualTo(UnitStatus.RELEASED);
            assertThat(unitService.getUnit("RL-006").status()).isEqualTo(UnitStatus.RELEASED);
            assertThat(issueRepository.countByBarcode("RL-006")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private ReleaseResult guardedRelease(CountDownLatch ready, CountDownLatch go, String requestId, String barcode)
            throws InterruptedException {
        ready.countDown();
        assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
        return releaseService.release(requestId, barcode, WINDOW_START, WINDOW_END);
    }

    @Test
    void raceBetweenRecheckCallbackAndExpiryDeterminationYieldsUniqueTerminalState() throws Exception {
        // 隔离态单位（初检合格、复检未回），时钟已越过效期边界
        unitService.checkIn("RL-007", "红细胞", EXPIRE_DATE);
        unitService.applyTestResult("RL-007", TestType.INITIAL, TestOutcome.QUALIFIED);
        assertThat(unitService.getUnit("RL-007").status()).isEqualTo(UnitStatus.ISOLATED);
        clock.setInstant(Instant.parse("2026-09-26T00:00:00Z"));

        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        Callable<Object> callback = () -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return unitService.applyTestResult("RL-007", TestType.RECHECK, TestOutcome.QUALIFIED);
        };
        Callable<Object> release = () -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return releaseService.release("REQ-7", "RL-007", WINDOW_START, WINDOW_END);
        };
        try {
            Future<Object> futureCallback = pool.submit(callback);
            Future<Object> futureRelease = pool.submit(release);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            futureCallback.get(30, TimeUnit.SECONDS);
            futureRelease.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 以时间先后形成唯一终态：过期报废，不得出现既放行又报废
        assertThat(unitService.getUnit("RL-007").status()).isEqualTo(UnitStatus.EXPIRED_DISCARDED);
        assertThat(issueRepository.countByBarcode("RL-007")).isZero();
        // 若过期判定先到达，复检合格回传被记为迟到事件；否则单位先可放行再被过期判定终结
        assertThat(lateEventRepository.countByBarcode("RL-007")).isIn(0L, 1L);
    }

    @Test
    void rollbackRemovesBothStockDeductionAndIssueRecord() {
        checkInReleasable("RL-008");
        // 外层事务回滚：库存扣减与发血记录同灭
        transactionTemplate.execute(status -> {
            ReleaseResult result = releaseService.release("REQ-8", "RL-008", WINDOW_START, WINDOW_END);
            assertThat(result.outcome()).isEqualTo(ReleaseService.OUTCOME_RELEASED);
            status.setRollbackOnly();
            return null;
        });
        assertThat(unitService.getUnit("RL-008").status()).isEqualTo(UnitStatus.RELEASABLE);
        assertThat(issueRepository.countByBarcode("RL-008")).isZero();
        // 回滚后同一 requestId 可重新放行成功（台账未残留）
        ReleaseResult retry = releaseService.release("REQ-8", "RL-008", WINDOW_START, WINDOW_END);
        assertThat(retry.outcome()).isEqualTo(ReleaseService.OUTCOME_RELEASED);
        assertThat(issueRepository.countByBarcode("RL-008")).isEqualTo(1);
    }
}
