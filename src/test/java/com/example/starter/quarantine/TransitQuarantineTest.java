package com.example.starter.quarantine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.quarantine.api.ApiDtos.AlertHandleCmd;
import com.example.starter.quarantine.api.ApiDtos.AlertScanView;
import com.example.starter.quarantine.api.ApiDtos.AlertView;
import com.example.starter.quarantine.api.ApiDtos.ArrivalCmd;
import com.example.starter.quarantine.api.ApiDtos.ArrivalView;
import com.example.starter.quarantine.api.ApiDtos.CertificateView;
import com.example.starter.quarantine.api.ApiDtos.DeclarationCmd;
import com.example.starter.quarantine.api.ApiDtos.DeclarationView;
import com.example.starter.quarantine.api.ApiDtos.StockCmd;
import com.example.starter.quarantine.api.ApiDtos.TrajectoryView;
import com.example.starter.quarantine.api.ApiException;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.HttpStatus;

/**
 * 过境运输形态测试：过境用途拦截、时限预警（恰满时限判定）、疫区加强监测、
 * 预警处理闭环、轨迹查询与并发抵达核销（真实 H2，可控时钟）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:quarantine_transit_test;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class TransitQuarantineTest extends AbstractQuarantineIntegrationTest {

    // ---------- 过境申报与出证 ----------

    @Test
    void transitDeclarationAndCertCarryTransitAttributes() {
        String farm = "F-TR-ATTR";
        stock(farm, Species.PIG, "TAG-T1", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-ATTR", farm, "DEST-PORT", Species.PIG, 5, "TAG-T1",
                TransportType.TRANSIT, 12));
        assertThat(declaration.transportType()).isEqualTo(TransportType.TRANSIT);
        assertThat(declaration.transitHours()).isEqualTo(12);
        assertThat(declaration.status()).isEqualTo(DeclarationStatus.DECLARED);

        CertificateView cert = issue("I-TR-ATTR", declaration.requestId());
        assertThat(cert.transportType()).isEqualTo(TransportType.TRANSIT);
        assertThat(cert.transitDeadline()).isNull();
        assertThat(cert.enhancedMonitoring()).isFalse();

        CertificateView departed = depart(cert.certNo());
        assertThat(departed.transitDeadline())
                .isEqualTo(departed.departedAt().plus(Duration.ofHours(12)));
    }

    @Test
    void defaultDeclarationRemainsLocalWithoutTransitFields() {
        String farm = "F-TR-LOCAL";
        stock(farm, Species.PIG, "TAG-T2", 10);
        DeclarationView declaration = declare("D-TR-LOCAL", farm, Species.PIG, 5, "TAG-T2");
        assertThat(declaration.transportType()).isEqualTo(TransportType.LOCAL);
        assertThat(declaration.transitHours()).isNull();

        CertificateView cert = issue("I-TR-LOCAL", declaration.requestId());
        assertThat(cert.transportType()).isEqualTo(TransportType.LOCAL);
        CertificateView departed = depart(cert.certNo());
        assertThat(departed.transitDeadline()).isNull();
    }

    @Test
    void transitDeclarationWithoutTransitHoursIsRejected() {
        String farm = "F-TR-NOHOURS";
        assertThatThrownBy(() -> service.declare(new DeclarationCmd(
                "D-TR-NOHOURS", farm, "DEST-PORT", Species.PIG, 5, "TAG-T3",
                TransportType.TRANSIT, null)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(countRows("quarantine_declaration")).isZero();
    }

    @Test
    void transitDeclarationIdempotencyCoversTransitFields() {
        String farm = "F-TR-IDEM";
        DeclarationCmd cmd = new DeclarationCmd("D-TR-IDEM", farm, "DEST-PORT",
                Species.PIG, 5, "TAG-T4", TransportType.TRANSIT, 8);
        DeclarationView first = service.declare(cmd);
        DeclarationView replay = service.declare(cmd);
        assertThat(replay).isEqualTo(first);
        assertThat(countRows("quarantine_declaration")).isEqualTo(1);

        // 同键异参（时限不同）→ 409，无副作用。
        assertThatThrownBy(() -> service.declare(new DeclarationCmd("D-TR-IDEM", farm, "DEST-PORT",
                Species.PIG, 5, "TAG-T4", TransportType.TRANSIT, 9)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(service.getDeclaration("D-TR-IDEM").transitHours()).isEqualTo(8);
    }

    // ---------- 过境用途拦截：不得用于本地销售或饲养流向登记 ----------

    @Test
    void transitCertIsRejectedForLocalStockFlowRegistration() {
        String farm = "F-TR-FLOW";
        stock(farm, Species.PIG, "TAG-T5", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-FLOW", farm, "DEST-PORT", Species.PIG, 5, "TAG-T5",
                TransportType.TRANSIT, 12));
        CertificateView transitCert = issue("I-TR-FLOW", declaration.requestId());

        // 过境证用于本地流向登记 → 422 拦截，存栏不变。
        assertThatThrownBy(() -> service.upsertStock(new StockCmd(
                "F-TR-FLOW-IN", Species.PIG, "TAG-T5", 5, transitCert.certNo())))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(countRows("farm_stock")).isEqualTo(1);

        // 本地证允许流向登记（另一耳标段，避免同批唯一有效证占用）。
        stock(farm, Species.PIG, "TAG-T5L", 10);
        DeclarationView localDecl = declare("D-TR-FLOW-L", farm, Species.PIG, 2, "TAG-T5L");
        CertificateView localCert = issue("I-TR-FLOW-L", localDecl.requestId());
        assertThat(localCert.transportType()).isEqualTo(TransportType.LOCAL);
        service.upsertStock(new StockCmd("F-TR-FLOW-IN", Species.PIG, "TAG-T5", 5, localCert.certNo()));
        assertThat(stockQty("F-TR-FLOW-IN", Species.PIG, "TAG-T5")).isEqualTo(5);

        // 不存在的证号 → 404。
        assertThatThrownBy(() -> service.upsertStock(new StockCmd(
                "F-TR-FLOW-IN", Species.PIG, "TAG-T5", 5, "AQ99999999")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------- 过境时限预警：恰满时限判定 ----------

    @Test
    void transitArrivalExactlyAtDeadlineIsAcceptedWithoutAlert() {
        String farm = "F-TR-EXACT";
        stock(farm, Species.PIG, "TAG-T6", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-EXACT", farm, "DEST-PORT", Species.PIG, 5, "TAG-T6",
                TransportType.TRANSIT, 2));
        CertificateView cert = issue("I-TR-EXACT", declaration.requestId());
        depart(cert.certNo());
        Instant deadline = service.getCertificate(cert.certNo()).transitDeadline();

        // 恰满时限：核销有效，不生成预警。
        clock.setInstant(deadline);
        ArrivalView arrival = service.arrive(new ArrivalCmd("A-TR-EXACT", cert.certNo(), 5));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(countRows("transit_alert_event")).isZero();

        // 恰满时限时刻扫描：也不算超时。
        AlertScanView scan = service.scanTransitOverdue();
        assertThat(scan.generated()).isZero();
        assertThat(countRows("transit_alert_event")).isZero();
    }

    @Test
    void transitArrivalPastDeadlineGeneratesAlertButKeepsCertEffective() {
        String farm = "F-TR-LATE";
        stock(farm, Species.PIG, "TAG-T7", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-LATE", farm, "DEST-PORT", Species.PIG, 5, "TAG-T7",
                TransportType.TRANSIT, 2));
        CertificateView cert = issue("I-TR-LATE", declaration.requestId());
        depart(cert.certNo());
        Instant deadline = service.getCertificate(cert.certNo()).transitDeadline();

        // 超过时限 1 秒（仍在证有效期内）：抵达核销仍受理，同时生成预警，证书效力不变。
        clock.setInstant(deadline.plusSeconds(1));
        ArrivalView arrival = service.arrive(new ArrivalCmd("A-TR-LATE", cert.certNo(), 5));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(arrival.certStatus()).isEqualTo(CertificateStatus.CLOSED);
        assertThat(countRows("transit_alert_event")).isEqualTo(1);

        AlertView alert = service.getTrajectory(cert.certNo()).alerts().get(0);
        assertThat(alert.eventType()).isEqualTo("TRANSIT_OVERDUE");
        assertThat(alert.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(alert.certNo()).isEqualTo(cert.certNo());
    }

    @Test
    void scanGeneratesAlertForOverdueTransitAndIsIdempotent() {
        String farm = "F-TR-SCAN";
        stock(farm, Species.PIG, "TAG-T8", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-SCAN", farm, "DEST-PORT", Species.PIG, 5, "TAG-T8",
                TransportType.TRANSIT, 2));
        CertificateView cert = issue("I-TR-SCAN", declaration.requestId());
        depart(cert.certNo());
        Instant deadline = service.getCertificate(cert.certNo()).transitDeadline();

        // 时限内扫描：无预警。
        clock.setInstant(deadline.minusSeconds(1));
        assertThat(service.scanTransitOverdue().generated()).isZero();

        // 超时扫描：生成一次；重复扫描幂等。
        clock.setInstant(deadline.plusSeconds(1));
        assertThat(service.scanTransitOverdue().generated()).isEqualTo(1);
        assertThat(service.scanTransitOverdue().generated()).isZero();
        assertThat(countRows("transit_alert_event")).isEqualTo(1);
    }

    // ---------- 途经疫区：加强监测标记与抵达记录同事务 ----------

    @Test
    void viaEpidemicRegionSetsEnhancedMonitoringWithArrival() {
        String farm = "F-TR-EP";
        stock(farm, Species.PIG, "TAG-T9", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-EP", farm, "DEST-PORT", Species.PIG, 5, "TAG-T9",
                TransportType.TRANSIT, 12));
        CertificateView cert = issue("I-TR-EP", declaration.requestId());
        depart(cert.certNo());

        ArrivalView arrival = service.arrive(new ArrivalCmd("A-TR-EP", cert.certNo(), 5,
                List.of("SYNTH-CITY-NORTH", "SYNTH-EPICENTER-ALPHA")));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(arrival.enhancedMonitoring()).isTrue();
        // 标记与抵达记录均已落库（同一事务）。
        assertThat(service.getCertificate(cert.certNo()).enhancedMonitoring()).isTrue();
        assertThat(countRows("arrival_report")).isEqualTo(1);
    }

    @Test
    void viaNonEpidemicRegionDoesNotSetEnhancedMonitoring() {
        String farm = "F-TR-NOEP";
        stock(farm, Species.PIG, "TAG-TA", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-NOEP", farm, "DEST-PORT", Species.PIG, 5, "TAG-TA",
                TransportType.TRANSIT, 12));
        CertificateView cert = issue("I-TR-NOEP", declaration.requestId());
        depart(cert.certNo());

        ArrivalView arrival = service.arrive(new ArrivalCmd("A-TR-NOEP", cert.certNo(), 5,
                List.of("SYNTH-CITY-NORTH", "SYNTH-CITY-SOUTH")));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(arrival.enhancedMonitoring()).isFalse();
        assertThat(service.getCertificate(cert.certNo()).enhancedMonitoring()).isFalse();
    }

    // ---------- 预警处理闭环与轨迹查询（审计口径） ----------

    @Test
    void alertHandleClosureIsIdempotentAndAudited() {
        String farm = "F-TR-HANDLE";
        stock(farm, Species.PIG, "TAG-TB", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-HANDLE", farm, "DEST-PORT", Species.PIG, 5, "TAG-TB",
                TransportType.TRANSIT, 2));
        CertificateView cert = issue("I-TR-HANDLE", declaration.requestId());
        depart(cert.certNo());
        clock.advance(Duration.ofHours(3));
        service.scanTransitOverdue();
        AlertView open = service.getTrajectory(cert.certNo()).alerts().get(0);
        assertThat(open.status()).isEqualTo(AlertStatus.OPEN);

        // 处理闭环。
        AlertView handled = service.handleAlert(new AlertHandleCmd("H-TR-1", open.id(), "已现场核查"));
        assertThat(handled.status()).isEqualTo(AlertStatus.CLOSED);
        assertThat(handled.handledAt()).isNotNull();
        assertThat(handled.handleNote()).isEqualTo("已现场核查");

        // 同键同参重放：返回首次结果，不重复处理。
        AlertView replay = service.handleAlert(new AlertHandleCmd("H-TR-1", open.id(), "已现场核查"));
        assertThat(replay).isEqualTo(handled);
        assertThat(countRows("transit_alert_event")).isEqualTo(1);

        // 同键异参 → 409，无副作用。
        assertThatThrownBy(() -> service.handleAlert(new AlertHandleCmd("H-TR-1", open.id(), "另一说明")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        // 不存在的预警 → 404。
        assertThatThrownBy(() -> service.handleAlert(new AlertHandleCmd("H-TR-2", 999999L, "核查")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

        // 轨迹查询纳入审计口径：抵达记录与预警闭环留痕均可查。
        TrajectoryView trajectory = service.getTrajectory(cert.certNo());
        assertThat(trajectory.certificate().certNo()).isEqualTo(cert.certNo());
        assertThat(trajectory.alerts()).hasSize(1);
        assertThat(trajectory.alerts().get(0).status()).isEqualTo(AlertStatus.CLOSED);
    }

    @Test
    void trajectoryQueryReturnsArrivalsAndAlerts() {
        String farm = "F-TR-TRAJ";
        stock(farm, Species.PIG, "TAG-TC", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-TRAJ", farm, "DEST-PORT", Species.PIG, 5, "TAG-TC",
                TransportType.TRANSIT, 2));
        CertificateView cert = issue("I-TR-TRAJ", declaration.requestId());
        depart(cert.certNo());
        clock.advance(Duration.ofHours(3));
        service.arrive(new ArrivalCmd("A-TR-TRAJ", cert.certNo(), 5,
                List.of("SYNTH-EPICENTER-BETA")));

        TrajectoryView trajectory = service.getTrajectory(cert.certNo());
        assertThat(trajectory.arrivals()).hasSize(1);
        assertThat(trajectory.arrivals().get(0).result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(trajectory.alerts()).hasSize(1);
        assertThat(trajectory.certificate().enhancedMonitoring()).isTrue();

        assertThatThrownBy(() -> service.getTrajectory("AQ99999999"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------- 并发：同一过境证抵达核销唯一成功 ----------

    @Test
    void concurrentTransitArrivalWriteOffSucceedsExactlyOnce() throws Exception {
        String farm = "F-TR-CC";
        stock(farm, Species.PIG, "TAG-TD", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-CC", farm, "DEST-PORT", Species.PIG, 5, "TAG-TD",
                TransportType.TRANSIT, 12));
        CertificateView cert = issue("I-TR-CC", declaration.requestId());
        depart(cert.certNo());

        List<ArrivalView> results = runConcurrently(List.of(
                () -> service.arrive(new ArrivalCmd("A-TR-CC-1", cert.certNo(), 5)),
                () -> service.arrive(new ArrivalCmd("A-TR-CC-2", cert.certNo(), 5))));

        // 并发核销唯一成功：恰一笔结案，另一笔为结案后的迟到事件。
        assertThat(results.stream().filter(v -> v.result() == ArrivalResult.ACCEPTED_FINAL).count())
                .isEqualTo(1);
        assertThat(results.stream().filter(v -> v.result() == ArrivalResult.LATE_IGNORED).count())
                .isEqualTo(1);
        CertificateView after = service.getCertificate(cert.certNo());
        assertThat(after.status()).isEqualTo(CertificateStatus.CLOSED);
        assertThat(after.reportedQty()).isEqualTo(5);
    }

    @Test
    void concurrentTransitArrivalSameRequestIdRecordedOnce() throws Exception {
        String farm = "F-TR-CC-KEY";
        stock(farm, Species.PIG, "TAG-TE", 10);
        DeclarationView declaration = service.declare(new DeclarationCmd(
                "D-TR-CC-KEY", farm, "DEST-PORT", Species.PIG, 5, "TAG-TE",
                TransportType.TRANSIT, 12));
        CertificateView cert = issue("I-TR-CC-KEY", declaration.requestId());
        depart(cert.certNo());

        List<ArrivalView> results = runConcurrently(List.of(
                () -> service.arrive(new ArrivalCmd("A-TR-CC-KEY", cert.certNo(), 5)),
                () -> service.arrive(new ArrivalCmd("A-TR-CC-KEY", cert.certNo(), 5))));

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(countRows("arrival_report")).isEqualTo(1);
        assertThat(service.getCertificate(cert.certNo()).reportedQty()).isEqualTo(5);
    }

    /** 以同一发令枪并发执行任务并收集结果。 */
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
