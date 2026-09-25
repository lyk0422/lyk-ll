package com.example.starter.ride.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.ride.ApiResult;
import com.example.starter.ride.BusinessClock;
import com.example.starter.ride.BusinessRuleException;
import com.example.starter.ride.EventType;
import com.example.starter.ride.RequestType;
import com.example.starter.ride.RideStatus;
import com.example.starter.ride.SafetyDeviceType;
import com.example.starter.ride.domain.Ride;
import com.example.starter.ride.dto.ArchiveRequest;
import com.example.starter.ride.dto.DailyCheckRequest;
import com.example.starter.ride.dto.OpenRequest;
import com.example.starter.ride.dto.ReadingRequest;
import com.example.starter.ride.dto.RecheckRequest;
import com.example.starter.ride.dto.SafetyFaultRequest;
import com.example.starter.ride.dto.TrialRunRequest;
import com.example.starter.ride.repo.ChecklistRepository;
import com.example.starter.ride.repo.DailyCheckRepository;
import com.example.starter.ride.repo.IdempotencyRepository;
import com.example.starter.ride.repo.IdempotencyRepository.StoredRequest;
import com.example.starter.ride.repo.OpenEventRepository;
import com.example.starter.ride.repo.ReadingRepository;
import com.example.starter.ride.repo.RideRepository;
import com.example.starter.ride.repo.StatusEventRepository;
import com.example.starter.ride.repo.TrialRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 游乐设施日检与载客联锁业务服务。
 *
 * <p>每个写接口以 requestId 幂等：指纹覆盖 requestId 与全部业务字段；同键同参返回首次
 * 结果（含首次业务失败），同键异参返回 409 且不改变状态。同一设备的写操作通过行级锁
 * 串行化，开放唯一约束保证并发开放仅一条成功。</p>
 */
@Service
public class RideOperationService {

    /** 当日合法空载试运行次数（恰 3 次）。 */
    public static final int REQUIRED_TRIAL_RUNS = 3;
    /** 当日合法复开次数上限（恰 2 次合法，第 3 次禁开）。 */
    public static final int MAX_REOPENS_PER_DAY = 2;

    private final RideRepository rideRepository;
    private final ChecklistRepository checklistRepository;
    private final DailyCheckRepository dailyCheckRepository;
    private final TrialRunRepository trialRunRepository;
    private final OpenEventRepository openEventRepository;
    private final ReadingRepository readingRepository;
    private final StatusEventRepository statusEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final BusinessClock clock;
    private final ObjectMapper objectMapper;

    public RideOperationService(RideRepository rideRepository,
                                ChecklistRepository checklistRepository,
                                DailyCheckRepository dailyCheckRepository,
                                TrialRunRepository trialRunRepository,
                                OpenEventRepository openEventRepository,
                                ReadingRepository readingRepository,
                                StatusEventRepository statusEventRepository,
                                IdempotencyRepository idempotencyRepository,
                                BusinessClock clock,
                                ObjectMapper objectMapper) {
        this.rideRepository = rideRepository;
        this.checklistRepository = checklistRepository;
        this.dailyCheckRepository = dailyCheckRepository;
        this.trialRunRepository = trialRunRepository;
        this.openEventRepository = openEventRepository;
        this.readingRepository = readingRepository;
        this.statusEventRepository = statusEventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ============================ 日检 ============================

    @Transactional
    public ApiResult dailyCheck(DailyCheckRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.rideId(), "rideId");
        Map<String, Object> payload = new TreeMap<>();
        payload.put("items", request.items() == null ? List.of() : request.items());
        return withIdempotency(RequestType.DAILY_CHECK, request.requestId(), request.rideId(), payload,
                ride -> doDailyCheck(ride, request));
    }

