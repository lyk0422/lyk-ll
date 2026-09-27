package com.example.starter.ride;

import java.time.Clock;
import java.time.LocalDate;
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
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
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

    private final RideRepository rides;
    private final RideRecordRepository records;
    private final Idempotency.Executor idempotency;
    private final InspectionGate inspectionGate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RideService(RideRepository rides, RideRecordRepository records,
            Idempotency.Executor idempotency, InspectionGate inspectionGate,
            ObjectMapper objectMapper, Clock clock) {
        this.rides = rides;
        this.records = records;
        this.idempotency = idempotency;
        this.inspectionGate = inspectionGate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 创建设备（合成数据），初始状态为待日检；inspectionRequired 缺省 false。 */
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

    /** 开放：须当日日检合格且试运行恰3次合格；受检设备另须年度检验与大修监督检验门禁通过。 */
    public ApiResult open(long rideId, OpenRequest request) {
        return idempotency.execute(request.requestId(), "OPEN", rideId, request, () -> {
            Ride ride = lock(rideId);
            if (ride.checkDate() == null || !ride.checkDate().equals(LocalDate.now(clock))) {
                throw ApiException.conflict("未日检设备无法开放");
            }
            requireStatus(ride, "当前状态不允许开放，须试运行恰3次合格",
                    RideStatus.READY);
            inspectionGate.requireOpenEligibility(ride);
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

    /** 复开：同日复开最多2次，恰2次合法；受检设备另须年度检验与大修监督检验门禁通过。 */
    public ApiResult reopen(long rideId, ReopenRequest request) {
        return idempotency.execute(request.requestId(), "REOPEN", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireStatus(ride, "仅复检合格设备可复开", RideStatus.RECHECKED);
            inspectionGate.requireOpenEligibility(ride);
            LocalDate today = LocalDate.now(clock);
            int used = today.equals(ride.reopenDate()) ? ride.reopenCount() : 0;
            if (used >= Ride.MAX_DAILY_REOPEN) {
                throw ApiException.unprocessable(
                        "同日复开已达上限" + Ride.MAX_DAILY_REOPEN + "次，当日禁开");
            }
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
        if (ride.inspectionRequired() && !inspectionGate.certificateValid(ride)) {
            // 证书过期/撤销设备：载客读数只记录并告警，不以收到正常读数恢复资格
            records.insertReading(rideId, request.loadValue(), request.speedValue(),
                    exceeded, false);
            records.insertEvent(rideId, "CERT_INVALID_ALARM",
                    "年度检验证书失效，载客读数仅记录并告警：载荷=" + request.loadValue()
                            + "，速度=" + request.speedValue());
            Map<String, Object> body = rideView(rideId);
            body.put("exceeded", exceeded);
            body.put("alarm", true);
            body.put("certificateValid", false);
            body.put("message", "年度检验证书失效，读数仅记录并告警，不恢复开放资格");
            return body;
        }
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
