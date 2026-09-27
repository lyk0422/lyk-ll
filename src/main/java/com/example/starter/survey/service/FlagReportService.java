package com.example.starter.survey.service;

import com.example.starter.survey.data.FlagRecognitionRepository;
import com.example.starter.survey.data.FlagReportRepository;
import com.example.starter.survey.data.FlagRiskNoteRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.FlagRecognition;
import com.example.starter.survey.domain.FlagReport;
import com.example.starter.survey.domain.FlagReportItem;
import com.example.starter.survey.domain.FlagReportRevision;
import com.example.starter.survey.domain.ItemConclusion;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyStatus;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.web.BusinessRuleException;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.CorrectReportRequest;
import com.example.starter.survey.web.dto.FlagReportItemView;
import com.example.starter.survey.web.dto.FlagReportResponse;
import com.example.starter.survey.web.dto.FlagReportRevisionResponse;
import com.example.starter.survey.web.dto.ImportReportRequest;
import com.example.starter.survey.web.dto.ReportItemInput;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 外部船旗国检查报告导入与更正（合成证据，不访问真实机构）。
 * <ul>
 *   <li>报告编号全局唯一：同参重复导入不新增（返回已有），异内容冲突 409；</li>
 *   <li>更正携带 expectedVersion（当前修订号），成功生成新修订并推进版本；</li>
 *   <li>更正与未签发检验互认失效、已签发检验风险记录在同一事务内原子完成；</li>
 *   <li>报告行锁（FOR UPDATE）与发证的一致性校验互斥，保证修订视图冻结一致。</li>
 * </ul>
 */
@Service
public class FlagReportService {

    /** 合成签认标记：只接受该固定值，不访问真实机构。 */
    public static final String SYNTHETIC_ATTESTATION = "SYNTHETIC-ATTEST";

    private final FlagReportRepository reportRepository;
    private final FlagRecognitionRepository recognitionRepository;
    private final FlagRiskNoteRepository riskNoteRepository;
    private final SurveyRepository surveyRepository;
    private final IdempotentExecutor executor;

    public FlagReportService(FlagReportRepository reportRepository,
                             FlagRecognitionRepository recognitionRepository,
                             FlagRiskNoteRepository riskNoteRepository,
                             SurveyRepository surveyRepository,
                             IdempotentExecutor executor) {
        this.reportRepository = reportRepository;
        this.recognitionRepository = recognitionRepository;
        this.riskNoteRepository = riskNoteRepository;
        this.surveyRepository = surveyRepository;
        this.executor = executor;
    }

    /**
     * 导入外部检查报告（幂等）。报告编号全局唯一：
     * 同参重复导入返回已有报告（existing=true，不新增修订）；异内容冲突 409。
     */
    public ApiResult<FlagReportResponse> importReport(ImportReportRequest request) {
        String fp = Fingerprint.of(request.requestId(), "IMPORT_FLAG_REPORT",
                importFields(request.reportNo(), request.shipImo(), request.flagCode(),
                        request.completedAt(), request.attestation(), request.items()));
        return executor.run(request.requestId(), fp, FlagReportResponse.class, () -> {
            requireSyntheticAttestation(request.attestation());
            var existing = reportRepository.findByReportNo(request.reportNo());
            if (existing.isPresent()) {
                return duplicateImport(request, existing.get());
            }
            Instant now = executor.now();
            FlagReport report;
            try {
                report = reportRepository.insertReport(
                        request.reportNo(), request.shipImo(), request.flagCode(), now);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发导入同一报告编号：唯一索引保证只一个首插成功，
                // 落败方读取已提交报告按重复导入处理。
                FlagReport raced = reportRepository.findByReportNo(request.reportNo())
                        .orElseThrow(() -> e);
                return duplicateImport(request, raced);
            }
            FlagReportRevision revision = reportRepository.insertRevision(
                    report.id(), 1, request.completedAt(), request.attestation(),
                    request.requestId(), fp, now);
            insertItems(revision.id(), request.items());
            return FlagReportResponse.from(report,
                    revisionView(revision, reportRepository.findItems(revision.id())), false);
        });
    }

    /** 重复导入：同参返回已有报告（不新增修订），异内容 409。 */
    private FlagReportResponse duplicateImport(ImportReportRequest request, FlagReport report) {
        FlagReportRevision revision = reportRepository
                .findRevision(report.id(), report.currentRevision()).orElseThrow();
        List<FlagReportItem> items = reportRepository.findItems(revision.id());
        if (contentMatches(request, report, revision, items)) {
            return FlagReportResponse.from(report, revisionView(revision, items), true);
        }
        throw new ConflictException("REPORT_CONTENT_CONFLICT",
                "报告编号已存在且内容不一致: " + request.reportNo());
    }

