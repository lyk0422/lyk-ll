package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.api.dto.CommandActionRequest;
import com.example.starter.api.dto.CommandResponse;
import com.example.starter.api.dto.DailySummaryResponse;
import com.example.starter.api.dto.ExecutePlanRequest;
import com.example.starter.api.dto.PlanResultResponse;
import com.example.starter.api.dto.PlanStatusResponse;
import com.example.starter.api.dto.PlanSubmitRequest;
import com.example.starter.api.dto.ReadingAckResponse;
import com.example.starter.api.dto.ReadingBatchRequest;
import com.example.starter.api.dto.ReadingItem;
import com.example.starter.api.dto.SealRequest;
import com.example.starter.api.dto.SwitchPlanRequest;
import com.example.starter.config.ApplicationClock;
import com.example.starter.config.CementProperties;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.CoFiringPlan;
import com.example.starter.domain.CommandStatus;
import com.example.starter.domain.EmissionReading;
import com.example.starter.domain.HazardousWasteLicense;
import com.example.starter.domain.HourlyAssessment;
import com.example.starter.domain.PlanFuel;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.RejectCategory;
import com.example.starter.domain.ReductionCommand;
import com.example.starter.repo.BanRepository;
import com.example.starter.repo.CommandRepository;
import com.example.starter.repo.DailySummaryRepository;
import com.example.starter.repo.FuelRepository;
import com.example.starter.repo.HourlyAssessmentRepository;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.KilnLockRepository;
import com.example.starter.repo.LicenseRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.ReadingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 水泥窑替代燃料掺烧与排放联动核心服务。
 *
 * <p>状态流转：申报→（同步合成审核）→审核通过/整单拒绝→执行→小时评估→日结封存。
 * 所有边界判定均为“恰等于合法/达标/触发”：替代率恰 40% 合法、均值恰限值达标、
 * 下发恰满 30 分钟升级、恰连续 3 次超标禁烧。</p>
 */
@Service
public class CementService {

    private static final int RATE_SCALE = 4;
    private static final int MASS_SCALE = 3;
    private static final int AVG_SCALE = 3;

    private final PlanRepository planRepository;
    private final FuelRepository fuelRepository;
    private final LicenseRepository licenseRepository;
    private final KilnLockRepository kilnLockRepository;
    private final ReadingRepository readingRepository;
    private final HourlyAssessmentRepository hourlyAssessmentRepository;
    private final CommandRepository commandRepository;
    private final IdempotentRepository idempotentRepository;
    private final BanRepository banRepository;
    private final DailySummaryRepository dailySummaryRepository;
    private final CementProperties properties;
    private final ApplicationClock clock;
    private final ObjectMapper objectMapper;

