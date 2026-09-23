package com.example.starter.dr.service;

import com.example.starter.dr.DrApiException;
import com.example.starter.dr.dto.AdjustmentResponse;
import com.example.starter.dr.dto.EnrollRequest;
import com.example.starter.dr.dto.EnrollmentResponse;
import com.example.starter.dr.dto.EventCreateRequest;
import com.example.starter.dr.dto.EventResponse;
import com.example.starter.dr.dto.FreezeRequest;
import com.example.starter.dr.dto.FreezeResponse;
import com.example.starter.dr.dto.IntervalEvidence;
import com.example.starter.dr.dto.MeterEvidence;
import com.example.starter.dr.dto.PointResponse;
import com.example.starter.dr.dto.PointWriteRequest;
import com.example.starter.dr.repo.DrRepository;
import com.example.starter.dr.repo.DrRepository.AdjustmentRow;
import com.example.starter.dr.repo.DrRepository.EnrollmentRow;
import com.example.starter.dr.repo.DrRepository.EventRow;
import com.example.starter.dr.repo.DrRepository.FreezeRow;
import com.example.starter.dr.repo.DrRepository.FrozenIntervalRow;
import com.example.starter.dr.repo.DrRepository.PointRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 需求响应事件执行与结算冻结领域服务。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>事件为 UTC 半开区间 [start,end) 与固定分钟粒度；</li>
 *   <li>同一电表不得在时间重叠事件中重复承诺，端点相接不算重叠；</li>
 *   <li>曲线点按乐观版本写入/更正，缺失数据不得按零结算；</li>
 *   <li>冻结在单事务内校验全部区间证据版本，逐区间计算
 *       {@code min(承诺值, max(0, 基线-实测))}，任一失败整笔回滚；</li>
 *   <li>冻结后原始数据可更正，但只形成独立调整建议，不改写冻结快照；</li>
 *   <li>requestId + 完整参数指纹支持重放，同键异参返回 409。</li>
 * </ul>
 */
@Service
public class DrService {

    /** 基线数据类型。 */
    public static final String TYPE_BASELINE = "BASELINE";
    /** 实测数据类型。 */
    public static final String TYPE_ACTUAL = "ACTUAL";

    private static final int SCALE = 6;

    private final DrRepository repository;
    private final Clock clock;

