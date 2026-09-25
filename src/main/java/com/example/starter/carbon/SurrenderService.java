package com.example.starter.carbon;

import com.example.starter.carbon.ApiDtos.AllowanceGrantRequest;
import com.example.starter.carbon.ApiDtos.BatchView;
import com.example.starter.carbon.ApiDtos.CertificateView;
import com.example.starter.carbon.ApiDtos.ComplianceStatusView;
import com.example.starter.carbon.ApiDtos.EmissionAssessRequest;
import com.example.starter.carbon.ApiDtos.EnterpriseCreateRequest;
import com.example.starter.carbon.ApiDtos.EnterpriseView;
import com.example.starter.carbon.ApiDtos.OffsetCreditRequest;
import com.example.starter.carbon.ApiDtos.SurrenderApplyRequest;
import com.example.starter.carbon.ApiDtos.SurrenderItemView;
import com.example.starter.carbon.ApiDtos.SurrenderResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 碳市场履约服务：企业/核定/批次管理、清缴申请（幂等+串行）、凭证查询与履约状态标记。
 *
 * <p>幂等：指纹覆盖 requestId、企业、履约年度与组合明细；同键同参返回首次注销清单，
 * 同键异参返回 409 且账户不变。</p>
 *
 * <p>串行：同一企业同一履约年度的申请按提交顺序在公平锁下串行生效，叠加数据库行锁
 * （SELECT ... FOR UPDATE）与唯一约束兜底，杜绝合计超额抵减与重复扣减。</p>
 */
@Service
public class SurrenderService {

    private final CarbonRepository repo;
    private final SurrenderExecutor executor;
    private final Clock clock;
    private final CarbonProperties properties;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SurrenderService(CarbonRepository repo, SurrenderExecutor executor,
                            Clock carbonClock, CarbonProperties properties) {
        this.repo = repo;
        this.executor = executor;
        this.clock = carbonClock;
        this.properties = properties;
    }

    // ---------- 基础数据维护 ----------

    @Transactional
    public EnterpriseView createEnterprise(EnterpriseCreateRequest req) {
        repo.findEnterpriseByCode(req.code()).ifPresent(row -> {
            throw ApiException.conflict("ENTERPRISE_EXISTS", "企业编码已存在: " + req.code());
        });
        long id = repo.insertEnterprise(req.code(), req.name());
        return new EnterpriseView(id, req.code(), req.name());
    }

    @Transactional
    public void assessEmission(EmissionAssessRequest req) {
        long enterpriseId = requireEnterprise(req.enterpriseCode()).id();
        repo.upsertEmission(enterpriseId, req.complianceYear(), req.emissionAmount());
    }

    @Transactional
    public BatchView grantAllowance(AllowanceGrantRequest req) {
        long enterpriseId = requireEnterprise(req.enterpriseCode()).id();
        long id = repo.insertAllowanceBatch(enterpriseId, req.vintageYear(), req.amount(), req.expiryDate());
        return new BatchView(id, req.amount(), req.amount(), req.expiryDate(), "ACTIVE");
    }

    @Transactional
    public BatchView issueOffsetCredit(OffsetCreditRequest req) {
        long enterpriseId = requireEnterprise(req.enterpriseCode()).id();
        long id = repo.insertOffsetBatch(enterpriseId, req.creditType(), req.amount(), req.expiryDate());
        return new BatchView(id, req.amount(), req.amount(), req.expiryDate(), "ACTIVE");
    }

