package com.example.starter.service;

import com.example.starter.domain.CertStatus;
import com.example.starter.domain.Certificate;
import com.example.starter.domain.FieldInspection;
import com.example.starter.domain.Grade;
import com.example.starter.domain.IdempotentRequest;
import com.example.starter.domain.LabInspection;
import com.example.starter.domain.LotStatus;
import com.example.starter.domain.RecheckApplication;
import com.example.starter.domain.RequestType;
import com.example.starter.domain.SeedLot;
import com.example.starter.repository.CertificationRepository;
import com.example.starter.repository.IdempotentRepository;
import com.example.starter.repository.InspectionRepository;
import com.example.starter.repository.LotEventRepository;
import com.example.starter.repository.SeedLotRepository;
import com.example.starter.service.InspectionRules.Evaluation;
import com.example.starter.web.CancelRequest;
import com.example.starter.web.CertView;
import com.example.starter.web.DeclareLotRequest;
import com.example.starter.web.FieldInspectionRequest;
import com.example.starter.web.InspectionView;
import com.example.starter.web.LabInspectionRequest;
import com.example.starter.web.LotView;
import com.example.starter.web.RecheckRequest;
import com.example.starter.web.RecheckView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * 种子批次认证主服务：申报 → 田间检验/室内检测 → 分级 → 发证，含复检与注销。
 *
 * <p>所有业务方法在单事务内完成“占用幂等键→校验状态→落库→推进状态机→留痕→回填结果快照”，
 * 保证同键同参返回首次结果、同键异参 409 且不产生新记录。
 */
@Service
public class CertificationService {

    /** 复检受理窗口：不合格日（UTC 日界）起 15 个自然日，含第 15 日。 */
    static final int RECHECK_WINDOW_DAYS = 15;