    public DrService(DrRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    // ---------- 事件 ----------

    @Transactional
    public EventResponse createEvent(EventCreateRequest request) {
        Instant start = request.startUtc();
        Instant end = request.endUtc();
        int intervalMinutes = request.intervalMinutes();
        if (!end.isAfter(start)) {
            throw DrApiException.badRequest("INVALID_RANGE", "事件结束时刻必须晚于起始时刻（半开区间 [start,end)）");
        }
        if (intervalMinutes <= 0) {
            throw DrApiException.badRequest("INVALID_INTERVAL", "区间粒度必须为正整数分钟");
        }
        long durationSeconds = Duration.between(start, end).toSeconds();
        long intervalSeconds = (long) intervalMinutes * 60L;
        if (durationSeconds % intervalSeconds != 0) {
            throw DrApiException.badRequest("INVALID_INTERVAL",
                    "事件时长必须是区间粒度的整数倍，不得出现不足一个区间的尾部");
        }

        EventRow row = new EventRow(null, request.eventRef(), start, end, intervalMinutes, 1);
        try {
            EventRow saved = repository.insertEvent(row, Instant.now(clock));
            return toEventResponse(saved, false);
        } catch (DuplicateKeyException ex) {
            throw DrApiException.conflict("EVENT_REF_EXISTS", "事件编码已存在: " + request.eventRef());
        }
    }

    public EventResponse getEvent(String eventRef) {
        EventRow event = requireEvent(eventRef);
        return toEventResponse(event, repository.isEventFrozen(event.id()));
    }

    // ---------- 登记 ----------

    @Transactional
    public EnrollmentResponse enrollMeter(String eventRef, EnrollRequest request) {
        // 先按主键顺序锁定全部事件行，使所有登记事务以相同顺序加锁、完全串行，
        // 杜绝时间重叠重复承诺的并发漏判，并避免与单事件写事务互锁。
        repository.lockAllEventRows();
        EventRow event = repository.findEventByRef(eventRef)
                .orElseThrow(() -> DrApiException.notFound("事件不存在: " + eventRef));
        if (repository.isEventFrozen(event.id())) {
            throw DrApiException.conflict("EVENT_FROZEN", "事件已冻结，不能再登记电表");
        }
        if (repository.findEnrollment(event.id(), request.meterId()).isPresent()) {
            throw DrApiException.conflict("METER_ALREADY_ENROLLED",
                    "电表已登记到该事件: " + request.meterId());
        }
        List<EnrollmentRow> overlaps = repository.findOverlappingEnrollments(
                request.meterId(), event.startUtc(), event.endUtc());
        if (!overlaps.isEmpty()) {
            throw DrApiException.conflict("OVERLAPPING_COMMITMENT",
                    "电表在时间重叠的事件中已作出承诺，端点相接不算重叠: " + request.meterId());
        }

        EnrollmentRow row = new EnrollmentRow(null, event.id(), request.meterId(),
                scale(request.committedValue()), request.baselineVersion());
        long enrollmentId = repository.insertEnrollment(row, Instant.now(clock));
        repository.incrementEventVersion(event.id());
        return new EnrollmentResponse(enrollmentId, row.meterId(), row.committedValue(),
                row.baselineVersion());
    }

    public List<EnrollmentResponse> listEnrollments(String eventRef) {
        EventRow event = requireEvent(eventRef);
        return repository.findEnrollmentsByEvent(event.id()).stream()
                .map(e -> new EnrollmentResponse(e.id(), e.meterId(), e.committedValue(), e.baselineVersion()))
                .toList();
    }

    // ---------- 曲线写入/更正 ----------

    @Transactional
    public PointResponse writePoint(String eventRef, String meterId, PointWriteRequest request) {
        EventRow event = lockEvent(eventRef);
        int intervalCount = intervalCount(event);
        int idx = request.intervalIdx();
        if (idx < 0 || idx >= intervalCount) {
            throw DrApiException.badRequest("INTERVAL_OUT_OF_RANGE",
                    "区间编号超出 [0," + intervalCount + ") 半开范围: " + idx);
        }
        String dataType = normalizeType(request.dataType());
        BigDecimal value = requireFiniteNonNegative(request.value());

        EnrollmentRow enrollment = repository.findEnrollment(event.id(), meterId)
                .orElseThrow(() -> DrApiException.badRequest("METER_NOT_ENROLLED",
                        "电表未登记到该事件，不能写入曲线: " + meterId));

        Optional<PointRow> existing = repository.lockPoint(event.id(), meterId, dataType, idx);
        int newVersion;
        if (existing.isEmpty()) {
            if (request.expectedVersion() != null) {
                throw DrApiException.conflict("VERSION_CONFLICT",
                        "曲线点尚不存在，首次写入不能携带前版本号");
            }
            newVersion = 1;
            repository.insertPoint(new PointRow(null, event.id(), meterId, dataType, idx,
                    scale(value), newVersion), Instant.now(clock));
        } else {
            PointRow current = existing.get();
            Integer expected = request.expectedVersion();
            if (expected == null || expected != current.version()) {
                throw DrApiException.conflict("VERSION_CONFLICT",
                        "更正必须显式携带当前前版本号，当前版本=" + current.version()
                                + "，请求携带=" + expected);
            }
            newVersion = current.version() + 1;
            int updated = repository.casUpdatePoint(current.id(), scale(value),
                    current.version(), newVersion, Instant.now(clock));
            if (updated == 0) {
                // 并发更正抢先提交：当前版本已变化，整笔失败。
                throw DrApiException.conflict("VERSION_CONFLICT",
                        "曲线点已被并发更正，请携带最新前版本号重试");
            }
        }

        // 冻结后更正：原始数据照常演进，但只形成/刷新独立调整建议，不改写冻结快照。
        maybeRecordAdjustment(event, enrollment, meterId, idx, dataType, value, newVersion);

        return new PointResponse(meterId, dataType, idx, scale(value), newVersion, existing.isPresent());
    }

    // ---------- 冻结 ----------

    @Transactional
    public FreezeResponse freeze(String eventRef, FreezeRequest request) {
        EventRow event = lockEvent(eventRef);

        // 先按 requestId 处理重放/冲突语义。
        Optional<FreezeRow> sameRequest = repository.findFreezeByRequestId(request.requestId());
        if (sameRequest.isPresent()) {
            FreezeRow prior = sameRequest.get();
            if (!prior.eventId().equals(event.id())) {
                throw DrApiException.conflict("REQUEST_ID_CONFLICT",
                        "requestId 已用于其他事件的冻结");
            }
            String requestFingerprint = fingerprint(event, request);
            if (!prior.fingerprint().equals(requestFingerprint)) {
                throw DrApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "相同 requestId 携带了不同的冻结参数（含任一证据版本变化）");
            }
            return toFreezeResponse(eventRef, prior);
        }

        Optional<FreezeRow> existing = repository.findFreezeByEvent(event.id());
        if (existing.isPresent()) {
            throw DrApiException.conflict("EVENT_ALREADY_FROZEN",
                    "事件已存在冻结结算，每个事件至多冻结一次");
        }

        if (event.version() != request.expectedVersion()) {
            throw DrApiException.conflict("EVENT_VERSION_CONFLICT",
                    "事件版本冲突：expectedVersion=" + request.expectedVersion()
                            + "，当前版本=" + event.version());
        }

        int intervalCount = intervalCount(event);
        List<EnrollmentRow> enrollments = repository.findEnrollmentsByEvent(event.id());
        if (enrollments.isEmpty()) {
            throw DrApiException.unprocessable("NO_ENROLLMENT", "事件尚无登记电表，不能冻结结算");
        }
        Map<String, EnrollmentRow> enrollmentByMeter = new LinkedHashMap<>();
        for (EnrollmentRow e : enrollments) {
            enrollmentByMeter.put(e.meterId(), e);
        }

        // 规范化请求证据：meter 不重复、区间不重复，且 meter 必须已登记。
        Map<String, Map<Integer, IntervalEvidence>> evidenceByMeter = normalizeEvidence(request);
        for (String meterId : evidenceByMeter.keySet()) {
            if (!enrollmentByMeter.containsKey(meterId)) {
                throw DrApiException.badRequest("UNKNOWN_METER",
                        "冻结证据包含未登记电表: " + meterId);
            }
        }
        if (!evidenceByMeter.keySet().equals(enrollmentByMeter.keySet())) {
            throw DrApiException.unprocessable("INCOMPLETE_EVIDENCE",
                    "冻结证据必须且只能覆盖全部已登记电表，不得缺漏或多余");
        }
        for (Map.Entry<String, Map<Integer, IntervalEvidence>> entry : evidenceByMeter.entrySet()) {
            validateIntervalCoverage(entry.getKey(), entry.getValue().keySet(), intervalCount);
        }

        // 按固定顺序对全部区间点加行锁并校验证据版本、存在性；任一缺失/冲突整笔失败。
        List<FrozenIntervalRow> snapshots = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (EnrollmentRow enrollment : enrollments) {
            Map<Integer, IntervalEvidence> evidence = evidenceByMeter.get(enrollment.meterId());
            for (int idx = 0; idx < intervalCount; idx++) {
                IntervalEvidence ev = evidence.get(idx);
                Optional<PointRow> baselineOpt = repository.lockPoint(event.id(),
                        enrollment.meterId(), TYPE_BASELINE, idx);
                if (baselineOpt.isEmpty()) {
                    throw DrApiException.unprocessable("MISSING_BASELINE",
                            "缺少基线数据，不得按零值结算: meter=" + enrollment.meterId()
                                    + ", interval=" + idx);
                }
                Optional<PointRow> actualOpt = repository.lockPoint(event.id(),
                        enrollment.meterId(), TYPE_ACTUAL, idx);
                if (actualOpt.isEmpty()) {
                    throw DrApiException.unprocessable("MISSING_ACTUAL",
                            "缺少实测数据，不得按零值结算: meter=" + enrollment.meterId()
                                    + ", interval=" + idx);
                }
                PointRow baseline = baselineOpt.get();
                PointRow actual = actualOpt.get();
                if (baseline.version() != ev.baselineVersion()) {
                    throw DrApiException.conflict("EVIDENCE_VERSION_CONFLICT",
                            "基线证据版本冲突: meter=" + enrollment.meterId() + ", interval=" + idx
                                    + ", expected=" + ev.baselineVersion()
                                    + ", current=" + baseline.version());
                }
                if (actual.version() != ev.actualVersion()) {
                    throw DrApiException.conflict("EVIDENCE_VERSION_CONFLICT",
                            "实测证据版本冲突: meter=" + enrollment.meterId() + ", interval=" + idx
                                    + ", expected=" + ev.actualVersion()
                                    + ", current=" + actual.version());
                }
                BigDecimal delivered = delivered(baseline.value(), actual.value(),
                        enrollment.committedValue());
                total = total.add(delivered);
                snapshots.add(new FrozenIntervalRow(null, null, enrollment.meterId(), idx,
                        baseline.value(), actual.value(), baseline.version(), actual.version(),
                        delivered));
            }
        }

        String fp = fingerprint(event, request);
        FreezeRow freezeRow = new FreezeRow(null, event.id(), request.requestId(),
                request.expectedVersion(), fp, scale(total), Instant.now(clock));
        FreezeRow saved;
        try {
            saved = repository.insertFreeze(freezeRow);
        } catch (DuplicateKeyException ex) {
            // 并发冻结兜底：唯一约束拦截，转为重放比对或冲突错误（事务将随异常回滚）。
            throw handleConcurrentFreeze(eventRef, event.id(), request);
        }
        for (FrozenIntervalRow snapshot : snapshots) {
            repository.insertFrozenInterval(new FrozenIntervalRow(null, saved.id(),
                    snapshot.meterId(), snapshot.intervalIdx(), snapshot.baselineValue(),
                    snapshot.actualValue(), snapshot.baselineVersion(), snapshot.actualVersion(),
                    snapshot.deliveredValue()));
        }
        return toFreezeResponse(eventRef, saved);
    }