    /**
     * 报告更正（幂等）：expectedVersion 须等于当前修订号，成功生成新修订（版本加 1）。
     * 同一事务内：未签发检验的旧互认原子失效；已签发检验的历史互认不改动，另记风险。
     */
    public ApiResult<FlagReportResponse> correct(CorrectReportRequest request) {
        FlagReport report = reportRepository.findByReportNo(request.reportNo())
                .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                        "报告不存在: " + request.reportNo()));
        String fp = Fingerprint.of(request.requestId(), "CORRECT_FLAG_REPORT",
                importFields(request.reportNo(), null, null,
                        request.completedAt(), request.attestation(), request.items(),
                        request.expectedVersion()));
        return executor.run(request.requestId(), fp, FlagReportResponse.class, () -> {
            // 行锁报告：并发更正串行化，并发发证的一致性校验与本事务互斥。
            FlagReport locked = reportRepository.findByIdForUpdate(report.id()).orElseThrow();
            if (locked.currentRevision() != request.expectedVersion()) {
                throw new ConflictException("VERSION_CONFLICT",
                        "报告当前修订号为 " + locked.currentRevision()
                                + "，与 expectedVersion=" + request.expectedVersion() + " 不一致");
            }
            requireSyntheticAttestation(request.attestation());
            Instant now = executor.now();
            int newRevision = request.expectedVersion() + 1;
            FlagReportRevision revision = reportRepository.insertRevision(
                    report.id(), newRevision, request.completedAt(), request.attestation(),
                    request.requestId(), fp, now);
            insertItems(revision.id(), request.items());
            if (!reportRepository.advanceRevision(report.id(), request.expectedVersion())) {
                throw new ConflictException("VERSION_CONFLICT",
                        "报告修订号已被并发修改: " + request.reportNo());
            }
            // 原子处理旧互认：未签发检验失效；已签发检验保留历史并另记风险。
            for (FlagRecognition rec : recognitionRepository.findActiveByReport(report.id())) {
                Survey survey = surveyRepository.findByIdForUpdate(rec.surveyId()).orElseThrow();
                if (survey.status() == SurveyStatus.CERTIFIED) {
                    riskNoteRepository.insert(rec.id(), survey.id(), report.id(),
                            rec.revision(), newRevision,
                            "报告 " + report.reportNo() + " 更正至修订 " + newRevision
                                    + "，已签发检验的历史互认（修订 " + rec.revision() + "）保留，另记风险",
                            now);
                } else {
                    recognitionRepository.invalidate(rec.id(), now);
                }
            }
            FlagReport updated = reportRepository.findById(report.id()).orElseThrow();
            return FlagReportResponse.from(updated,
                    revisionView(revision, reportRepository.findItems(revision.id())), false);
        });
    }

    /** 查询报告（含当前修订与逐项结论）。 */
    public FlagReportResponse getReport(String reportNo) {
        FlagReport report = reportRepository.findByReportNo(reportNo)
                .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                        "报告不存在: " + reportNo));
        FlagReportRevision revision = reportRepository
                .findRevision(report.id(), report.currentRevision()).orElseThrow();
        return FlagReportResponse.from(report,
                revisionView(revision, reportRepository.findItems(revision.id())), false);
    }

    /** 查询报告全部修订（报告修订查询）。 */
    public List<FlagReportRevisionResponse> listRevisions(String reportNo) {
        FlagReport report = reportRepository.findByReportNo(reportNo)
                .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                        "报告不存在: " + reportNo));
        return reportRepository.findRevisions(report.id()).stream()
                .map(r -> revisionView(r, reportRepository.findItems(r.id())))
                .toList();
    }

    private void requireSyntheticAttestation(String attestation) {
        if (!SYNTHETIC_ATTESTATION.equals(attestation)) {
            throw new BusinessRuleException("SYNTHETIC_ATTESTATION_REQUIRED",
                    "只接受合成签认标记 " + SYNTHETIC_ATTESTATION + "，不访问真实机构");
        }
    }

    private void insertItems(long revisionId, List<ReportItemInput> items) {
        Map<String, ItemConclusion> dedup = new TreeMap<>();
        for (ReportItemInput item : items) {
            if (dedup.put(item.itemCode(), item.conclusion()) != null) {
                throw new BusinessRuleException("REPORT_ITEM_DUPLICATED",
                        "同一报告内项目代码重复: " + item.itemCode());
            }
        }
        dedup.forEach((code, conclusion) -> reportRepository.insertItem(revisionId, code, conclusion));
    }

    /** 同参判定：船旗、IMO、完成时刻、签认标记与逐项结论集合全部一致。 */
    private boolean contentMatches(ImportReportRequest request, FlagReport report,
                                   FlagReportRevision revision, List<FlagReportItem> items) {
        if (!report.shipImo().equals(request.shipImo())
                || !report.flagCode().equals(request.flagCode())
                || !revision.completedAt().equals(request.completedAt())
                || !revision.attestation().equals(request.attestation())) {
            return false;
        }
        Map<String, ItemConclusion> existing = new TreeMap<>();
        items.forEach(i -> existing.put(i.itemCode(), i.conclusion()));
        Map<String, ItemConclusion> incoming = new TreeMap<>();
        request.items().forEach(i -> incoming.put(i.itemCode(), i.conclusion()));
        return existing.equals(incoming);
    }

    private FlagReportRevisionResponse revisionView(FlagReportRevision revision,
                                                    List<FlagReportItem> items) {
        return FlagReportRevisionResponse.from(revision,
                items.stream().map(FlagReportItemView::from).toList());
    }

    /** 指纹字段：集合按项目代码排序（同集合不同顺序视为同参），时刻用 ISO 精确字符串。 */
    static Map<String, Object> importFields(String reportNo, String shipImo, String flagCode,
                                            Instant completedAt, String attestation,
                                            List<ReportItemInput> items, Integer... expectedVersion) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", reportNo);
        if (shipImo != null) {
            fields.put("shipImo", shipImo);
        }
        if (flagCode != null) {
            fields.put("flagCode", flagCode);
        }
        if (expectedVersion.length > 0) {
            fields.put("expectedVersion", expectedVersion[0]);
        }
        fields.put("completedAt", completedAt.toString());
        fields.put("attestation", attestation);
        fields.put("items", items.stream()
                .sorted(java.util.Comparator.comparing(ReportItemInput::itemCode))
                .map(i -> Map.of("itemCode", i.itemCode(), "conclusion", i.conclusion().name()))
                .toList());
        return fields;
    }
}