    private final SeedLotRepository seedLotRepository;
    private final InspectionRepository inspectionRepository;
    private final CertificationRepository certificationRepository;
    private final IdempotentRepository idempotentRepository;
    private final LotEventRepository lotEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CertificationService(SeedLotRepository seedLotRepository,
                                InspectionRepository inspectionRepository,
                                CertificationRepository certificationRepository,
                                IdempotentRepository idempotentRepository,
                                LotEventRepository lotEventRepository,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.seedLotRepository = seedLotRepository;
        this.inspectionRepository = inspectionRepository;
        this.certificationRepository = certificationRepository;
        this.idempotentRepository = idempotentRepository;
        this.lotEventRepository = lotEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ============================ 申报 ============================

    @Transactional
    public LotView declare(DeclareLotRequest request) {
        Instant now = Instant.now(clock);
        String fingerprint = fingerprint(RequestType.DECLARE, request.requestId(),
                request.lotNo(), request.applicant(), request.varietyName(), request.fieldCode(),
                request.areaMu().toPlainString(), Integer.toString(request.availableLabels()));

        IdempotentRequest existing = occupyOrReplay(request.requestId(), RequestType.DECLARE,
                fingerprint);
        if (existing != null) {
            return readSnapshot(existing, LotView.class);
        }

        SeedLot lot = new SeedLot(null, request.lotNo(), request.applicant(),
                request.varietyName(), request.fieldCode(), request.areaMu(),
                request.availableLabels(), LotStatus.DECLARED, null, 0, null, now, now);
        SeedLot saved;
        try {
            saved = seedLotRepository.insert(lot);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("批次号已存在: " + request.lotNo());
        }
        lotEventRepository.record(saved.id(), "DECLARE",
                "批次申报，申报主体=" + request.applicant() + "，可用标签=" + request.availableLabels(),
                now);

        LotView view = toView(saved);
        idempotentRepository.bindResult(request.requestId(), saved.id(), "LOT:" + saved.lotNo(),
                writeSnapshot(view));
        return view;
    }

    // ============================ 田间检验 ============================

    @Transactional
    public LotView submitField(FieldInspectionRequest request) {
        Instant now = Instant.now(clock);
        Instant inspectedAt = parseInspectedAt(request.inspectedAt(), now);
        // 指纹仅覆盖客户端原始业务字段；inspectedAt 未显式提供时由服务端补时间，不参与指纹，
        // 否则同参数重试会因服务端时间不同而被误判为异参。
        String fingerprint = fingerprint(RequestType.FIELD, request.requestId(),
                request.lotNo(), request.purity().toPlainString(), request.inspector(),
                request.inspectedAt() == null ? "" : inspectedAt.toString());

        SeedLot lot = requireLotForUpdate(request.lotNo());

        IdempotentRequest existing = occupyOrReplay(request.requestId(), RequestType.FIELD,
                fingerprint);
        if (existing != null) {
            return readSnapshot(existing, LotView.class);
        }
        rejectIfCancelled(lot, "批次已注销，拒绝田间检验");

        int round = activeRound(lot);
        rejectInspectionIfStateInvalid(lot, round, "田间检验");
        Evaluation evaluation = InspectionRules.evaluateField(request.purity());
        FieldInspection inspection = new FieldInspection(null, lot.id(), round, request.purity(),
                evaluation.passOriginal(), evaluation.passBreeder(), evaluation.retestable(),
                request.inspector(), inspectedAt, now);
        try {
            inspectionRepository.insertField(inspection);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("该批次第 " + round + " 轮田间检验记录已存在");
        }
        lotEventRepository.record(lot.id(), "FIELD_SUBMIT",
                "第 " + round + " 轮田间检验提交，纯度=" + request.purity().toPlainString()
                        + "%，达原种=" + evaluation.passOriginal()
                        + "，达良种=" + evaluation.passBreeder(),
                now);

        SeedLot updated = advanceConclusion(lot, now);
        LotView view = toView(updated);
        idempotentRepository.bindResult(request.requestId(), lot.id(),
                "FIELD:" + lot.id() + ":" + round, writeSnapshot(view));
        return view;
    }

    // ============================ 室内检测 ============================

    @Transactional
    public LotView submitLab(LabInspectionRequest request) {
        Instant now = Instant.now(clock);
        Instant inspectedAt = parseInspectedAt(request.inspectedAt(), now);
        // 指纹仅覆盖客户端原始业务字段；inspectedAt 未显式提供时由服务端补时间，不参与指纹。
        String fingerprint = fingerprint(RequestType.LAB, request.requestId(),
                request.lotNo(), request.germinationRate().toPlainString(), request.inspector(),
                request.inspectedAt() == null ? "" : inspectedAt.toString());

        SeedLot lot = requireLotForUpdate(request.lotNo());

        IdempotentRequest existing = occupyOrReplay(request.requestId(), RequestType.LAB,
                fingerprint);
        if (existing != null) {
            return readSnapshot(existing, LotView.class);
        }
        rejectIfCancelled(lot, "批次已注销，拒绝室内检测");

        int round = activeRound(lot);
        rejectInspectionIfStateInvalid(lot, round, "室内检测");
        Evaluation evaluation = InspectionRules.evaluateLab(request.germinationRate());
        LabInspection inspection = new LabInspection(null, lot.id(), round,
                request.germinationRate(), evaluation.passOriginal(), evaluation.passBreeder(),
                evaluation.retestable(), request.inspector(), inspectedAt, now);
        try {
            inspectionRepository.insertLab(inspection);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("该批次第 " + round + " 轮室内检测记录已存在");
        }
        lotEventRepository.record(lot.id(), "LAB_SUBMIT",
                "第 " + round + " 轮室内检测提交，发芽率=" + request.germinationRate().toPlainString()
                        + "%，达原种=" + evaluation.passOriginal()
                        + "，达良种=" + evaluation.passBreeder(),
                now);

        SeedLot updated = advanceConclusion(lot, now);
        LotView view = toView(updated);
        idempotentRepository.bindResult(request.requestId(), lot.id(),
                "LAB:" + lot.id() + ":" + round, writeSnapshot(view));
        return view;
    }

    // ============================ 复检申请 ============================

    @Transactional
    public RecheckView applyRecheck(RecheckRequest request) {
        Instant now = Instant.now(clock);
        String fingerprint = fingerprint(RequestType.RECHECK, request.requestId(), request.lotNo());

        SeedLot lot = requireLotForUpdate(request.lotNo());

        IdempotentRequest existing = occupyOrReplay(request.requestId(), RequestType.RECHECK,
                fingerprint);
        if (existing != null) {
            return readSnapshot(existing, RecheckView.class);
        }
        rejectIfCancelled(lot, "批次已注销，拒绝复检");

        if (lot.status() == LotStatus.RECHECKING || lot.recheckCount() > 0) {
            throw new BusinessRuleException("该批次复检已受理，每批次仅限复检一次");
        }
        if (lot.status() != LotStatus.UNQUALIFIED || lot.unqualifiedAt() == null) {
            throw new BusinessRuleException("仅首检不合格的批次可申请复检，当前状态: " + lot.status());
        }

        LocalDate unqualifiedDate = LocalDate.ofInstant(lot.unqualifiedAt(), ZoneOffset.UTC);
        LocalDate deadline = unqualifiedDate.plusDays(RECHECK_WINDOW_DAYS);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (today.isAfter(deadline)) {
            throw new BusinessRuleException(
                    "复检申请逾期：受理截止日（UTC）为 " + deadline + "（不含），当前为 " + today);
        }

        RecheckApplication application = new RecheckApplication(null, lot.id(),
                request.requestId(), "ACCEPTED", now, deadline, now);
        try {
            certificationRepository.insertRecheck(application);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("该批次复检申请已存在，每批次仅限一次");
        }

        SeedLot updated = new SeedLot(lot.id(), lot.lotNo(), lot.applicant(), lot.varietyName(),
                lot.fieldCode(), lot.areaMu(), lot.availableLabels(), LotStatus.RECHECKING,
                lot.determinedGrade(), lot.recheckCount() + 1, lot.unqualifiedAt(),
                lot.createdAt(), now);
        seedLotRepository.update(updated);
        lotEventRepository.record(lot.id(), "RECHECK_ACCEPT",
                "复检申请受理，受理时间=" + now + "，受理截止日（UTC，含当日）=" + deadline, now);

        RecheckView view = new RecheckView(lot.lotNo(), request.requestId(), "ACCEPTED", now,
                deadline);
        idempotentRepository.bindResult(request.requestId(), lot.id(),
                "RECHECK:" + lot.id(), writeSnapshot(view));
        return view;
    }

    // ============================ 分级发证 ============================

    @Transactional
    public LotView issueCertificate(String lotNo) {
        Instant now = Instant.now(clock);
        SeedLot lot = seedLotRepository.findByLotNo(lotNo);
        if (lot == null) {
            throw new NotFoundException("批次不存在: " + lotNo);
        }
        lot = seedLotRepository.findByIdForUpdate(lot.id());
        rejectIfCancelled(lot, "批次已注销，拒绝发证");

        Certificate existingCert = certificationRepository.findCertificateByLotId(lot.id());
        if (existingCert != null || lot.status() == LotStatus.CERTIFIED) {
            throw new ConflictException("该批次已发证，证书号: "
                    + (existingCert != null ? existingCert.certNo() : ""));
        }
        if (lot.status() != LotStatus.QUALIFIED || lot.determinedGrade() == null) {
            throw new BusinessRuleException(
                    "批次未达到分级合格状态，不得越级发证，当前状态: " + lot.status());
        }
        int labelCount = lot.availableLabels();
        if (labelCount <= 0) {
            throw new BusinessRuleException("批次可用标签数量为 0，无法发证核减");
        }

        // 1) 条件核减标签：状态仍为 QUALIFIED 且库存充足才生效，与发证同事务。
        int deducted = seedLotRepository.deductLabelsConditionally(lot.id(), labelCount, now);
        if (deducted != 1) {
            throw new ConflictException("并发发证冲突：标签核减未生效，本次发证失败");
        }

        String certNo = generateCertNo(lot, now);
        Certificate certificate = new Certificate(null, certNo, lot.id(),
                lot.determinedGrade(), labelCount, CertStatus.VALID, now, null, null, now);
        try {
            // 2) 条件插入证书：唯一索引 uk_certificate_lot 兜底，并发仅一次成功。
            int inserted = certificationRepository.insertCertificateIfQualified(certificate);
            if (inserted != 1) {
                throw new ConflictException("并发发证冲突：该批次证书已存在，本次发证失败");
            }
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("并发发证冲突：该批次证书已存在，本次发证失败");
        }

        // 3) 批次推进为已发证（条件更新，防止与注销等终态操作并发）。
        int marked = seedLotRepository.markCertifiedIfQualified(lot.id(), now);
        if (marked != 1) {
            throw new ConflictException("并发发证冲突：批次状态已变化，本次发证失败");
        }

        SeedLot updated = requireLot(lotNo);
        lotEventRepository.record(lot.id(), "CERT_ISSUE",
                "发放" + gradeName(lot.determinedGrade()) + "证书，证书号=" + certNo
                        + "，同事务核减标签=" + labelCount + " 枚",
                now);
        return toView(updated);
    }

    // ============================ 注销 ============================

    @Transactional
    public LotView cancel(CancelRequest request) {
        Instant now = Instant.now(clock);
        SeedLot lot = seedLotRepository.findByLotNo(request.lotNo());
        if (lot == null) {
            throw new NotFoundException("批次不存在: " + request.lotNo());
        }
        lot = seedLotRepository.findByIdForUpdate(lot.id());

        // 注销为终态：重复注销幂等成功，不再重复留痕。
        if (lot.status() == LotStatus.CANCELLED) {
            return toView(lot);
        }

        Certificate certificate = certificationRepository.findCertificateByLotId(lot.id());
        if (certificate != null && certificate.status() == CertStatus.VALID) {
            certificationRepository.voidCertificate(lot.id(), now, "批次注销，证书同步作废");
            lotEventRepository.record(lot.id(), "CERT_VOID",
                    "批次注销，证书 " + certificate.certNo() + " 同步作废", now);
        }

        SeedLot cancelled = new SeedLot(lot.id(), lot.lotNo(), lot.applicant(),
                lot.varietyName(), lot.fieldCode(), lot.areaMu(), lot.availableLabels(),
                LotStatus.CANCELLED, lot.determinedGrade(), lot.recheckCount(),
                lot.unqualifiedAt(), lot.createdAt(), now);
        seedLotRepository.update(cancelled);
        lotEventRepository.record(lot.id(), "CANCEL", "批次注销（终态），后续检验/复检/发证一律拒绝", now);
        return toView(cancelled);
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public LotView getLot(String lotNo) {
        return toView(requireLot(lotNo));
    }

    // ============================ 内部辅助 ============================

    /**
     * 检验齐回后推进状态机。
     *
     * <p>首检轮次：一项不合格而另一项未回时保持“待定”，不形成越级结论。
     * 复检轮次：复检结论为最终结论并覆盖首检；复检未重检的单项沿用首检结果
     * （首检记录保留可查），从而在仅复检不合格项时也能形成最终结论。
     */
    private SeedLot advanceConclusion(SeedLot lot, Instant now) {
        int round = activeRound(lot);
        FieldInspection field = inspectionRepository.findFieldByLotIdAndRound(lot.id(), round);
        LabInspection lab = inspectionRepository.findLabByLotIdAndRound(lot.id(), round);

        LotStatus nextStatus;
        Grade nextGrade = lot.determinedGrade();
        Instant unqualifiedAt = lot.unqualifiedAt();

        boolean bothPresent = field != null && lab != null;
        if (!bothPresent && round == 1) {
            // 首检仅一项回：该项不合格时保持“待定”；合格时维持等待状态。
            boolean singleFailed = (field != null && field.retestable())
                    || (lab != null && lab.retestable());
            nextStatus = singleFailed ? LotStatus.PENDING : LotStatus.DECLARED;
        } else if (!bothPresent) {
            // 复检仅重检不合格项：缺失单项沿用首检结果。
            FieldInspection firstField = inspectionRepository.findFieldByLotIdAndRound(lot.id(), 1);
            LabInspection firstLab = inspectionRepository.findLabByLotIdAndRound(lot.id(), 1);
            field = field != null ? field : firstField;
            lab = lab != null ? lab : firstLab;
            if (field == null || lab == null) {
                nextStatus = LotStatus.RECHECKING;
            } else {
                SeedLot concluded = applyConclusion(lot, now, field, lab, round, true);
                return concluded;
            }
        } else {
            return applyConclusion(lot, now, field, lab, round, false);
        }

        SeedLot updated = new SeedLot(lot.id(), lot.lotNo(), lot.applicant(), lot.varietyName(),
                lot.fieldCode(), lot.areaMu(), lot.availableLabels(), nextStatus, nextGrade,
                lot.recheckCount(), unqualifiedAt, lot.createdAt(), now);
        seedLotRepository.update(updated);
        return updated;
    }

    /** 依据两项生效检验记录形成分级结论并落库；复检结论覆盖首检分级。 */
    private SeedLot applyConclusion(SeedLot lot, Instant now, FieldInspection field,
                                    LabInspection lab, int round, boolean reusedFirstRoundItem) {
        Evaluation fieldEvaluation = new Evaluation(field.passOriginal(), field.passBreeder(),
                field.retestable());
        Evaluation labEvaluation = new Evaluation(lab.passOriginal(), lab.passBreeder(),
                lab.retestable());
        Grade grade = InspectionRules.determineGrade(fieldEvaluation, labEvaluation);

        LotStatus nextStatus;
        Grade nextGrade = lot.determinedGrade();
        Instant unqualifiedAt = lot.unqualifiedAt();
        if (grade != null) {
            nextStatus = LotStatus.QUALIFIED;
            nextGrade = grade;
        } else {
            // 任一项不达标：不得越级发证；复检不合格为最终不合格结论。
            nextStatus = LotStatus.UNQUALIFIED;
            nextGrade = null;
            if (unqualifiedAt == null) {
                unqualifiedAt = now;
            }
        }
        lotEventRepository.record(lot.id(), "CONCLUSION",
                "第 " + round + " 轮结论形成："
                        + (grade != null ? "分级合格=" + gradeName(grade) : "不合格，不得分级")
                        + (reusedFirstRoundItem ? "（未重检单项沿用首检结果）" : "")
                        + "（田间=" + field.purity().toPlainString()
                        + "%，室内=" + lab.germinationRate().toPlainString() + "%）",
                now);

        SeedLot updated = new SeedLot(lot.id(), lot.lotNo(), lot.applicant(), lot.varietyName(),
                lot.fieldCode(), lot.areaMu(), lot.availableLabels(), nextStatus, nextGrade,
                lot.recheckCount(), unqualifiedAt, lot.createdAt(), now);
        seedLotRepository.update(updated);
        return updated;
    }

    /** 当前生效检验轮次：复检受理后第 2 轮，否则第 1 轮。 */
    private int activeRound(SeedLot lot) {
        return lot.recheckCount() > 0 || lot.status() == LotStatus.RECHECKING ? 2 : 1;
    }

    private SeedLot requireLot(String lotNo) {
        SeedLot lot = seedLotRepository.findByLotNo(lotNo);
        if (lot == null) {
            throw new NotFoundException("批次不存在: " + lotNo);
        }
        return lot;
    }

    /** 加批次行锁读取，保证同一批次检验/复检/状态推进串行化。 */
    private SeedLot requireLotForUpdate(String lotNo) {
        SeedLot lot = seedLotRepository.findByLotNoForUpdate(lotNo);
        if (lot == null) {
            throw new NotFoundException("批次不存在: " + lotNo);
        }
        return lot;
    }

    private void rejectIfCancelled(SeedLot lot, String message) {
        if (lot.status() == LotStatus.CANCELLED) {
            throw new BusinessRuleException(message);
        }
    }

    /**
     * 检验提交的状态前置校验：首检仅允许在已申报/待定时提交；复检（第 2 轮）仅允许在复检受理后提交。
     * 结论已形成（不合格/合格/已发证）后不得直接补检验，不合格批次须在窗口内申请复检。
     */
    private void rejectInspectionIfStateInvalid(SeedLot lot, int round, String inspectionName) {
        if (round == 2) {
            if (lot.status() != LotStatus.RECHECKING) {
                throw new BusinessRuleException(
                        "复检" + inspectionName + "仅在复检受理后可提交，当前状态: " + lot.status());
            }
            return;
        }
        if (lot.status() != LotStatus.DECLARED && lot.status() != LotStatus.PENDING) {
            throw new BusinessRuleException(
                    "首检" + inspectionName + "仅在申报后、结论形成前可提交，当前状态: "
                            + lot.status()
                            + (lot.status() == LotStatus.UNQUALIFIED ? "；不合格批次请在 15 个自然日内申请复检" : ""));
        }
    }

    /**
     * 占用幂等键。
     *
     * @return 已存在的幂等记录（同参，调用方返回其快照）；{@code null} 表示首次占用成功
     * @throws ConflictException 同键异参（409），不产生任何新业务记录
     */
    private IdempotentRequest occupyOrReplay(String requestId, RequestType type,
                                             String fingerprint) {
        IdempotentRequest placeholder = new IdempotentRequest(requestId, type, fingerprint,
                null, null, null, Instant.now(clock));
        if (idempotentRepository.tryInsert(placeholder)) {
            return null;
        }
        // 插入冲突：首次请求可能仍未提交（行暂不可见）或尚未回填快照，有界等待其事务完成。
        IdempotentRequest existing = awaitFirstResult(requestId, fingerprint);
        return existing;
    }

    /**
     * 有界等待首次请求落库并完成（最多约 5 秒）。
     *
     * @throws ConflictException 超时，或首次请求使用了不同业务参数
     */
    private IdempotentRequest awaitFirstResult(String requestId, String fingerprint) {
        IdempotentRequest latest = null;
        for (int i = 0; i < 100; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ConflictException("等待首次请求结果时被中断: " + requestId);
                }
            }
            latest = idempotentRepository.findByRequestId(requestId);
            if (latest != null && latest.resultSnapshot() != null) {
                break;
            }
        }
        if (latest == null) {
            throw new ConflictException("幂等键并发冲突，请稍后重试: " + requestId);
        }
        if (!latest.fingerprint().equals(fingerprint)) {
            throw new ConflictException("requestId 已用于不同业务参数的请求: " + requestId);
        }
        if (latest.resultSnapshot() == null) {
            throw new ConflictException("相同 requestId 的首次请求仍在处理中: " + requestId);
        }
        return latest;
    }