    public CementService(PlanRepository planRepository, FuelRepository fuelRepository,
                         LicenseRepository licenseRepository, KilnLockRepository kilnLockRepository,
                         ReadingRepository readingRepository,
                         HourlyAssessmentRepository hourlyAssessmentRepository,
                         CommandRepository commandRepository,
                         IdempotentRepository idempotentRepository,
                         BanRepository banRepository,
                         DailySummaryRepository dailySummaryRepository,
                         CementProperties properties,
                         Clock clock,
                         ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.fuelRepository = fuelRepository;
        this.licenseRepository = licenseRepository;
        this.kilnLockRepository = kilnLockRepository;
        this.readingRepository = readingRepository;
        this.hourlyAssessmentRepository = hourlyAssessmentRepository;
        this.commandRepository = commandRepository;
        this.idempotentRepository = idempotentRepository;
        this.banRepository = banRepository;
        this.dailySummaryRepository = dailySummaryRepository;
        this.properties = properties;
        this.clock = (ApplicationClock) clock;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------
    // 申报与审核
    // ---------------------------------------------------------------------

    /**
     * 方案申报并同步完成合成审核。以 requestId 幂等：同键同参返回首次结果，同键异参 409。
     * 整单拒绝条件（按顺序）：替代率超 40%、许可缺失/过期、高氯硫加权氯/硫超限、窑被占用。
     */
    @Transactional
    public PlanResultResponse submit(PlanSubmitRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同业务参数的申报，拒绝重复受理");
            }
            return readJson(existing.responseBody(), PlanResultResponse.class);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 同窑申报串行化，保证“并发申报唯一受理”
        kilnLockRepository.lockKiln(request.kilnId());
        // 获锁后重检：排队期间首个事务可能已提交同 requestId 结果
        var committed = idempotentRepository.find(request.requestId());
        if (committed != null) {
            if (!committed.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同业务参数的申报，拒绝重复受理");
            }
            return readJson(committed.responseBody(), PlanResultResponse.class);
        }

        BigDecimal chlorineTotal = BigDecimal.ZERO.setScale(MASS_SCALE, RoundingMode.HALF_UP);
        BigDecimal sulfurTotal = BigDecimal.ZERO.setScale(MASS_SCALE, RoundingMode.HALF_UP);
        for (var fuel : request.fuels()) {
            if (Boolean.TRUE.equals(fuel.highClSulfur())) {
                chlorineTotal = chlorineTotal.add(
                        fuel.massKg().multiply(fuel.chlorinePct()).movePointLeft(2));
                sulfurTotal = sulfurTotal.add(
                        fuel.massKg().multiply(fuel.sulfurPct()).movePointLeft(2));
            }
        }
        chlorineTotal = chlorineTotal.setScale(MASS_SCALE, RoundingMode.HALF_UP);
        sulfurTotal = sulfurTotal.setScale(MASS_SCALE, RoundingMode.HALF_UP);

        RejectCategory category = null;
        String reason = null;
        HazardousWasteLicense license = licenseRepository.findByLicenseNo(request.licenseNo());
        LocalDate today = LocalDate.now(clock);

        if (request.substitutionRate().compareTo(properties.getMaxSubstitutionRate()) > 0) {
            category = RejectCategory.RATE_EXCEEDED;
            reason = "替代燃料热量替代率 " + ratePercent(request.substitutionRate())
                    + "% 超过上限 " + ratePercent(properties.getMaxSubstitutionRate()) + "%，整单拒绝";
        } else if (license == null) {
            category = RejectCategory.LICENSE_INVALID;
            reason = "危废经营许可证号 " + request.licenseNo() + " 不存在，整单拒绝";
        } else if (license.validTo().isBefore(today)) {
            category = RejectCategory.LICENSE_INVALID;
            reason = "危废经营许可 " + license.licenseNo() + " 已于 " + license.validTo()
                    + " 过期，整单拒绝";
        } else if (chlorineTotal.compareTo(properties.getChlorineLimitKg()) > 0) {
            category = RejectCategory.CHLORINE_EXCEEDED;
            reason = "高氯硫燃料加权入窑氯总量 " + chlorineTotal + "kg 超过限值 "
                    + properties.getChlorineLimitKg() + "kg，整单拒绝";
        } else if (sulfurTotal.compareTo(properties.getSulfurLimitKg()) > 0) {
            category = RejectCategory.SULFUR_EXCEEDED;
            reason = "高氯硫燃料加权入窑硫总量 " + sulfurTotal + "kg 超过限值 "
                    + properties.getSulfurLimitKg() + "kg，整单拒绝";
        } else if (planRepository.findBlocking(request.kilnId(),
                request.plannedStart(), request.plannedEnd()) != null) {
            category = RejectCategory.KILN_OCCUPIED;
            reason = "窑 " + request.kilnId() + " 在 " + request.plannedStart() + "~"
                    + request.plannedEnd() + " 已存在审核通过/执行中方案，并发申报仅唯一受理";
        }

        PlanStatus status = category == null ? PlanStatus.APPROVED : PlanStatus.REJECTED;
        String planNo = generatePlanNo(now);
        CoFiringPlan plan = new CoFiringPlan(
                null, request.requestId(), planNo, request.kilnId(),
                license == null ? null : license.id(),
                request.substitutionRate().setScale(RATE_SCALE, RoundingMode.HALF_UP),
                status, request.plannedStart(), request.plannedEnd(),
                reason, category == null ? null : category.name(),
                now, now, null, null, null, null, null);
        long planId = planRepository.insert(plan);
        // 无论通过或拒绝均保留燃料快照，支撑氯硫加权复算
        fuelRepository.insertBatch(planId, request.fuels());

        CoFiringPlan saved = planRepository.findById(planId);
        PlanResultResponse response = toPlanResult(saved, chlorineTotal, sulfurTotal);
        idempotentRepository.trySave(request.requestId(), "PLAN_SUBMIT", fingerprint,
                200, writeJson(response), planId);
        return response;
    }

