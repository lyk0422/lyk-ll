package com.example.starter.pv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.pv.dto.CaseView;
import com.example.starter.pv.dto.CloseCaseResponse;
import com.example.starter.pv.dto.FollowUpRequest;
import com.example.starter.pv.dto.InitialReportRequest;
import com.example.starter.pv.dto.ReportResponse;
import com.example.starter.pv.dto.SubmissionBatchRequest;
import com.example.starter.pv.dto.SubmissionBatchResponse;

/**
 * 药物警戒个例核心业务：时限判定、重复个例并入、幂等提交、关闭终态与递交批次快照。
 *
 * <p>时限语义：获知日 T0 为 UTC 日界日期，初报期限截止时刻 dueAt = T0 + N 日 23:59:59.999999999（UTC），
 * N=7（致死或危及生命）或 15（其他严重）；提交时刻不晚于 dueAt 判为及时，否则迟报并强制迟报原因。
 *
 * <p>关闭语义：以最近随访时刻（无随访时为初报时刻）为基准，满 30 日（含恰满）方可关闭；关闭为终态。
 */
@Service
public class PvCaseService {

    /** 末次随访后申请关闭的静默期。 */
    static final Duration CLOSE_QUIET_PERIOD = Duration.ofDays(30);

    private final CaseRepository caseRepository;
    private final ReportRepository reportRepository;
    private final BatchRepository batchRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PvCaseService(CaseRepository caseRepository,
                         ReportRepository reportRepository,
                         BatchRepository batchRepository,
                         org.springframework.transaction.PlatformTransactionManager transactionManager,
                         Clock clock) {
        this.caseRepository = caseRepository;
        this.reportRepository = reportRepository;
        this.batchRepository = batchRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 提交初报。已存在活动个例时按随访并入；仅存在已关闭个例时新建关联个例。
     * 按 requestId 幂等：同键同参返回首次结果，同键异参返回 409。
     */
    public ReportResponse submitInitialReport(InitialReportRequest req) {
        Instant submittedAt = req.submittedAt() != null ? req.submittedAt() : Instant.now(clock);
        String fingerprint = fingerprint("INITIAL", req.requestId(), req.patientId(), req.drugName(),
                req.adverseEvent(), req.seriousness().name(), req.awarenessDate().toString(),
                req.narrative(), String.valueOf(req.lateReason()), submittedAt.toString());

        var existing = reportRepository.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            return replayReport(existing.get(), fingerprint);
        }

        Instant dueAt = dueAtOf(req.awarenessDate(), req.seriousness());
        boolean late = submittedAt.isAfter(dueAt);
        if (late && (req.lateReason() == null || req.lateReason().isBlank())) {
            throw ApiException.badRequest("迟报必须填写迟报原因 lateReason");
        }

        try {
            return transactionTemplate.execute(status ->
                    doSubmitInitial(req, submittedAt, dueAt, late, fingerprint));
        } catch (DuplicateKeyException e) {
            return resolveInitialConflict(req, submittedAt, dueAt, late, fingerprint);
        }
    }

    private ReportResponse doSubmitInitial(InitialReportRequest req, Instant submittedAt, Instant dueAt,
                                           boolean late, String fingerprint) {
        var active = caseRepository.findActiveByTriple(req.patientId(), req.drugName(), req.adverseEvent());
        if (active.isPresent()) {
            // 重复报告：按随访并入原个例，不新建、不判迟报；行锁保证并发并入串行化。
            PvCase locked = caseRepository.findByIdForUpdate(active.get().id())
                    .orElseThrow(() -> ApiException.conflict("个例并发变更，请重试"));
            return applyFollowUp(locked, req.requestId(), req.narrative(), submittedAt, fingerprint);
        }
        String linkedCaseNo = caseRepository
                .findLatestClosedByTriple(req.patientId(), req.drugName(), req.adverseEvent())
                .map(PvCase::caseNo)
                .orElse(null);
        Instant now = Instant.now(clock);
        PvCase draft = new PvCase(null, temporaryNo(), req.patientId(), req.drugName(), req.adverseEvent(),
                req.seriousness(), req.awarenessDate(), dueAt, CaseStatus.INITIAL_SUBMITTED,
                submittedAt, null, null, linkedCaseNo, 1);
        PvCase inserted = caseRepository.insert(draft, now);
        String caseNo = "PV-" + String.format("%08d", inserted.id());
        caseRepository.assignCaseNo(inserted.id(), caseNo);
        PvCase created = new PvCase(inserted.id(), caseNo, inserted.patientId(), inserted.drugName(),
                inserted.adverseEvent(), inserted.seriousness(), inserted.awarenessDate(), inserted.dueAt(),
                inserted.status(), inserted.initialSubmittedAt(), null, null, linkedCaseNo, 1);
        PvReport report = reportRepository.insert(new PvReport(null, created.id(), req.requestId(),
                fingerprint, ReportType.INITIAL, req.narrative(), late, late ? req.lateReason() : null,
                submittedAt, created.version()), now);
        return toReportResponse(report, created, false);
    }