    private <T> T readSnapshot(IdempotentRequest request, Class<T> type) {
        try {
            return objectMapper.readValue(request.resultSnapshot(), type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("首次结果快照解析失败: " + request.requestId(), ex);
        }
    }

    private String writeSnapshot(Object view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("结果快照序列化失败", ex);
        }
    }

    private Instant parseInspectedAt(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Instant.parse(value.trim());
    }

    private String generateCertNo(SeedLot lot, Instant now) {
        String day = DateTimeFormatter.ofPattern("yyyyMMdd")
                .format(LocalDate.ofInstant(now, ZoneOffset.UTC));
        return String.format("CERT-%s-%08d", day, lot.id());
    }

    private String gradeName(Grade grade) {
        return grade == Grade.ORIGINAL ? "原种" : "良种";
    }

    /** 指纹覆盖 requestId 与全部业务字段（含请求类型，避免跨接口复用碰撞）。 */
    private String fingerprint(RequestType type, String requestId, String... fields) {
        String canonical = type.name() + "|" + requestId + "|" + String.join("|", fields);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private LotView toView(SeedLot lot) {
        List<InspectionView> fieldViews = inspectionRepository.findFieldByLotId(lot.id()).stream()
                .map(f -> new InspectionView("FIELD", f.round(), f.purity(), f.passOriginal(),
                        f.passBreeder(), f.retestable(), f.inspector(), f.inspectedAt()))
                .toList();
        List<InspectionView> labViews = inspectionRepository.findLabByLotId(lot.id()).stream()
                .map(l -> new InspectionView("LAB", l.round(), l.germinationRate(),
                        l.passOriginal(), l.passBreeder(), l.retestable(), l.inspector(),
                        l.inspectedAt()))
                .toList();
        Certificate certificate = certificationRepository.findCertificateByLotId(lot.id());
        CertView certView = certificate == null ? null : new CertView(certificate.certNo(),
                certificate.grade().name(), certificate.labelCount(), certificate.status().name(),
                certificate.issuedAt(), certificate.voidedAt(), certificate.voidReason());
        return new LotView(lot.lotNo(), lot.applicant(), lot.varietyName(), lot.fieldCode(),
                lot.areaMu(), lot.availableLabels(), lot.status().name(),
                lot.determinedGrade() == null ? null : lot.determinedGrade().name(),
                lot.recheckCount(), lot.unqualifiedAt(), fieldViews, labViews, certView,
                lot.createdAt(), lot.updatedAt());
    }
}
