package com.example.starter.pest;

import static com.example.starter.common.IdempotencyService.fingerprint;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.starter.common.ApiException;
import com.example.starter.common.IdempotencyService;
import com.example.starter.pest.api.Requests.ConfirmRequest;
import com.example.starter.pest.api.Requests.ReportRequest;
import com.example.starter.pest.api.Results.ConfirmResult;
import com.example.starter.pest.api.Results.ReportResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 虫情上报与确认：确认后库区进入处置状态；相邻库区 90 日内恰第 2 次虫情触发整体预防性消杀评估。
 */
@Service
public class PestReportService {

    /**
     * 相邻库区虫情触发预防性评估的时间窗口（天）。
     */
    static final long EVALUATION_WINDOW_DAYS = 90;

    private final ZoneRepository zoneRepository;
    private final PestReportRepository reportRepository;
    private final TreatmentChainRepository chainRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public PestReportService(ZoneRepository zoneRepository, PestReportRepository reportRepository,
                             TreatmentChainRepository chainRepository,
                             IdempotencyService idempotencyService, Clock clock) {
        this.zoneRepository = zoneRepository;
        this.reportRepository = reportRepository;
        this.chainRepository = chainRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 按库区登记虫情上报。
     */
    @Transactional
    public ReportResult report(ReportRequest request) {
        if (!zoneRepository.zoneExists(request.zoneCode())) {
            throw ApiException.unprocessable("库区不存在: " + request.zoneCode());
        }
        return idempotencyService.execute(request.requestId(),
                fingerprint(request.requestId(), "report", request.zoneCode(),
                        request.description(), request.reporter()),
                ReportResult.class,
                () -> {
                    Instant now = clock.instant();
                    long id = reportRepository.insert(request.requestId(), request.zoneCode(),
                            request.description(), request.reporter(), "REPORTED", now);
                    return new ReportResult(id, request.zoneCode(), "REPORTED", now);
                });
    }

    /**
     * 确认虫情：库区进入处置状态（同库区同时刻仅允许一条进行中处置链），
     * 并检查相邻库区 90 日内是否已有未消耗的虫情，恰第 2 次触发整体预防性消杀评估。
     */
    @Transactional
    public ConfirmResult confirm(long reportId, ConfirmRequest request) {
        return idempotencyService.execute(request.requestId(),
                fingerprint(request.requestId(), "confirm", reportId, request.confirmer()),
                ConfirmResult.class,
                () -> doConfirm(reportId));
    }

    private ConfirmResult doConfirm(long reportId) {
        PestReportRepository.PestReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("虫情上报不存在: " + reportId));
        if (!"REPORTED".equals(report.status())) {
            throw ApiException.unprocessable("虫情上报已确认，不可重复确认");
        }
        Instant now = clock.instant();
        reportRepository.markConfirmed(reportId, now);
        long chainId = chainRepository.insert(report.zoneCode(), reportId, "TREATING", now);
        try {
            chainRepository.insertActive(report.zoneCode(), chainId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同库区已存在进行中的处置链: " + report.zoneCode());
        }
        Long evaluationId = maybeTriggerEvaluation(report, now);
        return new ConfirmResult(reportId, chainId, report.zoneCode(), "TREATING", evaluationId);
    }

    /**
     * 相邻库区 90 日内已有未被评估消耗的确认虫情时，恰第 2 次触发评估并消耗双方记录。
     */
    private Long maybeTriggerEvaluation(PestReportRepository.PestReport report, Instant now) {
        Instant windowStart = now.minus(EVALUATION_WINDOW_DAYS, ChronoUnit.DAYS);
        return reportRepository.findLatestUnconsumedAdjacentReport(
                        report.zoneCode(), windowStart, now, report.id())
                .map(previous -> {
                    long evaluationId = reportRepository.insertEvaluation(
                            report.zoneCode(), previous.zoneCode(), report.id(), now);
                    reportRepository.markEvaluationConsumed(report.id());
                    reportRepository.markEvaluationConsumed(previous.id());
                    return evaluationId;
                })
                .orElse(null);
    }
}
