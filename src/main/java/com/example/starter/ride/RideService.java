package com.example.starter.ride;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.ride.CertificateRepository.Certificate;
import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.ArchiveRequest;
import com.example.starter.ride.dto.RideDtos.CheckItem;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.OverhaulInspectionRequest;
import com.example.starter.ride.dto.RideDtos.OverhaulRequest;
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.RegisterCertificateRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 游乐设施日检与载客联锁核心服务。
 * 状态链：日检→试运行(恰3次)→开放→运行监测→闭园归档；
 * 故障停运/缓停→复检合格→复开(同日恰2次合法)。
 * 所有状态流转在事务内持行锁串行执行，保证同一设备同时刻只有一条状态链。
 */
@Service
public class RideService {

    /** 年度证书默认有效期：合格时刻加365×24小时（合成规则，不引用真实法定周期）。 */
    private static final Duration DEFAULT_CERT_VALIDITY = Duration.ofHours(365L * 24);

    private final RideRepository rides;
    private final RideRecordRepository records;
    private final CertificateRepository certificates;
    private final Idempotency.Executor idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RideService(RideRepository rides, RideRecordRepository records,
            CertificateRepository certificates, Idempotency.Executor idempotency,
            ObjectMapper objectMapper, Clock clock) {
        this.rides = rides;
        this.records = records;
        this.certificates = certificates;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 创建设备（合成数据），初始状态为待日检；inspectionRequired 为受检标志。 */
    @Transactional
    public Map<String, Object> createRide(CreateRideRequest request) {
        Ride ride = rides.insert(request.name(), request.maxLoad(), request.maxSpeed(),
                request.inspectionRequiredFlag());
        records.insertEvent(ride.id(), "CREATE", "设备登记：" + ride.name());
        return rideView(ride);
    }

    /** 日检：清单逐项合格方可试运行；安全装置任一失效整机立即停运。 */
    public ApiResult dailyCheck(long rideId, DailyCheckRequest request) {
        return idempotency.execute(request.requestId(), "DAILY_CHECK", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireStatus(ride, "当前状态不允许日检",
                    RideStatus.PENDING_CHECK, RideStatus.CHECKED);
            boolean allPassed = request.items().stream().allMatch(CheckItem::passed);
            List<String> failedSafety = request.items().stream()
                    .filter(item -> !item.passed() && isSafetyDevice(item.itemName()))
                    .map(CheckItem::itemName)
                    .toList();
            LocalDate today = LocalDate.now(clock);
            records.insertDailyCheck(rideId, request.requestId(), today, allPassed, request.items());
            records.insertEvent(rideId, "DAILY_CHECK",
                    allPassed ? "日检清单逐项合格" : "日检存在不合格项");

            RideStatus newStatus = ride.status();
            LocalDate checkDate = ride.checkDate();
            int trialCount = ride.trialCount();
            if (allPassed) {
                newStatus = RideStatus.CHECKED;
                checkDate = today;
                trialCount = 0;
            } else if (!failedSafety.isEmpty()) {
                newStatus = RideStatus.STOPPED;
                records.insertEvent(rideId, "FAULT_STOP",
                        "安全装置失效立即停运：" + String.join("、", failedSafety));
            }
            update(ride, newStatus, trialCount, ride.reopenCount(), checkDate, ride.reopenDate());

            Map<String, Object> body = rideView(rideId);
            body.put("allPassed", allPassed);
            body.put("checkDate", allPassed ? today.toString() : null);
            return ok(body);
        });
    }

