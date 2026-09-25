package com.example.starter.service;

import com.example.starter.db.AlarmRepository;
import com.example.starter.db.FanPo;
import com.example.starter.db.FanRepository;
import com.example.starter.db.IdempotentRequestPo;
import com.example.starter.db.IdempotencyRepository;
import com.example.starter.db.ReadingRepository;
import com.example.starter.db.RetestPo;
import com.example.starter.db.RetestRepository;
import com.example.starter.db.SiloPo;
import com.example.starter.db.SiloRepository;
import com.example.starter.db.VentilationOperationPo;
import com.example.starter.db.VentilationRepository;
import com.example.starter.domain.OperationStatus;
import com.example.starter.domain.ReadingPhase;
import com.example.starter.domain.RequestKind;
import com.example.starter.domain.SiloStatus;
import com.example.starter.domain.VentilationPurpose;
import com.example.starter.web.ApiException;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.AlarmRequest;
import com.example.starter.web.dto.AlarmResponse;
import com.example.starter.web.dto.ArchiveRequest;
import com.example.starter.web.dto.ArchiveResponse;
import com.example.starter.web.dto.CreateFanRequest;
import com.example.starter.web.dto.CreateSiloRequest;
import com.example.starter.web.dto.FanView;
import com.example.starter.web.dto.OperationView;
import com.example.starter.web.dto.ReadingAcceptResponse;
import com.example.starter.web.dto.ReadingBatchRequest;
import com.example.starter.web.dto.ReadingPoint;
import com.example.starter.web.dto.RetestRequest;
import com.example.starter.web.dto.RetestResponse;
import com.example.starter.web.dto.RetestView;
import com.example.starter.web.dto.SiloView;
import com.example.starter.web.dto.TemperaturePoint;
import com.example.starter.web.dto.VentilationRequest;
import com.example.starter.web.dto.VentilationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 粮情测控与通风作业联动主服务。
 *
 * <p>状态机：日常监测 MONITORING → 异常告警 ALARM → 通风作业 VENTILATING
 * → 待复测完成 → RETEST_PASS / RETEST_FAIL（可二次通风）→ 两轮失败转 MANUAL → ARCHIVED 终态。</p>
 */
@Service
public class GrainSiloService {

    private static final List<String> FIRST_ROUND_FROM = List.of(SiloStatus.ALARM.name());
    private static final List<String> SECOND_ROUND_FROM = List.of(SiloStatus.RETEST_FAIL.name());
    private static final List<String> ARCHIVABLE = List.of(
            SiloStatus.MONITORING.name(),
            SiloStatus.ALARM.name(),
            SiloStatus.RETEST_PENDING.name(),
            SiloStatus.RETEST_FAIL.name(),
            SiloStatus.RETEST_PASS.name(),
            SiloStatus.MANUAL.name());