    private ApiResult doDailyCheck(Ride ride, DailyCheckRequest request) {
        if (RideStatus.ARCHIVED.name().equals(ride.getStatus())) {
            throw new BusinessRuleException(400, "设备已闭园归档，终态不可再日检");
        }
        LocalDate today = clock.today();
        prepareForBizDate(ride, today);

        Map<String, String> checklist = checklistRepository.findItemsByRide(ride.getId());
        Map<String, Boolean> reported = new LinkedHashMap<>();
        if (request.items() != null) {
            for (DailyCheckRequest.ItemResult item : request.items()) {
                if (item == null || isBlank(item.itemCode())) {
                    throw new BusinessRuleException(400, "日检清单项编码不能为空");
                }
                reported.put(item.itemCode(), Boolean.TRUE.equals(item.qualified()));
            }
        }
        List<String> failedItems = new ArrayList<>();
        for (String code : checklist.keySet()) {
            Boolean ok = reported.get(code);
            if (ok == null) {
                failedItems.add(code + ":MISSING");
            } else if (!ok) {
                failedItems.add(code);
            }
        }
        boolean qualified = failedItems.isEmpty() && !checklist.isEmpty();
        if (!qualified && checklist.isEmpty()) {
            throw new BusinessRuleException(400, "设备合成日检清单缺失，无法完成日检");
        }
        Instant now = clock.now();
        dailyCheckRepository.insert(request.requestId(), ride.getId(), today, qualified,
                failedItems, request.items() == null ? List.of() : request.items(), now);

        if (qualified) {
            ride.setDailyCheckPassed(true);
            if (RideStatus.PENDING_DAILY_CHECK.name().equals(ride.getStatus())) {
                ride.setStatus(RideStatus.TRIAL.name());
            }
            statusEventRepository.append(ride.getId(), today, EventType.DAILY_CHECK_PASSED.name(),
                    request.requestId(), "清单逐项合格:" + String.join(",", checklist.keySet()), now);
        } else {
            ride.setDailyCheckPassed(false);
            ride.setStatus(RideStatus.PENDING_DAILY_CHECK.name());
            statusEventRepository.append(ride.getId(), today, EventType.DAILY_CHECK_FAILED.name(),
                    request.requestId(), "不合格项:" + String.join(",", failedItems), now);
        }
        ride.setUpdatedAt(now);
        rideRepository.updateState(ride);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rideId", ride.getId());
        data.put("bizDate", today.toString());
        data.put("qualified", qualified);
        data.put("failedItems", failedItems);
        data.put("status", ride.getStatus());
        String message = qualified ? "日检逐项合格，可进入空载试运行" : "日检存在不合格项，禁止进入试运行";
        return ApiResult.ok(message, data);
    }

    // ============================ 空载试运行 ============================

