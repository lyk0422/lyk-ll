package com.example.starter.quarantine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.quarantine.api.ApiDtos.ArrivalCmd;
import com.example.starter.quarantine.api.ApiDtos.ArrivalView;
import com.example.starter.quarantine.api.ApiDtos.CertificateView;
import com.example.starter.quarantine.api.ApiDtos.DeclarationCmd;
import com.example.starter.quarantine.api.ApiDtos.DeclarationView;
import com.example.starter.quarantine.api.ApiDtos.FlowCmd;
import com.example.starter.quarantine.api.ApiDtos.FlowView;
import com.example.starter.quarantine.api.ApiDtos.ResolveAlertCmd;
import com.example.starter.quarantine.api.ApiDtos.TrajectoryView;
import com.example.starter.quarantine.api.ApiDtos.TransitAlertView;
import com.example.starter.quarantine.api.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * 过境运输形态测试：过境申报/出证标注、流向用途拦截、时限预警、疫区加强监测与处理闭环
 * （真实 H2，可控时钟）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:quarantine_transit_test;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class TransitQuarantineTest extends AbstractQuarantineIntegrationTest {

    private static final String EPIC_REGION = "SYN-EP-001";
    private static final String SAFE_REGION = "SYN-SAFE-100";

    private DeclarationView declareTransit(String requestId, String farmId, int quantity,
                                           String earTagRange, int transitHours,
                                           List<String> routeRegions) {
        return service.declare(new DeclarationCmd(requestId, farmId, "DEST-" + farmId,
                Species.PIG, quantity, earTagRange, TransportType.TRANSIT, transitHours, routeRegions));
    }

    // ---------- 过境申报与证书标注 ----------

    @Test
    void transitDeclarationAndCertAreMarkedTransit() {
        String farm = "F-TR-MARK";
        stock(farm, Species.PIG, "TAG-T1", 10);
        DeclarationView declaration = declareTransit("D-TR-MARK", farm, 5, "TAG-T1", 6,
                List.of(SAFE_REGION));
        assertThat(declaration.transportType()).isEqualTo(TransportType.TRANSIT);
        assertThat(declaration.transitHours()).isEqualTo(6);
        assertThat(declaration.routeRegions()).containsExactly(SAFE_REGION);

        CertificateView cert = issue("I-TR-MARK", declaration.requestId());
        assertThat(cert.transportType()).isEqualTo(TransportType.TRANSIT);
        assertThat(cert.transitDeadlineAt()).isNull();

        // 启运后核销时限终点 = 启运时间 + 过境时限。
        CertificateView departed = depart(cert.certNo());
        assertThat(departed.transitDeadlineAt())
                .isEqualTo(departed.departedAt().plus(Duration.ofHours(6)));
    }

    @Test
    void transitDeclarationRequiresHoursAndRoute() {
        String farm = "F-TR-VALID";
        stock(farm, Species.PIG, "TAG-T2", 10);
        // 缺时限 → 422。
        assertThatThrownBy(() -> service.declare(new DeclarationCmd("D-TR-NOH", farm, "DEST",
                Species.PIG, 5, "TAG-T2", TransportType.TRANSIT, null, List.of(SAFE_REGION))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 缺途经区域 → 422。
        assertThatThrownBy(() -> service.declare(new DeclarationCmd("D-TR-NOR", farm, "DEST",
                Species.PIG, 5, "TAG-T2", TransportType.TRANSIT, 6, List.of())))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(countRows("quarantine_declaration")).isZero();
    }

    @Test
    void localDeclarationDefaultsAndIgnoresTransitFields() {
        String farm = "F-TR-LOCAL";
        stock(farm, Species.PIG, "TAG-T3", 10);
        DeclarationView declaration = declare("D-TR-LOCAL", farm, Species.PIG, 5, "TAG-T3");
        assertThat(declaration.transportType()).isEqualTo(TransportType.LOCAL);
        assertThat(declaration.transitHours()).isNull();
        CertificateView cert = issue("I-TR-LOCAL", declaration.requestId());
        assertThat(cert.transportType()).isEqualTo(TransportType.LOCAL);
        CertificateView departed = depart(cert.certNo());
        assertThat(departed.transitDeadlineAt()).isNull();
    }

    // ---------- 流向登记：按证书类型拦截不符用途 ----------

    @Test
    void transitCertRejectsLocalSaleAndBreedingFlows() {
        String farm = "F-TR-FLOW";
        stock(farm, Species.PIG, "TAG-T4", 10);
        DeclarationView declaration = declareTransit("D-TR-FLOW", farm, 5, "TAG-T4", 6,
                List.of(SAFE_REGION));
        CertificateView cert = issue("I-TR-FLOW", declaration.requestId());

        // 过境证用于本地销售 → 422，无副作用。
        assertThatThrownBy(() -> service.registerFlow(
                new FlowCmd("FL-TR-SALE", cert.certNo(), FlowPurpose.LOCAL_SALE)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 过境证用于饲养 → 422。
        assertThatThrownBy(() -> service.registerFlow(
                new FlowCmd("FL-TR-BREED", cert.certNo(), FlowPurpose.BREEDING)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(countRows("flow_registration")).isZero();

        // 过境通行用途 → 登记成功；同键同参重放返回首次结果；同键异参 409。
        FlowView flow = service.registerFlow(new FlowCmd("FL-TR-OK", cert.certNo(), FlowPurpose.TRANSIT));
        assertThat(flow.purpose()).isEqualTo(FlowPurpose.TRANSIT);
        FlowView replay = service.registerFlow(new FlowCmd("FL-TR-OK", cert.certNo(), FlowPurpose.TRANSIT));
        assertThat(replay).isEqualTo(flow);
        assertThat(countRows("flow_registration")).isEqualTo(1);
        assertThatThrownBy(() -> service.registerFlow(
                new FlowCmd("FL-TR-OK", cert.certNo(), FlowPurpose.LOCAL_SALE)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void localCertRejectsTransitFlow() {
        String farm = "F-TR-FLOW-L";
        stock(farm, Species.PIG, "TAG-T5", 10);
        DeclarationView declaration = declare("D-TR-FLOW-L", farm, Species.PIG, 5, "TAG-T5");
        CertificateView cert = issue("I-TR-FLOW-L", declaration.requestId());

        assertThatThrownBy(() -> service.registerFlow(
                new FlowCmd("FL-L-TRANSIT", cert.certNo(), FlowPurpose.TRANSIT)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 本地证正常用途可用。
        FlowView sale = service.registerFlow(new FlowCmd("FL-L-SALE", cert.certNo(), FlowPurpose.LOCAL_SALE));
        assertThat(sale.purpose()).isEqualTo(FlowPurpose.LOCAL_SALE);
        FlowView breed = service.registerFlow(new FlowCmd("FL-L-BREED", cert.certNo(), FlowPurpose.BREEDING));
        assertThat(breed.purpose()).isEqualTo(FlowPurpose.BREEDING);
    }

    // ---------- 过境时限：恰满时限判定与超时预警 ----------

    @Test
    void arrivalExactlyAtTransitDeadlineIsOnTimeAndNoAlert() {
        String farm = "F-TR-EXACT";
        stock(farm, Species.PIG, "TAG-T6", 10);
        DeclarationView declaration = declareTransit("D-TR-EXACT", farm, 5, "TAG-T6", 6,
                List.of(SAFE_REGION));
        CertificateView cert = issue("I-TR-EXACT", declaration.requestId());
        CertificateView departed = depart(cert.certNo());

        // 恰满时限：按时核销，结案且不产生预警。
        clock.setInstant(departed.transitDeadlineAt());
        ArrivalView arrival = service.arrive(new ArrivalCmd("A-TR-EXACT", cert.certNo(), 5));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(arrival.certStatus()).isEqualTo(CertificateStatus.CLOSED);
        assertThat(service.listTransitAlerts()).isEmpty();
    }

    @Test
    void arrivalAfterTransitDeadlineTriggersAlertButCertStaysValid() {
        String farm = "F-TR-LATE";
        stock(farm, Species.PIG, "TAG-T7", 10);
        DeclarationView declaration = declareTransit("D-TR-LATE", farm, 5, "TAG-T7", 6,
                List.of(SAFE_REGION));
        CertificateView cert = issue("I-TR-LATE", declaration.requestId());
        CertificateView departed = depart(cert.certNo());

        // 超过时限 1 秒（仍在证有效期内）：抵达核销仍受理结案，同时生成预警，预警不改变证书效力。
        clock.setInstant(departed.transitDeadlineAt().plusSeconds(1));
        ArrivalView arrival = service.arrive(new ArrivalCmd("A-TR-LATE", cert.certNo(), 5));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(arrival.certStatus()).isEqualTo(CertificateStatus.CLOSED);

        List<TransitAlertView> alerts = service.listTransitAlerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).alertKey()).isEqualTo("TA-" + cert.certNo());
        assertThat(alerts.get(0).certNo()).isEqualTo(cert.certNo());
        assertThat(alerts.get(0).status()).isEqualTo(TransitAlertStatus.OPEN);
        // 重复落地事件不重复生成预警。
        service.arrive(new ArrivalCmd("A-TR-LATE-2", cert.certNo(), 1));
        assertThat(service.listTransitAlerts()).hasSize(1);
    }

    @Test
    void scanGeneratesAlertForOverdueInTransitCertIdempotently() {
        String farm = "F-TR-SCAN";
        stock(farm, Species.PIG, "TAG-T8", 10);
        DeclarationView declaration = declareTransit("D-TR-SCAN", farm, 5, "TAG-T8", 6,
                List.of(SAFE_REGION));
        CertificateView cert = issue("I-TR-SCAN", declaration.requestId());
        CertificateView departed = depart(cert.certNo());

        // 时限内扫描：无预警。
        clock.setInstant(departed.transitDeadlineAt());
        assertThat(service.scanTransitAlerts()).isEmpty();

        // 超时未核销：扫描自动生成预警；重复扫描幂等，仍只有一条。
        clock.setInstant(departed.transitDeadlineAt().plusSeconds(1));
        List<TransitAlertView> alerts = service.scanTransitAlerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).status()).isEqualTo(TransitAlertStatus.OPEN);
        assertThat(service.scanTransitAlerts()).hasSize(1);
        assertThat(countRows("transit_alert")).isEqualTo(1);
        // 预警不改变证书效力：证仍在途。
        assertThat(service.getCertificate(cert.certNo()).status()).isEqualTo(CertificateStatus.IN_TRANSIT);
    }

    // ---------- 预警处理闭环 ----------

    @Test
    void alertResolveClosesLoopWithIdempotency() {
        String farm = "F-TR-RESOLVE";
        stock(farm, Species.PIG, "TAG-T9", 10);
        DeclarationView declaration = declareTransit("D-TR-RESOLVE", farm, 5, "TAG-T9", 6,
                List.of(SAFE_REGION));
        CertificateView cert = issue("I-TR-RESOLVE", declaration.requestId());
        CertificateView departed = depart(cert.certNo());
        clock.setInstant(departed.transitDeadlineAt().plusSeconds(1));
        service.scanTransitAlerts();
        String alertKey = "TA-" + cert.certNo();

        TransitAlertView resolved = service.resolveTransitAlert(alertKey,
                new ResolveAlertCmd("RS-TR-1", "已现场核查，无异常"));
        assertThat(resolved.status()).isEqualTo(TransitAlertStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.resolveNote()).isEqualTo("已现场核查，无异常");

        // 同键同参重放：返回首次结果，状态不变。
        TransitAlertView replay = service.resolveTransitAlert(alertKey,
                new ResolveAlertCmd("RS-TR-1", "已现场核查，无异常"));
        assertThat(replay).isEqualTo(resolved);
        // 同键异参 → 409。
        assertThatThrownBy(() -> service.resolveTransitAlert(alertKey,
                new ResolveAlertCmd("RS-TR-1", "另一份说明")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        // 已办结预警对新请求号幂等返回当前状态，不重复办结。
        TransitAlertView again = service.resolveTransitAlert(alertKey,
                new ResolveAlertCmd("RS-TR-2", "重复办结"));
        assertThat(again.status()).isEqualTo(TransitAlertStatus.RESOLVED);
        assertThat(again.resolvedAt()).isEqualTo(resolved.resolvedAt());
        assertThat(countRows("transit_alert")).isEqualTo(1);
        // 不存在的预警 → 404。
        assertThatThrownBy(() -> service.resolveTransitAlert("TA-AQ99999999",
                new ResolveAlertCmd("RS-TR-3", "无")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------- 途经疫区加强监测标记 ----------

    @Test
    void transitThroughEpidemicAreaMarksEnhancedMonitoringWithArrival() {
        String farm = "F-TR-EPIC";
        stock(farm, Species.PIG, "TAG-TA", 10);
        DeclarationView declaration = declareTransit("D-TR-EPIC", farm, 5, "TAG-TA", 6,
                List.of(SAFE_REGION, EPIC_REGION));
        CertificateView cert = issue("I-TR-EPIC", declaration.requestId());
        depart(cert.certNo());
        assertThat(service.getCertificate(cert.certNo()).enhancedMonitoring()).isFalse();

        // 途经合成疫区清单区域：抵达核销同事务写入加强监测标记。
        ArrivalView arrival = service.arrive(new ArrivalCmd("A-TR-EPIC", cert.certNo(), 5));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(arrival.enhancedMonitoring()).isTrue();
        assertThat(service.getCertificate(cert.certNo()).enhancedMonitoring()).isTrue();
    }

    @Test
    void transitWithoutEpidemicRouteIsNotMarked() {
        String farm = "F-TR-SAFE";
        stock(farm, Species.PIG, "TAG-TB", 10);
        DeclarationView declaration = declareTransit("D-TR-SAFE", farm, 5, "TAG-TB", 6,
                List.of(SAFE_REGION));
        CertificateView cert = issue("I-TR-SAFE", declaration.requestId());
        depart(cert.certNo());

        ArrivalView arrival = service.arrive(new ArrivalCmd("A-TR-SAFE", cert.certNo(), 5));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(arrival.enhancedMonitoring()).isFalse();
        assertThat(service.getCertificate(cert.certNo()).enhancedMonitoring()).isFalse();
    }

    // ---------- 轨迹查询（审计口径） ----------

    @Test
    void trajectoryCoversTransitLifecycleEvents() {
        String farm = "F-TR-TRAJ";
        stock(farm, Species.PIG, "TAG-TC", 10);
        DeclarationView declaration = declareTransit("D-TR-TRAJ", farm, 5, "TAG-TC", 6,
                List.of(EPIC_REGION));
        CertificateView cert = issue("I-TR-TRAJ", declaration.requestId());
        CertificateView departed = depart(cert.certNo());
        service.registerFlow(new FlowCmd("FL-TR-TRAJ", cert.certNo(), FlowPurpose.TRANSIT));
        clock.setInstant(departed.transitDeadlineAt().plusSeconds(1));
        service.arrive(new ArrivalCmd("A-TR-TRAJ", cert.certNo(), 5));
        service.resolveTransitAlert("TA-" + cert.certNo(), new ResolveAlertCmd("RS-TR-TRAJ", "已核查"));

        TrajectoryView trajectory = service.getTrajectory(cert.certNo());
        assertThat(trajectory.certNo()).isEqualTo(cert.certNo());
        assertThat(trajectory.transportType()).isEqualTo(TransportType.TRANSIT);
        List<String> types = trajectory.events().stream().map(e -> e.type()).toList();
        assertThat(types).contains("ISSUED", "DEPARTED", "FLOW_REGISTERED",
                "ARRIVAL", "ALERT_TRIGGERED", "ALERT_RESOLVED", "CLOSED");
        // 事件按时间升序。
        List<java.time.Instant> ats = trajectory.events().stream().map(e -> e.at()).toList();
        assertThat(ats).isSorted();
    }
}
