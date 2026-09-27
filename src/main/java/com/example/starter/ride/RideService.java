package com.example.starter.ride;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.ride.AnnualCertificate.CertStatus;
import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.ArchiveRequest;
import com.example.starter.ride.dto.RideDtos.CheckItem;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.IssueCertificateRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.RegisterOverhaulRequest;
import com.example.starter.ride.dto.RideDtos.RenewCertificateRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.SupervisoryInspectionRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 游乐设施日检、载客联锁与年度检验/大修门禁核心服务（合成规则，不用于生产）。
 * 状态链：日检→试运行(恰3次)→开放→运行监测→闭园归档；
 * 故障停运/缓停→复检合格→复开(同日恰2次合法)；
 * 受检设备：开放/复开额外须持当前有效年度证书；
 * 登记大修→大修版本原子+1、暂停开放、旧监督检验失效→监督检验(绑定当前版本、两名不同检验人)
 * →只恢复复开资格，不自动开放，不重置当日复开次数。
 * 所有状态流转在事务内持行锁串行执行，保证同一设备同时刻只有一条状态链。
 */
@Service
public class RideService {

    private final RideRepository rides;
    private final RideRecordRepository records;
    private final CertificateRepository certificates;
    private final SupervisoryInspectionRepository inspections;
    private final Idempotency.Executor idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RideService(RideRepository rides, RideRecordRepository records,
            CertificateRepository certificates,
            SupervisoryInspectionRepository inspections,
            Idempotency.Executor idempotency, ObjectMapper objectMapper, Clock clock) {
        this.rides = rides;
        this.records = records;
        this.certificates = certificates;
        this.inspections = inspections;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建设备（合成数据），初始状态为待日检。
     * 未显式启用受检标志的旧设备行为不变；检验周期版本缺省为1。
     */
    @Transactional
    public Map<String, Object> createRide(CreateRideRequest request) {
        boolean inspectionRequired = Boolean.TRUE.equals(request.inspectionRequired());
        int cycleVersion = request.cycleVersion() == null
                ? Ride.INITIAL_CYCLE_VERSION : request.cycleVersion();
        Ride ride = rides.insert(request.name(), request.maxLoad(), request.maxSpeed(),
                inspectionRequired, cycleVersion);
        records.insertEvent(ride.id(), "CREATE",
                "设备登记：" + ride.name() + (inspectionRequired ? "（受检设备）" : ""));
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

    /**
     * 开放：须当日日检合格、试运行恰3次合格，且受检设备须持当前有效年度证书；
     * 未日检设备无法开放；大修暂停期间禁止开放；并发开放唯一成功。
     */
    public ApiResult open(long rideId, OpenRequest request) {
        return idempotency.execute(request.requestId(), "OPEN", rideId, request, () -> {
            Ride ride = lock(rideId);
            if (ride.checkDate() == null || !ride.checkDate().equals(LocalDate.now(clock))) {
                throw ApiException.conflict("未日检设备无法开放");
            }
            requireStatus(ride, "当前状态不允许开放，须试运行恰3次合格",
                    RideStatus.READY);
            requireValidCertificate(ride);
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

    /**
     * 复开：同日复开最多2次，恰2次合法，超出当日禁开；
     * 受检设备复开还须持当前有效年度证书（旧证书或大修后未换证均不足）。
     */
    public ApiResult reopen(long rideId, ReopenRequest request) {
        return idempotency.execute(request.requestId(), "REOPEN", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireStatus(ride, "仅复检或大修监督检验合格设备可复开", RideStatus.RECHECKED);
            LocalDate today = LocalDate.now(clock);
            int used = today.equals(ride.reopenDate()) ? ride.reopenCount() : 0;
            if (used >= Ride.MAX_DAILY_REOPEN) {
                throw ApiException.unprocessable(
                        "同日复开已达上限" + Ride.MAX_DAILY_REOPEN + "次，当日禁开");
            }
            requireValidCertificate(ride);
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
     * 首次发放年度证书（合成规则）：固定设备编号，检验合格时刻与有效截止精确记录；
     * 默认截止为合格时刻加365×24小时；UTC 下 now&lt;截止有效，恰截止失效。
     */
    public ApiResult issueCertificate(long rideId, IssueCertificateRequest request) {
        return idempotency.execute(request.requestId(), "ANNUAL_CERT_ISSUE", rideId,
                request, () -> {
            Instant expiresAt = resolveExpiresAt(request.issuedAt(), request.expiresAt());
            Ride ride = lock(rideId);
            requireVersion(ride, request.expectedVersion());
            if (ride.status() == RideStatus.ARCHIVED) {
                throw ApiException.conflict("设备已归档，拒绝发放年度证书");
            }
            if (ride.currentCertificateId() != null) {
                throw ApiException.unprocessable(
                        "设备已存在当前年度证书，换证请使用换证接口");
            }
            String certificateNo = nextCertificateNo(ride);
            AnnualCertificate certificate = certificates.insert(rideId, certificateNo,
                    ride.cycleVersion(), ride.overhaulVersion(),
                    request.issuedAt(), expiresAt, request.requestId());
            if (!rides.updateWithVersion(ride, ride.status(), ride.trialCount(),
                    ride.reopenCount(), ride.checkDate(), ride.reopenDate(),
                    ride.overhaulVersion(), certificate.id())) {
                throw ApiException.conflict("设备状态并发变更，发证失败");
            }
            records.insertEvent(rideId, "ANNUAL_CERT_ISSUED",
                    "首次发放年度证书：" + certificateNo + "，截止 " + expiresAt);
            return ok(certificateView(rideId, certificate, validNow(certificate)));
        });
    }

    /**
     * 换证：原子切换唯一当前指针，旧证书置为 SUPERSEDED，历史证书不可覆盖。
     */
    public ApiResult renewCertificate(long rideId, RenewCertificateRequest request) {
        return idempotency.execute(request.requestId(), "CERT_RENEW", rideId, request, () -> {
            Instant expiresAt = resolveExpiresAt(request.issuedAt(), request.expiresAt());
            Ride ride = lock(rideId);
            requireVersion(ride, request.expectedVersion());
            if (ride.currentCertificateId() == null) {
                throw ApiException.unprocessable("设备无当前年度证书，不能换证，请走首次发证");
            }
            AnnualCertificate old = certificates.findById(ride.currentCertificateId())
                    .orElseThrow(() -> ApiException.notFound(
                            "当前年度证书不存在：" + ride.currentCertificateId()));
            if (old.status() != CertStatus.ACTIVE) {
                throw ApiException.unprocessable("当前指针证书非ACTIVE，不能换证：" + old.status());
            }
            String certificateNo = nextCertificateNo(ride);
            AnnualCertificate certificate = certificates.insert(rideId, certificateNo,
                    ride.cycleVersion(), ride.overhaulVersion(),
                    request.issuedAt(), expiresAt, request.requestId());
            // 同一事务、同一行锁：旧证置历史 + 当前指针原子切换
            int superseded = certificates.markSuperseded(old.id());
            if (superseded != 1) {
                throw ApiException.conflict("旧年度证书并发变更，换证失败");
            }
            if (!rides.updateWithVersion(ride, ride.status(), ride.trialCount(),
                    ride.reopenCount(), ride.checkDate(), ride.reopenDate(),
                    ride.overhaulVersion(), certificate.id())) {
                throw ApiException.conflict("设备状态并发变更，换证失败");
            }
            records.insertEvent(rideId, "CERT_RENEWED",
                    "换证：" + old.certificateNo() + " → " + certificateNo + "，截止 " + expiresAt);
            return ok(certificateView(rideId, certificate, validNow(certificate)));
        });
    }

    /**
     * 撤销年度证书：撤销当前证书并原子清空当前指针；开放资格随即失效。
     */
    public ApiResult revokeCertificate(long rideId, RevokeCertificateRequest request) {
        return idempotency.execute(request.requestId(), "CERT_REVOKE", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireVersion(ride, request.expectedVersion());
            if (ride.currentCertificateId() == null) {
                throw ApiException.unprocessable("设备无当前年度证书，不能撤销");
            }
            AnnualCertificate current = certificates.findById(ride.currentCertificateId())
                    .orElseThrow(() -> ApiException.notFound(
                            "当前年度证书不存在：" + ride.currentCertificateId()));
            int revoked = certificates.markRevoked(current.id(), request.revokedAt());
            if (revoked != 1) {
                throw ApiException.conflict("年度证书并发变更，撤销失败");
            }
            if (!rides.updateWithVersion(ride, ride.status(), ride.trialCount(),
                    ride.reopenCount(), ride.checkDate(), ride.reopenDate(),
                    ride.overhaulVersion(), null)) {
                throw ApiException.conflict("设备状态并发变更，撤销失败");
            }
            records.insertEvent(rideId, "CERT_REVOKED",
                    "年度证书已撤销：" + current.certificateNo());
            return ok(certificateView(rideId,
                    certificates.findById(current.id()).orElseThrow(), false));
        });
    }

    /**
     * 登记大修：原子递增设备大修版本、暂停开放（进入 OVERHAUL），
     * 旧监督检验因版本不再匹配而失效；旧年度证书仍可查但不足以复开（按本门禁规则）。
     */
    public ApiResult registerOverhaul(long rideId, RegisterOverhaulRequest request) {
        return idempotency.execute(request.requestId(), "OVERHAUL", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireVersion(ride, request.expectedVersion());
            if (ride.status() == RideStatus.ARCHIVED) {
                throw ApiException.conflict("设备已归档，拒绝登记大修");
            }
            if (ride.status() == RideStatus.OVERHAUL) {
                throw ApiException.unprocessable("设备已在大修中，不能重复登记大修");
            }
            int newOverhaulVersion = ride.overhaulVersion() + 1;
            if (!rides.updateWithVersion(ride, RideStatus.OVERHAUL, ride.trialCount(),
                    ride.reopenCount(), ride.checkDate(), ride.reopenDate(),
                    newOverhaulVersion, ride.currentCertificateId())) {
                throw ApiException.conflict("设备状态并发变更，大修登记失败");
            }
            records.insertEvent(rideId, "OVERHAUL_REGISTERED",
                    "登记大修，大修版本升至" + newOverhaulVersion + "，暂停开放，旧监督检验失效");
            return ok(rideView(rideId));
        });
    }

    /**
     * 大修后监督检验：须绑定当前大修版本；两名不同检验人共同确认全清单合格后，
     * 仅恢复复开资格（进入 RECHECKED），不自动开放，也不重置当日复开次数。
     */
    public ApiResult supervisoryInspection(long rideId, SupervisoryInspectionRequest request) {
        return idempotency.execute(request.requestId(), "SUPERVISORY_INSPECTION", rideId,
                request, () -> {
            if (request.inspectorA().equals(request.inspectorB())) {
                throw ApiException.badRequest("两名检验人不得相同，须共同确认");
            }
            Ride ride = lock(rideId);
            requireVersion(ride, request.expectedVersion());
            requireStatus(ride, "仅大修中设备可进行大修后监督检验", RideStatus.OVERHAUL);
            boolean allPassed = request.items().stream().allMatch(CheckItem::passed);
            inspections.insert(rideId, request.requestId(), ride.overhaulVersion(),
                    request.inspectorA(), request.inspectorB(), allPassed, request.items());
            if (!allPassed) {
                List<String> failed = request.items().stream()
                        .filter(item -> !item.passed())
                        .map(CheckItem::itemName)
                        .toList();
                records.insertEvent(rideId, "SUPERVISORY_INSPECTION",
                        "监督检验不合格（大修版本" + ride.overhaulVersion() + "）："
                                + String.join("、", failed));
                Map<String, Object> body = rideView(rideId);
                body.put("allPassed", false);
                body.put("overhaulVersion", ride.overhaulVersion());
                return ok(body);
            }
            update(ride, RideStatus.RECHECKED, ride.trialCount(), ride.reopenCount(),
                    ride.checkDate(), ride.reopenDate());
            records.insertEvent(rideId, "SUPERVISORY_INSPECTION",
                    "监督检验合格（大修版本" + ride.overhaulVersion() + "，检验人 "
                            + request.inspectorA() + "、" + request.inspectorB()
                            + "），仅恢复复开资格，不自动开放");
            Map<String, Object> body = rideView(rideId);
            body.put("allPassed", true);
            body.put("overhaulVersion", ride.overhaulVersion());
            return ok(body);
        });
    }

    /**
     * 运行监测读数：载荷或速度超阈值触发缓停，恰等阈值合格；
     * 归档后迟到数据只记迟到事件，不改变状态；
     * 受检设备若年度证书已失效（过期/撤销/大修后资格不足），读数只记录并告警，
     * 不以收到正常读数恢复资格。
     */
    @Transactional
    public Map<String, Object> reading(long rideId, ReadingRequest request) {
        Ride ride = lock(rideId);
        if (ride.status() == RideStatus.ARCHIVED) {
            records.insertReading(rideId, request.loadValue(), request.speedValue(), false, true, false);
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
        CertificateCheck cert = evaluateCertificate(ride);
        boolean warning = ride.inspectionRequired() && !cert.valid();
        records.insertReading(rideId, request.loadValue(), request.speedValue(),
                exceeded, false, warning);
        if (warning) {
            // 证书失效：正常或异常读数都只记录并告警，不改变资格、不触发缓停状态流转
            records.insertEvent(rideId, "CERT_WARNING",
                    "年度证书已失效（" + cert.reason() + "），载客读数仅记录告警：载荷="
                            + request.loadValue() + "，速度=" + request.speedValue());
            Map<String, Object> body = rideView(ride);
            body.put("exceeded", exceeded);
            body.put("certificateValid", false);
            body.put("warning", true);
            body.put("message", "年度证书已失效，读数仅记录并告警，不恢复开放资格");
            return body;
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
        Map<String, Object> body = rideView(ride);
        body.put("exceeded", exceeded);
        return body;
    }

    /** 查询设备当前状态。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getRide(long rideId) {
        return rideView(rides.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId)));
    }

    /** 开放资格证据：汇总原日检/试运行/复开规则与当前年度证书、大修监督检验判定。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getEligibility(long rideId) {
        Ride ride = rides.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId));
        LocalDate today = LocalDate.now(clock);
        boolean dailyCheckToday = ride.checkDate() != null && ride.checkDate().equals(today);
        boolean trialReady = ride.trialCount() >= Ride.REQUIRED_TRIAL_COUNT;
        CertificateCheck cert = evaluateCertificate(ride);
        boolean supervisoryReady = !ride.inspectionRequired()
                || ride.overhaulVersion() == 0
                || hasValidSupervisoryInspection(ride);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rideId", ride.id());
        view.put("status", ride.status().name());
        view.put("inspectionRequired", ride.inspectionRequired());
        view.put("version", ride.version());
        Map<String, Object> gates = new LinkedHashMap<>();
        gates.put("dailyCheckToday", dailyCheckToday);
        gates.put("checkDate", ride.checkDate() == null ? null : ride.checkDate().toString());
        gates.put("trialRunReady", trialReady);
        gates.put("trialCount", ride.trialCount());
        gates.put("statusAllowsOpen", ride.status() == RideStatus.READY
                || ride.status() == RideStatus.RECHECKED);
        gates.put("annualCertificate", cert.toMap());
        gates.put("overhaulVersion", ride.overhaulVersion());
        gates.put("supervisoryInspectionReady", supervisoryReady);
        gates.put("dailyReopenUsed", today.equals(ride.reopenDate()) ? ride.reopenCount() : 0);
        gates.put("dailyReopenRemaining",
                Math.max(0, Ride.MAX_DAILY_REOPEN
                        - (today.equals(ride.reopenDate()) ? ride.reopenCount() : 0)));
        view.put("gates", gates);
        view.put("eligibleToOpen", dailyCheckToday && trialReady
                && ride.status() == RideStatus.READY
                && (!ride.inspectionRequired() || cert.valid()));
        int usedToday = today.equals(ride.reopenDate()) ? ride.reopenCount() : 0;
        boolean reopenEligible = ride.status() == RideStatus.RECHECKED
                && usedToday < Ride.MAX_DAILY_REOPEN;
        view.put("eligibleToReopen", reopenEligible
                && (!ride.inspectionRequired() || cert.valid()));
        return view;
    }

    /** 证书证据：列出设备全部年度证书（含历史/撤销）及当前指针与即时有效性。 */
    @Transactional(readOnly = true)
    public Map<String, Object> listCertificates(long rideId) {
        Ride ride = rides.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId));
        Instant now = Instant.now(clock);
        List<Map<String, Object>> list = certificates.listByRide(rideId).stream()
                .map(cert -> certificateView(rideId, cert, cert.validAt(now)))
                .toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rideId", rideId);
        view.put("currentCertificateId", ride.currentCertificateId());
        view.put("certificates", list);
        return view;
    }

    /** 监督检验证据：列出全部监督检验记录（含明细），并标注是否匹配当前大修版本。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSupervisoryInspections(long rideId) {
        ensureExists(rideId);
        Ride ride = rides.findById(rideId).orElseThrow();
        List<Map<String, Object>> rows = inspections.listByRide(rideId);
        for (Map<String, Object> row : rows) {
            long inspectionId = ((Number) row.get("id")).longValue();
            int boundVersion = ((Number) row.get("overhaul_version")).intValue();
            row.put("items", inspections.listItems(inspectionId));
            row.put("current", boundVersion == ride.overhaulVersion());
        }
        return rows;
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

    /** 乐观锁版本校验：版本从1开始，expectedVersion 与当前不一致返回 409。 */
    private void requireVersion(Ride ride, Long expectedVersion) {
        if (expectedVersion == null) {
            throw ApiException.badRequest("expectedVersion不能为空");
        }
        if (expectedVersion != ride.version()) {
            throw ApiException.conflict("设备版本不匹配：expected=" + expectedVersion
                    + "，actual=" + ride.version());
        }
    }

    private void update(Ride ride, RideStatus status, int trialCount, int reopenCount,
            LocalDate checkDate, LocalDate reopenDate) {
        if (!rides.updateWithVersion(ride, status, trialCount, reopenCount,
                checkDate, reopenDate)) {
            throw ApiException.conflict("设备状态并发变更，请重试");
        }
    }

    /** 受检设备开放/复开门禁：必须存在当前指针且其证书在 UTC now 下有效。 */
    private void requireValidCertificate(Ride ride) {
        if (!ride.inspectionRequired()) {
            return;
        }
        CertificateCheck check = evaluateCertificate(ride);
        if (!check.valid()) {
            throw ApiException.unprocessable(
                    "受检设备开放门禁未通过：年度证书" + check.reason());
        }
    }

    private CertificateCheck evaluateCertificate(Ride ride) {
        Instant now = Instant.now(clock);
        if (!ride.inspectionRequired()) {
            return CertificateCheck.notRequired(now);
        }
        if (ride.currentCertificateId() == null) {
            return CertificateCheck.invalid(now, "缺失（未发证或已撤销/换证切换中）");
        }
        AnnualCertificate cert = certificates.findById(ride.currentCertificateId())
                .orElse(null);
        if (cert == null) {
            return CertificateCheck.invalid(now, "缺失（当前指针证书不存在）");
        }
        if (cert.status() != CertStatus.ACTIVE) {
            return CertificateCheck.invalid(now, "状态为" + cert.status(), cert);
        }
        if (cert.overhaulVersion() != ride.overhaulVersion()) {
            return CertificateCheck.invalid(now,
                    "快照大修版本" + cert.overhaulVersion() + "落后于当前大修版本"
                            + ride.overhaulVersion() + "，大修后旧证不足以开放/复开，须换证",
                    cert);
        }
        if (!now.isBefore(cert.expiresAt())) {
            return CertificateCheck.invalid(now,
                    "已过有效截止 " + cert.expiresAt() + "（恰截止即失效）", cert);
        }
        return CertificateCheck.valid(now, cert);
    }

    /** 最近一次监督检验是否绑定当前大修版本、全清单合格。 */
    private boolean hasValidSupervisoryInspection(Ride ride) {
        List<Map<String, Object>> rows = inspections.listByRide(ride.id());
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = rows.get(i);
            int boundVersion = ((Number) row.get("overhaul_version")).intValue();
            if (boundVersion != ride.overhaulVersion()) {
                return false;
            }
            return Boolean.TRUE.equals(row.get("all_passed"));
        }
        return false;
    }

    /** 截止时刻：缺省为合格时刻加365×24小时；显式给出时必须与默认值精确相等，否则400。 */
    private Instant resolveExpiresAt(Instant issuedAt, Instant requested) {
        if (issuedAt == null) {
            throw ApiException.badRequest("检验合格时刻issuedAt不能为空");
        }
        Instant defaultExpiry = issuedAt.plusSeconds(Ride.CERT_VALIDITY_HOURS * 3600L);
        if (requested == null) {
            return defaultExpiry;
        }
        if (!requested.equals(defaultExpiry)) {
            throw ApiException.badRequest("有效截止必须为合格时刻加365×24小时："
                    + defaultExpiry + "，收到 " + requested);
        }
        return requested;
    }

    private String nextCertificateNo(Ride ride) {
        long seq = certificates.listByRide(ride.id()).size() + 1L;
        return "AC-" + ride.id() + "-" + seq;
    }

    private boolean isSafetyDevice(String name) {
        return Arrays.asList(Ride.SAFETY_DEVICES).contains(name);
    }

    private boolean validNow(AnnualCertificate certificate) {
        return certificate.validAt(Instant.now(clock));
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
        view.put("cycleVersion", ride.cycleVersion());
        view.put("overhaulVersion", ride.overhaulVersion());
        view.put("currentCertificateId", ride.currentCertificateId());
        view.put("version", ride.version());
        return view;
    }

    private Map<String, Object> certificateView(long rideId, AnnualCertificate certificate,
            boolean valid) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rideId", rideId);
        view.put("certificateId", certificate.id());
        view.put("certificateNo", certificate.certificateNo());
        view.put("cycleVersion", certificate.cycleVersion());
        view.put("overhaulVersion", certificate.overhaulVersion());
        view.put("issuedAt", certificate.issuedAt().toString());
        view.put("expiresAt", certificate.expiresAt().toString());
        view.put("status", certificate.status().name());
        view.put("revokedAt", certificate.revokedAt() == null
                ? null : certificate.revokedAt().toString());
        view.put("validNow", valid);
        return view;
    }

    private ApiResult ok(Map<String, Object> body) {
        try {
            return new ApiResult(200, objectMapper.writeValueAsString(body));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /** 年度证书门禁判定结果。 */
    private record CertificateCheck(boolean valid, boolean required, Instant now,
            String reason, AnnualCertificate certificate) {

        static CertificateCheck notRequired(Instant now) {
            return new CertificateCheck(true, false, now, "非受检设备不校验年度证书", null);
        }

        static CertificateCheck valid(Instant now, AnnualCertificate certificate) {
            return new CertificateCheck(true, true, now, "当前有效", certificate);
        }

        static CertificateCheck invalid(Instant now, String reason) {
            return new CertificateCheck(false, true, now, reason, null);
        }

        static CertificateCheck invalid(Instant now, String reason, AnnualCertificate certificate) {
            return new CertificateCheck(false, true, now, reason, certificate);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("required", required);
            map.put("valid", valid);
            map.put("evaluatedAt", now.toString());
            map.put("reason", reason);
            if (certificate != null) {
                map.put("certificateId", certificate.id());
                map.put("certificateNo", certificate.certificateNo());
                map.put("status", certificate.status().name());
                map.put("issuedAt", certificate.issuedAt().toString());
                map.put("expiresAt", certificate.expiresAt().toString());
            }
            return map;
        }
    }
}
