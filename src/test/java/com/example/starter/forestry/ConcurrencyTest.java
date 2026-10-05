package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.ConfirmRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.LicenseView;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.web.ApiException;

/**
 * 真实并发测试（H2 数据库行锁，非 mock）：同档位发证串行校验不得合计超限；
 * 同 requestId 并发只产生一笔；采伐确认并发只产生一个终态与一条更新义务；
 * 采伐确认与逾期作废并发只作废一次、限额只释放一次。
 */
class ConcurrencyTest extends AbstractForestryTest {

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void runConcurrently(ThrowingRunnable a, ThrowingRunnable b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> {
                start.await();
                a.run();
                return null;
            });
            Future<?> f2 = pool.submit(() -> {
                start.await();
                b.run();
                return null;
            });
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIssuesOnSameQuotaTierNeverExceedTotal() throws Exception {
        // 杉木 2026 限额 200 株 / 50 立方米：两笔各 120 株 / 30 立方米，并发发证只可能一张成功
        var app1 = service.apply(new ApplyRequest("c-q-app1", "OP-C1", "SP-002",
                "CUNNINGHAMIA", "MAIN", 120, new BigDecimal("30.00")));
        var app2 = service.apply(new ApplyRequest("c-q-app2", "OP-C2", "SP-006",
                "CUNNINGHAMIA", "MAIN", 120, new BigDecimal("30.00")));
        service.review(app1.id(), new ReviewRequest(true, "OK"));
        service.review(app2.id(), new ReviewRequest(true, "OK"));

        final int[] success = {0};
        final int[] rejected = {0};
        runConcurrently(
                () -> {
                    try {
                        service.issue(new IssueRequest("c-q-issue1", app1.id()));
                        success[0]++;
                    } catch (ApiException e) {
                        if ("QUOTA_EXCEEDED".equals(e.getErrorCode())) {
                            rejected[0]++;
                        } else {
                            throw e;
                        }
                    }
                },
                () -> {
                    try {
                        service.issue(new IssueRequest("c-q-issue2", app2.id()));
                        success[0]++;
                    } catch (ApiException e) {
                        if ("QUOTA_EXCEEDED".equals(e.getErrorCode())) {
                            rejected[0]++;
                        } else {
                            throw e;
                        }
                    }
                });

        assertThat(success[0]).isOne();
        assertThat(rejected[0]).isOne();
        BigDecimal held = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='CUNNINGHAMIA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(held).isEqualByComparingTo("30.00");
        Integer issued = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_license WHERE status='ISSUED'", Integer.class);
        assertThat(issued).isOne();
    }

    @Test
    void concurrentIssuesOnTreeCountBoundTierNeverExceedTotal() throws Exception {
        // 栎类 2026 限额 90 株 / 40 立方米（株数口径先判满）：两笔各 50 株 / 20 立方米，
        // 蓄积合计恰达 40 合法，但株数合计 100 > 90，并发发证只可能一张成功
        var app1 = service.apply(new ApplyRequest("c-tq-app1", "OP-C6", "SP-007",
                "QUERCUS", "MAIN", 50, new BigDecimal("20.00")));
        var app2 = service.apply(new ApplyRequest("c-tq-app2", "OP-C7", "SP-008",
                "QUERCUS", "MAIN", 50, new BigDecimal("20.00")));
        service.review(app1.id(), new ReviewRequest(true, "OK"));
        service.review(app2.id(), new ReviewRequest(true, "OK"));

        final int[] success = {0};
        final int[] rejected = {0};
        runConcurrently(
                () -> {
                    try {
                        service.issue(new IssueRequest("c-tq-issue1", app1.id()));
                        success[0]++;
                    } catch (ApiException e) {
                        if ("QUOTA_EXCEEDED".equals(e.getErrorCode())) {
                            rejected[0]++;
                        } else {
                            throw e;
                        }
                    }
                },
                () -> {
                    try {
                        service.issue(new IssueRequest("c-tq-issue2", app2.id()));
                        success[0]++;
                    } catch (ApiException e) {
                        if ("QUOTA_EXCEEDED".equals(e.getErrorCode())) {
                            rejected[0]++;
                        } else {
                            throw e;
                        }
                    }
                });

        assertThat(success[0]).isOne();
        assertThat(rejected[0]).isOne();
        // 株数口径占用不超过 90 株，且只有一笔台账
        Integer heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tree_count),0) FROM quota_ledger WHERE species_code='QUERCUS' AND quota_year=2026",
                Integer.class);
        assertThat(heldTrees).isEqualTo(50);
        Integer holds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_ledger WHERE species_code='QUERCUS' AND action='HOLD'",
                Integer.class);
        assertThat(holds).isOne();
    }

    @Test
    void concurrentSameRequestIdCreatesExactlyOneApplication() throws Exception {        AtomicReference<String> id1 = new AtomicReference<>();
        AtomicReference<String> id2 = new AtomicReference<>();
        runConcurrently(
                () -> id1.set(service.apply(new ApplyRequest("c-same-key", "OP-C3", "SP-001",
                        "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("10.00"))).id()),
                () -> id2.set(service.apply(new ApplyRequest("c-same-key", "OP-C3", "SP-001",
                        "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("10.00"))).id()));

        // 两个并发同键同参请求都应返回同一个首次申请单
        assertThat(id1.get()).isNotNull();
        assertThat(id2.get()).isEqualTo(id1.get());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_application WHERE request_id='c-same-key'", Integer.class);
        assertThat(count).isOne();
    }

    @Test
    void concurrentConfirmsProduceSingleHarvestedTerminalAndOneRenewal() throws Exception {
        LicenseView license = issueLicense("c-confirm", "OP-C4", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("10.00"));
        clock.setToday(LocalDate.of(2026, 5, 30)); // 恰第 90 日

        runConcurrently(
                () -> service.confirm(new ConfirmRequest("c-confirm-1", license.id())),
                () -> service.confirm(new ConfirmRequest("c-confirm-2", license.id())));

        assertThat(service.getLicense(license.id()).status()).isEqualTo("HARVESTED");
        Integer renewals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM renewal_obligation WHERE application_id=?",
                Integer.class, license.applicationId());
        assertThat(renewals).isOne();
    }

    @Test
    void confirmOnExpiryRacingScanVoidsOnceAndReleasesOnce() throws Exception {
        LicenseView license = issueLicense("c-void", "OP-C5", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("10.00"));
        clock.setToday(LocalDate.of(2026, 5, 31)); // 第 91 日

        runConcurrently(
                () -> {
                    try {
                        service.confirm(new ConfirmRequest("c-void-confirm", license.id()));
                    } catch (ApiException e) {
                        // 逾期作废属于预期结果之一
                        if (!"LICENSE_EXPIRED".equals(e.getErrorCode())
                                && !"LICENSE_VOID".equals(e.getErrorCode())) {
                            throw e;
                        }
                    }
                },
                service::processExpiredLicenses);

        assertThat(service.getLicense(license.id()).status()).isEqualTo("VOID");
        Integer releases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_ledger WHERE license_id=? AND action='RELEASE'",
                Integer.class, license.id());
        assertThat(releases).isOne();
        BigDecimal held = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(held).isEqualByComparingTo("0.00");
    }
}
