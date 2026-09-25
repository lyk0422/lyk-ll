package com.example.starter.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.domain.Certificate;
import com.example.starter.domain.CertificateState;
import com.example.starter.domain.FieldInspectionRecord;
import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.InspectionVerdict;
import com.example.starter.domain.LabInspectionRecord;
import com.example.starter.domain.LotStatus;
import com.example.starter.domain.RequestKind;
import com.example.starter.domain.SeedGrade;
import com.example.starter.domain.SeedLot;
import com.example.starter.repo.CertificateRepository;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.InspectionRepository;
import com.example.starter.repo.ReferenceDataRepository;
import com.example.starter.repo.SeedLotRepository;
import com.example.starter.support.AppClock;
import com.example.starter.web.dto.AuditView;
import com.example.starter.web.dto.CertificateIssueResponse;
import com.example.starter.web.dto.CertificateView;
import com.example.starter.web.dto.DeclareLotRequest;
import com.example.starter.web.dto.FieldInspectionRequest;
import com.example.starter.web.dto.InspectionView;
import com.example.starter.web.dto.LabInspectionRequest;
import com.example.starter.web.dto.LotDetailView;
import com.example.starter.web.dto.LotRevokeResponse;
import com.example.starter.web.dto.LotView;
import com.example.starter.web.dto.RevokeLotRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 种子批次认证核心服务：申报→田间检验→室内检测→分级发证→注销。
 *
 * <p>并发与事务边界：
 * <ul>
 *     <li>每个写操作一个事务，先以 requestId 抢占幂等记录（主键唯一），再对批次行加 FOR UPDATE 写锁；</li>
 *     <li>同键并发请求在幂等行上等待首单提交，随后原样重放首次结果；</li>
 *     <li>检验按 (lot_no, round) 唯一，复检在同一事务内“旧结论失效 + 新结论生效”，保证唯一有效结论；</li>
 *     <li>发证时证书插入（批次唯一约束）与批次状态翻转、标签核减同事务；</li>
 *     <li>注销为终态，幂等重入，已发证书同事务作废并留痕。</li>
 * </ul>
 */
@Service
public class CertificationService {

    private static final Logger log = LoggerFactory.getLogger(CertificationService.class);

    private static final String ROUND_FIRST = "FIRST";
    private static final String ROUND_RETEST = "RETEST";

    private static final DateTimeFormatter CERT_NO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final SeedLotRepository lotRepository;
    private final InspectionRepository inspectionRepository;
    private final CertificateRepository certificateRepository;
    private final IdempotentRepository idempotentRepository;
    private final ReferenceDataRepository referenceDataRepository;
    private final AppClock clock;
    private final ObjectMapper objectMapper;

