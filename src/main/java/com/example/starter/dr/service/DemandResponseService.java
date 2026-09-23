package com.example.starter.dr.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.starter.dr.ApiModels;
import com.example.starter.dr.DrConflictException;
import com.example.starter.dr.DrNotFoundException;
import com.example.starter.dr.DrUnprocessableException;
import com.example.starter.dr.DrValidationException;
import com.example.starter.dr.SettlementMath;
import com.example.starter.dr.domain.Rows;
import com.example.starter.dr.repository.DrRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 需求响应事件执行与结算冻结服务。
 *
 * <p>时间统一使用 UTC 半开区间 [start,end) 与固定分钟粒度；功率单位 kW。</p>
 * <p>锁序约定（避免死锁）：冻结请求行 → 事件行 → 各电表曲线行；
 * 曲线写入与登记也先锁事件行，再操作曲线/登记行。</p>
 */
@Service
public class DemandResponseService {

    /** 曲线数据类型：基线。 */
    public static final String TYPE_BASELINE = "BASELINE";
    /** 曲线数据类型：实测。 */
    public static final String TYPE_ACTUAL = "ACTUAL";

    private static final Set<String> DATA_TYPES = Set.of(TYPE_BASELINE, TYPE_ACTUAL);

    private final DrRepository repo;
    private final Clock clock;