    // ---------------------------------------------------------------------
    // 执行 / 切换 / 停用
    // ---------------------------------------------------------------------

    /** 启动审核通过的方案；同窑同时刻只允许一个执行中方案，当日禁烧则拒绝。 */
    @Transactional
    public PlanStatusResponse execute(ExecutePlanRequest request) {
        CoFiringPlan plan = requirePlanByNo(request.planNo());
        LocalDateTime now = LocalDateTime.now(clock);
        return startPlan(plan, now);
    }

    private PlanStatusResponse startPlan(CoFiringPlan plan, LocalDateTime now) {
        if (plan.status() != PlanStatus.APPROVED) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "方案 " + plan.planNo() + " 当前状态 " + plan.status() + "，仅审核通过方案可启动执行");
        }
        if (banRepository.exists(plan.kilnId(), now.toLocalDate())) {
            throw BusinessException.unprocessable(RejectCategory.KILN_BANNED,
                    "窑 " + plan.kilnId() + " 当日已因连续3小时超标禁烧，禁止启动掺烧");
        }
        if (!kilnLockRepository.tryAcquire(plan.kilnId(), plan.id(), now)) {
            throw BusinessException.conflict(RejectCategory.KILN_OCCUPIED,
                    "窑 " + plan.kilnId() + " 同时刻已有执行中方案，方案 " + plan.planNo() + " 不予受理");
        }
        int updated = planRepository.markStarted(plan.id(), now);
        if (updated == 0) {
            kilnLockRepository.release(plan.kilnId());
            throw BusinessException.conflict(RejectCategory.KILN_OCCUPIED,
                    "方案 " + plan.planNo() + " 启动时状态已变化，受理失败");
        }
        return toStatusResponse(planRepository.findById(plan.id()));
    }

    /** 方案切换：先停旧后启新，两步在同一事务内完成。以 requestId 幂等。 */
    @Transactional
    public PlanStatusResponse switchPlan(SwitchPlanRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同切换参数，拒绝执行");
            }
            return readJson(existing.responseBody(), PlanStatusResponse.class);
        }

        kilnLockRepository.lockKiln(request.kilnId());
        CoFiringPlan oldPlan = planRepository.findActiveByKiln(request.kilnId());
        if (oldPlan == null) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "窑 " + request.kilnId() + " 当前无执行中方案，无需切换");
        }
        CoFiringPlan newPlan = requirePlanByNo(request.newPlanNo());
        if (!newPlan.kilnId().equals(request.kilnId())) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "新方案 " + newPlan.planNo() + " 不属于窑 " + request.kilnId());
        }
        if (newPlan.status() != PlanStatus.APPROVED) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "新方案 " + newPlan.planNo() + " 当前状态 " + newPlan.status() + "，仅审核通过方案可切换启动");
        }
        if (banRepository.exists(request.kilnId(), LocalDate.now(clock))) {
            throw BusinessException.unprocessable(RejectCategory.KILN_BANNED,
                    "窑 " + request.kilnId() + " 当日已禁烧，禁止切换到新掺烧方案");
        }
        LocalDateTime now = LocalDateTime.now(clock);

        // 先停旧：状态机条件更新
        int stopped = planRepository.markStopped(oldPlan.id(), now);
        if (stopped == 0) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "旧方案 " + oldPlan.planNo() + " 停用失败，状态已变化");
        }
        // 后启新：同事务直接把窑占用行改挂新方案
        int started = planRepository.markStarted(newPlan.id(), now);
        if (started == 0) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "新方案 " + newPlan.planNo() + " 启动失败，状态已变化");
        }
        if (kilnLockRepository.replace(request.kilnId(), newPlan.id(), now) == 0) {
            throw BusinessException.unprocessable(RejectCategory.KILN_OCCUPIED,
                    "窑占用槽缺失，切换失败");
        }
        PlanStatusResponse response = toStatusResponse(planRepository.findById(newPlan.id()));
        idempotentRepository.trySave(request.requestId(), "PLAN_SWITCH", fingerprint, 200,
                writeJson(response), newPlan.id());
        return response;
    }

    /** 手动停用并释放窑。 */
    @Transactional
    public PlanStatusResponse stop(String planNo) {
        CoFiringPlan plan = requirePlanByNo(planNo);
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = planRepository.markStopped(plan.id(), now);
        if (updated == 0) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "方案 " + planNo + " 当前状态 " + plan.status() + "，不可停用");
        }
        kilnLockRepository.release(plan.kilnId());
        return toStatusResponse(planRepository.findById(plan.id()));
    }

    // ---------------------------------------------------------------------
    // 读数上报与小时评估
    // ---------------------------------------------------------------------

    /**
     * 排放读数批量上报：按“测点＋采样时刻”去重，乱序数据按采样时刻归位评估。
     * 方案封存（SEALED）后到达的读数只登记迟到事件。
     */
    @Transactional
    public ReadingAckResponse ingestReadings(ReadingBatchRequest request) {
        CoFiringPlan plan = requirePlanByNo(request.planNo());
        boolean sealed = plan.status() == PlanStatus.SEALED;
        int inserted = 0;
        int duplicated = 0;
        int late = 0;

        List<ReadingItem> accepted = new ArrayList<>();
        for (ReadingItem item : request.readings()) {
            if (sealed) {
                readingRepository.insertLateEvent(plan.id(), item, writeJson(item));
                late++;
                continue;
            }
            boolean ok = readingRepository.tryInsert(plan.id(), plan.kilnId(), item, false);
            if (ok) {
                inserted++;
                accepted.add(item);
            } else {
                duplicated++;
            }
        }

        if (!sealed && inserted > 0) {
            // 乱序归位：重算受影响日期内所有小时评估
            LocalDate minDate = accepted.stream().map(i -> i.sampleTime().toLocalDate())
                    .min(Comparator.naturalOrder()).orElse(LocalDate.now(clock));
            LocalDate maxDate = accepted.stream().map(i -> i.sampleTime().toLocalDate())
                    .max(Comparator.naturalOrder()).orElse(minDate);
            for (LocalDate d = minDate; !d.isAfter(maxDate); d = d.plusDays(1)) {
                evaluateDay(plan, d);
            }
        }
        return new ReadingAckResponse(request.readings().size(), inserted, duplicated, late);
    }

    /**
     * 重算指定日期各小时均值：按小时分桶、跨测点算术平均（仅统计非空污染物）；
     * 均值严格大于限值判超标（恰等于达标）。连续超标序号按小时序累计，恰连续 3 次禁烧。
     */
    private void evaluateDay(CoFiringPlan plan, LocalDate date) {
        List<EmissionReading> readings = readingRepository.findByPlanAndDate(plan.id(), date);
        Map<LocalDateTime, List<EmissionReading>> buckets = new TreeMap<>();
        for (EmissionReading r : readings) {
            LocalDateTime hour = r.sampleTime().withMinute(0).withSecond(0).withNano(0);
            buckets.computeIfAbsent(hour, k -> new ArrayList<>()).add(r);
        }

        // 按采样时刻归位后顺序重算当日各小时，连续超标序号在内存中累计（跨日重置为0）
        int runningConsecutive = 0;
        for (Map.Entry<LocalDateTime, List<EmissionReading>> e : buckets.entrySet()) {
            LocalDateTime hour = e.getKey();
            List<EmissionReading> list = e.getValue();
            BigDecimal noxAvg = average(list, EmissionReading::nox);
            BigDecimal so2Avg = average(list, EmissionReading::so2);
            BigDecimal dustAvg = average(list, EmissionReading::dust);

            List<String> exceededPollutants = new ArrayList<>();
            BigDecimal worstAvg = null;
            if (noxAvg != null && exceeds(noxAvg, "NOX")) {
                exceededPollutants.add("NOX");
                worstAvg = max(worstAvg, noxAvg);
            }
            if (so2Avg != null && exceeds(so2Avg, "SO2")) {
                exceededPollutants.add("SO2");
                worstAvg = max(worstAvg, so2Avg);
            }
            if (dustAvg != null && exceeds(dustAvg, "DUST")) {
                exceededPollutants.add("DUST");
                worstAvg = max(worstAvg, dustAvg);
            }
            boolean exceeded = !exceededPollutants.isEmpty();
            String pollutants = String.join(",", exceededPollutants);
            runningConsecutive = exceeded ? runningConsecutive + 1 : 0;
            int consecutive = runningConsecutive;

            hourlyAssessmentRepository.upsert(new HourlyAssessment(
                    null, plan.id(), hour, noxAvg, so2Avg, dustAvg, list.size(),
                    exceeded, pollutants, consecutive));

            if (exceeded) {
                // 触发降掺指令（每小时至多一单）
                BigDecimal target = properties.getFloorSubstitutionRate().setScale(RATE_SCALE,
                        RoundingMode.HALF_UP);
                commandRepository.insertIfAbsent(new ReductionCommand(
                        null, plan.id(), hour, pollutants, worstAvg,
                        CommandStatus.TRIGGERED, target, LocalDateTime.now(clock),
                        null, null, null, null));
            }

            if (consecutive == properties.getConsecutiveExceedBan()
                    && planRepository.markBanned(plan.id(), LocalDateTime.now(clock)) > 0) {
                // 恰连续3次：当日禁烧；markBanned 条件更新保证只生效一次
                kilnLockRepository.release(plan.kilnId());
                banRepository.insert(plan.id(), plan.kilnId(), date,
                        "连续" + consecutive + "个小时均值超标，当日禁止掺烧");
            }
        }
    }

    // ---------------------------------------------------------------------
    // 降掺指令链
    // ---------------------------------------------------------------------

    /** 下发降掺指令（触发→下发）。以 requestId 幂等。 */
    @Transactional
    public CommandResponse issueCommand(CommandActionRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            if (!existing.requestType().equals("COMMAND_ISSUE")) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于其他类型操作，拒绝下发");
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同下发参数，拒绝执行");
            }
            return readJson(existing.responseBody(), CommandResponse.class);
        }

        CoFiringPlan plan = requirePlanByNo(request.planNo());
        ReductionCommand command = resolveCommand(plan, request.hourStart(), true);
        LocalDateTime now = LocalDateTime.now(clock);
        if (command.status() == CommandStatus.TRIGGERED) {
            int updated = commandRepository.markIssued(command.id(), now);
            if (updated == 0) {
                throw BusinessException.conflict(RejectCategory.ILLEGAL_STATE,
                        "指令状态已变化，下发失败");
            }
            command = commandRepository.findByPlanAndHour(plan.id(), command.hourStart());
        }
        CommandResponse response = toCommandResponse(plan.planNo(), command);
        idempotentRepository.trySave(request.requestId(), "COMMAND_ISSUE", fingerprint, 200,
                writeJson(response), plan.id());
        return response;
    }

    /** 执行确认（下发→确认）。以 requestId 幂等，且不改变已生效的执行参数。 */
    @Transactional
    public CommandResponse confirmCommand(CommandActionRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            if (!existing.requestType().equals("COMMAND_CONFIRM")) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于其他类型操作，拒绝确认");
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同确认参数，拒绝执行");
            }
            return readJson(existing.responseBody(), CommandResponse.class);
        }

        CoFiringPlan plan = requirePlanByNo(request.planNo());
        ReductionCommand command = resolveCommand(plan, request.hourStart(), false);
        LocalDateTime now = LocalDateTime.now(clock);
        if (command.status() == CommandStatus.TRIGGERED) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "指令尚未下发，不能执行确认");
        }
        if (command.status() == CommandStatus.ISSUED || command.status() == CommandStatus.ESCALATED) {
            int updated = commandRepository.markConfirmed(command.id(), request.requestId(), now);
            if (updated == 0) {
                throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                        "指令确认时状态已变化");
            }
            command = commandRepository.findByPlanAndHour(plan.id(), command.hourStart());
        }
        CommandResponse response = toCommandResponse(plan.planNo(), command);
        idempotentRepository.trySave(request.requestId(), "COMMAND_CONFIRM", fingerprint, 200,
                writeJson(response), plan.id());
        return response;
    }

    /**
     * 扫描已下发指令：下发恰满 30 分钟（issued_at + 30min &lt;= now）仍未确认，
     * 自动升级 ESCALATED 并强制降至保底比例。
     */
    @Transactional
    public int scanCommandTimeouts() {
        LocalDateTime deadline = LocalDateTime.now(clock)
                .minusMinutes(properties.getCommandConfirmTimeoutMinutes());
        List<ReductionCommand> overdue = commandRepository.findIssuedOverdue(deadline);
        int escalated = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (ReductionCommand cmd : overdue) {
            if (commandRepository.markEscalated(cmd.id(), now) > 0) {
                escalated++;
            }
        }
        return escalated;
    }

    // ---------------------------------------------------------------------
    // 日结与封存
    // ---------------------------------------------------------------------

    /** 查询日结（未封存实时复算；封存后返回封存快照）。 */
    @Transactional
    public DailySummaryResponse getDailySummary(String planNo, LocalDate date) {
        CoFiringPlan plan = requirePlanByNo(planNo);
        return buildSummary(plan, date);
    }

    /** 日结封存：终态。重复封存返回幂等成功；封存后迟到读数只记迟到事件。 */
    @Transactional
    public DailySummaryResponse seal(SealRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null && existing.requestType().equals("DAILY_SEAL")) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同封存参数，拒绝执行");
            }
            CoFiringPlan plan = existing.planId() == null ? null
                    : planRepository.findById(existing.planId());
            return buildSummary(plan, request.summaryDate());
        }

        CoFiringPlan plan = requirePlanByNo(request.planNo());
        DailySummaryRepository.DailySummaryRow row =
                dailySummaryRepository.find(plan.id(), request.summaryDate());
        LocalDateTime now = LocalDateTime.now(clock);
        if (row == null || !row.sealed()) {
            DailySummaryResponse response = buildSummary(plan, request.summaryDate());
            persistSummary(plan, request.summaryDate(), response, true, now);
            // 封存为终态：执行中/停用/禁烧方案统一置 SEALED
            if (plan.status() != PlanStatus.SEALED) {
                planRepository.markSealed(plan.id(), now, request.summaryDate());
            }
            // 封存即终结当日执行：释放窑占用槽
            if (plan.status() == PlanStatus.EXECUTING) {
                kilnLockRepository.release(plan.kilnId());
            }
            plan = planRepository.findById(plan.id());
        }
        DailySummaryResponse response = buildSummary(plan, request.summaryDate());
        idempotentRepository.trySave(request.requestId(), "DAILY_SEAL", fingerprint, 200,
                writeJson(response), plan.id());
        return response;
    }

    /**
     * 复算日结：逐小时实际生效比例（初值为申报比例，被下发/升级指令命中的小时起按指令时间生效保底线，
     * 取最早一条），实际替代率=各生效小时比例的算术平均；超标时段与降掺记录来自评估与指令表。
     */
    private DailySummaryResponse buildSummary(CoFiringPlan plan, LocalDate date) {
        DailySummaryRepository.DailySummaryRow row =
                dailySummaryRepository.find(plan.id(), date);
        List<HourlyAssessment> assessments = hourlyAssessmentRepository.findByPlanAndDate(plan.id(), date);
        List<ReductionCommand> commands = commandRepository.findByPlanAndDate(plan.id(), date);

        // 已封存：优先返回封存快照
        if (row != null && row.sealed()) {
            return new DailySummaryResponse(
                    plan.planNo(), date, row.actualSubstitutionRate(),
                    readJsonList(row.effectiveHoursJson(), DailySummaryResponse.EffectiveHour.class),
                    readJsonList(row.exceededPeriodsJson(), DailySummaryResponse.ExceededPeriod.class),
                    readJsonList(row.reductionRecordsJson(), CommandResponse.class),
                    true, row.sealedAt());
        }

        BigDecimal floor = properties.getFloorSubstitutionRate().setScale(RATE_SCALE,
                RoundingMode.HALF_UP);
        List<DailySummaryResponse.EffectiveHour> effectiveHours = new ArrayList<>();
        List<BigDecimal> ratesForAverage = new ArrayList<>();

        for (HourlyAssessment a : assessments) {
            // 截至该小时已“执行确认”或“超时升级强制降掺”的指令才实际改变生效比例
            boolean reduced = commands.stream().anyMatch(c ->
                    !c.hourStart().isAfter(a.hourStart())
                            && (c.status() == CommandStatus.CONFIRMED
                                    || c.status() == CommandStatus.ESCALATED));
            BigDecimal effective = reduced ? floor
                    : plan.substitutionRate().setScale(RATE_SCALE, RoundingMode.HALF_UP);
            effectiveHours.add(new DailySummaryResponse.EffectiveHour(a.hourStart(), effective,
                    a.exceeded()));
            ratesForAverage.add(effective);
        }

        BigDecimal actualRate = ratesForAverage.isEmpty()
                ? plan.substitutionRate().setScale(RATE_SCALE, RoundingMode.HALF_UP)
                : average(ratesForAverage);

        List<DailySummaryResponse.ExceededPeriod> exceededPeriods = assessments.stream()
                .filter(HourlyAssessment::exceeded)
                .map(a -> new DailySummaryResponse.ExceededPeriod(
                        a.hourStart(), a.pollutants(), a.noxAvg(), a.so2Avg(), a.dustAvg()))
                .toList();
        List<CommandResponse> reductionRecords = commands.stream()
                .map(c -> toCommandResponse(plan.planNo(), c)).toList();

        DailySummaryResponse response = new DailySummaryResponse(
                plan.planNo(), date, actualRate, effectiveHours, exceededPeriods,
                reductionRecords, false, null);

        // 未封存时每次查询均刷新可复算快照
        persistSummary(plan, date, response, false, null);
        return response;
    }

    private void persistSummary(CoFiringPlan plan, LocalDate date, DailySummaryResponse response,
                                boolean sealed, LocalDateTime sealedAt) {
        dailySummaryRepository.upsert(new DailySummaryRepository.DailySummaryRow(
                plan.id(), date, response.actualSubstitutionRate(),
                writeJson(response.exceededPeriods()),
                writeJson(response.reductionRecords()),
                writeJson(response.effectiveHours()),
                sealed, sealedAt));
    }

    // ---------------------------------------------------------------------
    // 查询视图
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PlanStatusResponse status(String planNo) {
        return toStatusResponse(requirePlanByNo(planNo));
    }

    private ReductionCommand resolveCommand(CoFiringPlan plan, String hourStartText,
                                            boolean allowTriggeredPick) {
        if (hourStartText != null && !hourStartText.isBlank()) {
            LocalDateTime hour = LocalDateTime.parse(hourStartText);
            ReductionCommand exact = commandRepository.findByPlanAndHour(plan.id(), hour);
            if (exact == null) {
                throw BusinessException.notFound(
                        "方案 " + plan.planNo() + " 在 " + hour + " 无超标降掺指令");
            }
            return exact;
        }
        return commandRepository.findAllByPlan(plan.id()).stream()
                .filter(c -> allowTriggeredPick
                        || c.status() != CommandStatus.TRIGGERED)
                .max(Comparator.comparing(ReductionCommand::hourStart))
                .orElseThrow(() -> BusinessException.notFound(
                        "方案 " + plan.planNo() + " 暂无可操作的降掺指令"));
    }

    // ---------------------------------------------------------------------
    // 换算与装配辅助
    // ---------------------------------------------------------------------

    private CoFiringPlan requirePlanByNo(String planNo) {
        CoFiringPlan plan = planRepository.findByPlanNo(planNo);
        if (plan == null) {
            throw BusinessException.notFound("方案 " + planNo + " 不存在");
        }
        return plan;
    }

    private BigDecimal limit(String pollutant) {
        BigDecimal limit = properties.getEmissionLimits().get(pollutant);
        if (limit == null) {
            throw new IllegalStateException("缺少污染物限值配置: " + pollutant);
        }
        return limit;
    }

    /** 恰等于限值判达标：仅严格大于为超标。 */
    private boolean exceeds(BigDecimal value, String pollutant) {
        return value.compareTo(limit(pollutant)) > 0;
    }

    private BigDecimal average(List<EmissionReading> list,
                               java.util.function.Function<EmissionReading, BigDecimal> getter) {
        List<BigDecimal> values = list.stream().map(getter)
                .filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? null : average(values);
    }

    private BigDecimal average(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), AVG_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal max(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        return a.max(b);
    }

    private String ratePercent(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
    }

    private String generatePlanNo(LocalDateTime now) {
        return "PL" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
                .format(now) + String.format("%03d", (int) (Math.random() * 1000));
    }

    private PlanResultResponse toPlanResult(CoFiringPlan plan, BigDecimal chlorine, BigDecimal sulfur) {
        return new PlanResultResponse(
                plan.requestId(), plan.id(), plan.planNo(), plan.kilnId(), plan.status().name(),
                plan.status() == PlanStatus.APPROVED,
                plan.rejectCategory(), plan.rejectReason(),
                chlorine, sulfur, plan.submittedAt());
    }

    private PlanStatusResponse toStatusResponse(CoFiringPlan p) {
        return new PlanStatusResponse(p.id(), p.planNo(), p.kilnId(), p.status().name(),
                p.substitutionRate(), p.submittedAt(), p.reviewedAt(), p.startedAt(),
                p.stoppedAt(), p.bannedAt(), p.sealedAt());
    }

    private CommandResponse toCommandResponse(String planNo, ReductionCommand c) {
        return new CommandResponse(c.id(), planNo, c.hourStart(), c.pollutant(), c.hourAvg(),
                c.status().name(), c.targetRate(), c.triggeredAt(), c.issuedAt(),
                c.confirmedAt(), c.escalatedAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    private <T> List<T> readJsonList(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }
}