    @Transactional(readOnly = true)
    public FreezeResponse getFreezeEvidence(String eventRef) {
        EventRow event = requireEvent(eventRef);
        FreezeRow freeze = repository.findFreezeByEvent(event.id())
                .orElseThrow(() -> DrApiException.notFound("事件尚未冻结，无结算证据: " + eventRef));
        return toFreezeResponse(eventRef, freeze);
    }

    // ---------- 调整建议 ----------

    @Transactional(readOnly = true)
    public List<AdjustmentResponse> listAdjustments(String eventRef) {
        EventRow event = requireEvent(eventRef);
        return repository.findAdjustmentsByEvent(event.id()).stream()
                .map(a -> new AdjustmentResponse(a.id(), eventRef, a.meterId(), a.intervalIdx(),
                        a.dataType(), a.frozenVersion(), a.frozenValue(), a.correctedVersion(),
                        a.correctedValue(), a.frozenIntervalDelivered(),
                        a.suggestedIntervalDelivered(), a.suggestedDelta(), a.status(),
                        a.createdAt()))
                .toList();
    }

    // ---------- 内部辅助 ----------

    /**
     * 冻结后曲线更正：用更正值与冻结快照中的另一类型值重算建议交付量，
     * 生成或刷新该（冻结、电表、区间、数据类型）唯一的 OPEN 建议。
     */
    private void maybeRecordAdjustment(EventRow event, EnrollmentRow enrollment, String meterId,
                                       int idx, String dataType, BigDecimal newValue,
                                       int correctedVersion) {
        Optional<FreezeRow> freezeOpt = repository.findFreezeByEvent(event.id());
        if (freezeOpt.isEmpty()) {
            return;
        }
        FreezeRow freeze = freezeOpt.get();
        FrozenIntervalRow frozenInterval = repository.findFrozenInterval(freeze.id(), meterId, idx)
                .orElseThrow(() -> new IllegalStateException(
                        "冻结快照缺失区间: freezeId=" + freeze.id() + ", meter=" + meterId + ", idx=" + idx));

        BigDecimal otherFrozenValue = TYPE_BASELINE.equals(dataType)
                ? frozenInterval.actualValue()
                : frozenInterval.baselineValue();
        BigDecimal suggestedBaseline = TYPE_BASELINE.equals(dataType) ? newValue : otherFrozenValue;
        BigDecimal suggestedActual = TYPE_ACTUAL.equals(dataType) ? newValue : otherFrozenValue;
        BigDecimal suggested = delivered(suggestedBaseline, suggestedActual,
                enrollment.committedValue());
        BigDecimal delta = scale(suggested.subtract(frozenInterval.deliveredValue()));

        int frozenPointVersion = TYPE_BASELINE.equals(dataType)
                ? frozenInterval.baselineVersion()
                : frozenInterval.actualVersion();
        BigDecimal frozenPointValue = TYPE_BASELINE.equals(dataType)
                ? frozenInterval.baselineValue()
                : frozenInterval.actualValue();

        Optional<AdjustmentRow> prior = repository.findOpenAdjustment(
                freeze.id(), meterId, idx, dataType);
        AdjustmentRow updated = new AdjustmentRow(
                prior.map(AdjustmentRow::id).orElse(null),
                event.id(), freeze.id(), meterId, idx, dataType,
                frozenPointVersion, frozenPointValue, correctedVersion, scale(newValue),
                frozenInterval.deliveredValue(), suggested, delta, "OPEN",
                prior.map(AdjustmentRow::createdAt).orElse(Instant.now(clock)));
        if (prior.isPresent()) {
            repository.updateAdjustment(updated);
        } else {
            repository.insertAdjustment(updated);
        }
    }