    private final SiloRepository siloRepository;
    private final FanRepository fanRepository;
    private final ReadingRepository readingRepository;
    private final AlarmRepository alarmRepository;
    private final VentilationRepository ventilationRepository;
    private final RetestRepository retestRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GrainSiloService(SiloRepository siloRepository, FanRepository fanRepository,
                            ReadingRepository readingRepository, AlarmRepository alarmRepository,
                            VentilationRepository ventilationRepository, RetestRepository retestRepository,
                            IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper,
                            Clock clock) {
        this.siloRepository = siloRepository;
        this.fanRepository = fanRepository;
        this.readingRepository = readingRepository;
        this.alarmRepository = alarmRepository;
        this.ventilationRepository = ventilationRepository;
        this.retestRepository = retestRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 合成数据维护
    // ------------------------------------------------------------------

    @Transactional
    public SiloView createSilo(CreateSiloRequest request) {
        try {
            SiloPo po = siloRepository.insert(new SiloRepository.CreateSiloRow(
                    request.code(), request.name(), request.grainType(), request.safeTemperature(),
                    request.maxGradient(), request.equilibriumHumidity()));
            return toSiloView(po);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("粮仓编号已存在: " + request.code());
        }
    }

    @Transactional
    public FanView createFan(CreateFanRequest request) {
        try {
            FanPo po = fanRepository.insert(request.code(), request.name());
            return new FanView(po.code(), po.name(), false);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("风机编号已存在: " + request.code());
        }
    }

    @Transactional(readOnly = true)
    public List<SiloView> listSilos() {
        return siloRepository.findAll().stream().map(this::toSiloView).toList();
    }

    @Transactional(readOnly = true)
    public SiloView.SiloDetail getSilo(String code) {
        SiloPo silo = getSiloByCode(code);
        List<OperationView> operations = ventilationRepository.findBySilo(silo.id()).stream()
                .map(this::toOperationView).toList();
        List<RetestView> retests = retestRepository.findBySilo(silo.id()).stream()
                .map(this::toRetestView).toList();
        return new SiloView.SiloDetail(toSiloView(silo), operations, retests);
    }

    // ------------------------------------------------------------------
    // 告警
    // ------------------------------------------------------------------

    @Transactional
    public AlarmResponse reportAlarm(AlarmRequest request) {
        validatePoints(request.readings());
        String fingerprint = fingerprint(RequestKind.ALARM, request.requestId(), request);
        return withIdempotency(RequestKind.ALARM, request.requestId(), fingerprint, request.siloCode(),
                AlarmResponse.class, silo -> {
                    if (SiloStatus.ARCHIVED.name().equals(silo.status())) {
                        throw new BusinessRuleException("粮仓已归档（终态），不再受理告警判定");
                    }
                    List<Double> temps = temperatures(request.readings());
                    double max = temps.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
                    double min = temps.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                    // 任一点严格大于安全温度才告警，恰等判正常。
                    boolean alarmed = temps.stream().anyMatch(t -> t > silo.safeTemperature());
                    persistReadings(silo.id(), request.readings(), request.measuredAt(), ReadingPhase.ALARM);
                    alarmRepository.insert(silo.id(), request.requestId(), max, min, alarmed,
                            request.measuredAt());
                    String nextState = silo.status();
                    if (alarmed) {
                        // 流转严格单向：监测中或告警中收到超温即进入/保持告警；通风流程中不回退。
                        if (!SiloStatus.VENTILATING.name().equals(silo.status())) {
                            siloRepository.updateStatus(silo.id(), SiloStatus.ALARM.name());
                            nextState = SiloStatus.ALARM.name();
                        }
                    }
                    // 恰等安全温度判正常：不改变既有流转状态（监测中保持监测，已告警须经通风解除）。
                    return new AlarmResponse(request.requestId(), silo.code(), alarmed, max, min,
                            silo.safeTemperature(), request.measuredAt(), nextState);
                });
    }

    // ------------------------------------------------------------------
    // 通风令
    // ------------------------------------------------------------------

    @Transactional
    public VentilationResponse orderVentilation(VentilationRequest request) {
        VentilationPurpose purpose = parsePurpose(request.purpose());
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BusinessRuleException("作业窗终点必须晚于起点（半开区间 [start,end)）");
        }
        if (request.ambientHumidity() < 0 || request.ambientHumidity() > 100) {
            throw new BusinessRuleException("外界湿度必须在 0-100（百分比）之间");
        }
        String fingerprint = fingerprint(RequestKind.VENTILATION, request.requestId(), request);
        return withIdempotency(RequestKind.VENTILATION, request.requestId(), fingerprint, request.siloCode(),
                VentilationResponse.class, silo -> {
                    FanPo fan = fanRepository.findByCode(request.fanCode())
                            .orElseThrow(() -> new NotFoundException("风机不存在: " + request.fanCode()));

                    String status = silo.status();
                    boolean secondRound = SiloStatus.RETEST_FAIL.name().equals(status);
                    if (SiloStatus.ARCHIVED.name().equals(status)) {
                        throw new BusinessRuleException("粮仓已归档（终态），不能下达通风令");
                    }
                    if (SiloStatus.VENTILATING.name().equals(status)) {
                        throw new BusinessRuleException("同一仓同时刻只允许一个执行中通风作业");
                    }
                    if (SiloStatus.MANUAL.name().equals(status)) {
                        throw new BusinessRuleException("两轮通风复测仍超标，已转人工处置，不能再通风");
                    }
                    if (!SiloStatus.ALARM.name().equals(status) && !secondRound) {
                        throw new BusinessRuleException(
                                "当前粮仓状态不允许通风，须先经异常告警（或复测超标）: " + status);
                    }
                    List<String> allowedFrom;
                    if (secondRound) {
                        if (silo.ventilationRounds() >= 2) {
                            throw new BusinessRuleException("通风最多两轮，两轮复测仍超标须转人工处置");
                        }
                        allowedFrom = SECOND_ROUND_FROM;
                    } else {
                        allowedFrom = FIRST_ROUND_FROM;
                    }
                    int nextRound = silo.ventilationRounds() + 1;

                    // 外界湿度严格大于粮堆平衡湿度禁止通风，恰等允许。
                    if (request.ambientHumidity() > silo.equilibriumHumidity()) {
                        throw new BusinessRuleException(String.format(
                                "外界湿度 %.2f%% 高于粮堆平衡湿度 %.2f%%，禁止通风",
                                request.ambientHumidity(), silo.equilibriumHumidity()));
                    }
                    // 作业窗半开区间 [start,end) 占用，端点相接不冲突。
                    if (ventilationRepository.windowOverlaps(fan.id(), request.startTime(), request.endTime())) {
                        throw new BusinessRuleException("风机作业窗与既有作业窗冲突（占用按 [start,end) 判定）");
                    }
                    if (fanRepository.isLocked(fan.id())) {
                        throw new ConflictException("风机正被其他仓占用，同一台风机同一时刻只服务一个仓");
                    }

                    LocalDateTime startedAt = LocalDateTime.now(clock);
                    // 作业开始与风机锁定在同一事务内：任一步失败整体回滚，不存在作业开始而风机未锁。
                    VentilationOperationPo op = ventilationRepository.insert(
                            silo.id(), fan.id(), request.requestId(), purpose.name(), nextRound,
                            request.ambientHumidity(), request.startTime(), request.endTime(), startedAt);
                    boolean claimed = siloRepository.compareAndUpdateStatus(
                            silo.id(), allowedFrom, SiloStatus.VENTILATING.name(),
                            silo.ventilationRounds(), true);
                    if (!claimed) {
                        // 行锁内并发状态被改变（唯一受理落败），回滚。
                        throw new ConflictException("通风令并发下达冲突，本请求未受理");
                    }
                    boolean locked = fanRepository.tryLock(fan.id(), op.id(), silo.id());
                    if (!locked) {
                        throw new ConflictException("风机已被占用，同一台风机同一时刻只服务一个仓");
                    }

                    return new VentilationResponse(request.requestId(), op.id(), silo.code(), fan.code(),
                            purpose.name(), nextRound, request.ambientHumidity(), request.startTime(),
                            request.endTime(), OperationStatus.EXECUTING.name(), startedAt);
                });
    }

