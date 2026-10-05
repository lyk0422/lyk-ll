package com.example.starter.quarantine;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.quarantine.api.ApiDtos.ArrivalCmd;
import com.example.starter.quarantine.api.ApiDtos.CertificateView;
import com.example.starter.quarantine.api.ApiDtos.DeclarationCmd;
import com.example.starter.quarantine.api.ApiDtos.DeclarationView;
import com.example.starter.quarantine.api.ApiDtos.IssueCmd;
import com.example.starter.quarantine.api.ApiDtos.LockdownCmd;
import com.example.starter.quarantine.api.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发与竞态测试：真实 H2 事务、行锁与唯一约束下的并发出证、封锁竞态与并发落地。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:quarantine_conc_test;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class QuarantineConcurrencyTest extends AbstractQuarantineIntegrationTest {

    /** 并发对同批动物出证：唯一一张证，存栏只核减一次。 */
    @Test
    void concurrentIssueSameBatchCreatesExactlyOneCert() throws Exception {
        String farm = "F-CC-BATCH";
        stock(farm, Species.PIG, "TAG-C", 100);
        DeclarationView d1 = declare("D-CC-1", farm, Species.PIG, 30, "TAG-C");
        DeclarationView d2 = declare("D-CC-2", farm, Species.PIG, 30, "TAG-C");

        List<CertificateView> results = runConcurrently(List.of(
                () -> issue("I-CC-1", d1.requestId()),
                () -> issue("I-CC-2", d2.requestId())));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).certNo()).isEqualTo(results.get(1).certNo());
        assertThat(countRows("quarantine_certificate")).isEqualTo(1);
        assertThat(countRows("cert_batch_hold")).isEqualTo(1);
        assertThat(stockQty(farm, Species.PIG, "TAG-C")).isEqualTo(70);
    }

    /** 并发同键同参出证：唯一成功，双方拿到同一张证。 */
    @Test
    void concurrentIssueSameRequestIdCreatesExactlyOneCert() throws Exception {
        String farm = "F-CC-KEY";
        stock(farm, Species.PIG, "TAG-K", 100);
        DeclarationView d1 = declare("D-CC-KEY", farm, Species.PIG, 30, "TAG-K");

        List<CertificateView> results = runConcurrently(List.of(
                () -> issue("I-CC-KEY", d1.requestId()),
                () -> issue("I-CC-KEY", d1.requestId())));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).certNo()).isEqualTo(results.get(1).certNo());
        assertThat(countRows("quarantine_certificate")).isEqualTo(1);
        assertThat(stockQty(farm, Species.PIG, "TAG-K")).isEqualTo(70);
    }

    /** 封锁发布与出证并发：形成唯一终态，不得封锁生效同刻仍正常出证。 */
    @Test
    void lockdownAndIssueRaceYieldsUniqueFinalState() throws Exception {
        for (int round = 0; round < 10; round++) {
            String farm = "F-RACE-" + round;
            String tag = "TAG-R";
            stock(farm, Species.CATTLE, tag, 10);
            DeclarationView declaration = declare("D-RACE-" + round, farm, Species.CATTLE, 4, tag);
            String issueRequestId = "I-RACE-" + round;
            String lockdownRequestId = "LK-RACE-" + round;

            List<Object> outcomes = runConcurrently(List.<Callable<Object>>of(
                    () -> {
                        try {
                            return issueExecutor.execute(new IssueCmd(issueRequestId,
                                    declaration.requestId()));
                        } catch (ApiException e) {
                            return e;
                        }
                    },
                    () -> service.lockdown(new LockdownCmd(lockdownRequestId, farm, "疫点封锁"))));

            Object issueOutcome = outcomes.get(0);
            boolean lockdownActive = countRows("lockdown_zone") == round + 1L;
            assertThat(lockdownActive).as("第 %d 轮封锁必须生效", round).isTrue();

            long certCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM quarantine_certificate WHERE farm_id = ?", Long.class, farm);
            if (issueOutcome instanceof CertificateView cert) {
                // 出证先完成：封锁随后必须将其作废，不得保持有效。
                assertThat(certCount).as("第 %d 轮", round).isEqualTo(1);
                String status = jdbc.queryForObject(
                        "SELECT status FROM quarantine_certificate WHERE cert_no = ?",
                        String.class, cert.certNo());
                assertThat(status).as("第 %d 轮封锁生效后证必须作废", round)
                        .isEqualTo(CertificateStatus.VOIDED.name());
                assertThat(stockQty(farm, Species.CATTLE, tag)).isEqualTo(6);
            } else {
                // 封锁先生效：出证被拒绝且无证书残留。
                assertThat(issueOutcome).isInstanceOf(ApiException.class);
                assertThat(((ApiException) issueOutcome).status().value()).isEqualTo(422);
                assertThat(certCount).as("第 %d 轮", round).isZero();
                assertThat(stockQty(farm, Species.CATTLE, tag)).isEqualTo(10);
            }
        }
    }

    /** 并发落地报告：累计数量不得超证载数量，超量整批拒绝。 */
    @Test
    void concurrentArrivalsNeverExceedCertQuantity() throws Exception {
        String farm = "F-CC-ARR";
        stock(farm, Species.PIG, "TAG-A", 10);
        DeclarationView declaration = declare("D-CC-ARR", farm, Species.PIG, 10, "TAG-A");
        CertificateView cert = issue("I-CC-ARR", declaration.requestId());
        depart(cert.certNo());

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String requestId = "A-CC-" + i;
            tasks.add(() -> service.arrive(new ArrivalCmd(requestId, cert.certNo(), 3)));
        }
        runConcurrently(tasks);

        // 5 笔 × 3 头对证载 10：恰 3 笔部分受理（累计 9），其余 2 笔超量整批拒绝。
        Long partial = jdbc.queryForObject(
                "SELECT COUNT(*) FROM arrival_report WHERE result = 'ACCEPTED_PARTIAL'", Long.class);
        Long overflow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM arrival_report WHERE result = 'REJECTED_OVERFLOW'", Long.class);
        assertThat(partial).isEqualTo(3);
        assertThat(overflow).isEqualTo(2);
        CertificateView after = service.getCertificate(cert.certNo());
        assertThat(after.reportedQty()).isEqualTo(9);
        assertThat(after.status()).isEqualTo(CertificateStatus.IN_TRANSIT);
    }

    /** 并发同键落地报告：只入账一次。 */
    @Test
    void concurrentArrivalSameRequestIdRecordedOnce() throws Exception {
        String farm = "F-CC-ARR-KEY";
        stock(farm, Species.PIG, "TAG-AK", 10);
        DeclarationView declaration = declare("D-CC-ARR-KEY", farm, Species.PIG, 10, "TAG-AK");
        CertificateView cert = issue("I-CC-ARR-KEY", declaration.requestId());
        depart(cert.certNo());

        List<?> results = runConcurrently(List.of(
                () -> service.arrive(new ArrivalCmd("A-CC-KEY", cert.certNo(), 4)),
                () -> service.arrive(new ArrivalCmd("A-CC-KEY", cert.certNo(), 4))));

        assertThat(results).hasSize(2);
        assertThat(countRows("arrival_report")).isEqualTo(1);
        assertThat(service.getCertificate(cert.certNo()).reportedQty()).isEqualTo(4);
    }

    /** 并发对同一过境证足额核销：唯一成功结案，其余只记录为迟到事件。 */
    @Test
    void concurrentTransitArrivalVerificationSucceedsExactlyOnce() throws Exception {
        String farm = "F-CC-TR-ARR";
        stock(farm, Species.PIG, "TAG-TR", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-CC-TR", farm, "DEST-TR", Species.PIG, 10, "TAG-TR",
                TransportType.TRANSIT, 6, List.of("SYN-EP-001")));
        CertificateView cert = issue("I-CC-TR", declaration.requestId());
        depart(cert.certNo());

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String requestId = "A-CC-TR-" + i;
            tasks.add(() -> service.arrive(new ArrivalCmd(requestId, cert.certNo(), 10)));
        }
        List<Object> results = runConcurrently(tasks);

        // 恰一笔 ACCEPTED_FINAL，其余为结案后的迟到事件；最终核销数量等于证载数量。
        long finals = results.stream()
                .map(r -> (com.example.starter.quarantine.api.ApiDtos.ArrivalView) r)
                .filter(v -> v.result() == ArrivalResult.ACCEPTED_FINAL)
                .count();
        assertThat(finals).isEqualTo(1);
        assertThat(countRows("arrival_report")).isEqualTo(4);
        CertificateView after = service.getCertificate(cert.certNo());
        assertThat(after.status()).isEqualTo(CertificateStatus.CLOSED);
        assertThat(after.reportedQty()).isEqualTo(10);
        // 途经疫区：加强监测标记与抵达记录同事务写入，并发下仍成立。
        assertThat(after.enhancedMonitoring()).isTrue();
    }

    /** 以同一发令枪并发执行任务并收集结果（异常作为结果返回）。 */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await(5, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