    /** 唯一约束兜底：区分同键重放、同键异参与事件已被其他请求冻结。 */
    private DrApiException handleConcurrentFreeze(String eventRef, long eventId, FreezeRequest request) {
        Optional<FreezeRow> byRequest = repository.findFreezeByRequestId(request.requestId());
        if (byRequest.isPresent() && byRequest.get().eventId().equals(eventId)) {
            EventRow event = repository.findEventByRef(eventRef).orElseThrow();
            if (byRequest.get().fingerprint().equals(fingerprint(event, request))) {
                // 语义上属于重放，但发生在并发唯一约束冲突时，提示重试读取首次结果。
                return DrApiException.conflict("FREEZE_IN_PROGRESS",
                        "相同冻结请求正在并发提交，请重试以获取首次冻结结果");
            }
            return DrApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 携带了不同的冻结参数（含任一证据版本变化）");
        }
        return DrApiException.conflict("EVENT_ALREADY_FROZEN",
                "事件已存在冻结结算，每个事件至多冻结一次");
    }

    private Map<String, Map<Integer, IntervalEvidence>> normalizeEvidence(FreezeRequest request) {
        Map<String, Map<Integer, IntervalEvidence>> byMeter = new LinkedHashMap<>();
        for (MeterEvidence meter : request.meters()) {
            if (byMeter.putIfAbsent(meter.meterId(), new LinkedHashMap<>()) != null) {
                throw DrApiException.badRequest("DUPLICATE_EVIDENCE",
                        "冻结证据中电表重复: " + meter.meterId());
            }
            Map<Integer, IntervalEvidence> byIdx = byMeter.get(meter.meterId());
            for (IntervalEvidence interval : meter.intervals()) {
                if (interval.intervalIdx() < 0) {
                    throw DrApiException.badRequest("INTERVAL_OUT_OF_RANGE",
                            "区间编号不能为负: " + interval.intervalIdx());
                }
                if (byIdx.putIfAbsent(interval.intervalIdx(), interval) != null) {
                    throw DrApiException.badRequest("DUPLICATE_EVIDENCE",
                            "冻结证据中区间重复: meter=" + meter.meterId()
                                    + ", interval=" + interval.intervalIdx());
                }
            }
        }
        return byMeter;
    }

