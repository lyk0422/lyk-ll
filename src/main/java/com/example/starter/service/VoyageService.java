package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ApiRequests.ArrivalRequest;
import com.example.starter.api.dto.ApiRequests.ClosureRequest;
import com.example.starter.api.dto.ApiRequests.DepartureRequest;
import com.example.starter.api.dto.ApiRequests.LogItem;
import com.example.starter.api.dto.ApiRequests.LogbookRequest;
import com.example.starter.api.dto.ApiResponses.ArrivalResponse;
import com.example.starter.api.dto.ApiResponses.ClosureResponse;
import com.example.starter.api.dto.ApiResponses.DepartureResponse;
import com.example.starter.api.dto.ApiResponses.LogGroup;
import com.example.starter.api.dto.ApiResponses.LogbookResponse;
import com.example.starter.domain.ReviewType;
import com.example.starter.domain.VoyageStatus;
import com.example.starter.repo.ArrivalDao;
import com.example.starter.repo.LogbookDao;
import com.example.starter.repo.LogbookDao.LogItemRow;
import com.example.starter.repo.QuotaDao;
import com.example.starter.repo.QuotaDao.LedgerRow;
import com.example.starter.repo.ReviewDao;
import com.example.starter.repo.VoyageDao;
import com.example.starter.repo.VoyageDao.VoyageRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 航次业务：出港申报、渔捞日志、进港报告与结案。
 */
@Service
public class VoyageService {

    /** 兼捕保护鱼种允许比例：恰 5% 合法，超过须标注并审查。 */
    static final BigDecimal BYCATCH_LIMIT_PERCENT = new BigDecimal("5");

    /** 卸渔偏差容忍比例：恰 3% 视为一致，超过须差异说明并人工复核。 */
    static final BigDecimal DEVIATION_LIMIT_PERCENT = new BigDecimal("3");

    private final VoyageDao voyageDao;
    private final QuotaDao quotaDao;
    private final LogbookDao logbookDao;
    private final ArrivalDao arrivalDao;
    private final ReviewDao reviewDao;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public VoyageService(VoyageDao voyageDao, QuotaDao quotaDao, LogbookDao logbookDao,
                         ArrivalDao arrivalDao, ReviewDao reviewDao, IdempotencyService idempotency,
                         Clock businessClock) {
        this.voyageDao = voyageDao;
        this.quotaDao = quotaDao;
        this.logbookDao = logbookDao;
        this.arrivalDao = arrivalDao;
        this.reviewDao = reviewDao;
        this.idempotency = idempotency;
        this.clock = businessClock;
    }

    /** 出港申报：同一渔船同时刻仅允许一个进行中航次；申报量累计超配额整笔拒绝。 */
    @Transactional
    public DepartureResponse depart(DepartureRequest req) {
        return idempotency.execute(req.requestId(), "DEPARTURE", req, DepartureResponse.class,
                () -> doDepart(req));
    }

    private DepartureResponse doDepart(DepartureRequest req) {
        if (!quotaDao.lockVessel(req.vesselId())) {
            throw ApiException.notFound("渔船不存在: " + req.vesselId());
        }
        voyageDao.findInProgressVoyage(req.vesselId()).ifPresent(v -> {
            throw ApiException.conflict("渔船存在进行中航次 " + v.voyageNo() + "，出港申报不予受理");
        });
        int quotaYear = utcYear(req.departureTime());
        for (var item : req.items()) {
            LedgerRow ledger = quotaDao.findLedger(req.vesselId(), item.speciesCode(), quotaYear)
                    .orElseThrow(() -> ApiException.unprocessable("QUOTA_NOT_FOUND",
                            "鱼种 " + item.speciesCode() + " 在 " + quotaYear + " 年度无配额台账"));
            BigDecimal cumulative = ledger.usedQuota().add(item.quantityKg());
            if (cumulative.compareTo(ledger.totalQuota()) > 0) {
                throw ApiException.unprocessable("QUOTA_EXCEEDED",
                        "鱼种 " + item.speciesCode() + " 申报后累计 " + cumulative
                                + "kg 超出年度配额 " + ledger.totalQuota() + "kg，整笔拒绝");
            }
        }
        String voyageNo = "VOY-" + UUID.randomUUID();
        long voyageId = voyageDao.insertVoyage(req.vesselId(), voyageNo, req.departureTime());
        long declarationId = voyageDao.insertDeclaration(req.requestId(), voyageId,
                req.vesselId(), req.fishingArea());
        voyageDao.insertDeclarationItems(declarationId, req.items());
        return new DepartureResponse(voyageId, voyageNo, VoyageStatus.DEPARTED.name());
    }

