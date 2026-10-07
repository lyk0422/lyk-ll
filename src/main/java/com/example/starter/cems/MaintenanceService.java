package com.example.starter.cems;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
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

import com.example.starter.cems.MaintenanceTypes.EffectiveAveragesView;
import com.example.starter.cems.MaintenanceTypes.EffectiveHourView;
import com.example.starter.cems.MaintenanceTypes.LabReportRequest;
import com.example.starter.cems.MaintenanceTypes.LabReportView;
import com.example.starter.cems.MaintenanceTypes.MaintenanceAdjustmentView;
import com.example.starter.cems.MaintenanceTypes.MaintenancePermitRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenancePermitView;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewItemView;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewView;
import com.example.starter.cems.MaintenanceTypes.ReplacementConfirmRequest;
import com.example.starter.cems.MaintenanceTypes.ReplacementSubmitRequest;
import com.example.starter.cems.MaintenanceTypes.ReplacementView;
import com.example.starter.cems.MaintenanceTypes.SubstituteExceedanceView;

/**
 * 监测仪检修期人工替代证据与有效监测统计服务（cems-03，合成规则，不用于生产）。
 *
 * <p>补证独立留痕：不删除原机测读数（monitor_reading）、缺点告警（data_gap）、超标链（exceedance_mark）
 * 与限排令（restriction_order）；替代小时超标仅向 substitute_exceedance 追加独立来源，
 * 不自动撤销旧限排令或缺点告警。
 *
 * <p>有效监测口径：每小时合法机测优先，无机测才取“已复核生效”的替代，两者不双计。
 * 封账（maintenance_review）冻结当前逐小时选择与替代版本；封账后补机测/更正替代仅形成独立调差，
 * 不重写冻结均值。所有写路径先取排口行锁，业务、版本与幂等结果在同一事务内提交；失败整体回滚、不占幂等键。
 *
 * <p>时间坐标：检修窗口/报告窗口/替代小时为 UTC，机测读数 period_start 在本合成口径中按同一 UTC 整点
 * 坐标参与联接（测试两侧使用一致的合成整点）。
 */
@Service
public class MaintenanceService {

    /** 日结小时数：UTC 日固定 24 个整点槽位。 */
    static final int DAY_SLOTS = 24;

    static final String SOURCE_MACHINE = "MACHINE";
    static final String SOURCE_REPLACEMENT = "REPLACEMENT";

    private final MaintenanceRepository repo;
    private final CemsRepository cemsRepo;
    private final CemsClock clock;
    private final IdempotencySupport idem;

    public MaintenanceService(MaintenanceRepository repo, CemsRepository cemsRepo,
                              CemsClock clock, IdempotencySupport idem) {
        this.repo = repo;
        this.cemsRepo = cemsRepo;
        this.clock = clock;
        this.idem = idem;
    }

    // ---------------- 检修许可版本（双人确认 + expectedVersion 乐观并发） ----------------

    @Transactional
    public MaintenancePermitView upsertPermit(MaintenancePermitRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("windowStartUtc", req.windowStartUtc() == null ? null : req.windowStartUtc().toString());
        fields.put("windowEndUtc", req.windowEndUtc() == null ? null : req.windowEndUtc().toString());
        fields.put("confirmerA", req.confirmerA());
        fields.put("confirmerB", req.confirmerB());
        return replayable(req.requestId(), "MAINTENANCE_PERMIT", fields, MaintenancePermitView.class,
                () -> doUpsertPermit(req));
    }

