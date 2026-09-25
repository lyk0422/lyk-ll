package com.example.starter.carbon.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.starter.carbon.repo.CarbonLedgerRepository;
import com.example.starter.carbon.repo.CarbonLedgerRepository.AssessmentRow;
import com.example.starter.carbon.repo.CarbonLedgerRepository.BatchRow;
import com.example.starter.carbon.repo.CarbonLedgerRepository.EnterpriseRow;
import com.example.starter.carbon.repo.SurrenderRequestRepository;
import com.example.starter.carbon.repo.SurrenderRequestRepository.CertificateRow;
import com.example.starter.carbon.repo.SurrenderRequestRepository.RequestRow;
import com.example.starter.carbon.repo.SurrenderRequestRepository.RetirementRow;
import com.example.starter.carbon.web.CertificateResponse;
import com.example.starter.carbon.web.RetirementItem;
import com.example.starter.carbon.web.SurrenderRequest;
import com.example.starter.carbon.web.SurrenderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 碳排放配额清缴与抵消核销核心服务。
 *
 * <p>关键规则：</p>
 * <ul>
 *   <li>同一企业同一履约年度可多笔累计清缴，累计配额+抵消须不超过核定排放量；</li>
 *   <li>累计抵消抵减不得超过排放量的 5%，恰好用满 5% 合法，超出整笔拒绝；</li>
 *   <li>配额/抵消按“先进先出＋到期日近者优先”逐批扣减，单笔组合扣减原子；</li>
 *   <li>requestId 幂等：同键同参返回首次注销清单，同键异参 409 且账户不变；</li>
 *   <li>企业+年度串行锁保证并发申请按提交顺序串行生效；</li>
 *   <li>请求到达时刻（进入服务时的 UTC 时刻）严格晚于截止时刻才拒绝，恰在截止时刻受理。</li>
 * </ul>
 *
 * <p>任何业务规则冲突均抛 {@link SurrenderRuleException}，由声明式事务整体回滚，
 * 保证“整笔拒绝且账户无变动”；落库的申请仅包含成功注销的申请。</p>
 */
@Service
public class SurrenderService {

    private static final Logger log = LoggerFactory.getLogger(SurrenderService.class);

    private static final BigDecimal OFFSET_CAP_RATE = new BigDecimal("0.05");
    private static final String STATUS_RETIRED = "RETIRED";
    private static final String ASSESSMENT_SURRENDERED = "SURRENDERED";
    private static final String ASSESSMENT_NOT_COMPLIED = "NOT_COMPLIED";

    private final CarbonLedgerRepository ledger;
    private final SurrenderRequestRepository requests;
    private final SystemTimeProvider timeProvider;

    public SurrenderService(CarbonLedgerRepository ledger,
                            SurrenderRequestRepository requests,
                            SystemTimeProvider timeProvider) {
        this.ledger = ledger;
        this.requests = requests;
        this.timeProvider = timeProvider;
    }

