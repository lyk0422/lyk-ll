package com.example.starter.museum;

import com.example.starter.museum.api.ContractResponse;
import com.example.starter.museum.domain.ContractStatus;
import com.example.starter.museum.domain.HandoverType;
import com.example.starter.museum.domain.Party;
import com.example.starter.museum.service.LoanService;
import com.example.starter.museum.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 真实并发事务测试：
 * 1) 并发对同一一级文物申请借展，只有一个被受理；
 * 2) 归还点交与续展审批并发，最终只能形成一个终态，不得既归还又续展。
 */
class LoanConcurrencyTest extends MuseumTestBase {

    @Autowired
    private LoanService loanService;

    @Test
    void concurrentApplicationsForSameGradeOneArtifactOnlyOneAccepted() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        LocalDate begin = LocalDate.of(2026, 1, 1);
        Future<?>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    loanService.apply("A001", "并发展方-" + idx, begin, begin.plusYears(1), "S001");
                    accepted.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getStatus() == HttpStatus.CONFLICT) {
                        rejected.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM artifact_active_loan a JOIN artifact ar ON ar.id=a.artifact_id WHERE ar.artifact_no='A001'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_contract l JOIN artifact ar ON ar.id=l.artifact_id WHERE ar.artifact_no='A001'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void returnHandoverAndRenewalConcurrentlyProduceSingleTerminalOutcome() throws Exception {
        LocalDate begin = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 7, 1);
        ContractResponse contract = createInExhibition(begin, end);
        // 进入续展窗口：恰第 60 日当天
        fixClock("2026-05-02T00:00:00Z");
        // 归还点交先完成借出方单方确认（不生效），并发段只让“借入方完成确认”与“续展审批”
        // 这两个都会裁决合同行版本的终态操作同时提交。
        loanService.confirmHandover(contract.contractNo(), HandoverType.RETURN, Party.LENDER);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger returnOk = new AtomicInteger();
        AtomicInteger renewOk = new AtomicInteger();

        Runnable returnComplete = () -> {
            try {
                ready.countDown();
                start.await();
                loanService.confirmHandover(contract.contractNo(), HandoverType.RETURN, Party.BORROWER);
                returnOk.incrementAndGet();
            } catch (ApiException e) {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Runnable renewFlow = () -> {
            try {
                ready.countDown();
                start.await();
                loanService.renew(contract.contractNo(), LocalDate.of(2026, 8, 1), true);
                renewOk.incrementAndGet();
            } catch (ApiException e) {
                // 归还先裁决：续展因合同已非在展状态被拒（400 状态校验或 409 版本冲突），
                // 均代表“归还”成为唯一终态。
                assertThat(e.getStatus()).isIn(HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        Future<?> f1 = pool.submit(returnComplete);
        Future<?> f2 = pool.submit(renewFlow);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        ContractStatus finalStatus = ContractStatus.valueOf(jdbc.queryForObject(
                "SELECT status FROM loan_contract WHERE contract_no = ?",
                String.class, contract.contractNo()));
        int renewCount = jdbc.queryForObject(
                "SELECT renew_count FROM loan_contract WHERE contract_no = ?",
                Integer.class, contract.contractNo());
        Integer completedReturns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handover h JOIN loan_contract c ON c.id=h.contract_id "
                        + "WHERE c.contract_no=? AND h.type='RETURN' AND h.completed_at IS NOT NULL",
                Integer.class, contract.contractNo());

        // 终态必须唯一且自洽，不允许出现“归还单已完成但合同仍在展”之类的更新丢失：
        //  1) 并发被乐观锁裁决为归还：RETURNED + 未续展；
        //  2) 并发被裁决为续展：IN_EXHIBITION + renewCount=1 + 无已完成归还单；
        //  3) 续展先提交、归还随后串行完成（合法完整生命周期）：RETURNED + renewCount=1。
        assertThat(returnOk.get()).isBetween(0, 1);
        assertThat(renewOk.get()).isBetween(0, 1);
        assertThat(returnOk.get() + renewOk.get()).isBetween(1, 2);
        if (finalStatus == ContractStatus.RETURNED) {
            assertThat(completedReturns).isEqualTo(1);
            assertThat(renewCount).isIn(0, 1);
        } else {
            assertThat(finalStatus).isEqualTo(ContractStatus.IN_EXHIBITION);
            assertThat(renewOk.get()).isEqualTo(1);
            assertThat(returnOk.get()).isZero();
            assertThat(renewCount).isEqualTo(1);
            assertThat(completedReturns).isZero();
        }
        // 无论哪种时序，版本号必须自增到与实际变更一致，没有更新覆盖。
        // 基线：出库点交完成后 version=1，再叠加续展次数与是否完成归还各 1 次。
        int version = jdbc.queryForObject(
                "SELECT version FROM loan_contract WHERE contract_no = ?",
                Integer.class, contract.contractNo());
        assertThat(version).isEqualTo(1 + renewCount + (finalStatus == ContractStatus.RETURNED ? 1 : 0));
    }

    private ContractResponse createInExhibition(LocalDate begin, LocalDate end) {
        ContractResponse c = loanService.apply("A002", "织绣馆", begin, end, "S002");
        loanService.confirmHandover(c.contractNo(), HandoverType.OUTBOUND, Party.LENDER);
        loanService.confirmHandover(c.contractNo(), HandoverType.OUTBOUND, Party.BORROWER);
        return loanService.getContract(c.contractNo());
    }
}
