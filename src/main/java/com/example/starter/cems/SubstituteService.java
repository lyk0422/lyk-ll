package com.example.starter.cems;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.cems.SubstituteTypes.AdjustmentView;
import com.example.starter.cems.SubstituteTypes.EffectiveAveragesView;
import com.example.starter.cems.SubstituteTypes.FreezeItemView;
import com.example.starter.cems.SubstituteTypes.FreezeRequest;
import com.example.starter.cems.SubstituteTypes.FreezeView;
import com.example.starter.cems.SubstituteTypes.LabReportRequest;
import com.example.starter.cems.SubstituteTypes.LabReportView;
import com.example.starter.cems.SubstituteTypes.PermitConfirmRequest;
import com.example.starter.cems.SubstituteTypes.PermitUpsertRequest;
import com.example.starter.cems.SubstituteTypes.PermitView;
import com.example.starter.cems.SubstituteTypes.SubstituteExceedanceView;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewResult;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitResult;
import com.example.starter.cems.SubstituteTypes.SubstituteView;

/**
 * 检修替代监测小时台账与有效监测统计服务（cems-03 新增，合成规则，不用于生产）。
 * 补证不删改原数据：原机测读数、缺点告警、超标链、限排令与溢流证据均保持不变；
 * 替代超标只按原阈值追加独立超标来源留证，不自动撤销旧限排或缺点告警。
 * 所有写路径（许可版本、两人确认、报告登记、替代提交/复核、补机测联动、日结冻结）
 * 先取排口行锁，资格校验、来源台账、统计留证与幂等结果在同一事务内提交；失败整体回滚、不占幂等键。
 */
@Service
public class SubstituteService {

    /** 日结小时数：UTC 日固定 24 个整点槽位。 */
    static final int DAY_SLOTS = 24;
    /** 检修许可生效所需的不同确认人数。 */
    static final int REQUIRED_CONFIRMS = 2;

    private final SubstituteRepository repo;
    private final CemsRepository cemsRepo;
    private final CemsClock clock;
    private final IdempotencySupport idem;

    public SubstituteService(SubstituteRepository repo, CemsRepository cemsRepo,
                             CemsClock clock, IdempotencySupport idem) {
        this.repo = repo;
        this.cemsRepo = cemsRepo;
        this.clock = clock;
        this.idem = idem;
    }

    // ---------------- 检修许可版本（expectedVersion 乐观并发，两名不同人员确认生效） ----------------

    @Transactional
    public PermitView upsertPermit(PermitUpsertRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("windowStartUtc", req.windowStartUtc() == null ? null : req.windowStartUtc().toString());
        fields.put("windowEndUtc", req.windowEndUtc() == null ? null : req.windowEndUtc().toString());
        fields.put("createdBy", req.createdBy());
        return replayable(req.requestId(), "SUB_PERMIT", fields, PermitView.class,
                () -> doUpsertPermit(req));
    }

    private PermitView doUpsertPermit(PermitUpsertRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空（无许可时传 0）");
        }
        if (req.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion 不能为负数");
        }
        if (req.windowStartUtc() == null || req.windowEndUtc() == null) {
            throw new IllegalArgumentException("windowStartUtc/windowEndUtc 不能为空");
        }
        if (!req.windowEndUtc().isAfter(req.windowStartUtc())) {
            throw new IllegalArgumentException("windowEndUtc 必须晚于 windowStartUtc（UTC 半开窗口）");
        }
        String createdBy = requireText(req.createdBy(), "createdBy");
        cemsRepo.lockOutlet(code);
        Map<String, Object> latest = repo.findLatestPermit(code);
        int current = latest == null ? 0 : ((Number) latest.get("version")).intValue();
        if (req.expectedVersion() != current) {
            throw new ConflictException("检修许可版本不匹配: expectedVersion=" + req.expectedVersion()
                    + " 当前版本=" + current);
        }
        int newVersion = current + 1;
        LocalDateTime now = clock.now();
        repo.supersedeOpenPermits(code);
        try {
            repo.insertPermit(code, newVersion, toUtcLdt(req.windowStartUtc()), toUtcLdt(req.windowEndUtc()),
                    createdBy, now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("检修许可版本 " + newVersion + " 已存在（并发新增冲突）: " + code);
        }
        cemsRepo.insertEvent(code, "SUB_PERMIT", now, false,
                "version=" + newVersion + " window=[" + req.windowStartUtc() + "," + req.windowEndUtc()
                        + ") createdBy=" + createdBy);
        return toPermitView(repo.findPermit(code, newVersion));
    }