    /**
     * 受理并原子处理一笔清缴申请。成功返回注销清单与凭证号；违反任一规则抛
     * {@link SurrenderRuleException} 且事务回滚、账户无任何变动。
     */
    @Transactional
    public SurrenderResponse submit(SurrenderRequest request) {
        // 请求到达时刻在任何锁等待之前确定，截止判定以此为准，保证结果唯一。
        Instant arrivalUtc = timeProvider.now();

        BigDecimal allowanceAmount = Fingerprints.normalize(request.allowanceAmount());
        BigDecimal offsetAmount = Fingerprints.normalize(request.offsetAmount());
        if (allowanceAmount.signum() < 0 || offsetAmount.signum() < 0) {
            throw rule("VALIDATION_FAILED", "清缴数量不得为负");
        }
        if (allowanceAmount.signum() == 0 && offsetAmount.signum() == 0) {
            throw rule("EMPTY_SURRENDER", "配额清缴量与抵消抵减量不能同时为0");
        }

        EnterpriseRow enterprise = ledger.findEnterpriseByCode(request.enterpriseCode());
        if (enterprise == null) {
            throw rule("ENTERPRISE_NOT_FOUND", "企业不存在: " + request.enterpriseCode());
        }

        // 同一企业同一履约年度的所有申请在此串行，重放请求同样排队，避免与首笔并发交叉。
        ledger.ensureComplianceLock(enterprise.id(), request.complianceYear());
        ledger.lockComplianceYear(enterprise.id(), request.complianceYear());

        RequestRow existing = requests.findByRequestId(request.requestId());
        if (existing != null) {
            return replay(existing, enterprise.code(), request.complianceYear(),
                    allowanceAmount, offsetAmount);
        }

        AssessmentRow assessment = ledger.findAssessment(
                enterprise.id(), request.complianceYear(), true);
        if (assessment == null) {
            throw rule("ASSESSMENT_NOT_FOUND", "该企业无此履约年度核定信息");
        }
        if (ASSESSMENT_NOT_COMPLIED.equals(assessment.status())) {
            throw rule("ALREADY_NOT_COMPLIED", "该履约年度已被标记为未履约，逾期申请不予受理");
        }
        if (ASSESSMENT_SURRENDERED.equals(assessment.status())) {
            throw rule("ALREADY_SURRENDERED", "该履约年度已足额清缴，不得再次申请");
        }
        if (arrivalUtc.isAfter(assessment.deadlineUtc())) {
            throw rule("PAST_DEADLINE", "请求到达时刻已晚于履约截止时刻（UTC），逾期申请一律拒绝");
        }

        BigDecimal emission = Fingerprints.normalize(assessment.verifiedEmission());
        BigDecimal offsetCap = Fingerprints.normalize(emission.multiply(OFFSET_CAP_RATE));
        Map<String, BigDecimal> already =
                requests.sumRetiredAmounts(enterprise.id(), request.complianceYear());
        BigDecimal cumulativeOffset = already.get("offset").add(offsetAmount);
        if (cumulativeOffset.compareTo(offsetCap) > 0) {
            throw rule("OFFSET_CAP_EXCEEDED",
                    "抵消抵减累计 " + cumulativeOffset.toPlainString()
                            + " 吨超过排放量5%上限 " + offsetCap.toPlainString() + " 吨，整笔申请拒绝");
        }
        BigDecimal cumulativeTotal = already.get("allowance").add(already.get("offset"))
                .add(allowanceAmount).add(offsetAmount);
        if (cumulativeTotal.compareTo(emission) > 0) {
            throw rule("SURRENDER_EXCEEDS_EMISSION",
                    "累计清缴 " + cumulativeTotal.toPlainString()
                            + " 吨超过核定排放量 " + emission.toPlainString() + " 吨，整笔申请拒绝");
        }

        // 先在锁内形成两类账户的完整扣减计划：任一账户可用余额不足则抛错回滚，账户无任何变动。
        List<RetirementRow> plan = new ArrayList<>();
        appendDeductionPlan(plan, CarbonLedgerRepository.TYPE_ALLOWANCE, request.requestId(),
                ledger.findActiveBatchesForUpdate(enterprise.id(), CarbonLedgerRepository.TYPE_ALLOWANCE),
                allowanceAmount);
        appendDeductionPlan(plan, CarbonLedgerRepository.TYPE_OFFSET, request.requestId(),
                ledger.findActiveBatchesForUpdate(enterprise.id(), CarbonLedgerRepository.TYPE_OFFSET),
                offsetAmount);

        Instant processedUtc = timeProvider.now();
        String fingerprint = Fingerprints.of(request.requestId(), enterprise.code(),
                request.complianceYear(), allowanceAmount, offsetAmount);
        long requestRowId;
        try {
            requestRowId = requests.insertRequestAndReturnId(new RequestRow(
                    0, request.requestId(), enterprise.id(), request.complianceYear(),
                    allowanceAmount, offsetAmount, fingerprint,
                    STATUS_RETIRED, null, arrivalUtc, processedUtc));
        } catch (DuplicateKeyException dup) {
            // 串行锁兜底之外的同键并发：首笔已落库则转为重放比对，尚未发生任何扣减。
            RequestRow winner = requests.findByRequestId(request.requestId());
            if (winner != null) {
                return replay(winner, enterprise.code(), request.complianceYear(),
                        allowanceAmount, offsetAmount);
            }
            throw dup;
        }

        // 执行条件扣减并写注销明细；FOR UPDATE 锁内条件更新失败理论上不会发生，失败即抛错回滚整笔。
        int seq = 0;
        for (RetirementRow item : plan) {
            seq++;
            int updated = ledger.deductBatch(item.accountType(), item.batchId(), item.amount());
            if (updated != 1) {
                throw rule("BALANCE_CHANGED",
                        "批次 " + item.batchNo() + " 余额在处理期间变化，整笔申请拒绝");
            }
            ledger.retireBatchIfEmpty(item.accountType(), item.batchId());
            requests.insertRetirement(requestRowId, new RetirementRow(
                    0, requestRowId, item.requestId(), item.accountType(), item.batchId(),
                    item.batchNo(), item.amount(), seq));
        }

        if (cumulativeTotal.compareTo(emission) == 0) {
            int migrated = ledger.markSurrendered(assessment.id());
            if (migrated != 1) {
                // 与逾期标记并发且对方抢先：必须回滚全部扣减，形成唯一结果。
                throw rule("ASSESSMENT_STATE_CHANGED", "履约年度状态已变化，整笔申请拒绝");
            }
        }

        String certificateNo = Fingerprints.certificateNo(request.complianceYear(), request.requestId());
        requests.insertCertificate(new CertificateRow(
                0, certificateNo, request.requestId(), enterprise.id(), request.complianceYear(),
                allowanceAmount.add(offsetAmount), allowanceAmount, offsetAmount, processedUtc));

        log.info("清缴申请受理成功 requestId={} enterprise={} year={} allowance={} offset={}",
                request.requestId(), enterprise.code(), request.complianceYear(),
                allowanceAmount, offsetAmount);

        return buildResponse(request.requestId(), enterprise.code(), request.complianceYear(),
                allowanceAmount, offsetAmount, certificateNo, processedUtc, requestRowId);
    }