    @Transactional
    public ApiResult trialRun(TrialRunRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.rideId(), "rideId");
        requireBoolean(request.pressureBarOk(), "pressureBarOk");
        requireBoolean(request.seatBeltOk(), "seatBeltOk");
        requireBoolean(request.interlockOk(), "interlockOk");
        requireBoolean(request.noAnomaly(), "noAnomaly");
        Map<String, Object> payload = new TreeMap<>();
        payload.put("pressureBarOk", request.pressureBarOk());
        payload.put("seatBeltOk", request.seatBeltOk());
        payload.put("interlockOk", request.interlockOk());
        payload.put("noAnomaly", request.noAnomaly());
        return withIdempotency(RequestType.TRIAL_RUN, request.requestId(), request.rideId(), payload,
                ride -> doTrialRun(ride, request));
    }

    private ApiResult doTrialRun(Ride ride, TrialRunRequest request) {
        LocalDate today = clock.today();
        ensureSameBizDate(ride, today);
        if (RideStatus.ARCHIVED.name().equals(ride.getStatus())) {
            throw new BusinessRuleException(400, "设备已闭园归档，终态不可再试运行");
        }
        if (!ride.isDailyCheckPassed()) {
            throw new BusinessRuleException(400, "当日日检未逐项合格，禁止试运行");
        }
        long attempts = trialRunRepository.countTotalAttempts(ride.getId(), today);
        if (ride.getTrialPassedCount() >= REQUIRED_TRIAL_RUNS) {
            throw new BusinessRuleException(400,
                    "合格空载试运行恰需 " + REQUIRED_TRIAL_RUNS + " 次，已完成，禁止再发起试运行");
        }
        boolean devicesOk = Boolean.TRUE.equals(request.pressureBarOk())
                && Boolean.TRUE.equals(request.seatBeltOk())
                && Boolean.TRUE.equals(request.interlockOk());
        boolean qualified = devicesOk && Boolean.TRUE.equals(request.noAnomaly());
        int attemptSeq = (int) attempts + 1;
        Instant now = clock.now();

        List<String> failedDevices = new ArrayList<>();
        if (!Boolean.TRUE.equals(request.pressureBarOk())) {
            failedDevices.add(SafetyDeviceType.PRESSURE_BAR.name());
        }
        if (!Boolean.TRUE.equals(request.seatBeltOk())) {
            failedDevices.add(SafetyDeviceType.SEAT_BELT.name());
        }
        if (!Boolean.TRUE.equals(request.interlockOk())) {
            failedDevices.add(SafetyDeviceType.INTERLOCK.name());
        }

        boolean stopped = false;
        if (!devicesOk) {
            // 压杠、安全带与联锁装置任一失效：整机立即停运。该故障上报不占用试运行名额，
            // 停运复检合格后仍可继续完成恰 3 次空载试运行。
            ride.setStatus(RideStatus.FAULT_STOPPED.name());
            stopped = true;
            ride.setUpdatedAt(now);
            rideRepository.updateState(ride);
            statusEventRepository.append(ride.getId(), today, EventType.FAULT_STOP.name(),
                    request.requestId(), "试运行中安全装置失效:" + String.join(",", failedDevices), now);
        } else {
            trialRunRepository.insert(request.requestId(), ride.getId(), today, attemptSeq,
                    request.pressureBarOk(), request.seatBeltOk(), request.interlockOk(),
                    request.noAnomaly(), qualified, now);
            statusEventRepository.append(ride.getId(), today, EventType.TRIAL_COMPLETED.name(),
                    request.requestId(),
                    "第" + attemptSeq + "次空载试运行:" + (qualified ? "合格无异常" : "存在异常"), now);
            if (qualified) {
                ride.setTrialPassedCount(ride.getTrialPassedCount() + 1);
                if (ride.getTrialPassedCount() == REQUIRED_TRIAL_RUNS
                        && RideStatus.TRIAL.name().equals(ride.getStatus())) {
                    ride.setStatus(RideStatus.READY.name());
                }
            }
            ride.setUpdatedAt(now);
            rideRepository.updateState(ride);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rideId", ride.getId());
        data.put("bizDate", today.toString());
        data.put("attemptSeq", attemptSeq);
        data.put("qualified", qualified);
        data.put("qualifiedCount", ride.getTrialPassedCount());
        data.put("requiredCount", REQUIRED_TRIAL_RUNS);
        data.put("stopped", stopped);
        data.put("failedDevices", failedDevices);
        data.put("status", ride.getStatus());
        String message = stopped ? "安全装置失效，整机立即停运"
                : qualified && ride.getTrialPassedCount() == REQUIRED_TRIAL_RUNS
                        ? "已完成恰 3 次合格空载试运行，具备首次开放条件"
                        : qualified ? "本次空载试运行合格" : "本次空载试运行存在异常，不计入合格次数";
        return ApiResult.ok(message, data);
    }

    // ============================ 开放/复开 ============================

    @Transactional
    public ApiResult open(OpenRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.rideId(), "rideId");
        return withIdempotency(RequestType.OPEN, request.requestId(), request.rideId(),
                new TreeMap<>(), ride -> doOpen(ride, request));
    }

    private ApiResult doOpen(Ride ride, OpenRequest request) {
        LocalDate today = clock.today();
        ensureSameBizDate(ride, today);
        if (RideStatus.ARCHIVED.name().equals(ride.getStatus())) {
            throw new BusinessRuleException(400, "设备已闭园归档，终态不可开放");
        }
        String status = ride.getStatus();
        boolean firstOpen = RideStatus.READY.name().equals(status);
        boolean reopen = RideStatus.RECHECK_PASSED.name().equals(status);
        if (!firstOpen && !reopen) {
            if (RideStatus.OPEN.name().equals(status)) {
                throw new BusinessRuleException(409, "设备已处于开放载客状态，禁止重复开放");
            }
            if (!ride.isDailyCheckPassed()) {
                throw new BusinessRuleException(400, "当日未通过日检，设备禁止开放");
            }
            if (ride.getTrialPassedCount() < REQUIRED_TRIAL_RUNS) {
                throw new BusinessRuleException(400,
                        "合格空载试运行未满 " + REQUIRED_TRIAL_RUNS + " 次，禁止载客开放");
            }
            if (RideStatus.FAULT_STOPPED.name().equals(status)
                    || RideStatus.SLOW_STOPPED.name().equals(status)) {
                throw new BusinessRuleException(400, "设备处于停运状态，须复检合格后方可复开");
            }
            throw new BusinessRuleException(400, "当前状态 " + status + " 不允许开放");
        }
        if (firstOpen && (ride.getTrialPassedCount() != REQUIRED_TRIAL_RUNS || !ride.isDailyCheckPassed())) {
            throw new BusinessRuleException(400, "日检或试运行条件不满足，禁止开放");
        }
        if (reopen && ride.getReopenCount() >= MAX_REOPENS_PER_DAY) {
            throw new BusinessRuleException(400,
                    "当日复开最多 " + MAX_REOPENS_PER_DAY + " 次，已达上限，当日禁开");
        }
        if (reopen && ride.getTrialPassedCount() < REQUIRED_TRIAL_RUNS) {
            throw new BusinessRuleException(400,
                    "合格空载试运行未满 " + REQUIRED_TRIAL_RUNS + " 次，禁止载客开放");
        }

        int openSeq = (int) rideRepository.countOpenEvents(ride.getId(), today) + 1;
        Instant now = clock.now();
        // (设备, 营业日, 开放序号) 唯一约束：并发开放只有一条成功。
        boolean inserted = openEventRepository.insertIfAbsent(request.requestId(),
                ride.getId(), today, openSeq, now);
        if (!inserted) {
            throw new BusinessRuleException(409, "并发开放冲突，同一设备同一时刻仅允许一条开放成功");
        }
        ride.setOpenSeq(openSeq);
        ride.setStatus(RideStatus.OPEN.name());
        if (openSeq > 1) {
            ride.setReopenCount(ride.getReopenCount() + 1);
            statusEventRepository.append(ride.getId(), today, EventType.REOPEN.name(),
                    request.requestId(), "第" + (openSeq - 1) + "次复开", now);
        } else {
            statusEventRepository.append(ride.getId(), today, EventType.OPEN.name(),
                    request.requestId(), "首次开放载客", now);
        }
        ride.setUpdatedAt(now);
        rideRepository.updateState(ride);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rideId", ride.getId());
        data.put("bizDate", today.toString());
        data.put("openSeq", openSeq);
        data.put("firstOpen", openSeq == 1);
        data.put("reopenCount", ride.getReopenCount());
        data.put("status", ride.getStatus());
        return ApiResult.ok(openSeq == 1 ? "首次开放成功" : "复开成功", data);
    }

    // ============================ 安全装置失效停运 ============================

    @Transactional
    public ApiResult safetyFault(SafetyFaultRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.rideId(), "rideId");
        requireText(request.deviceType(), "deviceType");
        SafetyDeviceType device;
        try {
            device = SafetyDeviceType.valueOf(request.deviceType());
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException(400, "未知安全装置类型: " + request.deviceType());
        }
        Map<String, Object> payload = new TreeMap<>();
        payload.put("deviceType", device.name());
        return withIdempotency(RequestType.SAFETY_FAULT, request.requestId(), request.rideId(),
                payload, ride -> doSafetyFault(ride, request, device));
    }

    private ApiResult doSafetyFault(Ride ride, SafetyFaultRequest request, SafetyDeviceType device) {
        LocalDate today = clock.today();
        ensureSameBizDate(ride, today);
        if (RideStatus.ARCHIVED.name().equals(ride.getStatus())) {
            throw new BusinessRuleException(400, "设备已闭园归档，终态不再受理停运上报");
        }
        if (!RideStatus.OPEN.name().equals(ride.getStatus())) {
            throw new BusinessRuleException(400,
                    "设备当前状态 " + ride.getStatus() + " 非开放载客，安全装置失效上报仅在运行中受理");
        }
        Instant now = clock.now();
        ride.setStatus(RideStatus.FAULT_STOPPED.name());
        ride.setUpdatedAt(now);
        rideRepository.updateState(ride);
        statusEventRepository.append(ride.getId(), today, EventType.FAULT_STOP.name(),
                request.requestId(), device.getDisplayName() + "失效，整机立即停运", now);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rideId", ride.getId());
        data.put("bizDate", today.toString());
        data.put("deviceType", device.name());
        data.put("status", ride.getStatus());
        data.put("reopenRequired", true);
        data.put("reopensUsed", ride.getReopenCount());
        data.put("reopensRemaining", MAX_REOPENS_PER_DAY - ride.getReopenCount());
        return ApiResult.ok(device.getDisplayName() + "失效，整机已故障停运，须复检合格方可复开", data);
    }

    // ============================ 运行监测读数 ============================

    @Transactional
    public ApiResult reading(ReadingRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.rideId(), "rideId");
        if (request.loadKg() == null || request.speedMps() == null) {
            throw new BusinessRuleException(400, "载荷与速度读数不能为空");
        }
        if (request.loadKg() < 0 || request.speedMps() < 0) {
            throw new BusinessRuleException(400, "载荷与速度读数不能为负");
        }
        Map<String, Object> payload = new TreeMap<>();
        payload.put("loadKg", request.loadKg());
        payload.put("speedMps", request.speedMps());
        return withIdempotency(RequestType.READING, request.requestId(), request.rideId(),
                payload, ride -> doReading(ride, request));
    }

    private ApiResult doReading(Ride ride, ReadingRequest request) {
        LocalDate today = clock.today();
        ensureSameBizDate(ride, today);
        Instant now = clock.now();
        if (RideStatus.ARCHIVED.name().equals(ride.getStatus())) {
            // 归档后迟到运行数据：只记迟到事件，不改变终态。
            readingRepository.insert(ride.getId(), today, request.loadKg(), request.speedMps(),
                    false, true, now);
            statusEventRepository.append(ride.getId(), today, EventType.LATE_READING.name(),
                    request.requestId(),
                    "归档后迟到读数:载荷=" + request.loadKg() + "kg,速度=" + request.speedMps() + "m/s", now);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("rideId", ride.getId());
            data.put("bizDate", today.toString());
            data.put("lateAfterArchive", true);
            data.put("triggeredSlowStop", false);
            data.put("status", ride.getStatus());
            return ApiResult.ok("设备已归档，迟到运行读数仅登记，不改变终态", data);
        }
        if (!RideStatus.OPEN.name().equals(ride.getStatus())) {
            throw new BusinessRuleException(400,
                    "设备当前状态 " + ride.getStatus() + " 非开放运行，不受理运行读数");
        }
        // 恰等阈值合格：严格大于才触发缓停。
        boolean loadOver = request.loadKg() > ride.getLoadThreshold();
        boolean speedOver = request.speedMps() > ride.getSpeedThreshold();
        boolean triggered = loadOver || speedOver;
        readingRepository.insert(ride.getId(), today, request.loadKg(), request.speedMps(),
                triggered, false, now);
        if (triggered) {
            ride.setStatus(RideStatus.SLOW_STOPPED.name());
            ride.setUpdatedAt(now);
            rideRepository.updateState(ride);
            statusEventRepository.append(ride.getId(), today, EventType.SLOW_STOP.name(),
                    request.requestId(),
                    "超阈值缓停:载荷=" + request.loadKg() + "/" + ride.getLoadThreshold()
                            + "kg,速度=" + request.speedMps() + "/" + ride.getSpeedThreshold() + "m/s"
                            + "(载荷超限=" + loadOver + ",速度超限=" + speedOver + ")",
                    now);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rideId", ride.getId());
        data.put("bizDate", today.toString());
        data.put("loadKg", request.loadKg());
        data.put("speedMps", request.speedMps());
        data.put("loadThresholdKg", ride.getLoadThreshold());
        data.put("speedThresholdMps", ride.getSpeedThreshold());
        data.put("triggeredSlowStop", triggered);
        data.put("lateAfterArchive", false);
        data.put("status", ride.getStatus());
        return ApiResult.ok(triggered ? "读数超阈值，已触发缓停，须复检合格方可复开" : "读数在阈值内，运行正常", data);
    }

    // ============================ 停运后复检 ============================

    @Transactional
    public ApiResult recheck(RecheckRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.rideId(), "rideId");
        requireBoolean(request.pressureBarOk(), "pressureBarOk");
        requireBoolean(request.seatBeltOk(), "seatBeltOk");
        requireBoolean(request.interlockOk(), "interlockOk");
        Map<String, Object> payload = new TreeMap<>();
        payload.put("pressureBarOk", request.pressureBarOk());
        payload.put("seatBeltOk", request.seatBeltOk());
        payload.put("interlockOk", request.interlockOk());
        return withIdempotency(RequestType.RECHECK, request.requestId(), request.rideId(),
                payload, ride -> doRecheck(ride, request));
    }

    private ApiResult doRecheck(Ride ride, RecheckRequest request) {
        LocalDate today = clock.today();
        ensureSameBizDate(ride, today);
        if (RideStatus.ARCHIVED.name().equals(ride.getStatus())) {
            throw new BusinessRuleException(400, "设备已闭园归档，终态不可复检");
        }
        if (!RideStatus.FAULT_STOPPED.name().equals(ride.getStatus())
                && !RideStatus.SLOW_STOPPED.name().equals(ride.getStatus())) {
            throw new BusinessRuleException(400, "设备当前状态 " + ride.getStatus() + " 非停运状态，无需复检");
        }
        boolean qualified = Boolean.TRUE.equals(request.pressureBarOk())
                && Boolean.TRUE.equals(request.seatBeltOk())
                && Boolean.TRUE.equals(request.interlockOk());
        Instant now = clock.now();
        if (!qualified) {
            List<String> failed = new ArrayList<>();
            if (!Boolean.TRUE.equals(request.pressureBarOk())) {
                failed.add(SafetyDeviceType.PRESSURE_BAR.name());
            }
            if (!Boolean.TRUE.equals(request.seatBeltOk())) {
                failed.add(SafetyDeviceType.SEAT_BELT.name());
            }
            if (!Boolean.TRUE.equals(request.interlockOk())) {
                failed.add(SafetyDeviceType.INTERLOCK.name());
            }
            Map<String, Object> failData = new LinkedHashMap<>();
            failData.put("rideId", ride.getId());
            failData.put("bizDate", today.toString());
            failData.put("qualified", false);
            failData.put("failedDevices", failed);
            failData.put("status", ride.getStatus());
            return ApiResult.ok("复检不合格，设备继续停运，不得复开", failData);
        }
        // 合格试运行已满 3 次→可复开；否则回到试运行补齐次数。
        String nextStatus = ride.getTrialPassedCount() >= REQUIRED_TRIAL_RUNS
                ? RideStatus.RECHECK_PASSED.name() : RideStatus.TRIAL.name();
        ride.setStatus(nextStatus);
        ride.setUpdatedAt(now);
        rideRepository.updateState(ride);
        statusEventRepository.append(ride.getId(), today, EventType.RECHECK_PASSED.name(),
                request.requestId(), "停运后复检合格，进入" + nextStatus, now);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rideId", ride.getId());
        data.put("bizDate", today.toString());
        data.put("qualified", true);
        data.put("status", ride.getStatus());
        data.put("reopenAllowed", RideStatus.RECHECK_PASSED.name().equals(nextStatus));
        data.put("reopensUsed", ride.getReopenCount());
        data.put("reopensRemaining",
                Math.max(0, MAX_REOPENS_PER_DAY - ride.getReopenCount()));
        return ApiResult.ok(RideStatus.RECHECK_PASSED.name().equals(nextStatus)
                ? "复检合格，可申请复开" : "复检合格，仍需补足 3 次合格空载试运行", data);
    }

    // ============================ 闭园归档 ============================

    @Transactional
    public ApiResult archive(ArchiveRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.rideId(), "rideId");
        return withIdempotency(RequestType.ARCHIVE, request.requestId(), request.rideId(),
                new TreeMap<>(), ride -> doArchive(ride, request));
    }

    private ApiResult doArchive(Ride ride, ArchiveRequest request) {
        LocalDate today = clock.today();
        ensureSameBizDate(ride, today);
        Instant now = clock.now();
        boolean alreadyArchived = RideStatus.ARCHIVED.name().equals(ride.getStatus());
        if (!alreadyArchived) {
            ride.setStatus(RideStatus.ARCHIVED.name());
            ride.setArchivedAt(now);
            ride.setUpdatedAt(now);
            rideRepository.updateState(ride);
            statusEventRepository.append(ride.getId(), today, EventType.ARCHIVE.name(),
                    request.requestId(), "闭园归档，终态", now);
        }
        // 重复归档（含异键）返回幂等成功，不再产生状态变化。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rideId", ride.getId());
        data.put("bizDate", today.toString());
        data.put("archivedAt", ride.getArchivedAt() == null ? null : ride.getArchivedAt().toString());
        data.put("alreadyArchived", alreadyArchived);
        data.put("status", RideStatus.ARCHIVED.name());
        return ApiResult.ok(alreadyArchived ? "设备已归档，重复归档幂等成功" : "闭园归档成功，状态链终结", data);
    }

    // ============================ 状态查询 ============================

    /**
     * 查询设备当前状态（只读）。
     */
    @Transactional(readOnly = true)
    public ApiResult status(String rideId) {
        requireText(rideId, "rideId");
        Ride ride = rideRepository.findById(rideId);
        if (ride == null) {
            throw new BusinessRuleException(404, "设备不存在: " + rideId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rideId", ride.getId());
        data.put("name", ride.getName());
        data.put("bizDate", ride.getBizDate() == null ? null : ride.getBizDate().toString());
        data.put("status", ride.getStatus());
        data.put("dailyCheckPassed", ride.isDailyCheckPassed());
        data.put("trialPassedCount", ride.getTrialPassedCount());
        data.put("requiredTrialCount", REQUIRED_TRIAL_RUNS);
        data.put("reopenCount", ride.getReopenCount());
        data.put("maxReopensPerDay", MAX_REOPENS_PER_DAY);
        data.put("openSeq", ride.getOpenSeq());
        data.put("loadThresholdKg", ride.getLoadThreshold());
        data.put("speedThresholdMps", ride.getSpeedThreshold());
        data.put("archived", ride.getArchivedAt() != null);
        return ApiResult.ok("设备当前状态", data);
    }

    // ============================ 启停链复算 ============================

    /**
     * 依据日检记录、试运行记录、开放事件与启停链事件复算当日状态链。
     */
    @Transactional(readOnly = true)
    public ApiResult replayChain(String rideId) {
        requireText(rideId, "rideId");
        Ride ride = rideRepository.findById(rideId);
        if (ride == null) {
            throw new BusinessRuleException(404, "设备不存在: " + rideId);
        }
        LocalDate today = clock.today();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rideId", rideId);
        data.put("bizDate", today.toString());
        data.put("dailyCheckPassedRecords", dailyCheckRepository.countPassed(rideId, today));
        data.put("trialQualifiedCount", trialRunRepository.countQualified(rideId, today));
        data.put("trialTotalAttempts", trialRunRepository.countTotalAttempts(rideId, today));
        data.put("trialDetails", trialRunRepository.findDetails(rideId, today));
        data.put("openEventCount", rideRepository.countOpenEvents(rideId, today));
        data.put("faultStopCount", statusEventRepository.countByType(rideId, today,
                EventType.FAULT_STOP.name()));
        data.put("slowStopCount", statusEventRepository.countByType(rideId, today,
                EventType.SLOW_STOP.name()));
        data.put("recheckPassedCount", statusEventRepository.countByType(rideId, today,
                EventType.RECHECK_PASSED.name()));
        data.put("reopenCount", statusEventRepository.countByType(rideId, today,
                EventType.REOPEN.name()));
        data.put("lateReadingCount", statusEventRepository.countByType(rideId, today,
                EventType.LATE_READING.name()));
        List<Map<String, Object>> events = new ArrayList<>();
        statusEventRepository.findByRideAndDate(rideId, today).forEach(row -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("seq", row.id());
            event.put("eventType", row.eventType());
            event.put("requestId", row.requestId());
            event.put("detail", row.detail());
            event.put("createdAt", row.createdAt().toString());
            events.add(event);
        });
        data.put("events", events);
        data.put("currentStatus", ride.getStatus());
        return ApiResult.ok("启停链复算完成", data);
    }

    // ============================ 幂等与公共逻辑 ============================

    /**
     * 幂等模板：行锁内先查重放/冲突，再执行业务并固化首次结果。
     */
    private ApiResult withIdempotency(RequestType type, String requestId, String rideId,
                                      Map<String, Object> businessPayload,
                                      java.util.function.Function<Ride, ApiResult> action) {
        StoredRequest stored = idempotencyRepository.find(requestId);
        if (stored != null) {
            return replayOrConflict(stored, type, rideId, fingerprint(type, requestId, rideId, businessPayload));
        }
        Ride ride = rideRepository.findByIdForUpdate(rideId);
        if (ride == null) {
            throw new BusinessRuleException(404, "设备不存在: " + rideId);
        }
        // 获得行锁后二次查重，覆盖同键并发。
        stored = idempotencyRepository.find(requestId);
        if (stored != null) {
            return replayOrConflict(stored, type, rideId, fingerprint(type, requestId, rideId, businessPayload));
        }

        ApiResult result;
        try {
            result = action.apply(ride);
        } catch (BusinessRuleException ex) {
            // 业务规则失败也作为“首次结果”固化，同键重放原样返回；失败路径不产生状态写入。
            result = new ApiResult(ex.getHttpStatus(), ex.getMessage(), null);
        }
        String fingerprint = fingerprint(type, requestId, rideId, businessPayload);
        String body;
        try {
            body = objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
        boolean won = idempotencyRepository.insert(requestId, type.name(), rideId, fingerprint,
                result.httpStatus(), body, clock.now());
        if (!won) {
            StoredRequest concurrent = idempotencyRepository.find(requestId);
            if (concurrent != null) {
                return replayOrConflict(concurrent, type, rideId, fingerprint);
            }
        }
        return result;
    }

    private ApiResult replayOrConflict(StoredRequest stored, RequestType type, String rideId,
                                       String currentFingerprint) {
        if (!stored.fingerprint().equals(currentFingerprint)
                || !stored.requestType().equals(type.name())
                || !stored.rideId().equals(rideId)) {
            throw new BusinessRuleException(409,
                    "requestId 已用于不同业务参数（原类型 " + stored.requestType() + "），请求冲突且不改变设备状态");
        }
        try {
            return objectMapper.readValue(stored.responseBody(), ApiResult.class);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    private String fingerprint(RequestType type, String requestId, String rideId,
                               Map<String, Object> businessPayload) {
        String canonical;
        try {
            canonical = type.name() + "|" + requestId + "|" + rideId + "|"
                    + objectMapper.writeValueAsString(new TreeMap<>(businessPayload));
        } catch (Exception ex) {
            throw new IllegalStateException("指纹序列化失败", ex);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 营业日初始化：跨日（JVM 内长期运行）重置当日计数器与日检标记。
     */
    private void prepareForBizDate(Ride ride, LocalDate today) {
        if (ride.getBizDate() == null || !ride.getBizDate().equals(today)) {
            if (RideStatus.ARCHIVED.name().equals(ride.getStatus())) {
                throw new BusinessRuleException(400, "设备已归档为终态，不可开启新营业日流程");
            }
            ride.setBizDate(today);
            ride.setDailyCheckPassed(false);
            ride.setTrialPassedCount(0);
            ride.setReopenCount(0);
            ride.setOpenSeq(0);
            ride.setStatus(RideStatus.PENDING_DAILY_CHECK.name());
        }
    }

    /**
     * 除日检外的接口要求当日流程已由日检开启（biz_date 已落当日）。
     */
    private void ensureSameBizDate(Ride ride, LocalDate today) {
        if (!RideStatus.ARCHIVED.name().equals(ride.getStatus())
                && (ride.getBizDate() == null || !ride.getBizDate().equals(today))) {
            throw new BusinessRuleException(400, "设备当日尚未完成日检流程，禁止该操作");
        }
    }

    private static void requireText(String value, String field) {
        if (isBlank(value)) {
            throw new BusinessRuleException(400, field + " 不能为空");
        }
    }

    private static void requireBoolean(Boolean value, String field) {
        if (value == null) {
            throw new BusinessRuleException(400, field + " 不能为空");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