    // ------------------------------------------------------------------
    // 效果复测
    // ------------------------------------------------------------------

    @Transactional
    public RetestResponse retest(RetestRequest request) {
        validatePoints(request.readings());
        String fingerprint = fingerprint(RequestKind.RETEST, request.requestId(), request);
        return withIdempotency(RequestKind.RETEST, request.requestId(), fingerprint, request.siloCode(),
                RetestResponse.class, silo -> {
                    if (SiloStatus.ARCHIVED.name().equals(silo.status())) {
                        throw new BusinessRuleException("粮仓已归档（终态），不再受理复测");
                    }
                    VentilationOperationPo op = ventilationRepository.findActiveBySilo(silo.id())
                            .orElseThrow(() -> new BusinessRuleException("当前没有执行中的通风作业，不能复测"));

                    ReadingPhase phase = op.roundNo() == 1 ? ReadingPhase.ROUND_1 : ReadingPhase.ROUND_2;
                    persistReadings(silo.id(), request.readings(), request.measuredAt(), phase);

                    List<Double> temps = temperatures(request.readings());
                    double max = temps.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
                    double min = temps.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                    double gradient = max - min;
                    // 任一点严格大于安全温度，或粮温梯度严格大于允许梯度，即复测不通过；恰等均合格。
                    boolean overTemperature = temps.stream().anyMatch(t -> t > silo.safeTemperature());
                    boolean gradientOver = gradient > silo.maxGradient();
                    boolean passed = !overTemperature && !gradientOver;

                    // 复测完成同时释放风机（同一事务）。
                    ventilationRepository.complete(op.id());
                    fanRepository.unlock(op.fanId());
                    retestRepository.insert(silo.id(), op.id(), request.requestId(), op.roundNo(),
                            max, min, gradient, passed, request.measuredAt());

                    String nextState;
                    String nextAction;
                    if (passed) {
                        nextState = SiloStatus.RETEST_PASS.name();
                        nextAction = "PASS";
                    } else if (silo.ventilationRounds() < 2) {
                        // 通风后复测粮温梯度仍超标可二次通风。
                        nextState = SiloStatus.RETEST_FAIL.name();
                        nextAction = "SECOND_VENTILATION";
                    } else {
                        // 最多两轮，两轮仍超标转人工处置。
                        nextState = SiloStatus.MANUAL.name();
                        nextAction = "MANUAL";
                    }
                    siloRepository.updateStatus(silo.id(), nextState);

                    return new RetestResponse(request.requestId(), silo.code(), op.roundNo(), max, min,
                            gradient, passed, nextAction, nextState, request.measuredAt());
                });
    }

