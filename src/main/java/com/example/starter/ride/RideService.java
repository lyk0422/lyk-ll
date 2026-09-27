package com.example.starter.ride;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.ArchiveRequest;
import com.example.starter.ride.dto.RideDtos.CheckItem;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.IssueCertificateRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.OverhaulRequest;
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.SupervisionConfirmRequest;
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

    private final RideRepository rides;
    private final RideRecordRepository records;
    private final CertificateRepository certificates;
    private final SupervisionRepository supervisions;
    private final Idempotency.Executor idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RideService(RideRepository rides, RideRecordRepository records,
            CertificateRepository certificates, SupervisionRepository supervisions,
            Idempotency.Executor idempotency, ObjectMapper objectMapper, Clock clock) {
        this.rides = rides;
        this.records = records;
        this.certificates = certificates;
        this.supervisions = supervisions;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 创建设备（合成数据），初始状态为待日检；inspectionRequired 缺省 false，旧设备行为不变。 */
    @Transactional
    public Map<String, Object> createRide(CreateRideRequest request) {
        boolean inspectionRequired = Boolean.TRUE.equals(request.inspectionRequired());
        Ride ride = rides.insert(request.name(), request.maxLoad(), request.maxSpeed(),
                inspectionRequired);
        records.insertEvent(ride.id(), "CREATE", "设备登记：" + ride.name()
                + (inspectionRequired ? "（受检设备）" : ""));
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

    /** 开放：须当日日检合格且试运行恰3次合格；未日检设备无法开放；并发开放唯一成功；
     * 受检设备还须持当前有效年度证书且完成当前大修版本监督检验。 */
    public ApiResult open(long rideId, OpenRequest request) {
        return idempotency.execute(request.requestId(), "OPEN", rideId, request, () -> {
            Ride ride = lock(rideId);
            if (ride.checkDate() == null || !ride.checkDate().equals(LocalDate.now(clock))) {
                throw ApiException.conflict("未日检设备无法开放");
            }
            requireStatus(ride, "当前状态不允许开放，须试运行恰3次合格",
                    RideStatus.READY);
            requireInspectionEligibility(ride);
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

    /** 复开：同日复开最多2次，恰2次合法，超出当日禁开；受检设备还须满足年度证书与监督检验门禁。 */
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
            requireInspectionEligibility(ride);
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
        boolean exceeded = request.loadValue() > ride.maxLoad()
                || request.speedValue() > ride.maxSpeed();
        records.insertReading(rideId, request.loadValue(), request.speedValue(), exceeded, false);
        // 受检设备证书失效（过期/恰截止/撤销/缺失）：载客读数只记录并告警，不以正常读数恢复资格
        boolean certificateInvalid = ride.inspectionRequired() && !certificateValidNow(rideId);
        if (certificateInvalid) {
            records.insertEvent(rideId, "CERT_EXPIRED_ALARM",
                    "年度证书已失效，载客读数仅记录并告警：载荷=" + request.loadValue()
                            + "，速度=" + request.speedValue());
        }
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
        if (certificateInvalid) {
            body.put("alarm", true);
            body.put("certificateValid", false);
        }
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
     * 签发/换证：固定设备编号、检验合格时刻与有效截止（默认合格时刻+365×24小时，恰截止失效）；
     * 换证原子切换唯一当前指针，历史证书不可覆盖。
     */
    public ApiResult issueCertificate(long rideId, IssueCertificateRequest request) {
        return idempotency.execute(request.requestId(), "CERT_ISSUE", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireInspectionRequired(ride);
            Instant passedAt = request.passedAt() != null ? request.passedAt()
                    : Instant.now(clock);
            Instant validUntil = request.validUntil() != null ? request.validUntil()
                    : passedAt.plus(Ride.CERT_DEFAULT_VALIDITY);
            if (!validUntil.isAfter(passedAt)) {
                throw ApiException.unprocessable("有效截止必须晚于检验合格时刻");
            }
            long certId = certificates.insert(rideId, request.requestId(), passedAt, validUntil);
            certificates.switchCurrent(rideId, certId);
            records.insertEvent(rideId, "CERT_ISSUE",
                    "签发年度证书#" + certId + "，检验合格时刻=" + passedAt
                            + "，有效截止=" + validUntil);
            return ok(certificateView(certificates.findById(certId).orElseThrow()));
        });
    }

    /** 撤销当前证书：携带 expectedVersion 乐观锁，成功版本加1；撤销后设备无当前证书。 */
    public ApiResult revokeCertificate(long rideId, RevokeCertificateRequest request) {
        return idempotency.execute(request.requestId(), "CERT_REVOKE", rideId, request, () -> {
            lock(rideId);
            Certificate current = certificates.findCurrent(rideId)
                    .orElseThrow(() -> ApiException.notFound(
                            "设备当前无年度证书可撤销：" + rideId));
            if (current.version() != request.expectedVersion()) {
                throw ApiException.conflict("证书版本冲突：expectedVersion="
                        + request.expectedVersion() + "，当前版本=" + current.version());
            }
            if (!certificates.revoke(current.id(), request.expectedVersion(),
                    request.reason(), Instant.now(clock))) {
                throw ApiException.conflict("证书并发变更，请重试");
            }
            certificates.clearCurrent(rideId);
            records.insertEvent(rideId, "CERT_REVOKE", "撤销年度证书#" + current.id()
                    + (request.reason() == null ? "" : "，原因：" + request.reason()));
            return ok(certificateView(certificates.findById(current.id()).orElseThrow()));
        });
    }

    /**
     * 登记大修：原子递增大修版本（检验周期版本）、开放中设备暂停开放、旧监督检验随之失效；
     * 旧年度证书可查但不足以复开，须重新完成当前版本监督检验。
     */
    public ApiResult registerOverhaul(long rideId, OverhaulRequest request) {
        return idempotency.execute(request.requestId(), "OVERHAUL", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireInspectionRequired(ride);
            if (ride.status() == RideStatus.ARCHIVED) {
                throw ApiException.unprocessable("设备已归档，拒绝登记大修");
            }
            long newOverhaulVersion = ride.overhaulVersion() + 1;
            RideStatus newStatus = ride.status() == RideStatus.OPEN
                    ? RideStatus.SUSPENDED : ride.status();
            if (!rides.registerOverhaul(ride, newStatus, newOverhaulVersion)) {
                throw ApiException.conflict("设备状态并发变更，请重试");
            }
            supervisions.insertOverhaul(rideId, request.requestId(), newOverhaulVersion);
            records.insertEvent(rideId, "OVERHAUL",
                    "登记大修，检验周期版本升至" + newOverhaulVersion + "，旧监督检验失效"
                            + (newStatus != ride.status() ? "，开放中设备暂停开放" : ""));
            Map<String, Object> body = rideView(rideId);
            body.put("overhaulVersion", newOverhaulVersion);
            return ok(body);
        });
    }

    /**
     * 监督检验确认：须绑定当前大修版本，两名不同检验人共同确认全清单合格；
     * 确认后只恢复开放资格，不自动开放，也不重置原当日复开次数。
     */
    public ApiResult confirmSupervision(long rideId, SupervisionConfirmRequest request) {
        return idempotency.execute(request.requestId(), "SUPERVISION", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireInspectionRequired(ride);
            if (request.overhaulVersion() != ride.overhaulVersion()) {
                throw ApiException.unprocessable("监督检验须绑定当前大修版本：请求="
                        + request.overhaulVersion() + "，当前=" + ride.overhaulVersion());
            }
            if (request.inspectorOne().equals(request.inspectorTwo())) {
                throw ApiException.unprocessable("两名检验人必须不同，不得同一人确认");
            }
            if (!request.items().stream().allMatch(CheckItem::passed)) {
                throw ApiException.unprocessable("监督检验须全清单合格方可确认");
            }
            long inspectionId = supervisions.insertInspection(rideId, request.requestId(),
                    request.overhaulVersion(), request.inspectorOne(), request.inspectorTwo(),
                    request.items());
            records.insertEvent(rideId, "SUPERVISION",
                    "监督检验确认：绑定大修版本" + request.overhaulVersion() + "，检验人="
                            + request.inspectorOne() + "、" + request.inspectorTwo()
                            + "，全清单合格，恢复开放资格（不自动开放）");
            Map<String, Object> body = rideView(rideId);
            body.put("inspectionId", inspectionId);
            body.put("allPassed", true);
            return ok(body);
        });
    }

    /** 设备全部年度证书（含历史与已撤销），历史证书不可覆盖、可复算。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCertificates(long rideId) {
        ensureExists(rideId);
        return certificates.listByRide(rideId).stream().map(this::certificateView).toList();
    }

    /** 当前年度证书；无当前证书返回404。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getCurrentCertificate(long rideId) {
        ensureExists(rideId);
        return certificateView(certificates.findCurrent(rideId)
                .orElseThrow(() -> ApiException.notFound("设备当前无年度证书：" + rideId)));
    }

    /** 撤销证据：设备全部已撤销证书。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRevocations(long rideId) {
        ensureExists(rideId);
        return certificates.listByRide(rideId).stream()
                .filter(Certificate::revoked)
                .map(this::certificateView)
                .toList();
    }

    /** 大修登记记录，可复算。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOverhauls(long rideId) {
        ensureExists(rideId);
        return supervisions.listOverhauls(rideId);
    }

    /** 监督检验记录（含清单明细），可复算。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSupervisions(long rideId) {
        ensureExists(rideId);
        return supervisions.listInspections(rideId);
    }

    /** 开放资格证据：证书有效性、监督检验状态与判定原因，可复算。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getEligibility(long rideId) {
        Ride ride = rides.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId));
        Map<String, Object> view = new LinkedHashMap<>();
        Instant now = Instant.now(clock);
        view.put("rideId", ride.id());
        view.put("status", ride.status().name());
        view.put("inspectionRequired", ride.inspectionRequired());
        view.put("overhaulVersion", ride.overhaulVersion());
        view.put("now", now.toString());
        List<String> reasons = new ArrayList<>();
        boolean eligible = true;
        Certificate current = null;
        boolean supervisionRequired = false;
        boolean supervisionConfirmed = false;
        Boolean certificateValid = null;
        if (ride.inspectionRequired()) {
            current = certificates.findCurrent(rideId).orElse(null);
            certificateValid = current != null && current.validAt(now);
            if (!certificateValid) {
                reasons.add(current == null ? "无当前年度证书" : "年度证书已失效（恰截止失效）");
            }
            supervisionRequired = ride.overhaulVersion() > 0;
            supervisionConfirmed = supervisionRequired
                    && supervisions.existsConfirmed(rideId, ride.overhaulVersion());
            if (supervisionRequired && !supervisionConfirmed) {
                reasons.add("大修后未完成当前大修版本监督检验");
            }
            eligible = certificateValid && (!supervisionRequired || supervisionConfirmed);
        }
        view.put("certificateValid", certificateValid);
        view.put("supervisionRequired", supervisionRequired);
        view.put("supervisionConfirmed", supervisionConfirmed);
        view.put("currentCertificate", current == null ? null : certificateView(current));
        view.put("openEligible", eligible);
        view.put("reasons", reasons);
        return view;
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
        if (!rides.updateWithVersion(ride, status, trialCount, reopenCount,
                checkDate, reopenDate)) {
            throw ApiException.conflict("设备状态并发变更，请重试");
        }
    }

    private boolean isSafetyDevice(String name) {
        return Arrays.asList(Ride.SAFETY_DEVICES).contains(name);
    }

    /** 受检设备开放/复开门禁：当前有效年度证书 + 当前大修版本监督检验，缺一不可。 */
    private void requireInspectionEligibility(Ride ride) {
        if (!ride.inspectionRequired()) {
            return;
        }
        Certificate current = certificates.findCurrent(ride.id())
                .orElseThrow(() -> ApiException.unprocessable(
                        "受检设备无当前年度证书，禁止开放或复开"));
        if (!current.validAt(Instant.now(clock))) {
            throw ApiException.unprocessable("年度证书已失效（恰截止失效），禁止开放或复开");
        }
        if (ride.overhaulVersion() > 0
                && !supervisions.existsConfirmed(ride.id(), ride.overhaulVersion())) {
            throw ApiException.unprocessable("大修后未完成当前大修版本监督检验，禁止开放或复开");
        }
    }

    private void requireInspectionRequired(Ride ride) {
        if (!ride.inspectionRequired()) {
            throw ApiException.unprocessable("设备未启用受检标志，不适用年度检验流程：" + ride.id());
        }
    }

    /** 当前时刻是否存在有效年度证书：无当前证书或已过期（恰截止失效）均为无效。 */
    private boolean certificateValidNow(long rideId) {
        Instant now = Instant.now(clock);
        return certificates.findCurrent(rideId).map(cert -> cert.validAt(now)).orElse(false);
    }

    private Map<String, Object> certificateView(Certificate cert) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("certificateId", cert.id());
        view.put("rideId", cert.rideId());
        view.put("passedAt", cert.passedAt().toString());
        view.put("validUntil", cert.validUntil().toString());
        view.put("revoked", cert.revoked());
        view.put("revokedAt", cert.revokedAt() == null ? null : cert.revokedAt().toString());
        view.put("revokeReason", cert.revokeReason());
        view.put("version", cert.version());
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
