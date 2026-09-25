package com.example.starter.carbon;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.carbon.repo.CarbonLedgerRepository;
import com.example.starter.carbon.service.IdempotencyConflictException;
import com.example.starter.carbon.service.NotFoundException;
import com.example.starter.carbon.service.SurrenderRuleException;
import com.example.starter.carbon.service.SurrenderService;
import com.example.starter.carbon.web.CertificateResponse;
import com.example.starter.carbon.web.RetirementItem;
import com.example.starter.carbon.web.SurrenderRequest;
import com.example.starter.carbon.web.SurrenderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 清缴核心规则测试：5%抵减边界、FIFO+到期优先、组合原子性、幂等重放/冲突、截止口径、凭证稳定性。
 */
class SurrenderCoreRulesTest extends AbstractCarbonIntegrationTest {

    private static final String ENT = "E-001";
    private static final int YEAR = 2025;
    private static final String DEADLINE = "2026-06-30T16:00:00Z";

    @Autowired
    private SurrenderService service;

    private long enterpriseId;

    @BeforeEach
    void setUpScenario() {
        freezeAt("2026-01-01T00:00:00Z");
        enterpriseId = createEnterprise(ENT, "测试企业");
        createAssessment(enterpriseId, YEAR, "1000.000", DEADLINE);
        // 配额：A/B 同获得日、到期日不同；C 获得日更早但到期日最远——检验先按获得日再按到期日。
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, enterpriseId, "AL-A", "400",
                "2025-03-01", "2026-03-31");
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, enterpriseId, "AL-B", "400",
                "2025-03-01", "2026-09-30");
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, enterpriseId, "AL-C", "400",
                "2025-01-01", "2027-12-31");
        // 抵消信用两批。
        createBatch(CarbonLedgerRepository.TYPE_OFFSET, enterpriseId, "OFF-A", "30",
                "2025-02-01", "2026-12-31");
        createBatch(CarbonLedgerRepository.TYPE_OFFSET, enterpriseId, "OFF-B", "30",
                "2025-04-01", "2027-12-31");
    }

    private SurrenderRequest request(String requestId, String allowance, String offset) {
        return new SurrenderRequest(requestId, ENT, YEAR,
                new BigDecimal(allowance), new BigDecimal(offset));
    }

    @Test
    @DisplayName("主流程：950配额+50抵消（恰满5%）成功，注销顺序满足FIFO+到期日近者优先")
    void fullSurrenderWithExactlyFivePercentOffset() {
        SurrenderResponse response = service.submit(request("REQ-1", "950", "50"));

        assertThat(response.status()).isEqualTo("RETIRED");
        assertThat(response.certificateNo()).startsWith("CRT-2025-");
        assertThat(response.allowanceAmount()).isEqualByComparingTo("950");
        assertThat(response.offsetAmount()).isEqualByComparingTo("50");

        List<String> order = response.retirements().stream().map(RetirementItem::batchNo).toList();
        // 配额：C 获得日最早先扣（400）；A/B 同获得日，A 到期更近先扣（400）；再从 B 扣 150。
        // 抵消：A 获得日更早，30 扣满；再从 B 扣 20。
        assertThat(order).containsExactly("AL-C", "AL-A", "AL-B", "OFF-A", "OFF-B");

        assertThat(response.retirements()).extracting(RetirementItem::amount)
                .map(BigDecimal::stripTrailingZeros)
                .containsExactly(new BigDecimal("400").stripTrailingZeros(),
                        new BigDecimal("400").stripTrailingZeros(),
                        new BigDecimal("150").stripTrailingZeros(),
                        new BigDecimal("30").stripTrailingZeros(),
                        new BigDecimal("20").stripTrailingZeros());
        assertThat(response.retirements()).extracting(RetirementItem::seqNo)
                .containsExactly(1, 2, 3, 4, 5);

        // 扣空批次进入终态，未扣完批次保持 ACTIVE。
        assertThat(ledger.batchStatus("ALLOWANCE", "AL-C")).isEqualTo("RETIRED");
        assertThat(ledger.batchStatus("ALLOWANCE", "AL-A")).isEqualTo("RETIRED");
        assertThat(ledger.remainingAmount("ALLOWANCE", "AL-B")).isEqualByComparingTo("250");
        assertThat(ledger.batchStatus("OFFSET", "OFF-A")).isEqualTo("RETIRED");
        assertThat(ledger.remainingAmount("OFFSET", "OFF-B")).isEqualByComparingTo("10");
        assertThat(ledger.assessmentStatus(enterpriseId, YEAR)).isEqualTo("SURRENDERED");
    }

    @Test
    @DisplayName("5%边界：抵消50.001吨超出上限，整笔拒绝且配额/抵消账户均无变动")
    void offsetBeyondFivePercentRejectsWholeRequestAtomically() {
        assertThatThrownBy(() -> service.submit(request("REQ-CAP", "949.999", "50.001")))
                .isInstanceOfSatisfying(SurrenderRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("OFFSET_CAP_EXCEEDED"));

        // 规则校验先于任何扣减：账户无任何变动，无凭证。
        assertThat(ledger.remainingAmount("ALLOWANCE", "AL-A")).isEqualByComparingTo("400");
        assertThat(ledger.remainingAmount("ALLOWANCE", "AL-B")).isEqualByComparingTo("400");
        assertThat(ledger.remainingAmount("ALLOWANCE", "AL-C")).isEqualByComparingTo("400");
        assertThat(ledger.remainingAmount("OFFSET", "OFF-A")).isEqualByComparingTo("30");
        assertThat(ledger.remainingAmount("OFFSET", "OFF-B")).isEqualByComparingTo("30");
        assertThat(ledger.assessmentStatus(enterpriseId, YEAR)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("组合原子性：抵消余额不足时同笔配额扣减一并回滚，账户无变动")
    void insufficientOffsetRollsBackAllowanceDeduction() {
        // 独立场景：抵消额度45 ≤ 5%上限50（先过上限校验），但抵消账户仅40吨可用，触发余额不足。
        long entId = createEnterprise("E-ATOM-1", "原子性企业1");
        createAssessment(entId, YEAR, "1000.000", DEADLINE);
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, entId, "ATOM-AL", "960",
                "2025-01-01", "2026-12-31");
        createBatch(CarbonLedgerRepository.TYPE_OFFSET, entId, "ATOM-OFF", "40",
                "2025-01-01", "2026-12-31");

        // 配额955足够、抵消45在5%上限内但余额不足：整笔回滚。
        assertThatThrownBy(() -> service.submit(new SurrenderRequest(
                "REQ-ATOM", "E-ATOM-1", YEAR, new BigDecimal("955"), new BigDecimal("45"))))
                .isInstanceOfSatisfying(SurrenderRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("OFFSET_INSUFFICIENT"));

        assertThat(ledger.remainingAmount("ALLOWANCE", "ATOM-AL")).isEqualByComparingTo("960");
        assertThat(ledger.remainingAmount("OFFSET", "ATOM-OFF")).isEqualByComparingTo("40");
        assertThat(ledger.assessmentStatus(entId, YEAR)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("组合原子性：配额余额不足时整笔拒绝，抵消账户无变动")
    void insufficientAllowanceRejectsAtomically() {
        // 独立场景：配额账户仅900吨，申请950配额+50抵消（合计1000=排放量，抵消恰满5%）。
        long entId = createEnterprise("E-ATOM-2", "原子性企业2");
        createAssessment(entId, YEAR, "1000.000", DEADLINE);
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, entId, "ATOM-AL-2", "900",
                "2025-01-01", "2026-12-31");
        createBatch(CarbonLedgerRepository.TYPE_OFFSET, entId, "ATOM-OFF-2", "50",
                "2025-01-01", "2026-12-31");

        assertThatThrownBy(() -> service.submit(new SurrenderRequest(
                "REQ-AL-LOW", "E-ATOM-2", YEAR, new BigDecimal("950"), new BigDecimal("50"))))
                .isInstanceOfSatisfying(SurrenderRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ALLOWANCE_INSUFFICIENT"));
        assertThat(ledger.remainingAmount("ALLOWANCE", "ATOM-AL-2")).isEqualByComparingTo("900");
        assertThat(ledger.remainingAmount("OFFSET", "ATOM-OFF-2")).isEqualByComparingTo("50");
        assertThat(ledger.assessmentStatus(entId, YEAR)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("累计抵消上限跨申请生效：先用25吨合法，再用26吨拒绝且第二笔无扣减")
    void offsetCapEnforcedAcrossCumulativeRequests() {
        SurrenderResponse first = service.submit(request("REQ-C1", "500", "25"));
        assertThat(first.status()).isEqualTo("RETIRED");
        assertThat(ledger.remainingAmount("OFFSET", "OFF-A")).isEqualByComparingTo("5");

        assertThatThrownBy(() -> service.submit(request("REQ-C2", "450", "26")))
                .isInstanceOfSatisfying(SurrenderRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("OFFSET_CAP_EXCEEDED"));
        // 第二笔整笔拒绝：配额未动、第一笔抵消剩余保持 5 吨。
        assertThat(ledger.remainingAmount("OFFSET", "OFF-A")).isEqualByComparingTo("5");
        // 累计清缴仅 525 吨，年度仍 PENDING。
        assertThat(ledger.assessmentStatus(enterpriseId, YEAR)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("累计清缴不得超过排放量：首笔500成功，第二笔501抛错回滚，再补500足额")
    void cumulativeSurrenderCannotExceedEmission() {
        assertThat(service.submit(request("REQ-P1", "500", "0")).status()).isEqualTo("RETIRED");
        assertThatThrownBy(() -> service.submit(request("REQ-P2", "501", "0")))
                .isInstanceOfSatisfying(SurrenderRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SURRENDER_EXCEEDS_EMISSION"));
        assertThat(ledger.assessmentStatus(enterpriseId, YEAR)).isEqualTo("PENDING");

        SurrenderResponse done = service.submit(request("REQ-P3", "500", "0"));
        assertThat(done.status()).isEqualTo("RETIRED");
        assertThat(ledger.assessmentStatus(enterpriseId, YEAR)).isEqualTo("SURRENDERED");
    }

    @Test
    @DisplayName("幂等重放：同键同参返回首次注销清单与凭证号，账户不二次扣减")
    void sameRequestIdSameParamsReplaysFirstResult() {
        SurrenderResponse first = service.submit(request("REQ-IDEM", "950", "50"));
        SurrenderResponse replay = service.submit(request("REQ-IDEM", "950", "50"));

        assertThat(replay.status()).isEqualTo("RETIRED");
        assertThat(replay.certificateNo()).isEqualTo(first.certificateNo());
        assertThat(replay.processedUtc()).isEqualTo(first.processedUtc());
        assertThat(replay.retirements()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(first.retirements());
        // 重放不产生第二次扣减。
        assertThat(ledger.remainingAmount("ALLOWANCE", "AL-B")).isEqualByComparingTo("250");
        assertThat(ledger.remainingAmount("OFFSET", "OFF-B")).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("幂等冲突：同键异参返回409且账户不变")
    void sameRequestIdDifferentParamsConflicts() {
        assertThat(service.submit(request("REQ-DUP", "950", "50")).status()).isEqualTo("RETIRED");
        assertThatThrownBy(() -> service.submit(request("REQ-DUP", "960", "40")))
                .isInstanceOf(IdempotencyConflictException.class);
        // 账户维持首次扣减结果。
        assertThat(ledger.remainingAmount("ALLOWANCE", "AL-B")).isEqualByComparingTo("250");
        assertThat(ledger.remainingAmount("OFFSET", "OFF-B")).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("截止口径：恰在截止时刻受理，晚1秒拒绝；逾期标记后申请一律拒绝")
    void deadlineBoundaryAcceptAtDeadlineRejectAfter() {
        freezeAt(DEADLINE);
        SurrenderResponse atDeadline = service.submit(request("REQ-EDGE", "950", "50"));
        assertThat(atDeadline.status()).isEqualTo("RETIRED");
        assertThat(ledger.assessmentStatus(enterpriseId, YEAR)).isEqualTo("SURRENDERED");

        // 另一个企业年度：晚 1 秒到达直接拒绝。
        long otherId = createEnterprise("E-002", "测试企业2");
        createAssessment(otherId, YEAR, "100.000", DEADLINE);
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, otherId, "AL-X", "100",
                "2025-01-01", "2027-01-01");
        freezeAt("2026-06-30T16:00:01Z");
        assertThatThrownBy(() -> service.submit(new SurrenderRequest(
                "REQ-LATE", "E-002", YEAR, new BigDecimal("100"), BigDecimal.ZERO)))
                .isInstanceOfSatisfying(SurrenderRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAST_DEADLINE"));

        // 逾期扫描只标记严格过期者，随后申请被拒。
        long thirdId = createEnterprise("E-003", "测试企业3");
        createAssessment(thirdId, YEAR, "100.000", DEADLINE);
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, thirdId, "AL-Y", "100",
                "2025-01-01", "2027-01-01");
        int marked = service.markOverdueAssessments();
        assertThat(marked).isGreaterThanOrEqualTo(1);
        assertThat(ledger.assessmentStatus(thirdId, YEAR)).isEqualTo("NOT_COMPLIED");
        assertThatThrownBy(() -> service.submit(new SurrenderRequest(
                "REQ-MARKED", "E-003", YEAR, new BigDecimal("100"), BigDecimal.ZERO)))
                .isInstanceOfSatisfying(SurrenderRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ALREADY_NOT_COMPLIED"));
    }

    @Test
    @DisplayName("逾期扫描口径：恰在截止时刻不标记")
    void overdueScanAtExactDeadlineDoesNotMark() {
        freezeAt(DEADLINE);
        assertThat(service.markOverdueAssessments()).isZero();
        assertThat(ledger.assessmentStatus(enterpriseId, YEAR)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("凭证：签发后重复查询结果稳定，不同申请凭证号唯一")
    void certificateIsStableAndUnique() {
        SurrenderResponse first = service.submit(request("REQ-CERT-1", "500", "0"));
        service.submit(request("REQ-CERT-2", "450", "50"));

        CertificateResponse c1 = service.getCertificate(first.certificateNo());
        CertificateResponse c1Again = service.getCertificate(first.certificateNo());
        assertThat(c1).isEqualTo(c1Again);
        assertThat(c1.requestId()).isEqualTo("REQ-CERT-1");
        assertThat(c1.enterpriseCode()).isEqualTo(ENT);
        assertThat(c1.totalRetired()).isEqualByComparingTo("500");
        assertThat(c1.allowanceRetired()).isEqualByComparingTo("500");
        assertThat(c1.offsetRetired()).isEqualByComparingTo("0");
        assertThat(c1.issuedUtc()).isEqualTo(first.processedUtc());

        CertificateResponse c2 = service.getCertificate(service
                .submit(request("REQ-CERT-1", "500", "0")).certificateNo());
        assertThat(c2.certificateNo()).isEqualTo(c1.certificateNo());

        assertThatThrownBy(() -> service.getCertificate("CRT-NOPE"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("已足额清缴的年度再次申请被拒绝")
    void surrenderAgainAfterFullySurrenderedRejected() {
        assertThat(service.submit(request("REQ-DONE-1", "950", "50")).status()).isEqualTo("RETIRED");
        assertThatThrownBy(() -> service.submit(request("REQ-DONE-2", "10", "0")))
                .isInstanceOfSatisfying(SurrenderRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ALREADY_SURRENDERED"));
    }
}