    private void validateIntervalCoverage(String meterId, java.util.Set<Integer> indexes, int intervalCount) {
        if (indexes.size() != intervalCount) {
            throw DrApiException.unprocessable("INCOMPLETE_EVIDENCE",
                    "电表证据区间不完整，要求恰好覆盖 [0," + intervalCount + "): " + meterId);
        }
        for (int idx = 0; idx < intervalCount; idx++) {
            if (!indexes.contains(idx)) {
                throw DrApiException.unprocessable("INCOMPLETE_EVIDENCE",
                        "电表证据缺少区间 " + idx + ": " + meterId);
            }
        }
    }

    private EventRow requireEvent(String eventRef) {
        return repository.findEventByRef(eventRef)
                .orElseThrow(() -> DrApiException.notFound("事件不存在: " + eventRef));
    }

    /** 锁定事件行；冻结等写事务依赖此锁串行化并发操作。 */
    private EventRow lockEvent(String eventRef) {
        return repository.lockEventByRef(eventRef)
                .orElseThrow(() -> DrApiException.notFound("事件不存在: " + eventRef));
    }

    private EventResponse toEventResponse(EventRow event, boolean frozen) {
        return new EventResponse(event.id(), event.eventRef(), event.startUtc(), event.endUtc(),
                event.intervalMinutes(), intervalCount(event), event.version(), frozen);
    }

