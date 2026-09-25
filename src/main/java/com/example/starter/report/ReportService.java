package com.example.starter.report;

import com.example.starter.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 执行回报服务：回报按“窗口＋站”去重且状态不可改；漏报标记与迟到回报竞态下由主键保证唯一终态。
 */
@Service
public class ReportService {

    private static final Set<String> ALLOWED_STATUS = Set.of("SUCCESS", "FAILED", "PARTIAL");

    private final ReportRepository repository;
    private final TransactionTemplate txTemplate;

    public ReportService(ReportRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 提交执行回报。重复回报不新增记录；已漏报窗口只记迟到事件，不改漏报事实。
     */
    public ReportDtos.ReportResult report(ReportDtos.ReportRequest request) {
        long[] ids = resolveAndValidate(request);
        try {
            return txTemplate.execute(status -> doReport(request, ids));
        } catch (DuplicateKeyException e) {
            // 与漏报标记或并发回报竞态：本事务回滚后重读终态调和，保证唯一终态
            return txTemplate.execute(status -> reconcileAfterRace(request, ids));
        }
    }

    private ReportDtos.ReportResult doReport(ReportDtos.ReportRequest request, long[] ids) {
        Optional<ReportRepository.OutcomeRow> outcome = repository.findOutcome(ids[0], ids[1]);
        if (outcome.isEmpty()) {
            repository.insertReported(ids[0], ids[1], request.status());
            return new ReportDtos.ReportResult(request.windowCode(), request.stationCode(),
                    "REPORTED", request.status());
        }
        return resolveExisting(request, ids, outcome.get());
    }

    private ReportDtos.ReportResult reconcileAfterRace(ReportDtos.ReportRequest request, long[] ids) {
        Optional<ReportRepository.OutcomeRow> outcome = repository.findOutcome(ids[0], ids[1]);
        if (outcome.isEmpty()) {
            // 竞态对方已回滚，按正常路径重试一次
            return doReport(request, ids);
        }
        return resolveExisting(request, ids, outcome.get());
    }

    private ReportDtos.ReportResult resolveExisting(ReportDtos.ReportRequest request, long[] ids,
                                                    ReportRepository.OutcomeRow outcome) {
        if ("REPORTED".equals(outcome.state())) {
            if (!outcome.reportStatus().equals(request.status())) {
                throw ApiException.conflict("REPORT_IMMUTABLE",
                        "回报状态提交后不可改：已存在状态 " + outcome.reportStatus());
            }
            return new ReportDtos.ReportResult(request.windowCode(), request.stationCode(),
                    "DUPLICATE", outcome.reportStatus());
        }
        // 已漏报：只记迟到事件（去重），漏报事实不变
        repository.insertLateEvent(ids[0], ids[1], request.status());
        return new ReportDtos.ReportResult(request.windowCode(), request.stationCode(),
                "LATE_RECORDED", null);
    }

    /**
     * 漏报标记：窗口结束时刻早于 now 且未回报的已编排窗口标记为漏报。
     * 与并发回报竞态时由主键兜底，回报先生效则跳过。
     */
    public ReportDtos.MarkMissedResult markMissed(Instant now) {
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        List<long[]> candidates = repository.findUnreportedActiveWindows(nowUtc);
        List<String> marked = new ArrayList<>();
        for (long[] candidate : candidates) {
            try {
                txTemplate.executeWithoutResult(status ->
                        repository.insertMissed(candidate[0], candidate[1]));
                marked.add(repository.windowCode(candidate[0]));
            } catch (DuplicateKeyException e) {
                // 回报在竞态中先生效：不标记漏报，终态唯一
            }
        }
        return new ReportDtos.MarkMissedResult(marked.size(), marked);
    }

    private long[] resolveAndValidate(ReportDtos.ReportRequest request) {
        if (request == null || isBlank(request.windowCode()) || isBlank(request.stationCode())) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowCode/stationCode 均为必填");
        }
        if (isBlank(request.status()) || !ALLOWED_STATUS.contains(request.status())) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "回报状态必须为 SUCCESS/FAILED/PARTIAL");
        }
        long[] window = repository.findWindow(request.windowCode())
                .orElseThrow(() -> ApiException.notFound("WINDOW_NOT_FOUND", "过境窗口不存在: " + request.windowCode()));
        long stationId = repository.findStationId(request.stationCode())
                .orElseThrow(() -> ApiException.notFound("STATION_NOT_FOUND", "地面站不存在: " + request.stationCode()));
        if (window[1] != stationId) {
            throw ApiException.badRequest("WINDOW_MISMATCH", "窗口不属于该地面站");
        }
        return new long[]{window[0], stationId};
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