    /**
     * 渔捞日志：按作业时刻 UTC 日界归属“船＋日期＋渔区”分组去重，乱序按作业时刻归位；
     * 兼捕保护鱼种占比超 5% 的日志单独标注并触发审查；结案后补交仅记迟到事件并转审查。
     */
    @Transactional
    public LogbookResponse submitLog(long voyageId, LogbookRequest req) {
        return idempotency.execute(req.requestId(), "LOGBOOK#" + voyageId, req, LogbookResponse.class,
                () -> doSubmitLog(voyageId, req));
    }

    private LogbookResponse doSubmitLog(long voyageId, LogbookRequest req) {
        VoyageRow voyage = voyageDao.findVoyage(voyageId)
                .orElseThrow(() -> ApiException.notFound("航次不存在: " + voyageId));
        if (voyage.status() == VoyageStatus.CLOSED) {
            String detail = "结案后补交日志：渔区 " + req.fishingArea() + "，明细 " + req.items().size() + " 条";
            reviewDao.insertLateEvent(voyageId, req.requestId(), detail);
            reviewDao.insertReview(voyageId, ReviewType.LATE_LOG, detail);
            return new LogbookResponse(voyageId, VoyageStatus.CLOSED.name(), true, List.of());
        }
        if (voyage.status() != VoyageStatus.DEPARTED && voyage.status() != VoyageStatus.ARRIVED) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "航次状态 " + voyage.status() + " 不允许提交渔捞日志");
        }
        Set<String> protectedCodes = quotaDao.protectedSpeciesCodes();
        Map<LocalDate, List<LogItem>> byDate = new TreeMap<>();
        for (LogItem item : req.items()) {
            LocalDate logDate = item.operationTime().atZone(ZoneOffset.UTC).toLocalDate();
            byDate.computeIfAbsent(logDate, d -> new ArrayList<>()).add(item);
        }
        List<LogGroup> groups = new ArrayList<>();
        for (Map.Entry<LocalDate, List<LogItem>> entry : byDate.entrySet()) {
            LocalDate logDate = entry.getKey();
            List<LogItem> items = entry.getValue();
            logbookDao.findByKey(voyage.vesselId(), logDate, req.fishingArea()).ifPresent(existing -> {
                throw ApiException.conflict("日志已存在（船＋日期＋渔区去重）：" + logDate
                        + " " + req.fishingArea() + "，请勿重复提交");
            });
            boolean bycatchFlagged = exceedsBycatchLimit(items, protectedCodes);
            long logId = logbookDao.insertLog(req.requestId(), voyageId, voyage.vesselId(),
                    logDate, req.fishingArea(), bycatchFlagged);
            logbookDao.insertItems(items.stream()
                    .map(i -> new LogItemRow(logId, i.speciesCode(), i.quantityKg(), i.operationTime()))
                    .toList());
            if (bycatchFlagged) {
                reviewDao.insertReview(voyageId, ReviewType.BYCATCH,
                        "日志 " + logDate + " " + req.fishingArea() + " 兼捕保护鱼种占比超过 5%");
            }
            groups.add(new LogGroup(logId, logDate, req.fishingArea(), bycatchFlagged));
        }
        return new LogbookResponse(voyageId, voyage.status().name(), false, groups);
    }

    /** 兼捕判定：保护鱼种占比严格大于 5%（恰 5% 合法）。 */
    private boolean exceedsBycatchLimit(List<LogItem> items, Set<String> protectedCodes) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal protectedQty = BigDecimal.ZERO;
        for (LogItem item : items) {
            total = total.add(item.quantityKg());
            if (protectedCodes.contains(item.speciesCode())) {
                protectedQty = protectedQty.add(item.quantityKg());
            }
        }
        return protectedQty.multiply(BigDecimal.valueOf(100))
                .compareTo(total.multiply(BYCATCH_LIMIT_PERCENT)) > 0;
    }

    /**
     * 进港报告：卸渔量与日志累计偏差超过 3%（恰 3% 视为一致）须附差异说明，
     * 触发人工复核并在复核期间冻结该船配额余额。
     */
    @Transactional
    public ArrivalResponse submitArrival(long voyageId, ArrivalRequest req) {
        return idempotency.execute(req.requestId(), "ARRIVAL#" + voyageId, req, ArrivalResponse.class,
                () -> doSubmitArrival(voyageId, req));
    }

    private ArrivalResponse doSubmitArrival(long voyageId, ArrivalRequest req) {
        VoyageRow voyage = voyageDao.findVoyage(voyageId)
                .orElseThrow(() -> ApiException.notFound("航次不存在: " + voyageId));
        if (voyage.status() == VoyageStatus.ARRIVED) {
            throw ApiException.conflict("航次已提交进港报告，请勿重复提交");
        }
        if (voyage.status() != VoyageStatus.DEPARTED) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "航次状态 " + voyage.status() + " 不允许提交进港报告");
        }
        Map<String, BigDecimal> logged = logbookDao.sumBySpeciesForVoyage(voyageId);
        boolean deviation = req.items().stream().anyMatch(item -> {
            BigDecimal loggedQty = logged.getOrDefault(item.speciesCode(), BigDecimal.ZERO);
            BigDecimal diff = item.unloadedKg().subtract(loggedQty).abs();
            // 日志无累计而卸渔为正，视为偏差；否则比较 |差值|/日志累计 是否严格大于 3%
            return loggedQty.signum() == 0 || diff.multiply(BigDecimal.valueOf(100))
                    .compareTo(loggedQty.multiply(DEVIATION_LIMIT_PERCENT)) > 0;
        });
        if (deviation && (req.differenceNote() == null || req.differenceNote().isBlank())) {
            throw ApiException.badRequest("DIFFERENCE_NOTE_REQUIRED",
                    "卸渔量与日志累计量偏差超过 3%，须提交差异说明");
        }
        long arrivalId = arrivalDao.insertReport(req.requestId(), voyageId, req.arrivalTime(),
                deviation, req.differenceNote());
        arrivalDao.insertItems(req.items().stream()
                .map(i -> new ArrivalDao.ArrivalItemRow(arrivalId, i.speciesCode(), i.unloadedKg()))
                .toList());
        voyageDao.updateStatus(voyageId, VoyageStatus.ARRIVED, req.arrivalTime(), null);
        Long reviewId = null;
        if (deviation) {
            reviewId = reviewDao.insertReview(voyageId, ReviewType.DEVIATION,
                    "进港卸渔量与日志累计量偏差超过 3%：" + req.differenceNote());
            quotaDao.setFrozen(voyage.vesselId(), utcYear(voyage.departureTime()), true);
        }
        return new ArrivalResponse(voyageId, VoyageStatus.ARRIVED.name(), deviation, reviewId);
    }

    /** 结案：终态流转；重复结案返回幂等成功。 */
    @Transactional
    public ClosureResponse close(long voyageId, ClosureRequest req) {
        return idempotency.execute(req.requestId(), "CLOSURE#" + voyageId, req, ClosureResponse.class,
                () -> doClose(voyageId));
    }

    private ClosureResponse doClose(long voyageId) {
        VoyageRow voyage = voyageDao.findVoyage(voyageId)
                .orElseThrow(() -> ApiException.notFound("航次不存在: " + voyageId));
        if (voyage.status() == VoyageStatus.CLOSED) {
            return new ClosureResponse(voyageId, VoyageStatus.CLOSED.name(), voyage.closedAt());
        }
        if (voyage.status() != VoyageStatus.SETTLED) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "航次状态 " + voyage.status() + " 不允许结案，须先完成配额核销");
        }
        // H2 TIMESTAMP 精度为微秒，截断保证首次响应与落库值一致（重复结案幂等比较）
        Instant closedAt = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        voyageDao.updateStatus(voyageId, VoyageStatus.CLOSED, null, closedAt);
        return new ClosureResponse(voyageId, VoyageStatus.CLOSED.name(), closedAt);
    }

    private static int utcYear(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).getYear();
    }
}