    public DemandResponseService(DrRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 创建事件：区间半开、长度须被分钟粒度整除。 */
    @Transactional
    public ApiModels.EventResponse createEvent(ApiModels.CreateEventRequest request) {
        Instant start = request.start();
        Instant end = request.end();
        if (!end.isAfter(start)) {
            throw new DrValidationException("事件结束时刻必须晚于开始时刻（半开区间 [start,end)）");
        }
        long intervalSeconds = request.intervalMinutes() * 60L;
        long durationSeconds = Duration.between(start, end).getSeconds();
        if (durationSeconds % intervalSeconds != 0) {
            throw new DrValidationException(
                    "事件区间长度必须是 intervalMinutes 的整数倍，端点按半开区间对齐");
        }
        try {
            repo.insertEvent(request.eventId(), start, end, request.intervalMinutes(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw new DrConflictException("事件已存在: " + request.eventId());
        }
        return getEvent(request.eventId());
    }

    /**
     * 登记参与电表：同一电表在时间重叠（半开区间相交，端点相接不算重叠）的事件中不得重复承诺。
     */
    @Transactional
    public ApiModels.EventResponse enrollMeter(String eventId, ApiModels.EnrollMeterRequest request) {
        Rows.EventRow event = lockEvent(eventId);
        if (event.frozen()) {
            throw new DrConflictException("事件已冻结，不能再登记电表: " + eventId);
        }
        if (repo.findEnrollment(eventId, request.meterId()).isPresent()) {
            throw new DrConflictException("电表已登记到该事件: " + request.meterId());
        }
        // 锁定该电表既有登记行后重查重叠，挡住同电表并发登记。
        repo.lockEnrollmentsForMeter(request.meterId());
        List<Rows.EventRow> overlaps =
                repo.findOverlappingEventsForMeter(request.meterId(), event.start(), event.end());
        if (!overlaps.isEmpty()) {
            throw new DrConflictException("电表在时间重叠事件中已作出承诺，冲突事件: "
                    + overlaps.get(0).eventId());
        }
        try {
            repo.insertEnrollment(eventId, request.meterId(), request.committedKw(),
                    request.baselineVersion(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw new DrConflictException("电表已登记到该事件: " + request.meterId());
        }
        repo.incrementEventVersion(eventId);
        return getEvent(eventId);
    }

    /** 查询事件与登记电表（端点相接区间结构按编号返回）。 */
    public ApiModels.EventResponse getEvent(String eventId) {
        Rows.EventRow event = requireEvent(eventId);
        return toEventResponse(event, repo.findEnrollments(eventId));
    }

    /**
     * 写入或更正曲线点。同一电表、区间、数据类型只有一个当前版本：
     * 首次写入不带 expectedVersion；更正必须显式携带前版本号。冻结后允许写入，
     * 但只形成独立调整建议，不改写冻结结果。
     */
    @Transactional
    public ApiModels.CurveWriteResponse writeCurve(String eventId, String meterId, String dataType,
                                                   ApiModels.CurveWriteRequest request) {
        if (!DATA_TYPES.contains(dataType)) {
            throw new DrValidationException("dataType 只能是 BASELINE 或 ACTUAL");
        }
        Rows.EventRow event = lockEvent(eventId);
        Rows.EnrollmentRow enrollment = repo.findEnrollment(eventId, meterId)
                .orElseThrow(() -> new DrNotFoundException(
                        "电表未登记到该事件: event=" + eventId + ", meter=" + meterId));

        int intervalCount = intervalCount(event);
        Set<Integer> seen = new HashSet<>();
        for (ApiModels.CurvePointRequest point : request.points()) {
            int idx = point.intervalIndex();
            if (idx < 0 || idx >= intervalCount) {
                throw new DrValidationException(
                        "intervalIndex 超出事件区间范围 [0," + intervalCount + "): " + idx);
            }
            if (!seen.add(idx)) {
                throw new DrValidationException("同一区间在单次请求中重复出现: " + idx);
            }
            if (point.value().stripTrailingZeros().scale() > 6
                    || point.value().precision() - Math.max(0, point.value().scale()) > 12) {
                throw new DrValidationException("功率值超出 DECIMAL(18,6) 可表示范围");
            }
        }

        int maxVersion = 0;
        Instant now = clock.instant();
        for (ApiModels.CurvePointRequest point : request.points()) {
            int idx = point.intervalIndex();
            Optional<Rows.CurvePointRow> current =
                    repo.findCurvePointForUpdate(eventId, meterId, idx, dataType);
            int newVersion;
            if (current.isEmpty()) {
                if (point.expectedVersion() != null) {
                    throw new DrConflictException(
                            "版本冲突：该点尚无当前版本，首次写入不得携带 expectedVersion");
                }
                newVersion = 1;
                repo.insertCurveCurrent(eventId, meterId, idx, dataType, point.value(), newVersion, now);
            } else {
                Integer expected = point.expectedVersion();
                if (expected == null) {
                    throw new DrConflictException(
                            "版本冲突：当前版本为 " + current.get().version() + "，更正必须显式携带前版本号");
                }
                if (expected != current.get().version()) {
                    throw new DrConflictException(
                            "版本冲突：期望前版本 " + expected + "，当前版本为 " + current.get().version());
                }
                newVersion = expected + 1;
                int updated = repo.updateCurveCurrent(eventId, meterId, idx, dataType,
                        point.value(), newVersion, expected, now);
                if (updated == 0) {
                    // 并发更正已抬升版本：整笔失败。
                    throw new DrConflictException("并发更正：曲线版本已变化，请刷新后重试 (interval=" + idx + ")");
                }
            }
            repo.insertCurveHistory(eventId, meterId, idx, dataType, newVersion, point.value(), now);
            maxVersion = Math.max(maxVersion, newVersion);
        }
        int eventVersion = repo.incrementEventVersion(eventId);

        // 冻结后的更正只形成独立调整建议。
        if (event.frozen()) {
            regenerateAdjustments(event, enrollment, request.points());
        }
        return new ApiModels.CurveWriteResponse(dataType, maxVersion, eventVersion, request.points().size());
    }

    /**
     * 冻结结算：一个事务内校验全部区间完整与证据版本，逐区间写入冻结证据。
     * 同 requestId 同参数指纹重放返回首次结果；同键异参返回 409。
     */
    @Transactional
    public ApiModels.SettlementResponse freeze(String eventId, ApiModels.FreezeRequest request) {
        String fingerprint = fingerprint(eventId, request);

        // 先锁幂等请求行，串行化同键重放。
        Optional<Rows.FreezeRequestRow> existingRequest = repo.findFreezeRequestForUpdate(request.requestId());
        if (existingRequest.isPresent()) {
            Rows.FreezeRequestRow prior = existingRequest.get();
            if (!prior.fingerprint().equals(fingerprint) || !prior.eventId().equals(eventId)) {
                throw new DrConflictException("相同 requestId 携带了不同参数（含版本变化），拒绝冻结");
            }
            return loadSettlementResponse(prior.settlementId());
        }

        // 锁事件行：与曲线更正互斥（更正事务先取事件锁再改曲线）。
        Rows.EventRow event = lockEvent(eventId);
        Optional<Rows.SettlementRow> existingSettlement = repo.findSettlementByEvent(eventId);
        if (event.frozen() || existingSettlement.isPresent()) {
            throw new DrConflictException("事件已冻结，冻结结果不可改写: " + eventId);
        }
        if (event.version() != request.expectedVersion()) {
            throw new DrConflictException(
                    "事件版本冲突：expectedVersion=" + request.expectedVersion()
                            + "，当前版本=" + event.version() + "（曲线已被写入或更正）");
        }

        List<Rows.EnrollmentRow> enrollments = repo.findEnrollments(eventId);
        if (enrollments.isEmpty()) {
            throw new DrValidationException("事件尚无登记电表，无法冻结");
        }
        Map<String, ApiModels.FreezeMeterVersion> requested = new LinkedHashMap<>();
        for (ApiModels.FreezeMeterVersion mv : request.meters()) {
            if (requested.putIfAbsent(mv.meterId(), mv) != null) {
                throw new DrValidationException("冻结请求中电表重复: " + mv.meterId());
            }
        }
        Map<String, Rows.EnrollmentRow> enrolledByMeter = new LinkedHashMap<>();
        for (Rows.EnrollmentRow e : enrollments) {
            enrolledByMeter.put(e.meterId(), e);
        }
        if (!requested.keySet().equals(enrolledByMeter.keySet())) {
            throw new DrValidationException("冻结电表集合必须与登记电表集合完全一致");
        }

        int intervalCount = intervalCount(event);
        List<Rows.SettlementIntervalRow> intervalRows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        // 全部区间逐点加锁校验：任一缺值或版本不符即抛异常，整笔回滚不留部分结算。
        for (ApiModels.FreezeMeterVersion mv : request.meters()) {
            Rows.EnrollmentRow enrollment = enrolledByMeter.get(mv.meterId());
            for (int idx = 0; idx < intervalCount; idx++) {
                final int intervalIndex = idx;
                Rows.CurvePointRow baseline =
                        repo.findCurvePointForUpdate(eventId, mv.meterId(), intervalIndex, TYPE_BASELINE)
                                .orElseThrow(() -> new DrUnprocessableException(
                                        "区间缺少基线数据，状态 PENDING，不得结算: meter="
                                                + mv.meterId() + ", interval=" + intervalIndex));
                Rows.CurvePointRow actual =
                        repo.findCurvePointForUpdate(eventId, mv.meterId(), intervalIndex, TYPE_ACTUAL)
                                .orElseThrow(() -> new DrUnprocessableException(
                                        "区间缺少实测数据，状态 PENDING，不得结算: meter="
                                                + mv.meterId() + ", interval=" + intervalIndex));
                if (baseline.version() != mv.baselineVersion()) {
                    throw new DrConflictException(
                            "基线证据版本冲突: meter=" + mv.meterId() + ", interval=" + intervalIndex
                                    + ", expected=" + mv.baselineVersion() + ", current=" + baseline.version());
                }
                if (actual.version() != mv.actualVersion()) {
                    throw new DrConflictException(
                            "实测证据版本冲突: meter=" + mv.meterId() + ", interval=" + intervalIndex
                                    + ", expected=" + mv.actualVersion() + ", current=" + actual.version());
                }
                BigDecimal delivered = SettlementMath.delivered(
                        baseline.value(), actual.value(), enrollment.committedKw());
                total = total.add(delivered);
                intervalRows.add(new Rows.SettlementIntervalRow(
                        null, eventId, mv.meterId(), intervalIndex, intervalStart(event, intervalIndex),
                        baseline.value(), actual.value(), baseline.version(), actual.version(), delivered));
            }
        }

        Instant now = clock.instant();
        String settlementId = "STL-" + eventId;
        Rows.SettlementRow settlement = new Rows.SettlementRow(
                settlementId, eventId, request.requestId(), event.version(), total, now);
        try {
            repo.insertSettlement(settlement, now);
            for (Rows.SettlementIntervalRow row : intervalRows) {
                repo.insertSettlementInterval(new Rows.SettlementIntervalRow(
                        settlementId, row.eventId(), row.meterId(), row.intervalIndex(), row.intervalStart(),
                        row.baselineValue(), row.actualValue(), row.baselineVersion(),
                        row.actualVersion(), row.deliveredKw()));
            }
            repo.markEventFrozen(eventId);
            repo.insertFreezeRequest(request.requestId(), eventId, fingerprint, settlementId, now);
        } catch (DuplicateKeyException e) {
            // 并发冻结（事件唯一或请求键唯一）：整笔失败。
            throw new DrConflictException("并发冻结冲突，事件或请求已被处理: " + eventId);
        }
        return loadSettlementResponse(settlementId);
    }

    /** 查询冻结证据；未冻结返回 404。 */
    public ApiModels.SettlementResponse getSettlement(String eventId) {
        requireEvent(eventId);
        Rows.SettlementRow settlement = repo.findSettlementByEvent(eventId)
                .orElseThrow(() -> new DrNotFoundException("事件尚未冻结: " + eventId));
        return toSettlementResponse(settlement);
    }

    /** 查询冻结后更正形成的独立调整建议；未冻结返回 404。 */
    public ApiModels.AdjustmentResponse getAdjustments(String eventId) {
        requireEvent(eventId);
        Rows.SettlementRow settlement = repo.findSettlementByEvent(eventId)
                .orElseThrow(() -> new DrNotFoundException("事件尚未冻结，无调整建议: " + eventId));
        List<Rows.AdjustmentRow> rows = repo.findAdjustments(settlement.settlementId());
        BigDecimal totalDelta = rows.stream()
                .map(Rows.AdjustmentRow::deltaKw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ApiModels.AdjustmentView> views = rows.stream()
                .map(r -> new ApiModels.AdjustmentView(
                        r.meterId(), r.intervalIndex(), intervalStart(requireEvent(eventId), r.intervalIndex()),
                        r.frozenDeliveredKw(), r.revisedBaselineKw(), r.revisedActualKw(),
                        r.revisedDeliveredKw(), r.deltaKw(),
                        r.baselineVersion(), r.actualVersion(), r.updatedAt()))
                .toList();
        return new ApiModels.AdjustmentResponse(
                settlement.settlementId(), eventId, totalDelta, views);
    }

    // ---------- 冻结后调整建议 ----------

    /**
     * 按更正后的当前曲线重算受影响区间，形成/更新独立调整建议；冻结证据不变。
     * 调用方已持有事件锁与相关曲线行锁。
     */
    private void regenerateAdjustments(Rows.EventRow event, Rows.EnrollmentRow enrollment,
                                       List<ApiModels.CurvePointRequest> points) {
        Rows.SettlementRow settlement = repo.findSettlementByEvent(event.eventId()).orElseThrow();
        Set<Integer> affected = new HashSet<>();
        for (ApiModels.CurvePointRequest p : points) {
            affected.add(p.intervalIndex());
        }
        Instant now = clock.instant();
        for (int idx : affected) {
            Rows.SettlementIntervalRow frozen =
                    repo.findSettlementInterval(settlement.settlementId(), enrollment.meterId(), idx)
                            .orElseThrow();
            Rows.CurvePointRow baseCurrent =
                    repo.findCurvePoint(event.eventId(), enrollment.meterId(), idx, TYPE_BASELINE).orElseThrow();
            Rows.CurvePointRow actCurrent =
                    repo.findCurvePoint(event.eventId(), enrollment.meterId(), idx, TYPE_ACTUAL).orElseThrow();
            BigDecimal revisedDelivered = SettlementMath.delivered(
                    baseCurrent.value(), actCurrent.value(), enrollment.committedKw());
            BigDecimal delta = revisedDelivered.subtract(frozen.deliveredKw());
            Optional<Rows.AdjustmentRow> existing =
                    repo.findAdjustment(settlement.settlementId(), enrollment.meterId(), idx);
            if (existing.isPresent()) {
                repo.updateAdjustment(existing.get().id(), baseCurrent.value(), actCurrent.value(),
                        revisedDelivered, delta, baseCurrent.version(), actCurrent.version(), now);
            } else {
                repo.insertAdjustment(new Rows.AdjustmentRow(
                        0, settlement.settlementId(), event.eventId(), enrollment.meterId(), idx,
                        frozen.deliveredKw(), baseCurrent.value(), actCurrent.value(), revisedDelivered, delta,
                        baseCurrent.version(), actCurrent.version(), now, now), now);
            }
        }
    }

    // ---------- 辅助 ----------

    private Rows.EventRow lockEvent(String eventId) {
        return repo.findEventForUpdate(eventId)
                .orElseThrow(() -> new DrNotFoundException("事件不存在: " + eventId));
    }

    private Rows.EventRow requireEvent(String eventId) {
        return repo.findEvent(eventId)
                .orElseThrow(() -> new DrNotFoundException("事件不存在: " + eventId));
    }

    private int intervalCount(Rows.EventRow event) {
        long durationSeconds = Duration.between(event.start(), event.end()).getSeconds();
        return Math.toIntExact(durationSeconds / (event.intervalMinutes() * 60L));
    }

    private Instant intervalStart(Rows.EventRow event, int idx) {
        return event.start().plusSeconds((long) idx * event.intervalMinutes() * 60L);
    }

    private ApiModels.EventResponse toEventResponse(Rows.EventRow event, List<Rows.EnrollmentRow> enrollments) {
        List<ApiModels.EnrollmentView> views = enrollments.stream()
                .sorted(Comparator.comparing(Rows.EnrollmentRow::meterId))
                .map(e -> new ApiModels.EnrollmentView(e.meterId(), e.committedKw(), e.baselineVersion()))
                .toList();
        return new ApiModels.EventResponse(
                event.eventId(), event.start(), event.end(), event.intervalMinutes(),
                intervalCount(event), event.version(), event.frozen(), views);
    }

    private ApiModels.SettlementResponse loadSettlementResponse(String settlementId) {
        Rows.SettlementRow settlement = repo.findSettlement(settlementId)
                .orElseThrow(() -> new DrNotFoundException("结算不存在: " + settlementId));
        return toSettlementResponse(settlement);
    }

    private ApiModels.SettlementResponse toSettlementResponse(Rows.SettlementRow settlement) {
        List<ApiModels.SettlementIntervalView> views =
                repo.findSettlementIntervals(settlement.settlementId()).stream()
                        .map(i -> new ApiModels.SettlementIntervalView(
                                i.meterId(), i.intervalIndex(), i.intervalStart(),
                                i.baselineValue(), i.actualValue(),
                                i.baselineVersion(), i.actualVersion(), i.deliveredKw()))
                        .toList();
        return new ApiModels.SettlementResponse(
                settlement.settlementId(), settlement.requestId(), settlement.eventId(),
                settlement.eventVersion(), settlement.totalDeliveredKw(), settlement.frozenAt(), views);
    }

    /** 完整参数指纹：事件、事件 expectedVersion 及每块电表的基线/实测版本。 */
    private String fingerprint(String eventId, ApiModels.FreezeRequest request) {
        StringBuilder sb = new StringBuilder("event=").append(eventId)
                .append("|v=").append(request.expectedVersion());
        request.meters().stream()
                .sorted(Comparator.comparing(ApiModels.FreezeMeterVersion::meterId))
                .forEach(m -> sb.append('|').append(m.meterId())
                        .append(":b=").append(m.baselineVersion())
                        .append(":a=").append(m.actualVersion()));
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
