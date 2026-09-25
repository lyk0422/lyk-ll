package com.example.starter.quarantine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.quarantine.api.ApiDtos.ArrivalCmd;
import com.example.starter.quarantine.api.ApiDtos.ArrivalView;
import com.example.starter.quarantine.api.ApiDtos.CertificateView;
import com.example.starter.quarantine.api.ApiDtos.DeclarationCmd;
import com.example.starter.quarantine.api.ApiDtos.DeclarationView;
import com.example.starter.quarantine.api.ApiDtos.IssueCmd;
import com.example.starter.quarantine.api.ApiDtos.LockdownCmd;
import com.example.starter.quarantine.api.ApiDtos.LockdownView;
import com.example.starter.quarantine.api.ApiException;
import com.example.starter.quarantine.support.TestClockConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * 检疫业务主流程、失败分支与状态边界测试（真实 H2，可控时钟）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:quarantine_svc_test;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class QuarantineServiceTest extends AbstractQuarantineIntegrationTest {

    // ---------- 24/48 小时有效期边界 ----------

    @Test
    void pigArrivalExactlyAt24hExpiryIsAcceptedAndCloses() {
        String farm = "F-PIG-24H";
        stock(farm, Species.PIG, "TAG-1", 10);
        DeclarationView declaration = declare("D-PIG-24H", farm, Species.PIG, 5, "TAG-1");
        CertificateView cert = issue("I-PIG-24H", declaration.requestId());
        assertThat(cert.expiresAt()).isEqualTo(cert.issuedAt().plus(Duration.ofHours(24)));
        depart(cert.certNo());

        // 恰在有效期终点（T0+24h）落地：有效并结案。
        clock.setInstant(cert.expiresAt());
        ArrivalView arrival = service.arrive(new ArrivalCmd("A-PIG-24H", cert.certNo(), 5));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(arrival.certStatus()).isEqualTo(CertificateStatus.CLOSED);
    }

    @Test
    void pigArrivalOneSecondPast24hExpiryIsRejectedAndCertExpires() {
        String farm = "F-PIG-EXP";
        stock(farm, Species.PIG, "TAG-1", 10);
        DeclarationView declaration = declare("D-PIG-EXP", farm, Species.PIG, 5, "TAG-1");
        CertificateView cert = issue("I-PIG-EXP", declaration.requestId());
        depart(cert.certNo());

        // 超过有效期终点 1 秒：拒绝并标记证失效。
        clock.setInstant(cert.expiresAt().plusSeconds(1));
        ArrivalView arrival = service.arrive(new ArrivalCmd("A-PIG-EXP", cert.certNo(), 5));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.REJECTED_EXPIRED);
        assertThat(service.getCertificate(cert.certNo()).status()).isEqualTo(CertificateStatus.EXPIRED);
        // 证失效后同批动物有效证占用已释放。
        assertThat(countRows("cert_batch_hold")).isZero();
    }

    @Test
    void poultryCertValidFor48h() {
        String farm = "F-POULTRY";
        stock(farm, Species.POULTRY, "TAG-9", 100);
        DeclarationView d1 = declare("D-POU-48H", farm, Species.POULTRY, 50, "TAG-9");
        CertificateView cert = issue("I-POU-48H", d1.requestId());
        assertThat(cert.expiresAt()).isEqualTo(cert.issuedAt().plus(Duration.ofHours(48)));
        depart(cert.certNo());

        // 禽 48 小时终点前 1 秒：有效。
        clock.setInstant(cert.expiresAt().minusSeconds(1));
        ArrivalView arrival = service.arrive(new ArrivalCmd("A-POU-48H", cert.certNo(), 50));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
    }

    @Test
    void poultryArrivalPast48hExpiryIsRejected() {
        String farm = "F-POULTRY-EXP";
        stock(farm, Species.POULTRY, "TAG-9", 100);
        DeclarationView d1 = declare("D-POU-EXP", farm, Species.POULTRY, 50, "TAG-9");
        CertificateView cert = issue("I-POU-EXP", d1.requestId());
        depart(cert.certNo());

        clock.setInstant(cert.expiresAt().plusSeconds(1));
        ArrivalView arrival = service.arrive(new ArrivalCmd("A-POU-EXP", cert.certNo(), 50));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.REJECTED_EXPIRED);
        assertThat(service.getCertificate(cert.certNo()).status()).isEqualTo(CertificateStatus.EXPIRED);
    }

    // ---------- 疫点封锁三级联动 ----------

    @Test
    void lockdownTriggersThreeLevelLinkage() {
        String farm = "F-LOCK";
        stock(farm, Species.CATTLE, "TAG-A", 10);
        stock(farm, Species.CATTLE, "TAG-B", 10);
        // 一级：未出证申报。
        DeclarationView pending = declare("D-LK-PENDING", farm, Species.CATTLE, 3, "TAG-A");
        // 二级：已出证未启运。
        DeclarationView toVoid = declare("D-LK-VOID", farm, Species.CATTLE, 3, "TAG-A");
        CertificateView voided = issue("I-LK-VOID", toVoid.requestId());
        // 三级：已在途。
        DeclarationView inTransitDecl = declare("D-LK-TRANSIT", farm, Species.CATTLE, 4, "TAG-B");
        CertificateView inTransit = issue("I-LK-TRANSIT", inTransitDecl.requestId());
        depart(inTransit.certNo());

        LockdownView lockdown = service.lockdown(new LockdownCmd("LK-1", farm, "口蹄疫疫点"));
        assertThat(lockdown.rejectedDeclarations()).isEqualTo(1);
        assertThat(lockdown.voidedCertificates()).isEqualTo(1);
        assertThat(lockdown.quarantinedInTransit()).isEqualTo(1);

        // 一级结果：未出证申报被驳回，且驳回后不得出证。
        assertThat(service.getDeclaration(pending.requestId()).status())
                .isEqualTo(DeclarationStatus.REJECTED);
        assertThatThrownBy(() -> issue("I-LK-PENDING", pending.requestId()))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 二级结果：未启运证自动作废，落地报告被拒。
        CertificateView voidedAfter = service.getCertificate(voided.certNo());
        assertThat(voidedAfter.status()).isEqualTo(CertificateStatus.VOIDED);
        ArrivalView voidedArrival = service.arrive(new ArrivalCmd("A-LK-VOID", voided.certNo(), 3));
        assertThat(voidedArrival.result()).isEqualTo(ArrivalResult.REJECTED_CERT_INVALID);

        // 三级结果：在途证强制隔离观察，仍允许完成落地并结案。
        CertificateView transitAfter = service.getCertificate(inTransit.certNo());
        assertThat(transitAfter.status()).isEqualTo(CertificateStatus.IN_TRANSIT);
        assertThat(transitAfter.quarantineObservation()).isTrue();
        ArrivalView transitArrival = service.arrive(new ArrivalCmd("A-LK-TRANSIT", inTransit.certNo(), 4));
        assertThat(transitArrival.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(transitArrival.quarantineObservation()).isTrue();
        assertThat(transitArrival.certStatus()).isEqualTo(CertificateStatus.CLOSED);

        // 封锁即刻生效：新申报一律驳回，新出证被拒绝。
        DeclarationView afterLock = declare("D-LK-AFTER", farm, Species.CATTLE, 1, "TAG-A");
        assertThat(afterLock.status()).isEqualTo(DeclarationStatus.REJECTED);
        assertThat(afterLock.rejectReason()).contains("封锁");
    }

    @Test
    void lockdownIsIdempotentByRequestIdAndByFarm() {
        String farm = "F-LK-IDEM";
        LockdownView first = service.lockdown(new LockdownCmd("LK-IDEM", farm, "禽流感疫点"));
        LockdownView replay = service.lockdown(new LockdownCmd("LK-IDEM", farm, "禽流感疫点"));
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(countRows("lockdown_zone")).isEqualTo(1);

        // 同键异参 → 409。
        assertThatThrownBy(() -> service.lockdown(new LockdownCmd("LK-IDEM", farm, "另一种疫情")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        // 同场新键：已有生效封锁，返回既有封锁，不重复联动。
        LockdownView second = service.lockdown(new LockdownCmd("LK-IDEM-2", farm, "禽流感疫点"));
        assertThat(second.requestId()).isEqualTo("LK-IDEM");
        assertThat(countRows("lockdown_zone")).isEqualTo(1);
    }

    // ---------- 出证核减原子性 ----------

    @Test
    void issueDecrementsStockAndGeneratesCertNoAtomically() {
        String farm = "F-STOCK";
        stock(farm, Species.SHEEP, "TAG-7", 10);
        DeclarationView declaration = declare("D-ST-1", farm, Species.SHEEP, 4, "TAG-7");
        CertificateView cert = issue("I-ST-1", declaration.requestId());

        assertThat(cert.certNo()).startsWith("AQ");
        assertThat(stockQty(farm, Species.SHEEP, "TAG-7")).isEqualTo(6);
        assertThat(service.getDeclaration(declaration.requestId()).status())
                .isEqualTo(DeclarationStatus.CERTIFIED);
    }

    @Test
    void issueWithInsufficientStockFailsAndLeavesNoTrace() {
        String farm = "F-STOCK-LOW";
        stock(farm, Species.SHEEP, "TAG-7", 3);
        DeclarationView declaration = declare("D-ST-LOW", farm, Species.SHEEP, 5, "TAG-7");

        assertThatThrownBy(() -> issue("I-ST-LOW", declaration.requestId()))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 事务回滚：存栏不变、无证生成、申报仍为已申报。
        assertThat(stockQty(farm, Species.SHEEP, "TAG-7")).isEqualTo(3);
        assertThat(countRows("quarantine_certificate")).isZero();
        assertThat(countRows("cert_batch_hold")).isZero();
        assertThat(service.getDeclaration(declaration.requestId()).status())
                .isEqualTo(DeclarationStatus.DECLARED);
    }

    // ---------- 幂等：同键重放与同键异参 ----------

    @Test
    void sameKeySameParamsReplayReturnsFirstResult() {
        String farm = "F-IDEM";
        stock(farm, Species.PIG, "TAG-1", 20);
        DeclarationCmd cmd = new DeclarationCmd("D-IDEM", farm, "DEST-X", Species.PIG, 6, "TAG-1");
        DeclarationView first = service.declare(cmd);
        DeclarationView replay = service.declare(cmd);
        assertThat(replay).isEqualTo(first);
        assertThat(countRows("quarantine_declaration")).isEqualTo(1);

        CertificateView certFirst = issue("I-IDEM", first.requestId());
        CertificateView certReplay = issue("I-IDEM", first.requestId());
        assertThat(certReplay).isEqualTo(certFirst);
        assertThat(countRows("quarantine_certificate")).isEqualTo(1);
        // 重放不重复核减存栏。
        assertThat(stockQty(farm, Species.PIG, "TAG-1")).isEqualTo(14);

        depart(certFirst.certNo());
        ArrivalView arrivalFirst = service.arrive(new ArrivalCmd("A-IDEM", certFirst.certNo(), 2));
        ArrivalView arrivalReplay = service.arrive(new ArrivalCmd("A-IDEM", certFirst.certNo(), 2));
        assertThat(arrivalReplay).isEqualTo(arrivalFirst);
        assertThat(countRows("arrival_report")).isEqualTo(1);
        // 重放不重复累计落地数量。
        assertThat(service.getCertificate(certFirst.certNo()).reportedQty()).isEqualTo(2);
    }

    @Test
    void sameKeyDifferentParamsReturns409AndKeepsState() {
        String farm = "F-IDEM-409";
        stock(farm, Species.PIG, "TAG-1", 20);
        service.declare(new DeclarationCmd("D-409", farm, "DEST-X", Species.PIG, 6, "TAG-1"));
        // 申报同键异参 → 409，原单不变。
        assertThatThrownBy(() -> service.declare(
                new DeclarationCmd("D-409", farm, "DEST-Y", Species.PIG, 6, "TAG-1")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(service.getDeclaration("D-409").destination()).isEqualTo("DEST-X");

        issue("I-409", "D-409");
        // 出证同键异参 → 409。
        assertThatThrownBy(() -> issueExecutor.execute(new IssueCmd("I-409", "D-OTHER")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(countRows("quarantine_certificate")).isEqualTo(1);

        CertificateView cert = service.getCertificate(
                jdbc.queryForObject("SELECT cert_no FROM quarantine_certificate", String.class));
        depart(cert.certNo());
        service.arrive(new ArrivalCmd("A-409", cert.certNo(), 2));
        // 落地报告同键异参 → 409，累计数量不变。
        assertThatThrownBy(() -> service.arrive(new ArrivalCmd("A-409", cert.certNo(), 3)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(service.getCertificate(cert.certNo()).reportedQty()).isEqualTo(2);
    }

    // ---------- 同批动物唯一有效证 ----------

    @Test
    void duplicateIssueForSameBatchReturnsExistingCert() {
        String farm = "F-BATCH";
        stock(farm, Species.CATTLE, "TAG-5", 20);
        DeclarationView d1 = declare("D-B1", farm, Species.CATTLE, 5, "TAG-5");
        DeclarationView d2 = declare("D-B2", farm, Species.CATTLE, 5, "TAG-5");

        CertificateView cert1 = issue("I-B1", d1.requestId());
        CertificateView cert2 = issue("I-B2", d2.requestId());
        // 重复出证按幂等处理：返回同一张有效证，存栏只核减一次。
        assertThat(cert2.certNo()).isEqualTo(cert1.certNo());
        assertThat(countRows("quarantine_certificate")).isEqualTo(1);
        assertThat(stockQty(farm, Species.CATTLE, "TAG-5")).isEqualTo(15);
    }

    // ---------- 落地报告：分批、超量、结案终态 ----------

    @Test
    void partialArrivalsAccumulateAndOverflowIsRejectedAsWhole() {
        String farm = "F-ARR";
        stock(farm, Species.PIG, "TAG-2", 10);
        DeclarationView declaration = declare("D-ARR", farm, Species.PIG, 10, "TAG-2");
        CertificateView cert = issue("I-ARR", declaration.requestId());
        depart(cert.certNo());

        ArrivalView partial = service.arrive(new ArrivalCmd("A-ARR-1", cert.certNo(), 4));
        assertThat(partial.result()).isEqualTo(ArrivalResult.ACCEPTED_PARTIAL);
        assertThat(partial.reportedQty()).isEqualTo(4);

        // 累计 4+7=11 超证载 10：整批拒绝，已受理数量不变。
        ArrivalView overflow = service.arrive(new ArrivalCmd("A-ARR-2", cert.certNo(), 7));
        assertThat(overflow.result()).isEqualTo(ArrivalResult.REJECTED_OVERFLOW);
        assertThat(service.getCertificate(cert.certNo()).reportedQty()).isEqualTo(4);
        assertThat(service.getCertificate(cert.certNo()).status()).isEqualTo(CertificateStatus.IN_TRANSIT);

        // 补足余量恰等于证载数量：结案。
        ArrivalView last = service.arrive(new ArrivalCmd("A-ARR-3", cert.certNo(), 6));
        assertThat(last.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);
        assertThat(last.certStatus()).isEqualTo(CertificateStatus.CLOSED);
    }

    @Test
    void closedCertIsTerminalAndLateEventsAreOnlyRecorded() {
        String farm = "F-TERM";
        stock(farm, Species.PIG, "TAG-3", 10);
        DeclarationView declaration = declare("D-TERM", farm, Species.PIG, 5, "TAG-3");
        CertificateView cert = issue("I-TERM", declaration.requestId());
        depart(cert.certNo());
        ArrivalView finalReport = service.arrive(new ArrivalCmd("A-TERM", cert.certNo(), 5));
        assertThat(finalReport.result()).isEqualTo(ArrivalResult.ACCEPTED_FINAL);

        // 重复报告（同键同参）返回首次结果。
        ArrivalView replay = service.arrive(new ArrivalCmd("A-TERM", cert.certNo(), 5));
        assertThat(replay).isEqualTo(finalReport);

        // 迟到事件（新键）：只记录不改状态。
        ArrivalView late = service.arrive(new ArrivalCmd("A-TERM-LATE", cert.certNo(), 1));
        assertThat(late.result()).isEqualTo(ArrivalResult.LATE_IGNORED);
        CertificateView after = service.getCertificate(cert.certNo());
        assertThat(after.status()).isEqualTo(CertificateStatus.CLOSED);
        assertThat(after.reportedQty()).isEqualTo(5);
        assertThat(countRows("arrival_report")).isEqualTo(2);
    }

    @Test
    void arrivalOnNotDepartedCertIsRejected() {
        String farm = "F-NOTDEP";
        stock(farm, Species.PIG, "TAG-4", 10);
        DeclarationView declaration = declare("D-NOTDEP", farm, Species.PIG, 5, "TAG-4");
        CertificateView cert = issue("I-NOTDEP", declaration.requestId());

        ArrivalView arrival = service.arrive(new ArrivalCmd("A-NOTDEP", cert.certNo(), 5));
        assertThat(arrival.result()).isEqualTo(ArrivalResult.REJECTED_NOT_IN_TRANSIT);
        assertThat(service.getCertificate(cert.certNo()).status()).isEqualTo(CertificateStatus.ISSUED);
    }

    // ---------- 启运 ----------

    @Test
    void departIsIdempotentAndRejectsInvalidStates() {
        String farm = "F-DEP";
        stock(farm, Species.PIG, "TAG-6", 10);
        DeclarationView declaration = declare("D-DEP", farm, Species.PIG, 5, "TAG-6");
        CertificateView cert = issue("I-DEP", declaration.requestId());

        CertificateView departed = depart(cert.certNo());
        assertThat(departed.status()).isEqualTo(CertificateStatus.IN_TRANSIT);
        assertThat(departed.departedAt()).isEqualTo(TestClockConfig.BASE_INSTANT);
        // 重复启运幂等返回当前状态。
        CertificateView again = depart(cert.certNo());
        assertThat(again.status()).isEqualTo(CertificateStatus.IN_TRANSIT);
        assertThat(countRows("quarantine_certificate")).isEqualTo(1);
    }
}