    private FreezeResponse toFreezeResponse(String eventRef, FreezeRow freeze) {
        List<FreezeResponse.IntervalResult> intervals = repository.findFrozenIntervals(freeze.id())
                .stream()
                .map(i -> new FreezeResponse.IntervalResult(i.meterId(), i.intervalIdx(),
                        i.baselineValue(), i.actualValue(), i.baselineVersion(), i.actualVersion(),
                        i.deliveredValue()))
                .toList();
        return new FreezeResponse(freeze.id(), freeze.requestId(), eventRef,
                freeze.expectedVersion(), freeze.fingerprint(), freeze.totalDelivered(),
                freeze.frozenAt(), intervals);
    }

    private int intervalCount(EventRow event) {
        long seconds = Duration.between(event.startUtc(), event.endUtc()).toSeconds();
        return Math.toIntExact(seconds / ((long) event.intervalMinutes() * 60L));
    }

    private String normalizeType(String raw) {
        if (raw == null) {
            throw DrApiException.badRequest("INVALID_DATA_TYPE", "数据类型不能为空");
        }
        String type = raw.trim().toUpperCase();
        if (!TYPE_BASELINE.equals(type) && !TYPE_ACTUAL.equals(type)) {
            throw DrApiException.badRequest("INVALID_DATA_TYPE",
                    "数据类型只能是 BASELINE 或 ACTUAL: " + raw);
        }
        return type;
    }

    private BigDecimal requireFiniteNonNegative(BigDecimal value) {
        if (value == null) {
            throw DrApiException.badRequest("INVALID_VALUE", "曲线值不能为空");
        }
        if (value.signum() < 0) {
            throw DrApiException.badRequest("INVALID_VALUE", "曲线值必须为非负有限小数");
        }
        return value;
    }

    /** 区间交付量：min(承诺值, max(0, 基线-实测))。 */
    private BigDecimal delivered(BigDecimal baseline, BigDecimal actual, BigDecimal committed) {
        BigDecimal reduction = baseline.subtract(actual).max(BigDecimal.ZERO);
        return scale(reduction.min(committed));
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 完整参数指纹：requestId、事件 expectedVersion 与全部证据版本
     * （电表、区间、基线版本、实测版本）按确定顺序序列化后取 SHA-256。
     */
    private String fingerprint(EventRow event, FreezeRequest request) {
        Map<String, Map<Integer, IntervalEvidence>> sorted = new TreeMap<>();
        for (MeterEvidence meter : request.meters()) {
            sorted.computeIfAbsent(meter.meterId(), k -> new TreeMap<>());
            for (IntervalEvidence interval : meter.intervals()) {
                sorted.get(meter.meterId()).put(interval.intervalIdx(), interval);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("v1|").append(request.requestId()).append('|')
                .append(event.id()).append('|')
                .append(request.expectedVersion());
        for (Map.Entry<String, Map<Integer, IntervalEvidence>> meterEntry : sorted.entrySet()) {
            for (Map.Entry<Integer, IntervalEvidence> intervalEntry
                    : meterEntry.getValue().entrySet()) {
                IntervalEvidence e = intervalEntry.getValue();
                sb.append('|').append(meterEntry.getKey())
                        .append('#').append(intervalEntry.getKey())
                        .append(":b").append(e.baselineVersion())
                        .append(":a").append(e.actualVersion());
            }
        }
        return sha256Hex(sb.toString());
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