    @Transactional
    public PermitView confirmPermit(PermitConfirmRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("permitVersion", req.permitVersion());
        fields.put("confirmer", req.confirmer());
        return replayable(req.requestId(), "SUB_PERMIT_CONFIRM", fields, PermitView.class,
                () -> doConfirmPermit(req));
    }

    private PermitView doConfirmPermit(PermitConfirmRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.permitVersion() == null || req.permitVersion() < 1) {
            throw new IllegalArgumentException("permitVersion 必须为正整数");
        }
        String confirmer = requireText(req.confirmer(), "confirmer");
        cemsRepo.lockOutlet(code);
        Map<String, Object> permit = repo.findPermit(code, req.permitVersion());
        if (permit == null) {
            throw new NotFoundException("检修许可版本不存在: " + code + " v" + req.permitVersion());
        }
        String status = (String) permit.get("status");
        if ("SUPERSEDED".equals(status)) {
            throw new ConflictException("检修许可版本已被取代，不能确认: " + code + " v" + req.permitVersion());
        }
        if ("ACTIVE".equals(status)) {
            throw new ConflictException("检修许可已生效，不能重复确认: " + code + " v" + req.permitVersion());
        }
        LocalDateTime now = clock.now();
        if (!repo.insertConfirm(code, req.permitVersion(), confirmer, now)) {
            throw new BusinessException("检修许可须两名不同人员确认，确认人 " + confirmer + " 已确认过: "
                    + code + " v" + req.permitVersion());
        }
        List<String> confirmers = repo.findConfirmers(code, req.permitVersion());
        if (confirmers.size() >= REQUIRED_CONFIRMS) {
            if (repo.activatePermit(code, req.permitVersion()) == 0) {
                throw new ConflictException("检修许可状态已变化，确认未受理: " + code + " v" + req.permitVersion());
            }
        }
        cemsRepo.insertEvent(code, "SUB_PERMIT_CONFIRM", now, false,
                "version=" + req.permitVersion() + " confirmer=" + confirmer
                        + " confirms=" + confirmers.size() + "/" + REQUIRED_CONFIRMS);
        return toPermitView(repo.findPermit(code, req.permitVersion()));
    }

    // ---------------- 合成检验报告 ----------------

    @Transactional
    public LabReportView registerReport(LabReportRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", req.reportNo());
        fields.put("outletCode", req.outletCode());
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        return replayable(req.requestId(), "SUB_REPORT", fields, LabReportView.class,
                () -> doRegisterReport(req));
    }

    private LabReportView doRegisterReport(LabReportRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        String reportNo = requireText(req.reportNo(), "reportNo");
        if (req.hourUtc() == null) {
            throw new IllegalArgumentException("hourUtc 不能为空");
        }
        requireWholeHour(req.hourUtc(), "hourUtc");
        LocalDateTime now = clock.now();
        try {
            repo.insertReport(reportNo, code, toUtcLdt(req.hourUtc()), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("检验报告编号已存在: " + reportNo);
        }
        cemsRepo.insertEvent(code, "SUB_REPORT", now, false,
                "reportNo=" + reportNo + " hour=" + req.hourUtc());
        return new LabReportView(reportNo, code, req.hourUtc(), now);
    }

    // ---------------- 替代小时提交/更正（一小时最多一个当前版本） ----------------

    @Transactional
    public SubstituteSubmitResult submitSubstitute(SubstituteSubmitRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("value", req.value());
        fields.put("reportNo", req.reportNo());
        fields.put("submittedBy", req.submittedBy());
        fields.put("expectedVersion", req.expectedVersion());
        return replayable(req.requestId(), "SUB_SUBMIT", fields, SubstituteSubmitResult.class,
                () -> doSubmitSubstitute(req));
    }

    private SubstituteSubmitResult doSubmitSubstitute(SubstituteSubmitRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.hourUtc() == null) {
            throw new IllegalArgumentException("hourUtc 不能为空");
        }
        requireWholeHour(req.hourUtc(), "hourUtc");
        requireNonNullNonNegative(req.value(), "value");
        String reportNo = requireText(req.reportNo(), "reportNo");
        String submittedBy = requireText(req.submittedBy(), "submittedBy");
        if (req.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空（首次提交传 0）");
        }
        if (req.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion 不能为负数");
        }
        cemsRepo.lockOutlet(code);
        LocalDateTime hourUtc = toUtcLdt(req.hourUtc());
        // 资格校验：同排口同小时合成检验报告；缺报告不能按零浓度填补。
        Map<String, Object> report = repo.findReport(reportNo);
        if (report == null) {
            throw new BusinessException("缺少合成检验报告 " + reportNo + "，不能按零浓度填补: " + code);
        }
        if (!code.equals(report.get("outlet_code"))) {
            throw new BusinessException("检验报告 " + reportNo + " 属于排口 " + report.get("outlet_code")
                    + "，跨排口报告拒绝: " + code);
        }
        if (!hourUtc.equals(lt(report.get("hour_utc")))) {
            throw new BusinessException("检验报告 " + reportNo + " 对应整点 " + lt(report.get("hour_utc"))
                    + "，跨小时报告拒绝: " + hourUtc);
        }
        Map<String, Object> latest = repo.findLatestSubstitute(code, hourUtc);
        int current = latest == null ? 0 : ((Number) latest.get("version")).intValue();
        if (req.expectedVersion() != current) {
            throw new ConflictException("替代版本不匹配: expectedVersion=" + req.expectedVersion()
                    + " 当前版本=" + current + " (" + code + " " + req.hourUtc() + ")");
        }
        LocalDateTime now = clock.now();
        LocalDate dayUtc = hourUtc.toLocalDate();
        if (repo.findFreeze(code, dayUtc) != null) {
            // 日结冻结后：只登记独立调差，不改台账、不改冻结快照、版本不加。
            Map<String, Object> item = repo.findFreezeItem(code, dayUtc, hourUtc);
            repo.insertAdjustment(code, dayUtc, hourUtc,
                    current == 0 ? "SUBSTITUTE_SUBMIT" : "SUBSTITUTE_CORRECT",
                    item == null ? null : (String) item.get("source"),
                    item == null ? null : bd(item.get("item_value")),
                    "SUBSTITUTE", req.value(), null, req.requestId(), now);
            cemsRepo.insertEvent(code, "SUB_ADJUSTMENT", now, false,
                    "day=" + dayUtc + " hour=" + req.hourUtc() + " substitute value=" + req.value()
                            + "（冻结后仅调差）");
            return new SubstituteSubmitResult(req.requestId(), code, req.hourUtc(), current,
                    latest == null ? "NONE" : (String) latest.get("status"), true, true);
        }
        // 缺机测小时才可提交替代（冻结后调差不受此限）。
        if (hasMachineReading(code, hourUtc)) {
            throw new BusinessException("该小时已有合法机测读数，不能提交替代: " + code + " " + req.hourUtc());
        }
        // 替代小时必须整小时落入现行检修许可 UTC 半开窗口。
        Map<String, Object> permit = repo.findActivePermit(code);
        if (permit == null) {
            throw new BusinessException("无现行检修许可（须两名不同人员确认生效），不能提交替代: " + code);
        }
        LocalDateTime windowStart = lt(permit.get("window_start_utc"));
        LocalDateTime windowEnd = lt(permit.get("window_end_utc"));
        if (hourUtc.isBefore(windowStart) || hourUtc.plusHours(1).isAfter(windowEnd)) {
            throw new BusinessException("替代小时未整小时落入检修许可 UTC 半开窗口 [" + windowStart + ","
                    + windowEnd + "): " + hourUtc);
        }
        if (latest != null) {
            boolean wasEffective = "EFFECTIVE".equals(latest.get("status"));
            repo.supersedeSubstitute(((Number) latest.get("id")).longValue());
            if (wasEffective) {
                // 旧生效版本被新替代版本取代：其独立超标来源置 SUPERSEDED（留证不删除）。
                repo.supersedeActiveExceedances(code, hourUtc);
            }
        }
        int newVersion = current + 1;
        try {
            repo.insertSubstitute(code, hourUtc, newVersion, reportNo, req.value(), submittedBy, now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("替代版本 " + newVersion + " 已存在（并发更正冲突）: " + code + " "
                    + req.hourUtc());
        }
        cemsRepo.insertEvent(code, "SUB_SUBMIT", now, false,
                "hour=" + req.hourUtc() + " version=" + newVersion + " value=" + req.value()
                        + " reportNo=" + reportNo + " submittedBy=" + submittedBy);
        return new SubstituteSubmitResult(req.requestId(), code, req.hourUtc(), newVersion,
                "PENDING_REVIEW", false, false);
    }

    // ---------------- 替代复核（复核人须不同于提交人） ----------------

    @Transactional
    public SubstituteReviewResult reviewSubstitute(SubstituteReviewRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("version", req.version());
        fields.put("reviewer", req.reviewer());
        return replayable(req.requestId(), "SUB_REVIEW", fields, SubstituteReviewResult.class,
                () -> doReviewSubstitute(req));
    }

    private SubstituteReviewResult doReviewSubstitute(SubstituteReviewRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        Map<String, Object> outlet = loadOutlet(code);
        if (req.hourUtc() == null) {
            throw new IllegalArgumentException("hourUtc 不能为空");
        }
        requireWholeHour(req.hourUtc(), "hourUtc");
        if (req.version() == null || req.version() < 1) {
            throw new IllegalArgumentException("version 必须为正整数");
        }
        String reviewer = requireText(req.reviewer(), "reviewer");
        cemsRepo.lockOutlet(code);
        LocalDateTime hourUtc = toUtcLdt(req.hourUtc());
        Map<String, Object> row = repo.findSubstitute(code, hourUtc, req.version());
        if (row == null) {
            throw new NotFoundException("替代版本不存在: " + code + " " + req.hourUtc() + " v" + req.version());
        }
        String status = (String) row.get("status");
        if ("SUPERSEDED".equals(status)) {
            throw new ConflictException("替代版本已被新版本取代，不能复核: " + code + " " + req.hourUtc()
                    + " v" + req.version());
        }
        if ("INVALIDATED".equals(status)) {
            throw new ConflictException("替代已被补到的合法机测失效，不能复核: " + code + " " + req.hourUtc()
                    + " v" + req.version());
        }
        if ("EFFECTIVE".equals(status)) {
            throw new ConflictException("替代已复核生效，不能重复复核: " + code + " " + req.hourUtc()
                    + " v" + req.version());
        }
        if (reviewer.equals(row.get("submitted_by"))) {
            throw new BusinessException("复核人须不同于提交人 " + row.get("submitted_by") + ": "
                    + code + " " + req.hourUtc());
        }
        LocalDateTime now = clock.now();
        LocalDate dayUtc = hourUtc.toLocalDate();
        BigDecimal value = bd(row.get("sub_value"));
        if (repo.findFreeze(code, dayUtc) != null) {
            // 日结冻结后：只登记独立调差，台账保持待复核、不改冻结快照。
            Map<String, Object> item = repo.findFreezeItem(code, dayUtc, hourUtc);
            repo.insertAdjustment(code, dayUtc, hourUtc, "SUBSTITUTE_REVIEW",
                    item == null ? null : (String) item.get("source"),
                    item == null ? null : bd(item.get("item_value")),
                    "SUBSTITUTE", value, req.version(), req.requestId(), now);
            cemsRepo.insertEvent(code, "SUB_ADJUSTMENT", now, false,
                    "day=" + dayUtc + " hour=" + req.hourUtc() + " review v" + req.version()
                            + "（冻结后仅调差）");
            return new SubstituteReviewResult(req.requestId(), code, req.hourUtc(), req.version(),
                    status, false, true, true);
        }
        long id = ((Number) row.get("id")).longValue();
        if (repo.markEffective(id, reviewer, now) == 0) {
            throw new ConflictException("替代状态已变化，复核未受理: " + code + " " + req.hourUtc()
                    + " v" + req.version());
        }
        // 替代超标按原小时限值追加独立超标来源；不自动撤销旧限排或缺点告警。
        BigDecimal hourlyLimit = bd(outlet.get("hourly_limit"));
        boolean exceedanceMarked = value.compareTo(hourlyLimit) > 0;
        if (exceedanceMarked) {
            repo.insertExceedance(code, hourUtc, hourlyLimit, value, req.version(), now);
        }
        cemsRepo.insertEvent(code, "SUB_REVIEW", now, false,
                "hour=" + req.hourUtc() + " version=" + req.version() + " reviewer=" + reviewer
                        + " exceedance=" + exceedanceMarked);
        return new SubstituteReviewResult(req.requestId(), code, req.hourUtc(), req.version(),
                "EFFECTIVE", exceedanceMarked, false, false);
    }

    // ---------------- 补机测联动（由机测读数入口同事务调用） ----------------

    /**
     * 合法机测读数写入后的联动：封账前该小时生效中替代失效（留证）、其独立超标来源置 SUPERSEDED；
     * 日结冻结后只登记独立调差，不改台账与冻结快照。与读数写入同一事务、同一排口行锁。
     */
    @Transactional
    public void onMachineReading(String code, LocalDateTime localHour, BigDecimal value) {
        LocalDateTime hourUtc = localHour.atZone(CemsClock.ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        cemsRepo.lockOutlet(code);
        LocalDate dayUtc = hourUtc.toLocalDate();
        LocalDateTime now = clock.now();
        if (repo.findFreeze(code, dayUtc) != null) {
            Map<String, Object> item = repo.findFreezeItem(code, dayUtc, hourUtc);
            String oldSource = item == null ? null : (String) item.get("source");
            if ("MACHINE".equals(oldSource)) {
                // 冻结时已是机测来源：新读数属重复上报场景，不产生调差。
                return;
            }
            repo.insertAdjustment(code, dayUtc, hourUtc, "MACHINE_SUPPLEMENT",
                    oldSource, item == null ? null : bd(item.get("item_value")),
                    "MACHINE", value, null, null, now);
            cemsRepo.insertEvent(code, "SUB_ADJUSTMENT", now, false,
                    "day=" + dayUtc + " hour=" + hourUtc + " machine value=" + value + "（冻结后仅调差）");
            return;
        }
        if (repo.invalidateEffective(code, hourUtc) > 0) {
            repo.supersedeActiveExceedances(code, hourUtc);
            cemsRepo.insertEvent(code, "SUB_INVALIDATE", now, false,
                    "hour=" + hourUtc + " 封账前补到合法机测，生效替代失效（留证）");
        }
    }

    // ---------------- 有效监测均值（机测优先，不双计） ----------------

    /**
     * 有效监测均值：每小时合法机测优先，没有机测才取有效替代，两者不双计；
     * 分母、缺失小时与来源明示；空集合均值为 null（无结果而非零）。
     */
    @Transactional(readOnly = true)
    public EffectiveAveragesView averages(String code, Instant fromUtc, Instant toUtc) {
        requireText(code, "outletCode");
        loadOutlet(code);
        if (fromUtc == null || toUtc == null) {
            throw new IllegalArgumentException("from/to 不能为空（UTC 精确时刻，如 2026-09-26T00:00:00Z）");
        }
        requireWholeHour(fromUtc, "from");
        requireWholeHour(toUtc, "to");
        if (!toUtc.isAfter(fromUtc)) {
            throw new IllegalArgumentException("to 必须晚于 from（UTC 半开区间）");
        }
        LocalDateTime from = toUtcLdt(fromUtc);
        LocalDateTime to = toUtcLdt(toUtc);
        List<SlotSelection> slots = computeSelections(code, from,
                (int) java.time.Duration.between(from, to).toHours());
        List<Instant> missingHours = new ArrayList<>();
        int machineCount = 0;
        int substituteCount = 0;
        BigDecimal sum = BigDecimal.ZERO;
        int covered = 0;
        for (SlotSelection slot : slots) {
            switch (slot.source()) {
                case "MACHINE" -> {
                    machineCount++;
                    sum = sum.add(slot.value());
                    covered++;
                }
                case "SUBSTITUTE" -> {
                    substituteCount++;
                    sum = sum.add(slot.value());
                    covered++;
                }
                default -> missingHours.add(slot.hourUtc().toInstant(ZoneOffset.UTC));
            }
        }
        BigDecimal avg = covered == 0 ? null
                : sum.divide(BigDecimal.valueOf(covered), 4, RoundingMode.HALF_UP);
        return new EffectiveAveragesView(code, fromUtc, toUtc, slots.size(), machineCount,
                substituteCount, missingHours.size(), avg, missingHours);
    }

    // ---------------- 日结冻结（冻结当前选择及版本） ----------------

    @Transactional
    public FreezeView freeze(FreezeRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("dayUtc", req.dayUtc() == null ? null : req.dayUtc().toString());
        return replayable(req.requestId(), "SUB_FREEZE", fields, FreezeView.class, () -> doFreeze(req));
    }

    private FreezeView doFreeze(FreezeRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.dayUtc() == null) {
            throw new IllegalArgumentException("dayUtc 不能为空");
        }
        // 与替代复核、补机测联动竞争同一排口行锁：冻结到的来源选择必然来自同一一致快照。
        cemsRepo.lockOutlet(code);
        if (repo.findFreeze(code, req.dayUtc()) != null) {
            throw new ConflictException("该 UTC 日已冻结，不能重复冻结: " + code + " " + req.dayUtc());
        }
        LocalDateTime dayStart = toUtcLdt(req.dayUtc().atStartOfDay(ZoneOffset.UTC).toInstant());
        List<SlotSelection> slots = computeSelections(code, dayStart, DAY_SLOTS);
        int machineCount = 0;
        int substituteCount = 0;
        int missingCount = 0;
        BigDecimal sum = BigDecimal.ZERO;
        int covered = 0;
        for (SlotSelection slot : slots) {
            switch (slot.source()) {
                case "MACHINE" -> {
                    machineCount++;
                    sum = sum.add(slot.value());
                    covered++;
                }
                case "SUBSTITUTE" -> {
                    substituteCount++;
                    sum = sum.add(slot.value());
                    covered++;
                }
                default -> missingCount++;
            }
        }
        BigDecimal avg = covered == 0 ? null
                : sum.divide(BigDecimal.valueOf(covered), 4, RoundingMode.HALF_UP);
        LocalDateTime now = clock.now();
        long freezeId;
        try {
            freezeId = repo.insertFreeze(code, req.dayUtc(), avg, machineCount, substituteCount,
                    missingCount, req.requestId(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("该 UTC 日已冻结（并发冻结唯一受理）: " + code + " " + req.dayUtc());
        }
        for (SlotSelection slot : slots) {
            repo.insertFreezeItem(freezeId, code, req.dayUtc(), slot.hourUtc(), slot.source(),
                    slot.value(), slot.substituteVersion());
        }
        cemsRepo.insertEvent(code, "SUB_FREEZE", now, false,
                "day=" + req.dayUtc() + " avg=" + avg + " machine=" + machineCount
                        + " substitute=" + substituteCount + " missing=" + missingCount);
        return toFreezeView(repo.findFreeze(code, req.dayUtc()));
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public PermitView getCurrentPermit(String code) {
        requireText(code, "outletCode");
        loadOutlet(code);
        Map<String, Object> permit = repo.findLatestPermit(code);
        if (permit == null) {
            throw new NotFoundException("排口无检修许可: " + code);
        }
        return toPermitView(permit);
    }

    @Transactional(readOnly = true)
    public List<SubstituteView> listSubstitutes(String code) {
        requireText(code, "outletCode");
        loadOutlet(code);
        List<SubstituteView> views = new ArrayList<>();
        for (Map<String, Object> row : repo.findSubstitutes(code)) {
            views.add(toSubstituteView(row));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public FreezeView getFreeze(String code, LocalDate dayUtc) {
        requireText(code, "outletCode");
        loadOutlet(code);
        Map<String, Object> freeze = repo.findFreeze(code, dayUtc);
        if (freeze == null) {
            throw new NotFoundException("有效监测日结快照不存在: " + code + " " + dayUtc);
        }
        return toFreezeView(freeze);
    }

    @Transactional(readOnly = true)
    public List<AdjustmentView> listAdjustments(String code, LocalDate dayUtc) {
        requireText(code, "outletCode");
        loadOutlet(code);
        List<AdjustmentView> views = new ArrayList<>();
        for (Map<String, Object> a : repo.findAdjustments(code, dayUtc)) {
            views.add(new AdjustmentView(code, dayUtc,
                    lt(a.get("hour_utc")).toInstant(ZoneOffset.UTC),
                    (String) a.get("adjust_type"),
                    (String) a.get("old_source"),
                    bd(a.get("old_value")),
                    (String) a.get("new_source"),
                    bd(a.get("new_value")),
                    a.get("substitute_version") == null ? null
                            : ((Number) a.get("substitute_version")).intValue(),
                    (String) a.get("request_id"),
                    lt(a.get("created_at"))));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public List<SubstituteExceedanceView> listExceedances(String code) {
        requireText(code, "outletCode");
        loadOutlet(code);
        List<SubstituteExceedanceView> views = new ArrayList<>();
        for (Map<String, Object> e : repo.findExceedances(code)) {
            views.add(new SubstituteExceedanceView(code,
                    lt(e.get("hour_utc")).toInstant(ZoneOffset.UTC),
                    bd(e.get("limit_value")), bd(e.get("actual_value")),
                    ((Number) e.get("substitute_version")).intValue(),
                    (String) e.get("status"), lt(e.get("created_at"))));
        }
        return views;
    }

    // ---------------- 来源选择（机测优先、有效替代次之、否则缺失） ----------------

    /**
     * 逐整点来源选择：合法机测（monitor_reading 本地整点换算 UTC）优先；
     * 无机测取该小时当前版本且已生效的替代；两者不双计；都没有为缺失。
     */
    private List<SlotSelection> computeSelections(String code, LocalDateTime fromUtc, int slots) {
        Map<String, BigDecimal> machineByUtcHour = new LinkedHashMap<>();
        for (Map<String, Object> r : cemsRepo.findHourlyReadings(code)) {
            LocalDateTime utcHour = lt(r.get("period_start")).atZone(CemsClock.ZONE)
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            machineByUtcHour.put(utcHour.toString(), bd(r.get("avg_value")));
        }
        Map<String, Map<String, Object>> substituteByHour = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.findSubstitutesBetween(code, fromUtc, fromUtc.plusHours(slots))) {
            // findSubstitutesBetween 按 version 升序，后者覆盖前者，最终保留最新版本。
            substituteByHour.put(lt(row.get("hour_utc")).toString(), row);
        }
        List<SlotSelection> result = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            LocalDateTime slot = fromUtc.plusHours(i);
            BigDecimal machine = machineByUtcHour.get(slot.toString());
            if (machine != null) {
                result.add(new SlotSelection(slot, "MACHINE", machine, null));
                continue;
            }
            Map<String, Object> sub = substituteByHour.get(slot.toString());
            if (sub != null && "EFFECTIVE".equals(sub.get("status"))) {
                result.add(new SlotSelection(slot, "SUBSTITUTE", bd(sub.get("sub_value")),
                        ((Number) sub.get("version")).intValue()));
                continue;
            }
            result.add(new SlotSelection(slot, "MISSING", null, null));
        }
        return result;
    }

    /** 该 UTC 整点是否已有合法机测读数（机测读数按 Asia/Shanghai 本地整点存储）。 */
    private boolean hasMachineReading(String code, LocalDateTime hourUtc) {
        LocalDateTime localHour = hourUtc.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(CemsClock.ZONE).toLocalDateTime();
        for (Map<String, Object> r : cemsRepo.findHourlyReadings(code)) {
            if (localHour.equals(lt(r.get("period_start")))) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 幂等支撑（与 CemsService/OverflowService 同构） ----------------

    private <T> T replayable(String requestId, String requestType, Map<String, Object> fields,
                             Class<T> resultType, Supplier<T> action) {
        String rid = requireText(requestId, "requestId");
        String fingerprint = idem.fingerprint(rid, fields);
        Map<String, Object> existing = cemsRepo.findIdempotent(rid);
        if (existing != null) {
            return replayOrConflict(rid, fingerprint, existing, resultType);
        }
        while (true) {
            if (cemsRepo.insertIdempotent(rid, requestType, fingerprint, "", clock.now())) {
                T result = action.get();
                cemsRepo.updateIdempotentResult(rid, idem.toJson(result));
                return result;
            }
            Map<String, Object> raced = waitForIdempotentResult(rid);
            if (raced != null) {
                return replayOrConflict(rid, fingerprint, raced, resultType);
            }
        }
    }

    private <T> T replayOrConflict(String rid, String fingerprint, Map<String, Object> row,
                                   Class<T> resultType) {
        if (!fingerprint.equals(row.get("fingerprint"))) {
            throw new ConflictException("requestId=" + rid + " 请求参数与首次调用不一致（409 幂等冲突）");
        }
        return idem.fromJson((String) row.get("result_json"), resultType);
    }

    private Map<String, Object> waitForIdempotentResult(String rid) {
        long deadlineMillis = System.currentTimeMillis() + 15_000L;
        while (true) {
            Map<String, Object> row = cemsRepo.findIdempotent(rid);
            if (row == null) {
                return null;
            }
            if (!"".equals(row.get("result_json"))) {
                return row;
            }
            if (System.currentTimeMillis() >= deadlineMillis) {
                throw new ConflictException("requestId=" + rid + " 并发请求等待首次结果超时");
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("并发幂等等待被中断");
            }
        }
    }

    // ---------------- 公共辅助 ----------------

    private Map<String, Object> loadOutlet(String code) {
        Map<String, Object> outlet = cemsRepo.findOutlet(code);
        if (outlet == null) {
            throw new NotFoundException("排口不存在: " + code);
        }
        return outlet;
    }

    private static void requireWholeHour(Instant instant, String field) {
        if (instant.getNano() != 0 || instant.getEpochSecond() % 3600 != 0) {
            throw new IllegalArgumentException(field + " 必须为 UTC 整点（分秒为 0）: " + instant);
        }
    }

    private static LocalDateTime toUtcLdt(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static BigDecimal bd(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static LocalDateTime lt(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Timestamp t ? t.toLocalDateTime() : LocalDateTime.parse(value.toString());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static void requireNonNullNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
    }

    private PermitView toPermitView(Map<String, Object> p) {
        String code = (String) p.get("outlet_code");
        int version = ((Number) p.get("version")).intValue();
        return new PermitView(code, version,
                lt(p.get("window_start_utc")).toInstant(ZoneOffset.UTC),
                lt(p.get("window_end_utc")).toInstant(ZoneOffset.UTC),
                (String) p.get("status"),
                (String) p.get("created_by"),
                repo.findConfirmers(code, version),
                lt(p.get("created_at")));
    }

    private static SubstituteView toSubstituteView(Map<String, Object> row) {
        return new SubstituteView(
                (String) row.get("outlet_code"),
                lt(row.get("hour_utc")).toInstant(ZoneOffset.UTC),
                ((Number) row.get("version")).intValue(),
                (String) row.get("report_no"),
                bd(row.get("sub_value")),
                (String) row.get("submitted_by"),
                (String) row.get("reviewed_by"),
                (String) row.get("status"),
                lt(row.get("created_at")));
    }

    private FreezeView toFreezeView(Map<String, Object> f) {
        long freezeId = ((Number) f.get("id")).longValue();
        List<FreezeItemView> items = new ArrayList<>();
        for (Map<String, Object> item : repo.findFreezeItems(freezeId)) {
            Object sv = item.get("substitute_version");
            items.add(new FreezeItemView(
                    lt(item.get("hour_utc")).toInstant(ZoneOffset.UTC),
                    (String) item.get("source"),
                    bd(item.get("item_value")),
                    sv == null ? null : ((Number) sv).intValue()));
        }
        return new FreezeView(
                (String) f.get("outlet_code"),
                f.get("day_utc") instanceof java.sql.Date d ? d.toLocalDate()
                        : LocalDate.parse(f.get("day_utc").toString()),
                bd(f.get("avg_value")),
                ((Number) f.get("machine_count")).intValue(),
                ((Number) f.get("substitute_count")).intValue(),
                ((Number) f.get("missing_count")).intValue(),
                (String) f.get("status"),
                lt(f.get("frozen_at")),
                items);
    }

    /** 单个 UTC 整点的来源选择结果。 */
    private record SlotSelection(LocalDateTime hourUtc, String source, BigDecimal value,
                                 Integer substituteVersion) {
    }
}