    /**
     * 按凭证号查询清缴凭证；重复查询结果稳定，不存在抛 404。
     */
    public CertificateResponse getCertificate(String certificateNo) {
        CertificateRow row = requests.findCertificateByNo(certificateNo);
        if (row == null) {
            throw new NotFoundException("清缴凭证不存在: " + certificateNo);
        }
        EnterpriseRow enterprise = ledger.findEnterpriseById(row.enterpriseId());
        return new CertificateResponse(row.certificateNo(), row.requestId(),
                enterprise == null ? null : enterprise.code(), row.complianceYear(),
                row.totalRetired(), row.allowanceRetired(), row.offsetRetired(), row.issuedUtc());
    }

    /**
     * 逾期扫描：将当前 UTC 时刻已过截止时刻且仍未足额（PENDING）的企业年度标记为 NOT_COMPLIED。
     * 恰在截止时刻不标记。单条条件更新与清缴事务在同一核定行上互斥，截止与受理并发时只有一个结果。
     * 返回本次标记的行数。
     */
    @Transactional
    public int markOverdueAssessments() {
        int marked = ledger.markNotComplied(timeProvider.now());
        if (marked > 0) {
            log.info("逾期未履约标记完成，本次标记 {} 个企业年度", marked);
        }
        return marked;
    }

    /**
     * 同键重放：指纹一致返回首次注销清单与凭证，指纹不一致抛 409 且账户不变。
     * 指纹覆盖 requestId、企业、年度与组合明细，任一字段不同均判为异参冲突。
     */
    private SurrenderResponse replay(RequestRow existing, String enterpriseCode, int complianceYear,
                                     BigDecimal allowanceAmount, BigDecimal offsetAmount) {
        String incomingFingerprint = Fingerprints.of(existing.requestId(), enterpriseCode,
                complianceYear, allowanceAmount, offsetAmount);
        if (!incomingFingerprint.equals(existing.fingerprint())) {
            throw new IdempotencyConflictException(
                    "requestId=" + existing.requestId() + " 已存在但申请参数不一致，拒绝处理且账户不变");
        }
        CertificateRow certificate = requests.findCertificateByRequestId(existing.requestId());
        if (certificate == null) {
            // 落库申请均为成功注销，凭证缺失属于数据不一致，不可静默。
            throw new IllegalStateException("成功申请缺少凭证: " + existing.requestId());
        }
        return buildResponse(existing.requestId(), enterpriseCode, existing.complianceYear(),
                existing.allowanceAmount(), existing.offsetAmount(),
                certificate.certificateNo(), existing.processedUtc(), existing.id());
    }

    /**
     * 追加一个账户的扣减计划：按批次顺序（先进先出、到期日近者优先）逐批消耗；
     * 可用总量不足时抛业务异常，整笔申请拒绝。
     */
    private void appendDeductionPlan(List<RetirementRow> plan, String accountType, String requestId,
                                     List<BatchRow> batches, BigDecimal need) {
        BigDecimal remainingNeed = need;
        for (BatchRow batch : batches) {
            if (remainingNeed.signum() == 0) {
                break;
            }
            BigDecimal take = batch.remainingAmount().min(remainingNeed);
            if (take.signum() > 0) {
                plan.add(new RetirementRow(0, 0, requestId, accountType, batch.id(),
                        batch.batchNo(), Fingerprints.normalize(take), plan.size() + 1));
                remainingNeed = remainingNeed.subtract(take);
            }
        }
        if (remainingNeed.signum() > 0) {
            boolean offset = CarbonLedgerRepository.TYPE_OFFSET.equals(accountType);
            throw rule(offset ? "OFFSET_INSUFFICIENT" : "ALLOWANCE_INSUFFICIENT",
                    (offset ? "核证抵消信用余额不足" : "配额余额不足")
                            + "，尚缺 " + remainingNeed.toPlainString()
                            + " 吨，整笔申请拒绝且账户无变动");
        }
    }

    private SurrenderResponse buildResponse(String requestId, String enterpriseCode, int year,
                                            BigDecimal allowanceAmount, BigDecimal offsetAmount,
                                            String certificateNo, Instant processedUtc,
                                            long requestRowId) {
        List<RetirementItem> items = requests.findRetirements(requestRowId).stream()
                .map(r -> new RetirementItem(r.accountType(), r.batchNo(), r.amount(), r.seqNo()))
                .toList();
        return new SurrenderResponse(requestId, enterpriseCode, year, STATUS_RETIRED, null,
                allowanceAmount, offsetAmount, certificateNo, items, processedUtc);
    }

    private static SurrenderRuleException rule(String code, String message) {
        return new SurrenderRuleException(code, message);
    }
}