    public CertificationService(SeedLotRepository lotRepository,
                                InspectionRepository inspectionRepository,
                                CertificateRepository certificateRepository,
                                IdempotentRepository idempotentRepository,
                                ReferenceDataRepository referenceDataRepository,
                                AppClock clock,
                                ObjectMapper objectMapper) {
        this.lotRepository = lotRepository;
        this.inspectionRepository = inspectionRepository;
        this.certificateRepository = certificateRepository;
        this.idempotentRepository = idempotentRepository;
        this.referenceDataRepository = referenceDataRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等处理结果：非空表示同键重放，调用方直接返回其状态码与首次响应正文。
     */
    public record ReplayedRequest(int httpStatus, JsonNode body) {
    }

    // ============================ 申报 ============================

    /** 批次申报（requestId 幂等）。成功返回 LotView；同键重放返回 ReplayedRequest。 */
    @Transactional
    public Object declare(DeclareLotRequest request) {
        ReplayedRequest replay = beginRequest(request.requestId(), request,
                RequestKind.DECLARE, request.lotNo());
        if (replay != null) {
            return replay;
        }
        if (!referenceDataRepository.varietyExists(request.varietyCode())) {
            throw ApiException.unprocessable("UNKNOWN_VARIETY",
                    "品种编码不存在：" + request.varietyCode());
        }
        if (!referenceDataRepository.plotExists(request.plotCode())) {
            throw ApiException.unprocessable("UNKNOWN_PLOT",
                    "地块编码不存在：" + request.plotCode());
        }
        Instant now = clock.now();
        SeedLot lot = new SeedLot(
                request.lotNo(),
                request.varietyCode(),
                request.plotCode(),
                SeedGrade.valueOf(request.declaredGrade()),
                request.labelQuantity(),
                request.labelQuantity(),
                LotStatus.PENDING,
                now, null, null, now);
        try {
            lotRepository.insert(lot);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("LOT_NO_EXISTS",
                    "批次编号已存在：" + request.lotNo());
        }
        LotView view = ViewMapper.lotView(lot);
        finishRequest(request.requestId(), 200, view);
        return view;
    }

    // ============================ 田间检验 ============================

    /** 田间检验提交（首检/复检，requestId 幂等）。 */
    @Transactional
    public Object submitField(FieldInspectionRequest request) {
        ReplayedRequest replay = beginRequest(request.requestId(), request,
                RequestKind.FIELD, request.lotNo());
        if (replay != null) {
            return replay;
        }
        SeedLot lot = lockLot(request.lotNo());
        ensureInspectionOpen(lot);
        Instant now = clock.now();
        boolean retest = ROUND_RETEST.equals(request.round());
        FieldInspectionRecord saved;
        if (!retest) {
            if (inspectionRepository.findFieldByRound(lot.lotNo(), 1).isPresent()) {
                throw ApiException.conflict("FIELD_ALREADY_INSPECTED",
                        "该批次田间首检已存在，不合格请在期限内申请复检");
            }
            InspectionVerdict verdict = verdict(
                    CertificationRules.purityPasses(lot.declaredGrade(), request.purity()));
            saved = new FieldInspectionRecord(null, lot.lotNo(), request.requestId(),
                    request.purity(), verdict, 1, true, false, request.inspector(), now, now);
            insertFieldOrConflict(saved);
        } else {
            FieldInspectionRecord first = inspectionRepository.findFieldByRound(lot.lotNo(), 1)
                    .orElseThrow(() -> ApiException.unprocessable("RETEST_WITHOUT_FIRST",
                            "无田间首检记录，不能申请复检"));
            saved = submitFieldRetest(lot, first, request, now);
        }
        InspectionView view = ViewMapper.fieldView(saved,
                saved.verdict() == InspectionVerdict.FAIL && saved.round() == 1,
                retestDeadline(saved));
        finishRequest(request.requestId(), 200, view);
        return view;
    }

    private FieldInspectionRecord submitFieldRetest(SeedLot lot, FieldInspectionRecord first,
                                                    FieldInspectionRequest request, Instant now) {
        if (first.verdict() == InspectionVerdict.PASS) {
            throw ApiException.unprocessable("RETEST_NOT_ALLOWED",
                    "田间首检结论为合格，不接受复检");
        }
        if (inspectionRepository.findFieldByRound(lot.lotNo(), 2).isPresent()) {
            throw ApiException.conflict("RETEST_ALREADY_SUBMITTED",
                    "田间复检已提交，复检以一次为限");
        }
        if (!CertificationRules.withinRetestWindow(first.inspectedAt(), now)) {
            throw ApiException.unprocessable("RETEST_EXPIRED",
                    "田间复检逾期：须在首检后 15 个自然日（UTC 日界，含第 15 日）内提交");
        }
        InspectionVerdict verdict = verdict(
                CertificationRules.purityPasses(lot.declaredGrade(), request.purity()));
        // 同一事务内“首检失效 + 复检生效”：复检为最终结论，首检记录保留可查。
        inspectionRepository.invalidateField(lot.lotNo());
        FieldInspectionRecord retest = new FieldInspectionRecord(null, lot.lotNo(),
                request.requestId(), request.purity(), verdict, 2, true, false,
                request.inspector(), now, now);
        insertFieldOrConflict(retest);
        return retest;
    }

    // ============================ 室内检测 ============================

    /** 室内检测提交（首检/复检，requestId 幂等）。 */
    @Transactional
    public Object submitLab(LabInspectionRequest request) {
        ReplayedRequest replay = beginRequest(request.requestId(), request,
                RequestKind.LAB, request.lotNo());
        if (replay != null) {
            return replay;
        }
        SeedLot lot = lockLot(request.lotNo());
        ensureInspectionOpen(lot);
        Instant now = clock.now();
        boolean retest = ROUND_RETEST.equals(request.round());
        LabInspectionRecord saved;
        if (!retest) {
            if (inspectionRepository.findLabByRound(lot.lotNo(), 1).isPresent()) {
                throw ApiException.conflict("LAB_ALREADY_INSPECTED",
                        "该批次室内首检已存在，不合格请在期限内申请复检");
            }
            InspectionVerdict verdict = verdict(
                    CertificationRules.germinationPasses(lot.declaredGrade(),
                            request.germinationRate()));
            saved = new LabInspectionRecord(null, lot.lotNo(), request.requestId(),
                    request.germinationRate(), verdict, 1, true, false,
                    request.inspector(), now, now);
            insertLabOrConflict(saved);
        } else {
            LabInspectionRecord first = inspectionRepository.findLabByRound(lot.lotNo(), 1)
                    .orElseThrow(() -> ApiException.unprocessable("RETEST_WITHOUT_FIRST",
                            "无室内首检记录，不能申请复检"));
            saved = submitLabRetest(lot, first, request, now);
        }
        InspectionView view = ViewMapper.labView(saved,
                saved.verdict() == InspectionVerdict.FAIL && saved.round() == 1,
                retestDeadline(saved));
        finishRequest(request.requestId(), 200, view);
        return view;
    }

    private LabInspectionRecord submitLabRetest(SeedLot lot, LabInspectionRecord first,
                                                LabInspectionRequest request, Instant now) {
        if (first.verdict() == InspectionVerdict.PASS) {
            throw ApiException.unprocessable("RETEST_NOT_ALLOWED",
                    "室内首检结论为合格，不接受复检");
        }
        if (inspectionRepository.findLabByRound(lot.lotNo(), 2).isPresent()) {
            throw ApiException.conflict("RETEST_ALREADY_SUBMITTED",
                    "室内复检已提交，复检以一次为限");
        }
        if (!CertificationRules.withinRetestWindow(first.inspectedAt(), now)) {
            throw ApiException.unprocessable("RETEST_EXPIRED",
                    "室内复检逾期：须在首检后 15 个自然日（UTC 日界，含第 15 日）内提交");
        }
        InspectionVerdict verdict = verdict(
                CertificationRules.germinationPasses(lot.declaredGrade(),
                        request.germinationRate()));
        inspectionRepository.invalidateLab(lot.lotNo());
        LabInspectionRecord retest = new LabInspectionRecord(null, lot.lotNo(),
                request.requestId(), request.germinationRate(), verdict, 2, true, false,
                request.inspector(), now, now);
        insertLabOrConflict(retest);
        return retest;
    }

    // ============================ 分级发证 ============================

    /**
     * 分级发证：两项有效结论齐备后按门槛分级；证书落库与标签核减同事务，
     * 同批次并发发证仅一次成功。
     */
    @Transactional
    public CertificateIssueResponse certify(String lotNo) {
        SeedLot lot = lockLot(lotNo);
        if (lot.status() == LotStatus.CANCELLED) {
            throw ApiException.unprocessable("LOT_CANCELLED",
                    "批次已注销（终态），拒绝发证");
        }
        if (certificateRepository.findByLotNo(lotNo).isPresent()) {
            throw ApiException.conflict("ALREADY_CERTIFIED", "该批次已发证，不得重复发证");
        }
        FieldInspectionRecord field = inspectionRepository.findEffectiveField(lotNo)
                .orElseThrow(() -> ApiException.unprocessable("FIELD_RESULT_MISSING",
                        "田间检验结论未回，批次保持待定"));
        LabInspectionRecord lab = inspectionRepository.findEffectiveLab(lotNo)
                .orElseThrow(() -> ApiException.unprocessable("LAB_RESULT_MISSING",
                        "室内检测结论未回，批次保持待定"));
        SeedGrade grade = CertificationRules.gradeFor(field.purity(), lab.germinationRate());
        if (grade == null) {
            throw ApiException.unprocessable("NOT_CERTIFIABLE",
                    "两项指标未同时达到良种门槛，不得发证");
        }
        Instant now = clock.now();
        String certificateNo = "SEED-" + now.atZone(java.time.ZoneOffset.UTC).toLocalDate()
                .format(CERT_NO_DATE) + "-" + lotNo;
        Certificate certificate = new Certificate(null, certificateNo, lotNo, grade,
                lot.availableLabels(), CertificateState.VALID, now, null);
        try {
            certificateRepository.insert(certificate);
        } catch (DuplicateKeyException e) {
            // 并发发证：uq_certificate_lot 保证仅一次成功
            throw ApiException.conflict("ALREADY_CERTIFIED", "该批次已发证，不得重复发证");
        }
        int updated = lotRepository.certifyIfPending(lotNo, lot.availableLabels(), now);
        if (updated == 0) {
            // 批次行翻转失败（并发注销或并发发证），证书插入随事务回滚
            throw ApiException.conflict("CERTIFICATION_RACE_LOST",
                    "发证竞争失败：批次状态已变化");
        }
        certificateRepository.insertAudit(lotNo, "CERT_ISSUED",
                "发放" + (grade == SeedGrade.ORIGINAL ? "原种" : "良种")
                        + "证书，证书号 " + certificateNo + "，核减标签 "
                        + lot.availableLabels() + " 张",
                "SYSTEM", now);
        SeedLot refreshed = lotRepository.findByLotNo(lotNo).orElseThrow();
        return new CertificateIssueResponse(ViewMapper.certificateView(certificate),
                ViewMapper.lotView(refreshed));
    }

    // ============================ 注销 ============================

    /**
     * 批次注销（终态）：重复注销返回幂等成功；首次注销拒绝后续一切业务，
     * 已发证书同事务作废并双重留痕。
     */
    @Transactional
    public LotRevokeResponse revoke(String lotNo, RevokeLotRequest request) {
        SeedLot lot = lockLot(lotNo);
        if (lot.status() == LotStatus.CANCELLED) {
            // 幂等重入：不重复留痕、不报错，返回当前终态
            CertificateView revokedCertificate = certificateRepository.findByLotNo(lotNo)
                    .map(ViewMapper::certificateView).orElse(null);
            return new LotRevokeResponse(ViewMapper.lotView(lot), revokedCertificate, false);
        }
        Instant now = clock.now();
        int rows = lotRepository.cancelIfActive(lotNo, request.reason(), now);
        if (rows == 0) {
            throw ApiException.conflict("LOT_STATUS_CHANGED",
                    "批次状态在注销时发生变化，请重试");
        }
        CertificateView revokedCertificate = null;
        var existingCertificate = certificateRepository.findByLotNo(lotNo);
        if (existingCertificate.isPresent()
                && existingCertificate.get().state() == CertificateState.VALID) {
            certificateRepository.revokeByLotNo(lotNo, now);
            certificateRepository.insertAudit(lotNo, "CERT_REVOKED",
                    "批次注销，证书 " + existingCertificate.get().certificateNo() + " 同步作废",
                    request.operator(), now);
            revokedCertificate = ViewMapper.certificateView(new Certificate(
                    existingCertificate.get().id(),
                    existingCertificate.get().certificateNo(),
                    lotNo,
                    existingCertificate.get().grade(),
                    existingCertificate.get().deductedLabels(),
                    CertificateState.REVOKED,
                    existingCertificate.get().issuedAt(),
                    now));
        }
        certificateRepository.insertAudit(lotNo, "LOT_REVOKED",
                "批次注销：" + request.reason(), request.operator(), now);
        SeedLot refreshed = lotRepository.findByLotNo(lotNo).orElseThrow();
        return new LotRevokeResponse(ViewMapper.lotView(refreshed), revokedCertificate, true);
    }

    // ============================ 查询 ============================

    /** 批次详情：批次信息、全部检验记录（失效首检仍可查）、证书与留痕。 */
    @Transactional(readOnly = true)
    public LotDetailView getDetail(String lotNo) {
        SeedLot lot = lotRepository.findByLotNo(lotNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在：" + lotNo));
        Instant now = clock.now();
        List<InspectionView> fieldViews = new ArrayList<>();
        for (FieldInspectionRecord record : inspectionRepository.findAllField(lotNo)) {
            boolean retestable = isRetestable(record.round(), record.verdict(),
                    record.inspectedAt(), now)
                    && inspectionRepository.findFieldByRound(lotNo, 2).isEmpty();
            fieldViews.add(ViewMapper.fieldView(record, retestable, retestDeadline(record)));
        }
        List<InspectionView> labViews = new ArrayList<>();
        for (LabInspectionRecord record : inspectionRepository.findAllLab(lotNo)) {
            boolean retestable = isRetestable(record.round(), record.verdict(),
                    record.inspectedAt(), now)
                    && inspectionRepository.findLabByRound(lotNo, 2).isEmpty();
            labViews.add(ViewMapper.labView(record, retestable, retestDeadline(record)));
        }
        CertificateView certificateView = certificateRepository.findByLotNo(lotNo)
                .map(ViewMapper::certificateView).orElse(null);
        List<AuditView> auditViews = certificateRepository.findAudits(lotNo).stream()
                .map(a -> new AuditView(a.action(), a.detail(), a.operator(), a.createdAt()))
                .toList();
        return new LotDetailView(ViewMapper.lotView(lot), fieldViews, labViews,
                certificateView, auditViews);
    }

    // ============================ 幂等与内部工具 ============================

    /**
     * 抢占/校验 requestId。
     *
     * @return 非空表示同键同参重放；null 表示当前事务首次占用该键
     */
    private ReplayedRequest beginRequest(String requestId, Object payload,
                                         RequestKind kind, String lotNo) {
        String fingerprint = Fingerprint.of(payload);
        boolean inserted = idempotentRepository.tryInsert(new IdempotentRecord(
                requestId, kind, fingerprint, lotNo, 0, null, clock.now()));
        if (inserted) {
            return null;
        }
        // 同键并发：在幂等行上加锁等待首单事务提交后读取首次结果
        for (int attempt = 0; attempt < 3; attempt++) {
            IdempotentRecord existing = idempotentRepository.findForUpdate(requestId)
                    .orElse(null);
            if (existing == null) {
                // 首单回滚导致行消失，尝试重新占用
                if (idempotentRepository.tryInsert(new IdempotentRecord(
                        requestId, kind, fingerprint, lotNo, 0, null, clock.now()))) {
                    return null;
                }
                continue;
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENT_PARAM_MISMATCH",
                        "requestId 已用于不同业务参数的请求，冲突（HTTP 409）");
            }
            if (existing.responseBody() == null) {
                throw ApiException.conflict("IDEMPOTENT_REQUEST_IN_PROGRESS",
                        "相同 requestId 的请求正在处理中，请稍后重放");
            }
            try {
                return new ReplayedRequest(existing.httpStatus(),
                        objectMapper.readTree(existing.responseBody()));
            } catch (Exception e) {
                throw new IllegalStateException("解析首次响应失败", e);
            }
        }
        throw ApiException.conflict("IDEMPOTENT_RACE_UNRESOLVED",
                "相同 requestId 的并发请求冲突未决，请稍后重试");
    }

    private void finishRequest(String requestId, int httpStatus, Object body) {        try {
            idempotentRepository.saveResponse(requestId, httpStatus,
                    objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException("持久化首次响应失败", e);
        }
    }

    private SeedLot lockLot(String lotNo) {
        return lotRepository.findByLotNoForUpdate(lotNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在：" + lotNo));
    }

    /**
     * 检验/复检仅在批次“待定”阶段受理：注销终态拒绝一切检验，已发证批次检验通道关闭。
     */
    private static void ensureInspectionOpen(SeedLot lot) {
        if (lot.status() == LotStatus.CANCELLED) {
            throw ApiException.unprocessable("LOT_CANCELLED",
                    "批次已注销（终态），检验与复检一律拒绝");
        }
        if (lot.status() == LotStatus.CERTIFIED) {
            throw ApiException.unprocessable("LOT_CERTIFIED",
                    "批次已分级发证，不再受理检验或复检");
        }
    }

    /** 插入田间检验记录；并发重复提交同一轮次时由唯一约束兜底为 409。 */
    private void insertFieldOrConflict(FieldInspectionRecord record) {
        try {
            inspectionRepository.insertField(record);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("FIELD_INSPECTION_DUPLICATE",
                    "该批次该轮次田间检验已存在（并发提交仅一笔生效）");
        }
    }

    /** 插入室内检测记录；并发重复提交同一轮次时由唯一约束兜底为 409。 */
    private void insertLabOrConflict(LabInspectionRecord record) {
        try {
            inspectionRepository.insertLab(record);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("LAB_INSPECTION_DUPLICATE",
                    "该批次该轮次室内检测已存在（并发提交仅一笔生效）");
        }
    }

    private static InspectionVerdict verdict(boolean passes) {
        return passes ? InspectionVerdict.PASS : InspectionVerdict.FAIL;
    }

    private static boolean isRetestable(int round, InspectionVerdict verdict,
                                        Instant firstInspectedAt, Instant now) {
        return round == 1 && verdict == InspectionVerdict.FAIL
                && CertificationRules.withinRetestWindow(firstInspectedAt, now);
    }

    private static Instant retestDeadline(FieldInspectionRecord record) {
        if (record.round() == 1 && record.verdict() == InspectionVerdict.FAIL) {
            return CertificationRules.retestDeadline(record.inspectedAt())
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        }
        return null;
    }

    private static Instant retestDeadline(LabInspectionRecord record) {
        if (record.round() == 1 && record.verdict() == InspectionVerdict.FAIL) {
            return CertificationRules.retestDeadline(record.inspectedAt())
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        }
        return null;
    }
}