    // ------------------------------------------------------------------
    // 归档（终态，重复归档幂等成功）
    // ------------------------------------------------------------------

    @Transactional
    public ArchiveResponse archive(ArchiveRequest request) {
        String fingerprint = fingerprint(RequestKind.ARCHIVE, request.requestId(), request);
        return withIdempotency(RequestKind.ARCHIVE, request.requestId(), fingerprint, request.siloCode(),
                ArchiveResponse.class, silo -> {
                    if (SiloStatus.ARCHIVED.name().equals(silo.status())) {
                        return new ArchiveResponse(request.requestId(), silo.code(),
                                SiloStatus.ARCHIVED.name(), true, true);
                    }
                    if (SiloStatus.VENTILATING.name().equals(silo.status())) {
                        throw new BusinessRuleException("通风作业执行中不能归档，须先完成复测释放风机");
                    }
                    boolean moved = siloRepository.compareAndUpdateStatusIn(silo.id(), ARCHIVABLE,
                            SiloStatus.ARCHIVED.name());
                    if (!moved) {
                        throw new ConflictException("粮仓当前状态不能归档: " + silo.status());
                    }
                    return new ArchiveResponse(request.requestId(), silo.code(),
                            SiloStatus.ARCHIVED.name(), true, false);
                });
    }

    // ------------------------------------------------------------------
    // 日常温湿度上报；归档后只登记迟到事件
    // ------------------------------------------------------------------

    @Transactional
    public ReadingAcceptResponse uploadReadings(String siloCode, ReadingBatchRequest request) {
        validatePoints(request.readings());
        SiloPo silo = getSiloByCode(siloCode);
        LocalDateTime now = LocalDateTime.now(clock);
        if (SiloStatus.ARCHIVED.name().equals(silo.status())) {
            for (ReadingPoint point : request.readings()) {
                readingRepository.insertLateEvent(silo.id(), point.pointCode(), point.temperature(),
                        point.humidity(), now);
            }
            return new ReadingAcceptResponse(silo.code(), request.readings().size(), true,
                    SiloStatus.ARCHIVED.name());
        }
        persistReadings(silo.id(), request.readings(), now, ReadingPhase.DAILY);
        return new ReadingAcceptResponse(silo.code(), request.readings().size(), false, silo.status());
    }