    private MaintenancePermitView doUpsertPermit(MaintenancePermitRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空（无检修许可时传 0）");
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
        String a = requireText(req.confirmerA(), "confirmerA");
        String b = requireText(req.confirmerB(), "confirmerB");
        if (a.equals(b)) {
            throw new IllegalArgumentException("检修许可须两名不同人员确认（confirmerA 不能等于 confirmerB）");
        }
        cemsRepo.lockOutlet(code);
        Map<String, Object> latest = repo.findLatestPermit(code);
        int current = latest == null ? 0 : ((Number) latest.get("version")).intValue();
        if (req.expectedVersion() != current) {
            throw new ConflictException("检修许可版本不匹配: expectedVersion=" + req.expectedVersion()
                    + " 当前版本=" + current);
        }
        int newVersion = current + 1;
        LocalDateTime now = clock.now();
        repo.supersedeActivePermits(code);
        try {
            repo.insertPermit(code, newVersion, toUtcLdt(req.windowStartUtc()), toUtcLdt(req.windowEndUtc()),
                    a, b, now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("检修许可版本 " + newVersion + " 已存在（并发新增冲突）: " + code);
        }
        cemsRepo.insertEvent(code, "MAINTENANCE_PERMIT", now, false,
                "version=" + newVersion + " window=[" + req.windowStartUtc() + "," + req.windowEndUtc()
                        + ") confirmers=" + a + "," + b);
        return toPermitView(repo.findLatestPermit(code));
    }

    // ---------------- 合成检验报告 ----------------

    @Transactional
    public LabReportView registerLabReport(LabReportRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("reportNo", req.reportNo());
        fields.put("windowStartUtc", req.windowStartUtc() == null ? null : req.windowStartUtc().toString());
        fields.put("windowEndUtc", req.windowEndUtc() == null ? null : req.windowEndUtc().toString());
        return replayable(req.requestId(), "LAB_REPORT", fields, LabReportView.class,
                () -> doRegisterLabReport(req));
    }

    private LabReportView doRegisterLabReport(LabReportRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        String reportNo = requireText(req.reportNo(), "reportNo");
        if (req.windowStartUtc() == null || req.windowEndUtc() == null) {
            throw new IllegalArgumentException("windowStartUtc/windowEndUtc 不能为空");
        }
        if (!req.windowEndUtc().isAfter(req.windowStartUtc())) {
            throw new IllegalArgumentException("windowEndUtc 必须晚于 windowStartUtc（UTC 半开窗口）");
        }
        cemsRepo.lockOutlet(code);
        LocalDateTime now = clock.now();
        if (!repo.insertLabReport(reportNo, code, toUtcLdt(req.windowStartUtc()),
                toUtcLdt(req.windowEndUtc()), now)) {
            throw new ConflictException("检验报告已存在（报告号重复或同排口同采样窗口已登记）: " + reportNo);
        }
        cemsRepo.insertEvent(code, "LAB_REPORT", now, false,
                "reportNo=" + reportNo + " window=[" + req.windowStartUtc() + "," + req.windowEndUtc() + ")");
        return toLabReportView(repo.findLabReport(reportNo));
    }

    // ---------------- 替代小时提交（缺机测 + 有限非负浓度 + 同排口报告 + 整点落窗） ----------------

    @Transactional
    public ReplacementView submitReplacement(ReplacementSubmitRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("concentration", req.concentration());
        fields.put("reportNo", req.reportNo());
        fields.put("submitter", req.submitter());
        fields.put("expectedVersion", req.expectedVersion());
        return replayable(req.requestId(), "REPLACEMENT_SUBMIT", fields, ReplacementView.class,
                () -> doSubmitReplacement(req));
    }

    private ReplacementView doSubmitReplacement(ReplacementSubmitRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.hourUtc() == null) {
            throw new IllegalArgumentException("hourUtc 不能为空");
        }
        requireWholeHour(req.hourUtc(), "hourUtc");
        requireFiniteNonNegative(req.concentration(), "concentration");
        String reportNo = requireText(req.reportNo(), "reportNo");
        requireText(req.submitter(), "submitter");
        if (req.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空（该小时无替代时传 0）");
        }
        if (req.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion 不能为负数");
        }
        LocalDateTime hourUtc = toUtcLdt(req.hourUtc());
        cemsRepo.lockOutlet(code);

        // 替代小时必须整小时完整落入现行检修许可 UTC 半开窗口，越窗拒绝。
        Map<String, Object> permit = repo.findActivePermit(code);
        if (permit == null) {
            throw new BusinessException("无现行检修许可，不能提交替代监测证据: " + code);
        }
        LocalDateTime windowStart = lt(permit.get("window_start_utc"));
        LocalDateTime windowEnd = lt(permit.get("window_end_utc"));
        if (hourUtc.isBefore(windowStart) || hourUtc.plusHours(1).isAfter(windowEnd)) {
            throw new BusinessException("替代小时未完整落入检修许可 UTC 半开窗口 [" + windowStart + ","
                    + windowEnd + "): " + hourUtc);
        }

        // 检验报告必须存在、同排口，且窗口恰为 [hour, hour+1)；跨小时报告拒绝。
        Map<String, Object> report = repo.findLabReport(reportNo);
        if (report == null) {
            throw new NotFoundException("检验报告不存在: " + reportNo);
        }
        if (!code.equals(report.get("outlet_code"))) {
            throw new BusinessException("检验报告不属于该排口（跨排口引用拒绝）: " + reportNo);
        }
        if (!hourUtc.equals(lt(report.get("window_start_utc")))
                || !hourUtc.plusHours(1).equals(lt(report.get("window_end_utc")))) {
            throw new BusinessException("检验报告窗口必须与替代小时 [" + hourUtc + "," + hourUtc.plusHours(1)
                    + ") 精确一致（跨小时报告拒绝）: " + reportNo);
        }

        // 缺机测小时才可替代：机测优先，已有合法机测不能再提替代。
        if (hasMachineReading(code, hourUtc)) {
            throw new BusinessException("该小时已补到合法机测，机测优先，不能再提交替代: " + req.hourUtc());
        }

        Map<String, Object> latest = repo.findLatestReplacement(code, hourUtc);
        int currentVersion = latest == null ? 0 : ((Number) latest.get("version")).intValue();
        if (req.expectedVersion() != currentVersion) {
            throw new ConflictException("替代版本不匹配: expectedVersion=" + req.expectedVersion()
                    + " 当前版本=" + currentVersion);
        }
        if (latest != null && "PENDING".equals(latest.get("status"))) {
            throw new ConflictException("该小时存在待复核替代版本（版本 " + currentVersion
                    + "），须先由他人复核，不能并发再提新版本");
        }

        int newVersion = currentVersion + 1;
        LocalDateTime now = clock.now();
        try {
            repo.insertReplacement(code, hourUtc, newVersion, req.concentration(), reportNo,
                    req.submitter(), req.requestId(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("替代版本 " + newVersion + " 已存在（并发提交冲突）: "
                    + code + " " + req.hourUtc());
        }
        cemsRepo.insertEvent(code, "REPLACEMENT_SUBMIT", now, false,
                "hour=" + req.hourUtc() + " version=" + newVersion + " concentration=" + req.concentration()
                        + " report=" + reportNo + " submitter=" + req.submitter());
        return toReplacementView(repo.findReplacementByVersion(code, hourUtc, newVersion));
    }

    // ---------------- 替代复核（复核人不同于提交人，生效并独立评价替代超标） ----------------

    @Transactional
    public ReplacementView confirmReplacement(ReplacementConfirmRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("reviewer", req.reviewer());
        return replayable(req.requestId(), "REPLACEMENT_CONFIRM", fields, ReplacementView.class,
                () -> doConfirmReplacement(req));
    }

    private ReplacementView doConfirmReplacement(ReplacementConfirmRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.hourUtc() == null) {
            throw new IllegalArgumentException("hourUtc 不能为空");
        }
        requireWholeHour(req.hourUtc(), "hourUtc");
        String reviewer = requireText(req.reviewer(), "reviewer");
        LocalDateTime hourUtc = toUtcLdt(req.hourUtc());
        cemsRepo.lockOutlet(code);

        Map<String, Object> pending = repo.findPendingReplacement(code, hourUtc);
        if (pending == null) {
            throw new NotFoundException("该小时无待复核替代版本: " + code + " " + req.hourUtc());
        }
        int version = ((Number) pending.get("version")).intValue();
        String submitter = (String) pending.get("submitter");
        if (reviewer.equals(submitter)) {
            throw new BusinessException("替代复核人必须不同于提交人: submitter=" + submitter
                    + " reviewer=" + reviewer);
        }
        // 复核期间若已补到合法机测，机测优先：待复核版本应已被失效，不再受理复核。
        if (hasMachineReading(code, hourUtc)) {
            throw new BusinessException("该小时已补到合法机测，机测优先，替代复核不再受理: " + req.hourUtc());
        }

        BigDecimal concentration = bd(pending.get("concentration"));
        LocalDateTime now = clock.now();

        // 旧生效版本先让位（释放同小时唯一占槽），再生效新版本；保证一小时最多一个当前替代版本。
        Map<String, Object> currentEffective = repo.findEffectiveReplacement(code, hourUtc);
        if (currentEffective != null) {
            repo.supersedeReplacement(code, hourUtc, ((Number) currentEffective.get("version")).intValue());
        }
        if (repo.confirmReplacement(code, hourUtc, version, reviewer, now) != 1) {
            throw new ConflictException("替代版本状态已变化，复核未受理（并发复核冲突）: "
                    + code + " " + req.hourUtc() + " v" + version);
        }

        // 封账后更正替代：仅登记 REPLACEMENT_CORRECTION 独立调差（旧选择取自上一生效版本/冻结快照，
        // 原缺点为空），不重写冻结均值；封账前不产生调差（查询侧自动按新版本重算新口径）。
        Map<String, Object> review = repo.findReview(code, hourUtc.toLocalDate());
        if (review != null) {
            String oldSource = null;
            BigDecimal oldValue = null;
            Integer oldVersion = null;
            if (currentEffective != null) {
                oldSource = SOURCE_REPLACEMENT;
                oldValue = bd(currentEffective.get("concentration"));
                oldVersion = ((Number) currentEffective.get("version")).intValue();
            } else {
                Map<String, Object> frozenItem = findFrozenItem(
                        repo.findReviewItems(code, hourUtc.toLocalDate()), hourUtc);
                if (frozenItem != null) {
                    oldSource = (String) frozenItem.get("source");
                    oldValue = bd(frozenItem.get("concentration"));
                    Object rv = frozenItem.get("replacement_version");
                    oldVersion = rv == null ? null : ((Number) rv).intValue();
                }
            }
            repo.insertAdjustment(code, hourUtc.toLocalDate(), hourUtc, "REPLACEMENT_CORRECTION",
                    oldSource, oldValue, SOURCE_REPLACEMENT, concentration, oldVersion,
                    req.requestId(), now);
        }

        // 独立替代超标来源：按原排口小时限值评价，严格大于才追加；不触碰原超标链与限排令。
        BigDecimal hourlyLimit = bd(loadOutlet(code).get("hourly_limit"));
        repo.supersedeOtherActiveSubstituteExceedances(code, hourUtc, version);
        if (concentration.compareTo(hourlyLimit) > 0) {
            repo.insertSubstituteExceedance(code, hourUtc, version, hourlyLimit, concentration, now);
            cemsRepo.insertEvent(code, "SUBSTITUTE_EXCEEDANCE", now, false,
                    "hour=" + req.hourUtc() + " version=" + version + " actual=" + concentration
                            + " limit=" + hourlyLimit);
        }
        cemsRepo.insertEvent(code, "REPLACEMENT_CONFIRM", now, false,
                "hour=" + req.hourUtc() + " version=" + version + " reviewer=" + reviewer
                        + " frozen=" + (review != null));
        return toReplacementView(repo.findReplacementByVersion(code, hourUtc, version));
    }

    // ---------------- 有效监测均值（机测优先，不双计；分母、缺失、来源明示；空集合无结果） ----------------

    @Transactional(readOnly = true)
    public EffectiveAveragesView effectiveAverages(String code, Instant fromUtc, Instant toUtc) {
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
        Selection selection = selectHours(code, from, to);
        return new EffectiveAveragesView(code, fromUtc, toUtc, selection.totalSlots(),
                selection.counted().size(), selection.missingHours().size(),
                averageOrNull(selection.counted()), toInstants(selection.missingHours()),
                toEffectiveHourViews(selection.counted()));
    }

    // ---------------- 有效监测日结封账（冻结选择与版本） ----------------

    @Transactional
    public MaintenanceReviewView freezeReview(MaintenanceReviewRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("dayUtc", req.dayUtc() == null ? null : req.dayUtc().toString());
        return replayable(req.requestId(), "MAINTENANCE_REVIEW", fields, MaintenanceReviewView.class,
                () -> doFreezeReview(req));
    }

    private MaintenanceReviewView doFreezeReview(MaintenanceReviewRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.dayUtc() == null) {
            throw new IllegalArgumentException("dayUtc 不能为空");
        }
        // 与机测补报/替代写路径竞争同一排口行锁：冻结到的逐小时选择必然来自同一一致快照。
        cemsRepo.lockOutlet(code);
        if (repo.findReview(code, req.dayUtc()) != null) {
            throw new ConflictException("该 UTC 日有效监测已封账，不能重复封账: " + code + " " + req.dayUtc());
        }
        LocalDateTime dayStart = toUtcLdt(req.dayUtc().atStartOfDay(ZoneOffset.UTC).toInstant());
        Selection selection = selectHours(code, dayStart, dayStart.plusDays(1));

        Map<String, Object> permit = repo.findActivePermit(code);
        Integer permitVersion = permit == null ? null : ((Number) permit.get("version")).intValue();
        LocalDateTime now = clock.now();
        BigDecimal avg = averageOrNull(selection.counted());
        try {
            repo.insertReview(code, req.dayUtc(), permitVersion, avg, selection.counted().size(),
                    selection.missingHours().size(), req.requestId(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("该 UTC 日有效监测已封账（并发封账唯一受理）: "
                    + code + " " + req.dayUtc());
        }
        List<MaintenanceReviewItemView> items = new ArrayList<>();
        for (SelectedHour h : selection.counted()) {
            Integer replacementVersion = h.source().equals(SOURCE_REPLACEMENT) ? h.replacementVersion() : null;
            String reportNo = h.source().equals(SOURCE_REPLACEMENT) ? h.reportNo() : null;
            repo.insertReviewItem(code, req.dayUtc(), h.hour(), h.source(), h.value(),
                    replacementVersion, reportNo);
            items.add(new MaintenanceReviewItemView(h.hour().toInstant(ZoneOffset.UTC), h.source(),
                    h.value(), replacementVersion, reportNo));
        }
        cemsRepo.insertEvent(code, "MAINTENANCE_REVIEW", now, false,
                "day=" + req.dayUtc() + " permitVersion=" + permitVersion + " effective=" + avg
                        + "/" + selection.counted().size() + " missing=" + selection.missingHours().size());
        return new MaintenanceReviewView(code, req.dayUtc(), permitVersion, avg, selection.counted().size(),
                selection.missingHours().size(), "FROZEN", now, items);
    }

    // ---------------- 封账后补到合法机测：独立调差 + 失效替代来源（不重写冻结均值） ----------------

    /**
     * 机测小时读数补入后的同事务对账（由 {@code CemsService#reportHourly} 在新读数插入后调用，
     * 传播 REQUIRED 加入调用方事务）。先取排口行锁，与封账/替代写路径串行：
     * <ul>
     *   <li>封账前：重算新口径（查询侧自动以机测优先），失效该小时待复核/生效替代及其替代超标来源；</li>
     *   <li>封账后：仅登记 MACHINE_BACKFILL 独立调差（旧选择取自冻结快照，原缺点为 null），
     *       不重写冻结均值；同时失效替代来源，超标历史只留证不删除。</li>
     * </ul>
     */
    @Transactional
    public void reconcileMachineReading(String code, LocalDateTime hourUtc) {
        cemsRepo.lockOutlet(code);
        LocalDate dayUtc = hourUtc.toLocalDate();
        LocalDateTime now = clock.now();
        BigDecimal machineValue = machineValue(code, hourUtc);
        if (machineValue == null) {
            return;
        }
        Map<String, Object> review = repo.findReview(code, dayUtc);
        List<Map<String, Object>> liveReplacements = new ArrayList<>();
        Map<String, Object> effective = repo.findEffectiveReplacement(code, hourUtc);
        Map<String, Object> pending = repo.findPendingReplacement(code, hourUtc);
        if (effective != null) {
            liveReplacements.add(effective);
        }
        if (pending != null) {
            liveReplacements.add(pending);
        }
        if (review != null) {
            // 已封账：任何新机测都是封账后变化，登记独立调差，旧值/来源取自冻结快照（原缺点为空）。
            Map<String, Object> frozenItem = findFrozenItem(repo.findReviewItems(code, dayUtc), hourUtc);
            String oldSource = frozenItem == null ? null : (String) frozenItem.get("source");
            BigDecimal oldValue = frozenItem == null ? null : bd(frozenItem.get("concentration"));
            Integer replacementVersion = null;
            if (frozenItem != null && SOURCE_REPLACEMENT.equals(oldSource)) {
                Object v = frozenItem.get("replacement_version");
                replacementVersion = v == null ? null : ((Number) v).intValue();
            } else if (effective != null) {
                replacementVersion = ((Number) effective.get("version")).intValue();
            }
            repo.insertAdjustment(code, dayUtc, hourUtc, "MACHINE_BACKFILL", oldSource, oldValue,
                    SOURCE_MACHINE, machineValue, replacementVersion, null, now);
        }
        if (!liveReplacements.isEmpty()) {
            // 机测优先：失效该小时全部待复核/生效替代版本及其生效中替代超标来源（历史只留证不删除）。
            StringBuilder versions = new StringBuilder();
            for (Map<String, Object> replacement : liveReplacements) {
                int version = ((Number) replacement.get("version")).intValue();
                repo.invalidateReplacement(code, hourUtc, version);
                versions.append(version).append(",");
            }
            repo.invalidateActiveSubstituteExceedances(code, hourUtc);
            cemsRepo.insertEvent(code, "MAINTENANCE_BACKFILL", now, false,
                    "hour=" + hourUtc + " invalidatedReplacementVersions=" + versions
                            + "machineValue=" + machineValue + " frozen=" + (review != null));
        } else if (review != null) {
            cemsRepo.insertEvent(code, "MAINTENANCE_BACKFILL", now, false,
                    "hour=" + hourUtc + " machineValue=" + machineValue
                            + " frozen=true old=missing（独立调差）");
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public MaintenancePermitView getCurrentPermit(String code) {
        requireText(code, "outletCode");
        loadOutlet(code);
        Map<String, Object> permit = repo.findActivePermit(code);
        if (permit == null) {
            throw new NotFoundException("排口无现行检修许可: " + code);
        }
        return toPermitView(permit);
    }

    @Transactional(readOnly = true)
    public MaintenanceReviewView getReview(String code, LocalDate dayUtc) {
        requireText(code, "outletCode");
        loadOutlet(code);
        Map<String, Object> review = repo.findReview(code, dayUtc);
        if (review == null) {
            throw new NotFoundException("有效监测日结快照不存在: " + code + " " + dayUtc);
        }
        List<MaintenanceReviewItemView> items = new ArrayList<>();
        for (Map<String, Object> it : repo.findReviewItems(code, dayUtc)) {
            Object rv = it.get("replacement_version");
            items.add(new MaintenanceReviewItemView(
                    lt(it.get("hour_utc")).toInstant(ZoneOffset.UTC),
                    (String) it.get("source"), bd(it.get("concentration")),
                    rv == null ? null : ((Number) rv).intValue(),
                    (String) it.get("report_no")));
        }
        return toReviewView(review, items);
    }

    /** 替代复核记录链查询：返回某 UTC 日（或指定小时）某排口全部替代版本，按整点与版本排序。 */
    @Transactional(readOnly = true)
    public List<ReplacementView> listReplacements(String code, Instant fromUtc, Instant toUtc) {
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
        List<ReplacementView> views = new ArrayList<>();
        for (Map<String, Object> r : repo.findReplacementsBetween(code, toUtcLdt(fromUtc), toUtcLdt(toUtc))) {
            views.add(toReplacementView(r));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceAdjustmentView> listAdjustments(String code, LocalDate dayUtc) {        requireText(code, "outletCode");
        loadOutlet(code);
        if (dayUtc == null) {
            throw new IllegalArgumentException("day 不能为空");
        }
        List<MaintenanceAdjustmentView> views = new ArrayList<>();
        for (Map<String, Object> a : repo.findAdjustments(code, dayUtc)) {
            views.add(toAdjustmentView(a));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public List<SubstituteExceedanceView> listSubstituteExceedances(String code) {
        requireText(code, "outletCode");
        loadOutlet(code);
        List<SubstituteExceedanceView> views = new ArrayList<>();
        for (Map<String, Object> s : repo.findSubstituteExceedances(code)) {
            views.add(toSubstituteView(s));
        }
        return views;
    }

    // ---------------- 逐小时选择（机测优先，无机测才取有效替代，不双计） ----------------

    private Selection selectHours(String code, LocalDateTime from, LocalDateTime to) {
        Map<String, Map<String, Object>> machineByHour = new LinkedHashMap<>();
        for (Map<String, Object> m : repo.findMachineHoursBetween(code, from, to)) {
            machineByHour.put(lt(m.get("period_start")).toString(), m);
        }
        Map<String, Map<String, Object>> replacementByHour = new LinkedHashMap<>();
        for (Map<String, Object> r : repo.findReplacementsBetween(code, from, to)) {
            if ("EFFECTIVE".equals(r.get("status"))) {
                replacementByHour.put(lt(r.get("hour_utc")).toString(), r);
            }
        }
        long totalSlots = Duration.between(from, to).toHours();
        List<SelectedHour> counted = new ArrayList<>();
        List<LocalDateTime> missing = new ArrayList<>();
        for (long i = 0; i < totalSlots; i++) {
            LocalDateTime slot = from.plusHours(i);
            String key = slot.toString();
            Map<String, Object> machine = machineByHour.get(key);
            if (machine != null) {
                counted.add(new SelectedHour(slot, SOURCE_MACHINE, bd(machine.get("avg_value")), null, null));
                continue;
            }
            Map<String, Object> replacement = replacementByHour.get(key);
            if (replacement != null) {
                counted.add(new SelectedHour(slot, SOURCE_REPLACEMENT, bd(replacement.get("concentration")),
                        ((Number) replacement.get("version")).intValue(),
                        (String) replacement.get("report_no")));
            } else {
                missing.add(slot);
            }
        }
        return new Selection(totalSlots, counted, missing);
    }

    private boolean hasMachineReading(String code, LocalDateTime hourUtc) {
        return machineValue(code, hourUtc) != null;
    }

    private BigDecimal machineValue(String code, LocalDateTime hourUtc) {
        List<Map<String, Object>> rows = repo.findMachineHoursBetween(code, hourUtc, hourUtc.plusHours(1));
        return rows.isEmpty() ? null : bd(rows.get(0).get("avg_value"));
    }

    private static Map<String, Object> findFrozenItem(List<Map<String, Object>> items, LocalDateTime hourUtc) {
        for (Map<String, Object> it : items) {
            if (hourUtc.equals(lt(it.get("hour_utc")))) {
                return it;
            }
        }
        return null;
    }

    private static BigDecimal averageOrNull(List<SelectedHour> counted) {
        if (counted.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (SelectedHour h : counted) {
            sum = sum.add(h.value());
        }
        return sum.divide(BigDecimal.valueOf(counted.size()), 4, RoundingMode.HALF_UP);
    }

    private static List<Instant> toInstants(List<LocalDateTime> hours) {
        List<Instant> instants = new ArrayList<>();
        for (LocalDateTime h : hours) {
            instants.add(h.toInstant(ZoneOffset.UTC));
        }
        return instants;
    }

    private static List<EffectiveHourView> toEffectiveHourViews(List<SelectedHour> counted) {
        List<EffectiveHourView> views = new ArrayList<>();
        for (SelectedHour h : counted) {
            views.add(new EffectiveHourView(h.hour().toInstant(ZoneOffset.UTC), h.source(), h.value(),
                    h.replacementVersion(), h.reportNo()));
        }
        return views;
    }

    // ---------------- 幂等支撑（同键同参回放、异参 409、失败不占键） ----------------

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

    private static void requireFiniteNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空（缺报告不能按零浓度填补）");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
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

    private static MaintenancePermitView toPermitView(Map<String, Object> p) {
        return new MaintenancePermitView(
                (String) p.get("outlet_code"),
                ((Number) p.get("version")).intValue(),
                lt(p.get("window_start_utc")).toInstant(ZoneOffset.UTC),
                lt(p.get("window_end_utc")).toInstant(ZoneOffset.UTC),
                (String) p.get("confirmer_a"),
                (String) p.get("confirmer_b"),
                (String) p.get("status"),
                lt(p.get("created_at")),
                lt(p.get("confirmed_at")));
    }

    private static LabReportView toLabReportView(Map<String, Object> r) {
        return new LabReportView(
                (String) r.get("report_no"),
                (String) r.get("outlet_code"),
                lt(r.get("window_start_utc")).toInstant(ZoneOffset.UTC),
                lt(r.get("window_end_utc")).toInstant(ZoneOffset.UTC),
                lt(r.get("created_at")));
    }

    private static ReplacementView toReplacementView(Map<String, Object> r) {
        Object rv = r.get("reviewer");
        int version = ((Number) r.get("version")).intValue();
        return new ReplacementView(
                (String) r.get("outlet_code"),
                lt(r.get("hour_utc")).toInstant(ZoneOffset.UTC),
                version,
                version - 1,
                bd(r.get("concentration")),
                (String) r.get("report_no"),
                (String) r.get("submitter"),
                rv == null ? null : rv.toString(),
                (String) r.get("status"),
                lt(r.get("created_at")),
                lt(r.get("confirmed_at")));
    }

    private static MaintenanceReviewView toReviewView(Map<String, Object> r,
                                                      List<MaintenanceReviewItemView> items) {
        Object pv = r.get("permit_version");
        return new MaintenanceReviewView(
                (String) r.get("outlet_code"),
                r.get("day_utc") instanceof java.sql.Date d ? d.toLocalDate()
                        : LocalDate.parse(r.get("day_utc").toString()),
                pv == null ? null : ((Number) pv).intValue(),
                bd(r.get("effective_avg")),
                ((Number) r.get("counted_count")).intValue(),
                ((Number) r.get("missing_count")).intValue(),
                (String) r.get("status"),
                lt(r.get("frozen_at")),
                items);
    }

    private static MaintenanceAdjustmentView toAdjustmentView(Map<String, Object> a) {
        Object rv = a.get("replacement_version");
        return new MaintenanceAdjustmentView(
                ((Number) a.get("id")).longValue(),
                (String) a.get("outlet_code"),
                a.get("day_utc") instanceof java.sql.Date d ? d.toLocalDate()
                        : LocalDate.parse(a.get("day_utc").toString()),
                lt(a.get("hour_utc")).toInstant(ZoneOffset.UTC),
                (String) a.get("change_type"),
                (String) a.get("old_source"),
                bd(a.get("old_value")),
                (String) a.get("new_source"),
                bd(a.get("new_value")),
                rv == null ? null : ((Number) rv).intValue(),
                (String) a.get("request_id"),
                lt(a.get("created_at")));
    }

    private static SubstituteExceedanceView toSubstituteView(Map<String, Object> s) {
        return new SubstituteExceedanceView(
                ((Number) s.get("id")).longValue(),
                (String) s.get("outlet_code"),
                lt(s.get("hour_utc")).toInstant(ZoneOffset.UTC),
                bd(s.get("limit_value")),
                bd(s.get("actual_value")),
                ((Number) s.get("replacement_version")).intValue(),
                (String) s.get("status"),
                lt(s.get("created_at")));
    }

    /** 逐小时选择中间结果。 */
    private record Selection(long totalSlots, List<SelectedHour> counted, List<LocalDateTime> missingHours) {
    }

    /** 单个被计入小时：来源、值与（替代时）版本/报告。 */
    private record SelectedHour(LocalDateTime hour, String source, BigDecimal value,
                                Integer replacementVersion, String reportNo) {
    }
}