    @Transactional(readOnly = true)
    public List<BatchView> listAllowances(String enterpriseCode) {
        long enterpriseId = requireEnterprise(enterpriseCode).id();
        return repo.listAllowanceBatches(enterpriseId).stream()
                .map(b -> new BatchView(b.id(), b.totalAmount(), b.remainingAmount(), b.expiryDate(), b.status()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BatchView> listOffsets(String enterpriseCode) {
        long enterpriseId = requireEnterprise(enterpriseCode).id();
        return repo.listOffsetBatches(enterpriseId).stream()
                .map(b -> new BatchView(b.id(), b.totalAmount(), b.remainingAmount(), b.expiryDate(), b.status()))
                .toList();
    }

    // ---------- 清缴申请 ----------

    /**
     * 受理清缴申请。到达时刻以进入本方法时读取的应用时钟为准，与截止时刻比较形成唯一结果。
     */
    public SurrenderResponse surrender(SurrenderApplyRequest req) {
        if (req.allowanceAmount() + req.offsetAmount() <= 0) {
            throw ApiException.unprocessable("EMPTY_COMBINATION", "清缴组合不能为空：配额与抵消量不能同时为 0");
        }
        Instant arrival = clock.instant();
        String fingerprint = fingerprint(req);

        // 幂等快速路径：同键同参直接返回首次注销清单，同键异参 409。
        var existing = repo.findRequestByRequestId(req.requestId());
        if (existing.isPresent()) {
            return resolveReplay(existing.get(), fingerprint);
        }

        CarbonRepository.EnterpriseRow enterprise = requireEnterprise(req.enterpriseCode());
        String lockKey = enterprise.id() + ":" + req.complianceYear();
        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock(true));
        lock.lock();
        try {
            return executor.execute(req, enterprise.id(), fingerprint, arrival);
        } catch (DuplicateKeyException e) {
            // 唯一约束兜底：并发下同 requestId 仅一笔落库，其余按重放/冲突处理。
            return repo.findRequestByRequestId(req.requestId())
                    .map(row -> resolveReplay(row, fingerprint))
                    .orElseThrow(() -> e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重放解析：指纹一致返回首次注销清单，不一致返回 409 且账户不变。
     */
    private SurrenderResponse resolveReplay(CarbonRepository.RequestRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已存在但申请明细不一致: " + row.requestId());
        }
        return toResponse(row, true);
    }

    SurrenderResponse toResponse(CarbonRepository.RequestRow row, boolean replay) {
        return buildResponse(repo, row, replay);
    }

    /**
     * 由申请行构建响应（含注销明细与凭证号）。
     */
    static SurrenderResponse buildResponse(CarbonRepository repo, CarbonRepository.RequestRow row,
                                           boolean replay) {
        List<SurrenderItemView> items = repo.listItems(row.id()).stream()
                .map(i -> new SurrenderItemView(i.sourceType(), i.batchId(), i.amount(), i.itemStatus()))
                .toList();
        String certificateNo = repo.findCertificateByRequestPk(row.id())
                .map(CarbonRepository.CertificateRow::certificateNo)
                .orElse(null);
        return new SurrenderResponse(row.requestId(), row.status(), row.allowanceAmount(),
                row.offsetAmount(), certificateNo, items, replay);
    }

    /**
     * 申请指纹：requestId + 企业 + 履约年度 + 组合明细的 SHA-256。
     */
    static String fingerprint(SurrenderApplyRequest req) {
        String material = String.join("|", req.requestId(), req.enterpriseCode(),
                String.valueOf(req.complianceYear()), String.valueOf(req.allowanceAmount()),
                String.valueOf(req.offsetAmount()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ---------- 凭证与履约状态 ----------

    @Transactional(readOnly = true)
    public CertificateView getCertificate(String certificateNo) {
        CarbonRepository.CertificateRow cert = repo.findCertificateByNo(certificateNo)
                .orElseThrow(() -> ApiException.notFound("CERTIFICATE_NOT_FOUND",
                        "凭证不存在: " + certificateNo));
        String requestId = repo.findRequestIdByPk(cert.requestPk())
                .orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "凭证对应申请不存在"));
        String enterpriseCode = repo.findEnterpriseCodeById(cert.enterpriseId())
                .orElseThrow(() -> ApiException.notFound("ENTERPRISE_NOT_FOUND", "企业不存在"));
        return new CertificateView(cert.certificateNo(), requestId, enterpriseCode,
                cert.complianceYear(), cert.totalAmount(), cert.offsetAmount(),
                cert.issuedAt().toString());
    }

    @Transactional(readOnly = true)
    public ComplianceStatusView getComplianceStatus(String enterpriseCode, int complianceYear) {
        CarbonRepository.EnterpriseRow enterprise = requireEnterprise(enterpriseCode);
        long emission = repo.findEmission(enterprise.id(), complianceYear)
                .orElseThrow(() -> ApiException.notFound("EMISSION_NOT_ASSESSED",
                        "该年度尚未核定排放量: " + enterpriseCode + "/" + complianceYear));
        String status = repo.findComplianceStatus(enterprise.id(), complianceYear).orElse("PENDING");
        return new ComplianceStatusView(enterpriseCode, complianceYear, status, emission,
                repo.sumSurrenderedTotal(enterprise.id(), complianceYear),
                repo.sumOffsetUsed(enterprise.id(), complianceYear));
    }

    /**
     * 截止时刻到达后将未足额企业标记为“未履约”。由定时任务与测试调用。
     */
    @Transactional
    public int markNonCompliantIfDue() {
        if (!clock.instant().isAfter(properties.deadline())) {
            return 0;
        }
        return repo.markPendingAsNonCompliant();
    }

    CarbonRepository.EnterpriseRow requireEnterprise(String code) {
        return repo.findEnterpriseByCode(code)
                .orElseThrow(() -> ApiException.notFound("ENTERPRISE_NOT_FOUND", "企业不存在: " + code));
    }
}