    /** 试运行：恰3次空载无异常方可载客；任一次异常立即故障停运。 */
    public ApiResult trialRun(long rideId, TrialRunRequest request) {
        return idempotency.execute(request.requestId(), "TRIAL_RUN", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireStatus(ride, "日检合格后方可试运行", RideStatus.CHECKED);
            int seq = ride.trialCount() + 1;
            records.insertTrialRun(rideId, request.requestId(), seq, request.normal());

            RideStatus newStatus = RideStatus.CHECKED;
            int trialCount = ride.trialCount();
            if (request.normal()) {
                trialCount = seq;
                records.insertEvent(rideId, "TRIAL_RUN", "第" + seq + "次空载试运行正常");
                if (trialCount == Ride.REQUIRED_TRIAL_COUNT) {
                    newStatus = RideStatus.READY;
                    records.insertEvent(rideId, "TRIAL_RUN", "试运行恰3次合格，允许开放载客");
                }
            } else {
                newStatus = RideStatus.STOPPED;
                trialCount = 0;
                records.insertEvent(rideId, "FAULT_STOP", "第" + seq + "次试运行异常，整机立即停运");
            }
            update(ride, newStatus, trialCount, ride.reopenCount(),
                    ride.checkDate(), ride.reopenDate());
            return ok(rideView(rideId));
        });
    }

    /** 开放：须当日日检合格且试运行恰3次合格；受检设备还须当前有效年度证书；并发开放唯一成功。 */
    public ApiResult open(long rideId, OpenRequest request) {
        return idempotency.execute(request.requestId(), "OPEN", rideId, request, () -> {
            Ride ride = lock(rideId);
            if (ride.checkDate() == null || !ride.checkDate().equals(LocalDate.now(clock))) {
                throw ApiException.conflict("未日检设备无法开放");
            }
            requireStatus(ride, "当前状态不允许开放，须试运行恰3次合格",
                    RideStatus.READY);
            requireEligibility(ride, "开放");
            update(ride, RideStatus.OPEN, ride.trialCount(), ride.reopenCount(),
                    ride.checkDate(), ride.reopenDate());
            records.insertEvent(rideId, "OPEN", "设备开放载客");
            return ok(rideView(rideId));
        });
    }

    /** 安全装置失效上报：压杠、安全带与联锁装置任一失效整机立即停运。 */
    public ApiResult safetyFault(long rideId, SafetyFaultRequest request) {
        return idempotency.execute(request.requestId(), "SAFETY_FAULT", rideId, request, () -> {
            if (!isSafetyDevice(request.device())) {
                throw ApiException.unprocessable("未知安全装置：" + request.device()
                        + "，合法值为压杠/安全带/联锁装置");
            }
            Ride ride = lock(rideId);
            if (ride.status() == RideStatus.ARCHIVED) {
                throw ApiException.conflict("设备已归档，拒绝安全装置失效上报");
            }
            if (ride.status() == RideStatus.STOPPED) {
                Map<String, Object> body = rideView(rideId);
                body.put("message", "设备已处于故障停运状态");
                return ok(body);
            }
            requireStatus(ride, "设备未在运行流程中，无法上报安全装置失效",
                    RideStatus.CHECKED, RideStatus.READY, RideStatus.OPEN,
                    RideStatus.SUSPENDED, RideStatus.RECHECKED);
            update(ride, RideStatus.STOPPED, ride.trialCount(), ride.reopenCount(),
                    ride.checkDate(), ride.reopenDate());
            records.insertEvent(rideId, "FAULT_STOP",
                    "安全装置失效立即停运：" + request.device());
            return ok(rideView(rideId));
        });
    }

    /** 复检：故障停运或缓停后复检合格方可复开。 */
    public ApiResult recheck(long rideId, RecheckRequest request) {
        return idempotency.execute(request.requestId(), "RECHECK", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireStatus(ride, "仅故障停运或缓停设备可复检",
                    RideStatus.STOPPED, RideStatus.SUSPENDED);
            boolean allPassed = request.items().stream().allMatch(CheckItem::passed);
            if (allPassed) {
                update(ride, RideStatus.RECHECKED, ride.trialCount(), ride.reopenCount(),
                        ride.checkDate(), ride.reopenDate());
                records.insertEvent(rideId, "RECHECK", "复检合格，允许复开");
            } else {
                List<String> failed = request.items().stream()
                        .filter(item -> !item.passed())
                        .map(CheckItem::itemName)
                        .toList();
                records.insertEvent(rideId, "RECHECK",
                        "复检不合格：" + String.join("、", failed));
            }
            Map<String, Object> body = rideView(rideId);
            body.put("allPassed", allPassed);
            return ok(body);
        });
    }

    /** 复开：同日复开最多2次，恰2次合法，超出当日禁开；受检设备还须当前有效年度证书。 */
    public ApiResult reopen(long rideId, ReopenRequest request) {
        return idempotency.execute(request.requestId(), "REOPEN", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireStatus(ride, "仅复检合格设备可复开", RideStatus.RECHECKED);
            LocalDate today = LocalDate.now(clock);
            int used = today.equals(ride.reopenDate()) ? ride.reopenCount() : 0;
            if (used >= Ride.MAX_DAILY_REOPEN) {
                throw ApiException.unprocessable(
                        "同日复开已达上限" + Ride.MAX_DAILY_REOPEN + "次，当日禁开");
            }
            requireEligibility(ride, "复开");
            int reopenCount = used + 1;
            update(ride, RideStatus.OPEN, ride.trialCount(), reopenCount,
                    ride.checkDate(), today);
            records.insertEvent(rideId, "REOPEN", "当日第" + reopenCount + "次复开");
            return ok(rideView(rideId));
        });
    }

    /** 闭园归档：终态；重复归档返回幂等成功；归档后迟到运行数据只记迟到事件。 */
    public ApiResult archive(long rideId, ArchiveRequest request) {
        return idempotency.execute(request.requestId(), "ARCHIVE", rideId, request, () -> {
            Ride ride = lock(rideId);
            if (ride.status() == RideStatus.ARCHIVED) {
                Map<String, Object> body = rideView(rideId);
                body.put("message", "设备已归档，重复归档幂等成功");
                return ok(body);
            }
            update(ride, RideStatus.ARCHIVED, ride.trialCount(), ride.reopenCount(),
                    ride.checkDate(), ride.reopenDate());
            records.insertEvent(rideId, "ARCHIVE", "闭园归档，进入终态");
            return ok(rideView(rideId));
        });
    }

    /**
     * 运行监测读数：载荷或速度超阈值触发缓停，恰等阈值合格；
     * 归档后迟到数据只记迟到事件，不改变状态。
     */
    @Transactional
    public Map<String, Object> reading(long rideId, ReadingRequest request) {
        Ride ride = lock(rideId);
        if (ride.status() == RideStatus.ARCHIVED) {
            records.insertReading(rideId, request.loadValue(), request.speedValue(), false, true);
            records.insertEvent(rideId, "LATE_READING",
                    "归档后迟到运行数据：载荷=" + request.loadValue()
                            + "，速度=" + request.speedValue());
            Map<String, Object> body = rideView(ride);
            body.put("late", true);
            body.put("message", "设备已归档，迟到数据仅记录事件");
            return body;
        }
        if (ride.status() != RideStatus.OPEN) {
            throw ApiException.conflict("设备未开放，拒绝运行监测读数");
        }
        // 受检设备资格失效（证书过期/撤销/大修未确认）：载客读数只记录并告警，不恢复资格
        Eligibility eligibility = eligibility(ride);
        if (ride.inspectionRequired() && !eligibility.eligible()) {
            records.insertReading(rideId, request.loadValue(), request.speedValue(), false, false);
            records.insertEvent(rideId, "CERT_ALARM",
                    "年度检验资格失效，载客读数仅记录并告警：" + String.join("；", eligibility.reasons())
                            + "（载荷=" + request.loadValue() + "，速度=" + request.speedValue() + "）");
            Map<String, Object> body = rideView(rideId);
            body.put("certificateAlarm", true);
            body.put("alarmReasons", eligibility.reasons());
            body.put("message", "年度检验资格失效，读数仅记录并告警，不恢复开放资格");
            return body;
        }
        boolean exceeded = request.loadValue() > ride.maxLoad()
                || request.speedValue() > ride.maxSpeed();
        records.insertReading(rideId, request.loadValue(), request.speedValue(), exceeded, false);
        if (exceeded) {
            update(ride, RideStatus.SUSPENDED, ride.trialCount(), ride.reopenCount(),
                    ride.checkDate(), ride.reopenDate());
            records.insertEvent(rideId, "SLOW_STOP",
                    "载荷或速度超阈值触发缓停：载荷=" + request.loadValue()
                            + "/" + ride.maxLoad() + "，速度=" + request.speedValue()
                            + "/" + ride.maxSpeed());
        } else {
            records.insertEvent(rideId, "READING",
                    "运行读数合格：载荷=" + request.loadValue()
                            + "，速度=" + request.speedValue());
        }
        Map<String, Object> body = rideView(rideId);
        body.put("exceeded", exceeded);
        return body;
    }

    /** 查询设备当前状态。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getRide(long rideId) {
        return rideView(rides.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId)));
    }

    /** 启停链事件，可复算。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEvents(long rideId) {
        ensureExists(rideId);
        return records.listEvents(rideId);
    }

    /** 日检清单（含明细），可复算。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDailyChecks(long rideId) {
        ensureExists(rideId);
        List<Map<String, Object>> checks = records.listDailyChecks(rideId);
        for (Map<String, Object> check : checks) {
            long checkId = ((Number) check.get("id")).longValue();
            check.put("items", records.listDailyCheckItems(checkId));
        }
        return checks;
    }

    /** 试运行记录，可复算。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTrialRuns(long rideId) {
        ensureExists(rideId);
        return records.listTrialRuns(rideId);
    }

    /** 运行读数记录。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listReadings(long rideId) {
        ensureExists(rideId);
        return records.listReadings(rideId);
    }

    /**
     * 登记/换证年度检验证书：证书固定设备编号与检验合格时刻，
     * 默认截止为合格时刻加365×24小时；换证原子切换唯一当前指针，历史证书不可覆盖。
     */
    public ApiResult registerCertificate(long rideId, RegisterCertificateRequest request) {
        return idempotency.execute(request.requestId(), "CERT_REGISTER", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireVersion(ride, request.expectedVersion());
            requireInspected(ride);
            requireNotArchived(ride, "登记年度检验证书");
            Instant validUntil = request.validUntil() != null ? request.validUntil()
                    : request.passedAt().plus(DEFAULT_CERT_VALIDITY);
            if (!validUntil.isAfter(request.passedAt())) {
                throw ApiException.unprocessable("有效截止必须晚于检验合格时刻");
            }
            long certificateId = certificates.insertCertificate(rideId, request.requestId(),
                    request.passedAt(), validUntil);
            update(ride, ride.status(), ride.trialCount(), ride.reopenCount(),
                    ride.checkDate(), ride.reopenDate(), ride.overhaulVersion(), certificateId);
            records.insertEvent(rideId, "CERT_REGISTER",
                    "年度检验证书登记并切换为当前证书：证书ID=" + certificateId
                            + "，合格时刻=" + request.passedAt() + "，截止=" + validUntil);
            Map<String, Object> body = rideView(rideId);
            body.put("certificate", certificateView(
                    certificates.findCertificate(certificateId).orElseThrow(), true));
            return ok(body);
        });
    }

    /** 撤销证书：撤销当前证书后开放资格立即失效；重复撤销幂等成功。 */
    public ApiResult revokeCertificate(long rideId, long certificateId,
            RevokeCertificateRequest request) {
        return idempotency.execute(request.requestId(), "CERT_REVOKE", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireVersion(ride, request.expectedVersion());
            Certificate cert = certificates.findCertificate(certificateId)
                    .filter(c -> c.rideId() == rideId)
                    .orElseThrow(() -> ApiException.notFound("证书不存在：" + certificateId));
            if (cert.revoked()) {
                Map<String, Object> body = rideView(rideId);
                body.put("certificate", certificateView(cert,
                        Objects.equals(ride.currentCertificateId(), certificateId)));
                body.put("message", "证书已撤销，重复撤销幂等成功");
                return ok(body);
            }
            Instant now = Instant.now(clock);
            certificates.revokeCertificate(certificateId, request.reason(), now);
            Long newCurrent = Objects.equals(ride.currentCertificateId(), certificateId)
                    ? null : ride.currentCertificateId();
            update(ride, ride.status(), ride.trialCount(), ride.reopenCount(),
                    ride.checkDate(), ride.reopenDate(), ride.overhaulVersion(), newCurrent);
            records.insertEvent(rideId, "CERT_REVOKE",
                    "年度检验证书撤销：证书ID=" + certificateId
                            + (request.reason() == null ? "" : "，原因=" + request.reason()));
            Map<String, Object> body = rideView(rideId);
            body.put("certificate", certificateView(
                    certificates.findCertificate(certificateId).orElseThrow(), false));
            return ok(body);
        });
    }

    /** 登记大修：原子递增大修版本、暂停开放并使旧监督检验失效（旧年度证书可查但不足以复开）。 */
    public ApiResult registerOverhaul(long rideId, OverhaulRequest request) {
        return idempotency.execute(request.requestId(), "OVERHAUL", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireVersion(ride, request.expectedVersion());
            requireInspected(ride);
            requireNotArchived(ride, "登记大修");
            int newOverhaulVersion = ride.overhaulVersion() + 1;
            RideStatus newStatus = ride.status() == RideStatus.OPEN
                    ? RideStatus.SUSPENDED : ride.status();
            update(ride, newStatus, ride.trialCount(), ride.reopenCount(),
                    ride.checkDate(), ride.reopenDate(), newOverhaulVersion,
                    ride.currentCertificateId());
            records.insertEvent(rideId, "OVERHAUL",
                    "登记大修：大修版本=" + newOverhaulVersion
                            + "，暂停开放，旧监督检验失效，须新监督检验确认后方可复开");
            Map<String, Object> body = rideView(rideId);
            body.put("overhaulVersion", newOverhaulVersion);
            return ok(body);
        });
    }

    /**
     * 大修后监督检验确认：绑定当前大修版本，两名不同检验人共同确认全清单合格。
     * 确认后只恢复开放资格，不自动开放，也不重置原当日复开次数。
     */
    public ApiResult confirmOverhaulInspection(long rideId, OverhaulInspectionRequest request) {
        return idempotency.execute(request.requestId(), "OVERHAUL_INSPECTION", rideId, request,
                () -> {
                    Ride ride = lock(rideId);
                    requireVersion(ride, request.expectedVersion());
                    requireInspected(ride);
                    requireNotArchived(ride, "监督检验确认");
                    if (ride.overhaulVersion() == 0) {
                        throw ApiException.unprocessable("设备未登记大修，拒绝监督检验确认");
                    }
                    if (request.inspectorOne().equals(request.inspectorTwo())) {
                        throw ApiException.unprocessable("两名检验人须为不同人员："
                                + request.inspectorOne());
                    }
                    boolean allPassed = request.items().stream().allMatch(CheckItem::passed);
                    certificates.insertOverhaulInspection(rideId, request.requestId(),
                            ride.overhaulVersion(), request.inspectorOne(),
                            request.inspectorTwo(), allPassed, request.items());
                    records.insertEvent(rideId, "OVERHAUL_INSPECTION",
                            allPassed ? "监督检验双人确认全清单合格，恢复开放资格（不自动开放，大修版本="
                                    + ride.overhaulVersion() + "）"
                                    : "监督检验存在不合格项，开放资格未恢复");
                    // 只恢复开放资格：不改变设备状态、不重置当日复开次数；成功变更版本加1
                    update(ride, ride.status(), ride.trialCount(), ride.reopenCount(),
                            ride.checkDate(), ride.reopenDate(), ride.overhaulVersion(),
                            ride.currentCertificateId());
                    Map<String, Object> body = rideView(rideId);
                    body.put("allPassed", allPassed);
                    body.put("overhaulVersion", ride.overhaulVersion());
                    return ok(body);
                });
    }

    /** 证书查询（含历史与撤销信息），current 标记唯一当前指针。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCertificates(long rideId) {
        Ride ride = rides.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId));
        List<Map<String, Object>> list = certificates.listCertificates(rideId);
        for (Map<String, Object> cert : list) {
            long certId = ((Number) cert.get("id")).longValue();
            cert.put("current", ride.currentCertificateId() != null
                    && ride.currentCertificateId() == certId);
        }
        return list;
    }

    /** 大修后监督检验记录查询（含清单明细），可复算。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOverhaulInspections(long rideId) {
        ensureExists(rideId);
        return certificates.listOverhaulInspections(rideId);
    }

    /** 开放资格证据查询：受检标志、当前证书、大修版本与监督检验确认状态及失效原因。 */
    @Transactional(readOnly = true)
    public Map<String, Object> eligibilityEvidence(long rideId) {
        Ride ride = rides.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId));
        Eligibility result = eligibility(ride);
        Certificate current = ride.currentCertificateId() == null ? null
                : certificates.findCertificate(ride.currentCertificateId()).orElse(null);
        Map<String, Object> body = rideView(ride);
        body.put("inspectionRequired", ride.inspectionRequired());
        body.put("eligible", result.eligible());
        body.put("reasons", result.reasons());
        body.put("now", Instant.now(clock).toString());
        body.put("overhaulVersion", ride.overhaulVersion());
        body.put("overhaulInspectionConfirmed", ride.overhaulVersion() > 0
                && certificates.existsConfirmedInspection(rideId, ride.overhaulVersion()));
        body.put("currentCertificate", current == null ? null : certificateView(current, true));
        return body;
    }

    private void ensureExists(long rideId) {
        if (rides.findById(rideId).isEmpty()) {
            throw ApiException.notFound("设备不存在：" + rideId);
        }
    }

    private Ride lock(long rideId) {
        return rides.findByIdForUpdate(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId));
    }

    private void requireStatus(Ride ride, String message, RideStatus... allowed) {
        if (Arrays.stream(allowed).noneMatch(s -> s == ride.status())) {
            throw ApiException.conflict(message + "（当前状态：" + ride.status() + "）");
        }
    }

    private void update(Ride ride, RideStatus status, int trialCount, int reopenCount,
            LocalDate checkDate, LocalDate reopenDate) {
        update(ride, status, trialCount, reopenCount, checkDate, reopenDate,
                ride.overhaulVersion(), ride.currentCertificateId());
    }

    private void update(Ride ride, RideStatus status, int trialCount, int reopenCount,
            LocalDate checkDate, LocalDate reopenDate, int overhaulVersion,
            Long currentCertificateId) {
        if (!rides.updateWithVersion(ride, status, trialCount, reopenCount,
                checkDate, reopenDate, overhaulVersion, currentCertificateId)) {
            throw ApiException.conflict("设备状态并发变更，请重试");
        }
    }

    private boolean isSafetyDevice(String name) {
        return Arrays.asList(Ride.SAFETY_DEVICES).contains(name);
    }

    /** 开放资格判定结果：eligible 为 false 时 reasons 给出全部失效原因。 */
    private record Eligibility(boolean eligible, List<String> reasons) {
    }

    /**
     * 受检设备开放资格：须当前有效年度证书（UTC下now<截止有效、恰截止失效），
     * 且大修后须当前大修版本的监督检验已确认；未受检设备不参与门禁。
     */
    private Eligibility eligibility(Ride ride) {
        List<String> reasons = new ArrayList<>();
        if (!ride.inspectionRequired()) {
            return new Eligibility(true, reasons);
        }
        Instant now = Instant.now(clock);
        Certificate current = ride.currentCertificateId() == null ? null
                : certificates.findCertificate(ride.currentCertificateId()).orElse(null);
        if (current == null) {
            reasons.add("无当前年度检验证书");
        } else if (current.revoked()) {
            reasons.add("当前年度检验证书已撤销");
        } else if (!now.isBefore(current.validUntil())) {
            reasons.add("年度检验证书已过期或恰截止失效（截止=" + current.validUntil() + "）");
        }
        if (ride.overhaulVersion() > 0
                && !certificates.existsConfirmedInspection(ride.id(), ride.overhaulVersion())) {
            reasons.add("大修后监督检验未确认（当前大修版本=" + ride.overhaulVersion() + "）");
        }
        return new Eligibility(reasons.isEmpty(), reasons);
    }

    /** 受检设备开放/复开门禁：资格不满足按业务拒绝422。 */
    private void requireEligibility(Ride ride, String action) {
        Eligibility result = eligibility(ride);
        if (!result.eligible()) {
            throw ApiException.unprocessable("受检设备" + action + "被拒绝："
                    + String.join("；", result.reasons()));
        }
    }

    /** 版本校验：expectedVersion 须等于当前版本，不一致按冲突409。 */
    private void requireVersion(Ride ride, long expectedVersion) {
        if (ride.version() != expectedVersion) {
            throw ApiException.conflict("expectedVersion与当前版本不一致：期望="
                    + expectedVersion + "，当前=" + ride.version());
        }
    }

    /** 受检标志校验：未启用受检标志的旧设备拒绝年度检验相关操作，按业务拒绝422。 */
    private void requireInspected(Ride ride) {
        if (!ride.inspectionRequired()) {
            throw ApiException.unprocessable("设备未启用受检标志，拒绝年度检验相关操作");
        }
    }

    /** 归档终态校验：归档设备拒绝年度检验相关变更，按业务拒绝422。 */
    private void requireNotArchived(Ride ride, String action) {
        if (ride.status() == RideStatus.ARCHIVED) {
            throw ApiException.unprocessable("设备已归档，拒绝" + action);
        }
    }

    private Map<String, Object> certificateView(Certificate cert, boolean current) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("certificateId", cert.id());
        view.put("rideId", cert.rideId());
        view.put("passedAt", cert.passedAt().toString());
        view.put("validUntil", cert.validUntil().toString());
        view.put("revoked", cert.revoked());
        view.put("revokeReason", cert.revokeReason());
        view.put("revokedAt", cert.revokedAt() == null ? null : cert.revokedAt().toString());
        view.put("current", current);
        return view;
    }

    private Map<String, Object> rideView(long rideId) {
        return rideView(rides.findById(rideId).orElseThrow());
    }

    private Map<String, Object> rideView(Ride ride) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rideId", ride.id());
        view.put("name", ride.name());
        view.put("status", ride.status().name());
        view.put("maxLoad", ride.maxLoad());
        view.put("maxSpeed", ride.maxSpeed());
        view.put("trialCount", ride.trialCount());
        view.put("reopenCount", ride.reopenCount());
        view.put("checkDate", ride.checkDate() == null ? null : ride.checkDate().toString());
        view.put("inspectionRequired", ride.inspectionRequired());
        view.put("overhaulVersion", ride.overhaulVersion());
        view.put("currentCertificateId", ride.currentCertificateId());
        view.put("version", ride.version());
        return view;
    }

    private ApiResult ok(Map<String, Object> body) {
        try {
            return new ApiResult(200, objectMapper.writeValueAsString(body));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
