package com.example.starter.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.quarantine.api.dto.BlockadeRequest;
import com.example.starter.quarantine.api.dto.DeclareRequest;
import com.example.starter.quarantine.domain.AnimalType;
import com.example.starter.quarantine.domain.CertificateStatus;
import com.example.starter.quarantine.domain.RequestStatus;
import com.example.starter.quarantine.repository.CertificateRepository;
import com.example.starter.quarantine.repository.RequestRepository;
import com.example.starter.quarantine.repository.StockRepository;
import com.example.starter.quarantine.service.QuarantineService;
import com.example.starter.quarantine.service.ResourceConflictException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 真实并发测试：同批动物并发出证唯一成功；出证与封锁发布并发形成唯一终态。
 * 以 CyclicBarrier 协调两个线程同时进入事务，靠养殖场行锁串行化并断言终态不变量。
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private QuarantineService service;
    @Autowired
    private RequestRepository requestRepository;
    @Autowired
    private CertificateRepository certificateRepository;
    @Autowired
    private StockRepository stockRepository;

    private ExecutorService executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentIssueOnSameSlot_onlyOneSucceedsAndStockDeductedOnce() throws Exception {
        service.declare(new DeclareRequest("REQ-C1", "FARM-001", "D",
                AnimalType.CATTLE, "TAG-C-0001-0100", 10));
        service.declare(new DeclareRequest("REQ-C2", "FARM-001", "D",
                AnimalType.CATTLE, "TAG-C-0001-0100", 10));

        executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        Runnable issue1 = () -> awaitAndRun(barrier,
                () -> service.issue("REQ-C1"), success, conflict);
        Runnable issue2 = () -> awaitAndRun(barrier,
                () -> service.issue("REQ-C2"), success, conflict);

        Future<?> f1 = executor.submit(issue1);
        Future<?> f2 = executor.submit(issue2);
        f1.get(20, TimeUnit.SECONDS);
        f2.get(20, TimeUnit.SECONDS);

        assertEquals(1, success.get(), "同批动物并发出证必须唯一成功");
        assertEquals(1, conflict.get(), "落选方必须收到唯一冲突");
        // 存栏恰好核减一次（失败事务回滚其核减）。
        assertEquals(90, stockRepository.findSlot(
                "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM quarantine_certificate WHERE active_slot IS NOT NULL",
                Integer.class));
    }

    @Test
    void issueAndBlockadeConcurrently_neverBothSucceed() throws Exception {
        // 重复 12 轮，覆盖两种先后顺序；每轮独立清理。
        for (int round = 1; round <= 12; round++) {
            resetTables();
            String requestId = "REQ-RACE-" + round;
            service.declare(new DeclareRequest(requestId, "FARM-001", "D",
                    AnimalType.CATTLE, "TAG-C-0001-0100", 10));

            executor = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            final String requestIdFinal = requestId;
            final String blockadeIdFinal = "BLK-RACE-" + round;
            Future<?> fIssue = executor.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                service.issue(requestIdFinal);
            });
            Future<?> fBlock = executor.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                service.publishBlockade(new BlockadeRequest(blockadeIdFinal, "FARM-001", "竞态封锁"));
            });
            fIssue.get(20, TimeUnit.SECONDS);
            fBlock.get(20, TimeUnit.SECONDS);
            executor.shutdown();
            executor = null;

            // 唯一终态不变量：封锁必生效；证绝不允许与生效封锁并存为 VALID。
            Integer blockadeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM blockade WHERE farm_id = 'FARM-001' AND status = 'ACTIVE'",
                    Integer.class);
            assertEquals(1, blockadeCount, "第 " + round + " 轮封锁必须生效");

            var request = requestRepository.findById(requestIdFinal);
            var cert = certificateRepository.findByRequestId(requestIdFinal);
            if (cert == null) {
                // 封锁先得锁：申报被驳回，无证、存栏未动。
                assertEquals(RequestStatus.REJECTED, request.getStatus(), "第 " + round + " 轮");
                assertEquals(100, stockRepository.findSlot(
                        "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount());
            } else {
                // 出证先得锁：封锁随后自动作废该证并返还存栏。
                assertEquals(CertificateStatus.VOID, cert.getStatus(), "第 " + round + " 轮");
                assertNull(cert.getActiveSlot(), "第 " + round + " 轮作废证必须释放耳标段占位");
                assertEquals(RequestStatus.VOID, request.getStatus(), "第 " + round + " 轮");
                assertEquals(100, stockRepository.findSlot(
                        "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount());
            }
        }
    }

    @Test
    void insufficientStock_failsAtomicallyWithoutCertificate() {
        service.declare(new DeclareRequest("REQ-LOW", "FARM-001", "D",
                AnimalType.SHEEP, "TAG-S-0001-0050", 50));
        service.issue("REQ-LOW");
        // 羊耳标段全部核减，结案释放后再申报 1 只应因存栏不足失败且不产生证。
        service.depart("REQ-LOW");
        service.landing(new com.example.starter.quarantine.api.dto.LandingReportRequest(
                "REQ-LOW", "R-1", 50));
        service.declare(new DeclareRequest("REQ-LOW2", "FARM-001", "D",
                AnimalType.SHEEP, "TAG-S-0001-0050", 1));
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.quarantine.service.BusinessRuleException.class,
                () -> service.issue("REQ-LOW2"));
        assertTrue(ex.getMessage().contains("存栏不足"));
        assertEquals(RequestStatus.SUBMITTED, requestRepository.findById("REQ-LOW2").getStatus());
        assertNull(certificateRepository.findByRequestId("REQ-LOW2"));
        assertEquals(0, stockRepository.findSlot(
                "FARM-001", AnimalType.SHEEP, "TAG-S-0001-0050").getAvailableCount());
    }

    private void awaitAndRun(CyclicBarrier barrier, Runnable action,
                             AtomicInteger success, AtomicInteger conflict) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            action.run();
            success.incrementAndGet();
        } catch (ResourceConflictException ex) {
            conflict.incrementAndGet();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void resetTables() {
        jdbcTemplate.update("DELETE FROM landing_report");
        jdbcTemplate.update("DELETE FROM quarantine_certificate");
        jdbcTemplate.update("DELETE FROM quarantine_request");
        jdbcTemplate.update("DELETE FROM blockade");
        jdbcTemplate.update("DELETE FROM idempotent_command");
        jdbcTemplate.update("UPDATE farm_stock SET available_count = total_count, version = 0");
        jdbcTemplate.update("ALTER SEQUENCE cert_no_seq RESTART WITH 1");
    }
}