    /**
     * 并发唯一索引冲突后的协调：若是本请求幂等重放则返回首次结果；若是活动个例并发创建则并入随访。
     */
    private ReportResponse resolveInitialConflict(InitialReportRequest req, Instant submittedAt, Instant dueAt,
                                                  boolean late, String fingerprint) {
        var existing = reportRepository.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            return replayReport(existing.get(), fingerprint);
        }
        var active = caseRepository.findActiveByTriple(req.patientId(), req.drugName(), req.adverseEvent());
        if (active.isPresent()) {
            return transactionTemplate.execute(status -> {
                PvCase locked = caseRepository.findByIdForUpdate(active.get().id())
                        .orElseThrow(() -> ApiException.conflict("个例并发变更，请重试"));
                return applyFollowUp(locked, req.requestId(), req.narrative(), submittedAt, fingerprint);
            });
        }
        throw ApiException.conflict("并发提交冲突，请重试");
    }

    /**
     * 提交随访。关闭的个例不得重开，须通过初报入口新建关联个例。
     */
    public ReportResponse submitFollowUp(String caseNo, FollowUpRequest req) {
        Instant submittedAt = req.submittedAt() != null ? req.submittedAt() : Instant.now(clock);
        String fingerprint = fingerprint("FOLLOW_UP", req.requestId(), caseNo, req.narrative(),
                submittedAt.toString());

        var existing = reportRepository.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            return replayReport(existing.get(), fingerprint);
        }
        try {
            return transactionTemplate.execute(status -> {
                PvCase c = caseRepository.findByCaseNoForUpdate(caseNo)
                        .orElseThrow(() -> ApiException.notFound("个例不存在: " + caseNo));
                if (c.status() == CaseStatus.CLOSED) {
                    throw ApiException.conflict("个例已关闭（终态），新随访信息须新建关联个例并引用原编号: " + caseNo);
                }
                return applyFollowUp(c, req.requestId(), req.narrative(), submittedAt, fingerprint);
            });
        } catch (DuplicateKeyException e) {
            var report = reportRepository.findByRequestId(req.requestId())
                    .orElseThrow(() -> ApiException.conflict("并发提交冲突，请重试"));
            return replayReport(report, fingerprint);
        }
    }

    /**
     * 在个例上应用一次随访：刷新最近随访日、递增版本、写入随访报告。调用方须持有事务与行锁。
     */
    private ReportResponse applyFollowUp(PvCase c, String requestId, String narrative, Instant submittedAt,
                                         String fingerprint) {
        Instant now = Instant.now(clock);
        PvCase updated = new PvCase(c.id(), c.caseNo(), c.patientId(), c.drugName(), c.adverseEvent(),
                c.seriousness(), c.awarenessDate(), c.dueAt(), CaseStatus.FOLLOW_UP_SUBMITTED,
                c.initialSubmittedAt(), submittedAt, null, c.linkedCaseNo(), c.version() + 1);
        int rows = caseRepository.updateWithVersion(updated, now);
        if (rows != 1) {
            throw ApiException.conflict("个例并发变更，请重试");
        }
        PvReport report = reportRepository.insert(new PvReport(null, c.id(), requestId, fingerprint,
                ReportType.FOLLOW_UP, narrative, false, null, submittedAt, updated.version()), now);
        return toReportResponse(report, updated, true);
    }

    /**
     * 申请关闭个例。重复关闭返回首次关闭结果（幂等成功）。
     */
    public CloseCaseResponse closeCase(String caseNo) {
        return transactionTemplate.execute(status -> {
            PvCase c = caseRepository.findByCaseNoForUpdate(caseNo)
                    .orElseThrow(() -> ApiException.notFound("个例不存在: " + caseNo));
            if (c.status() == CaseStatus.CLOSED) {
                return new CloseCaseResponse(c.caseNo(), c.status(), c.closedAt(), c.version());
            }
            Instant baseline = c.lastFollowUpAt() != null ? c.lastFollowUpAt() : c.initialSubmittedAt();
            Instant now = Instant.now(clock);
            if (now.isBefore(baseline.plus(CLOSE_QUIET_PERIOD))) {
                throw ApiException.conflict("末次随访后 30 日内存在新信息窗口，暂不可关闭；最早可关闭时刻: "
                        + baseline.plus(CLOSE_QUIET_PERIOD));
            }
            PvCase closed = new PvCase(c.id(), c.caseNo(), c.patientId(), c.drugName(), c.adverseEvent(),
                    c.seriousness(), c.awarenessDate(), c.dueAt(), CaseStatus.CLOSED,
                    c.initialSubmittedAt(), c.lastFollowUpAt(), now, c.linkedCaseNo(), c.version() + 1);
            int rows = caseRepository.updateWithVersion(closed, now);
            if (rows != 1) {
                throw ApiException.conflict("个例并发变更，请重试");
            }
            return new CloseCaseResponse(closed.caseNo(), closed.status(), closed.closedAt(), closed.version());
        });
    }

    /**
     * 创建监管递交批次：批次内容为提交时刻已确认个例（已初报且未关闭）的快照，集合固定。
     * 按 requestId 幂等，重复提交返回首次批次。
     */
    public SubmissionBatchResponse createSubmissionBatch(SubmissionBatchRequest req) {
        String fingerprint = fingerprint("BATCH", req.requestId());
        var existing = batchRepository.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            return toBatchResponse(existing.get());
        }
        try {
            return transactionTemplate.execute(status -> {
                List<PvCase> snapshot = caseRepository.findSubmittableForUpdate();
                Instant now = Instant.now(clock);
                PvBatch inserted = batchRepository.insertBatch(temporaryNo(), req.requestId(), fingerprint, now);
                String batchNo = "PB-" + String.format("%08d", inserted.id());
                batchRepository.assignBatchNo(inserted.id(), batchNo);
                for (PvCase c : snapshot) {
                    batchRepository.insertItem(inserted.id(), c);
                }
                PvBatch batch = new PvBatch(inserted.id(), batchNo, req.requestId(), fingerprint, now);
                return toBatchResponse(batch);
            });
        } catch (DuplicateKeyException e) {
            var batch = batchRepository.findByRequestId(req.requestId())
                    .orElseThrow(() -> ApiException.conflict("并发提交冲突，请重试"));
            return toBatchResponse(batch);
        }
    }

    /**
     * 查询个例详情（含全部报告记录）。
     */
    public CaseView getCase(String caseNo) {
        PvCase c = caseRepository.findByCaseNo(caseNo)
                .orElseThrow(() -> ApiException.notFound("个例不存在: " + caseNo));
        List<CaseView.ReportItem> reports = reportRepository.findByCaseId(c.id()).stream()
                .map(r -> new CaseView.ReportItem(r.id(), r.requestId(), r.reportType(), r.late(),
                        r.lateReason(), r.submittedAt(), r.caseVersion()))
                .toList();
        return new CaseView(c.caseNo(), c.patientId(), c.drugName(), c.adverseEvent(), c.seriousness(),
                c.awarenessDate(), c.dueAt(), c.status(), c.initialSubmittedAt(), c.lastFollowUpAt(),
                c.closedAt(), c.linkedCaseNo(), c.version(), reports);
    }

    /**
     * 按批次号查询递交批次（快照内容稳定）。
     */
    public SubmissionBatchResponse getBatch(String batchNo) {
        PvBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchNo));
        return toBatchResponse(batch);
    }

    /**
     * 初报期限截止时刻：T0 + N 日 23:59:59.999999999（UTC，含边界）。
     */
    static Instant dueAtOf(java.time.LocalDate awarenessDate, Seriousness seriousness) {
        return awarenessDate.plusDays(seriousness.reportingDays())
                .atTime(LocalTime.MAX)
                .toInstant(ZoneOffset.UTC);
    }

    private ReportResponse replayReport(PvReport stored, String fingerprint) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已存在但业务参数不一致，已拒绝且不产生新版本: "
                    + stored.requestId());
        }
        PvCase c = caseRepository.findById(stored.caseId())
                .orElseThrow(() -> ApiException.conflict("数据不一致：报告对应个例不存在"));
        return toReportResponse(stored, c, stored.reportType() == ReportType.FOLLOW_UP);
    }

    private ReportResponse toReportResponse(PvReport report, PvCase c, boolean mergedAsFollowUp) {
        return new ReportResponse(report.id(), report.requestId(), c.caseNo(), report.reportType(),
                c.status(), mergedAsFollowUp, report.late(), report.lateReason(), c.dueAt(),
                report.submittedAt(), report.caseVersion());
    }

    private SubmissionBatchResponse toBatchResponse(PvBatch batch) {
        List<SubmissionBatchResponse.Item> items = batchRepository.findItems(batch.id()).stream()
                .map(i -> new SubmissionBatchResponse.Item(i.caseNo(), i.caseStatus().name(), i.caseVersion(),
                        i.initialSubmittedAt(), i.lastFollowUpAt()))
                .toList();
        return new SubmissionBatchResponse(batch.batchNo(), batch.requestId(), batch.createdAt(), items);
    }

    private static String temporaryNo() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 31);
    }

    /**
     * 幂等指纹：覆盖 requestId 与全部业务字段的 SHA-256。
     */
    static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                String value = part == null ? "" : part;
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1F);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