    // ------------------------------------------------------------------
    // 可复算查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TemperaturePoint> temperatureCurve(String siloCode) {
        SiloPo silo = getSiloByCode(siloCode);
        return readingRepository.findCurve(silo.id()).stream()
                .map(r -> new TemperaturePoint(silo.code(), r.pointCode(), r.temperature(), r.humidity(),
                        r.measuredAt(), r.phase()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OperationView> windows(String siloCode) {
        SiloPo silo = getSiloByCode(siloCode);
        return ventilationRepository.findBySilo(silo.id()).stream().map(this::toOperationView).toList();
    }

    @Transactional(readOnly = true)
    public List<RetestView> retests(String siloCode) {
        SiloPo silo = getSiloByCode(siloCode);
        return retestRepository.findBySilo(silo.id()).stream().map(this::toRetestView).toList();
    }

    // ------------------------------------------------------------------
    // 内部辅助（独立方法集中在类末尾）
    // ------------------------------------------------------------------

    private SiloPo getSiloByCode(String code) {
        return siloRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("粮仓不存在: " + code));
    }

    private SiloPo lockSilo(String code) {
        SiloPo silo = getSiloByCode(code);
        // 行级写锁串行化同仓并发：同仓并发通风令在此排队，保证唯一受理。
        siloRepository.lockForUpdate(silo.id());
        return siloRepository.findByCode(code).orElseThrow();
    }

    private void persistReadings(long siloId, List<ReadingPoint> points, LocalDateTime measuredAt,
                                 ReadingPhase phase) {
        for (ReadingPoint point : points) {
            readingRepository.insertReading(siloId, point.pointCode(), point.temperature(),
                    point.humidity(), measuredAt, phase.name());
        }
    }

    private void validatePoints(List<ReadingPoint> points) {
        for (ReadingPoint point : points) {
            if (point.pointCode() == null || point.pointCode().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "测点编号不能为空");
            }
            if (point.temperature() == null && point.humidity() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "测点 " + point.pointCode() + " 的温度与湿度不能同时为空");
            }
        }
    }

    private List<Double> temperatures(List<ReadingPoint> points) {
        List<Double> temps = points.stream().map(ReadingPoint::temperature).filter(java.util.Objects::nonNull)
                .toList();
        if (temps.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "至少需要一个温度测点数据");
        }
        return temps;
    }

    private VentilationPurpose parsePurpose(String raw) {
        try {
            return VentilationPurpose.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "通风目的非法，允许 COOLING/DEHUMIDIFY/QUALITY: " + raw);
        }
    }

    /**
     * 幂等执行：同键同参返回首次结果；同键异参抛 409 且不改变作业窗。
     * 先对粮仓加行级写锁再查台账：同仓并发请求在锁上串行，首请求提交后重放请求才能读到台账。
     */
    private <T> T withIdempotency(RequestKind kind, String requestId, String fingerprint, String siloCode,
                                  Class<T> responseType, IdempotentAction<T> action) {
        SiloPo lockedSilo = lockSilo(siloCode);
        var existing = idempotencyRepository.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, responseType);
        }
        T result = action.run(lockedSilo);
        try {
            idempotencyRepository.insert(requestId, kind.name(), fingerprint, lockedSilo.id(), 201,
                    writeJson(result), LocalDateTime.now(clock));
        } catch (DuplicateKeyException ex) {
            // 极端情况：同一 requestId 跨仓竞争（request_id 全局唯一）。首请求已落台账，
            // 按重放语义返回，本事务既有写入随之回滚。
            IdempotentRequestPo winner = idempotencyRepository.find(requestId).orElseThrow();
            return replay(winner, fingerprint, responseType);
        }
        return result;
    }

    private <T> T replay(IdempotentRequestPo stored, String fingerprint, Class<T> responseType) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ConflictException("requestId 已存在但业务参数不一致（同键异参），返回 409 且不改变作业窗");
        }
        return readJson(stored.response(), responseType);
    }

    private String fingerprint(RequestKind kind, String requestId, Object payload) {
        try {
            String canonical = kind.name() + '|' + requestId + '|' + objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("指纹计算失败", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    private SiloView toSiloView(SiloPo po) {
        return new SiloView(po.code(), po.name(), po.grainType(), po.safeTemperature(), po.maxGradient(),
                po.equilibriumHumidity(), po.status(), po.ventilationRounds(), po.createdAt());
    }

    private OperationView toOperationView(VentilationOperationPo po) {
        String fanCode = fanRepository.findById(po.fanId()).map(FanPo::code)
                .orElse("FAN#" + po.fanId());
        return new OperationView(po.id(), po.requestId(), fanCode, po.purpose(), po.roundNo(),
                po.ambientHumidity(), po.startTime(), po.endTime(), po.status(), po.startedAt(),
                po.completedAt());
    }

    private RetestView toRetestView(RetestPo po) {
        return new RetestView(po.id(), po.requestId(), po.operationId(), po.roundNo(), po.maxTemperature(),
                po.minTemperature(), po.gradient(), po.passed(), po.measuredAt());
    }

    /**
     * 幂等保护下的业务动作，入参为已加行锁的粮仓。
     */
    @FunctionalInterface
    private interface IdempotentAction<T> {
        T run(SiloPo lockedSilo);
    }
}
