package com.example.starter.service;

import com.example.starter.dao.AlertRepository;
import com.example.starter.dao.AlertRow;
import com.example.starter.dao.CatalogRepository;
import com.example.starter.dao.DiveEventRepository;
import com.example.starter.dao.DiveRepository;
import com.example.starter.dao.DiveRow;
import com.example.starter.dao.LedgerRow;
import com.example.starter.dao.PlanRepository;
import com.example.starter.dao.PlanRow;
import com.example.starter.dao.RecordRepository;
import com.example.starter.dao.RecordRow;
import com.example.starter.domain.EventTypes;
import com.example.starter.domain.Phase;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.ApplyResult;
import com.example.starter.web.dto.CloseRequest;
import com.example.starter.web.dto.PlanRequest;
import com.example.starter.web.dto.RecordRequest;
import com.example.starter.web.dto.SurfaceRequest;
import com.example.starter.web.dto.VitalsRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 潜水作业核心领域服务：申请→方案→执行→出水观察→结案。
 *
 * <p>全部操作经 {@link IdempotencyService} 在单事务内执行业务并登记幂等台账。</p>
 */
@Service
public class DiveService {

    /** 下潜/出水速率上限，米/分钟；恰 18 合格，大于 18 告警。 */
    public static final int RATE_LIMIT = 18;
    /** 同一潜水员 24 小时（UTC）累计水下时间上限，分钟；恰 6 小时合法。 */
    public static final int MAX_BOTTOM_MIN_24H = 360;
    /** 出水后观察时长，小时；恰满 2 小时方可结案。 */
    public static final Duration OBSERVATION = Duration.ofHours(2);

    private final DiveRepository dives;
    private final PlanRepository plans;
    private final RecordRepository records;
    private final AlertRepository alerts;
    private final DiveEventRepository events;
    private final CatalogRepository catalog;
    private final IdempotencyService idempotency;
    private final ControlledDiveService controlled;
    private final Clock clock;

    public DiveService(DiveRepository dives, PlanRepository plans, RecordRepository records,
                       AlertRepository alerts, DiveEventRepository events,
                       CatalogRepository catalog, IdempotencyService idempotency,
                       ControlledDiveService controlled, Clock clock) {
        this.dives = dives;
        this.plans = plans;
        this.records = records;
        this.alerts = alerts;
        this.events = events;
        this.catalog = catalog;
        this.idempotency = idempotency;
        this.controlled = controlled;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 1. 作业申请
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ApplyResult> apply(ApplyRequest req) {
        if (req.diverACode().equals(req.diverBCode())) {
            throw new ApiException(400, "潜水两人一组必须为两名不同潜水员");
        }
        if (!catalog.diverExists(req.diverACode()) || !catalog.diverExists(req.diverBCode())) {
            throw new ApiException(400, "潜水员不在合成名册中: " + req.diverACode() + "/" + req.diverBCode());
        }
        String teamKey = teamKey(req.diverACode(), req.diverBCode());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diverACode", req.diverACode());
        fields.put("diverBCode", req.diverBCode());
        fields.put("plannedDepthM", req.plannedDepthM());
        fields.put("plannedBottomMin", req.plannedBottomMin());

        return idempotency.execute(req.requestId(), "APPLY", fields, () -> {
            Instant now = Instant.now(clock);
            String fingerprint = com.example.starter.support.Fingerprints.sha256(
                    new java.util.TreeMap<>(fields) {{
                        put("requestId", req.requestId());
                    }});
            DiveRow row = new DiveRow(req.requestId(), fingerprint, teamKey, teamKey,
                    req.diverACode(), req.diverBCode(), req.plannedDepthM(), req.plannedBottomMin(),
                    "APPLIED", 0, null, false, null, now, now);
            // 唯一冲突（同 requestId 并发，或同组 active_team_slot 占用）原样抛出，
            // 由幂等层在胜方事务提交后区分：等到同 requestId 台账则重放/409，等不到则同组 409
            dives.insert(row);
            return IdempotencyService.FirstResult.created(new ApplyResult(
                    req.requestId(), teamKey, req.diverACode(), req.diverBCode(),
                    req.plannedDepthM(), req.plannedBottomMin(), "APPLIED", false));
        });
    }

    // ------------------------------------------------------------------
    // 2. 减压方案（查表 NDL，可变更；旧方案与旧记录同事务冻结）
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ApplyResult.PlanResult> plan(PlanRequest req) {
        requireDive(req.diveRequestId());
        Integer ndl = catalog.findNdlMinutes(req.depthM());
        if (ndl == null) {
            throw new ApiException(400, "合成减压表中无深度 " + req.depthM() + " 米的条目");
        }
        if (req.bottomMin() > ndl) {
            throw new ApiException(422, "底时间 " + req.bottomMin() + " 分钟超过 " + req.depthM()
                    + " 米免减压极限 " + ndl + " 分钟");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("depthM", req.depthM());
        fields.put("bottomMin", req.bottomMin());

        return idempotency.execute(req.requestId(), "PLAN", fields, () -> {
            // 动作内重读最新状态：仅首次受理执行，重放不进入，不会因潜次已流转而误判
            DiveRow latest = requireDive(req.diveRequestId());
            if ("SURFACED".equals(latest.status()) || "CLOSED".equals(latest.status())
                    || "FROZEN".equals(latest.status())) {
                throw new ApiException(409, "潜次已进入" + latest.status() + "阶段，减压方案不可变更");
            }
            if (latest.currentPlanVersion() > 0
                    && plans.getVersion(req.diveRequestId(), latest.currentPlanVersion())
                            .scoreControlled()) {
                throw new ApiException(422, "该潜次已启用反复潜水积分控制，旧减压方案入口不能改写受控方案");
            }
            Instant now = Instant.now(clock);
            int newVersion = latest.currentPlanVersion() + 1;
            int frozenOld = 0;
            if (newVersion > 1) {
                // 方案变更：旧方案、其全部已生成记录与台账在同一事务内冻结/清退，
                // 后续记录只能挂在新版本，杜绝沿用旧方案记录
                frozenOld = plans.freezeAll(req.diveRequestId());
                records.freezeByDive(req.diveRequestId());
                records.deleteLedgerByDive(req.diveRequestId());
                events.insert(req.diveRequestId(), EventTypes.PLAN_FROZEN,
                        "方案变更：冻结 v" + (newVersion - 1) + " 及其水下记录", now);
            }
            String profileJson = Profiles.buildJson(req.depthM(), req.bottomMin());
            String planFingerprint = com.example.starter.support.Fingerprints.sha256(
                    new java.util.TreeMap<>(fields) {{
                        put("requestId", req.requestId());
                    }});
            plans.insert(new com.example.starter.dao.PlanRow(null, req.diveRequestId(),
                    newVersion, req.depthM(), req.bottomMin(), ndl, RATE_LIMIT, RATE_LIMIT,
                    profileJson, false, false, planFingerprint, now));
            plans.bumpDiveVersion(req.diveRequestId(), newVersion, now);

            List<ApplyResult.Segment> segments = List.of(
                    new ApplyResult.Segment(Phase.DESCENT.name(), 0, req.depthM(),
                            (int) Math.ceil(req.depthM() / (double) RATE_LIMIT), RATE_LIMIT),
                    new ApplyResult.Segment(Phase.BOTTOM.name(), req.depthM(), req.depthM(),
                            req.bottomMin(), RATE_LIMIT),
                    new ApplyResult.Segment(Phase.ASCENT.name(), req.depthM(), 0,
                            (int) Math.ceil(req.depthM() / (double) RATE_LIMIT), RATE_LIMIT));
            return IdempotencyService.FirstResult.created(new ApplyResult.PlanResult(
                    req.diveRequestId(), newVersion, req.depthM(), req.bottomMin(), ndl,
                    RATE_LIMIT, segments, frozenOld, false));
        });
    }

    // ------------------------------------------------------------------
    // 3. 水下记录（单人不生效；同阶段成对才生效：复算速率、告警、入台账、6 小时校验）
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ApplyResult.RecordResult> record(RecordRequest req) {
        req.validate();
        requireDive(req.diveRequestId());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("diverCode", req.diverCode());
        fields.put("phase", req.phase());
        fields.put("fromDepthM", req.fromDepthM());
        fields.put("toDepthM", req.toDepthM());
        fields.put("durationMin", req.durationMin());
        fields.put("eventTime", req.eventTime());

        return idempotency.execute(req.requestId(), "RECORD", fields, () -> {
            // 动作内重读潜次与当前方案：首次受理才执行，重放直接返回首次结果
            DiveRow dive = requireDive(req.diveRequestId());
            if (dive.currentPlanVersion() == 0) {
                throw new ApiException(409, "潜次尚无减压方案，不能提交水下记录");
            }
            if (!req.diverCode().equals(dive.diverACode()) && !req.diverCode().equals(dive.diverBCode())) {
                throw new ApiException(403, "潜水员 " + req.diverCode() + " 不属于本潜次潜水组");
            }
            if (!"PLANNED".equals(dive.status()) && !"DIVING".equals(dive.status())) {
                throw new ApiException(409, "潜次处于 " + dive.status() + " 阶段，不能提交水下记录");
            }
            PlanRow plan = plans.getVersion(req.diveRequestId(), dive.currentPlanVersion());
            validatePhaseShape(req, plan);

            Instant now = Instant.now(clock);
            BigDecimal rate = RecordRepository.rateOf(req.phase(), req.fromDepthM(),
                    req.toDepthM(), req.durationMin());
            RecordRow row = new RecordRow(null, req.diveRequestId(), plan.version(),
                    req.diverCode(), req.phase(), req.fromDepthM(), req.toDepthM(),
                    req.durationMin(), rate, false, false, null, req.eventTime(), now);
            long recordId;
            try {
                recordId = records.insert(row);
            } catch (DuplicateKeyException dup) {
                throw new ApiException(409, "潜水员 " + req.diverCode() + " 在方案 v"
                        + plan.version() + " 的 " + req.phase() + " 阶段记录已存在");
            }

            boolean effective = false;
            String pairedWith = null;
            boolean speedAlert = false;
            int cumulative = 0;
            String newStatus = dive.status();

            RecordRow peer = records.findPairPeer(req.diveRequestId(), plan.version(),
                    req.phase(), req.diverCode()).orElse(null);
            if (peer != null) {
                // 两人同阶段成对：同事务内同时生效
                records.markPaired(recordId, peer.id());
                records.markPaired(peer.id(), recordId);
                effective = true;
                pairedWith = peer.diverCode();
                newStatus = "DIVING";

                if (Phase.BOTTOM.name().equals(req.phase())) {
                    cumulative = settleBottomPair(dive, plan,
                            records.getById(recordId), records.getById(peer.id()), now);
                } else {
                    speedAlert = settleSpeedPair(dive, plan,
                            records.getById(recordId), records.getById(peer.id()), now);
                }
                // 受控方案：成对下潜生效时重查来源未变并占用潜水员槽；成对水底时复核实际时长与积分上限
                if (plan.scoreControlled()) {
                    RecordRow mineRow = records.getById(recordId);
                    RecordRow peerRow = records.getById(peer.id());
                    if (Phase.DESCENT.name().equals(req.phase())) {
                        controlled.onDescentPaired(dive, plan, mineRow, peerRow, now);
                    } else if (Phase.BOTTOM.name().equals(req.phase())) {
                        controlled.onBottomPaired(dive, plan, mineRow, peerRow, now);
                    }
                } else if (Phase.DESCENT.name().equals(req.phase())) {
                    // 旧流程不建积分槽，但不得与占用该潜水员的受控潜次并发下潜
                    controlled.onLegacyDescentPaired(dive, records.getById(recordId),
                            records.getById(peer.id()));
                }
                dives.updateStatus(req.diveRequestId(), newStatus, now);
            }

            return IdempotencyService.FirstResult.ok(new ApplyResult.RecordResult(
                    req.diveRequestId(), plan.version(), req.diverCode(), req.phase(),
                    rate.toPlainString(), speedAlert, effective, pairedWith, newStatus,
                    cumulative, false));
        });
    }

    /** 成对 BOTTOM：逐人做 24 小时累计 6 小时校验（恰 6 合法），通过后两人同事务入账。 */
    private int settleBottomPair(DiveRow dive, PlanRow plan, RecordRow mine, RecordRow peer,
                                 Instant now) {
        Instant windowEnd = !mine.eventTimeUtc().isBefore(peer.eventTimeUtc())
                ? mine.eventTimeUtc() : peer.eventTimeUtc();
        int myCumulative = checkCumulative(mine, windowEnd);
        int peerCumulative = checkCumulative(peer, windowEnd);
        insertLedgerIfAbsent(dive, plan, mine, now);
        insertLedgerIfAbsent(dive, plan, peer, now);
        return Math.max(myCumulative, peerCumulative);
    }

    /** 校验某潜水员窗口内累计加本次不超过 6 小时；返回含本次的累计分钟。 */
    private int checkCumulative(RecordRow bottom, Instant windowEnd) {
        Timestamp start = Timestamp.from(windowEnd.minus(Duration.ofHours(24)));
        Timestamp end = Timestamp.from(windowEnd);
        int existed = records.sumBottomMinutes(bottom.diverCode(), start, end);
        int total = existed + bottom.durationMin();
        if (total > MAX_BOTTOM_MIN_24H) {
            throw new ApiException(422, "潜水员 " + bottom.diverCode() + " 24 小时内累计水下时间 "
                    + total + " 分钟超过上限 " + MAX_BOTTOM_MIN_24H + " 分钟（6 小时），记录回滚不生效");
        }
        return total;
    }

    private void insertLedgerIfAbsent(DiveRow dive, PlanRow plan, RecordRow bottom, Instant now) {
        if (!records.ledgerExists(dive.requestId(), plan.version(), bottom.diverCode())) {
            records.insertLedger(new LedgerRow(null, bottom.diverCode(), dive.requestId(),
                    plan.version(), bottom.durationMin(), bottom.eventTimeUtc()));
        }
    }

    /** 成对下潜/上升：逐人复算速率，大于 18 落告警（恰 18 合格），返回本人是否告警。 */
    private boolean settleSpeedPair(DiveRow dive, PlanRow plan, RecordRow mine, RecordRow peer,
                                    Instant now) {
        raiseAlertIfOverspeed(dive, plan, mine, now);
        raiseAlertIfOverspeed(dive, plan, peer, now);
        return mine.rateMMin().compareTo(BigDecimal.valueOf(RATE_LIMIT)) > 0;
    }

    private void raiseAlertIfOverspeed(DiveRow dive, PlanRow plan, RecordRow rec, Instant now) {
        if (rec.rateMMin().compareTo(BigDecimal.valueOf(RATE_LIMIT)) > 0) {
            alerts.insert(new AlertRow(null, dive.requestId(), plan.version(), rec.diverCode(),
                    rec.phase(), rec.rateMMin(), rec.eventTimeUtc(), now));
        }
    }

    private void validatePhaseShape(RecordRequest req, PlanRow plan) {
        int d = plan.depthM();
        switch (req.phase()) {
            case "DESCENT" -> {
                if (req.fromDepthM() != 0 || req.toDepthM() != d) {
                    throw new ApiException(400, "DESCENT 深度须为 0→" + d + " 米");
                }
            }
            case "BOTTOM" -> {
                if (req.fromDepthM() != d || req.toDepthM() != d) {
                    throw new ApiException(400, "BOTTOM 深度须恒为 " + d + " 米");
                }
            }
            case "ASCENT" -> {
                if (req.fromDepthM() != d || req.toDepthM() != 0) {
                    throw new ApiException(400, "ASCENT 深度须为 " + d + "→0 米");
                }
            }
            default -> throw new ApiException(400, "未知阶段 " + req.phase());
        }
    }

    // ------------------------------------------------------------------
    // 4. 出水登记（进入 2 小时观察期）
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ApplyResult.SurfaceResult> surface(SurfaceRequest req) {
        requireDive(req.diveRequestId());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("surfacedAt", req.surfacedAt() == null ? null : req.surfacedAt().toString());

        return idempotency.execute(req.requestId(), "SURFACE", fields, () -> {
            DiveRow latest = requireDive(req.diveRequestId());
            if (!"DIVING".equals(latest.status())) {
                throw new ApiException(409,
                        "潜次处于 " + latest.status() + " 阶段，不能登记出水（须先成对执行）");
            }
            Instant surfacedAt = req.surfacedAt() != null ? req.surfacedAt() : Instant.now(clock);
            Instant now = Instant.now(clock);
            dives.markSurfaced(req.diveRequestId(), surfacedAt, "SURFACED", now);
            // 受控方案：出水原子更新两人积分账、结算台账并释放潜水员占用槽（同一事务，失败整体回滚）
            PlanRow currentPlan = plans.getVersion(req.diveRequestId(), latest.currentPlanVersion());
            if (currentPlan.scoreControlled()) {
                controlled.onSurface(latest, currentPlan, surfacedAt, now);
            }
            Instant due = surfacedAt.plus(OBSERVATION);
            return IdempotencyService.FirstResult.ok(new ApplyResult.SurfaceResult(
                    req.diveRequestId(), surfacedAt.toString(), due.toString(), "SURFACED", false));
        });
    }

    // ------------------------------------------------------------------
    // 5. 观察期体征（有症状即加压舱预案并冻结；结案后迟到数据只记事件）
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ApplyResult.VitalsResult> vitals(VitalsRequest req) {
        requireDive(req.diveRequestId());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("symptomatic", req.symptomatic());
        fields.put("observedAt", req.observedAt() == null ? null : req.observedAt().toString());

        return idempotency.execute(req.requestId(), "SYMPTOM", fields, () -> {
            DiveRow dive = requireDive(req.diveRequestId());
            Instant now = Instant.now(clock);
            Instant observedAt = req.observedAt() != null ? req.observedAt() : now;

            if ("CLOSED".equals(dive.status())) {
                // 结案为终态：迟到体征只记迟到事件，不改状态
                events.insert(req.diveRequestId(), EventTypes.LATE_VITALS,
                        "结案后迟到体征 symptomatic=" + req.symptomatic()
                                + " observedAt=" + observedAt, now);
                return IdempotencyService.FirstResult.ok(new ApplyResult.VitalsResult(
                        req.diveRequestId(), "CLOSED", req.symptomatic(), false, true, false));
            }
            if ("FROZEN".equals(dive.status())) {
                return IdempotencyService.FirstResult.ok(new ApplyResult.VitalsResult(
                        req.diveRequestId(), "FROZEN", true, true, false, false));
            }
            if (!"SURFACED".equals(dive.status())) {
                throw new ApiException(409, "潜次处于 " + dive.status() + " 阶段，尚在出水前，不能上报观察体征");
            }
            if (Boolean.TRUE.equals(req.symptomatic())) {
                dives.markSymptomatic(req.diveRequestId(), now);
                events.insert(req.diveRequestId(), EventTypes.CHAMBER_PROTOCOL,
                        "观察期出现症状，启动加压舱预案 observedAt=" + observedAt, now);
                return IdempotencyService.FirstResult.ok(new ApplyResult.VitalsResult(
                        req.diveRequestId(), "FROZEN", true, true, false, false));
            }
            return IdempotencyService.FirstResult.ok(new ApplyResult.VitalsResult(
                    req.diveRequestId(), "SURFACED", false, false, false, false));
        });
    }

    // ------------------------------------------------------------------
    // 6. 结案（恰满 2 小时观察且无症状；终态；重复结案幂等成功；冻结态禁止结案）
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ApplyResult.CloseResult> close(CloseRequest req) {
        DiveRow dive = requireDive(req.diveRequestId());
        if ("CLOSED".equals(dive.status())) {
            // 结案终态：任何重复结案都返回幂等成功（即使换了操作 requestId）
            return new IdempotencyService.Outcome<>(new ApplyResult.CloseResult(
                    req.diveRequestId(), "CLOSED",
                    dive.closedAtUtc() == null ? null : dive.closedAtUtc().toString(),
                    true, true), 200, true);
        }
        if ("FROZEN".equals(dive.status())) {
            throw new ApiException(409, "观察期出现症状已启动加压舱预案，结案冻结");
        }
        if (!"SURFACED".equals(dive.status())) {
            throw new ApiException(409, "潜次处于 " + dive.status() + " 阶段，尚未出水观察，不能结案");
        }
        Instant closedAt = req.closedAt() != null ? req.closedAt() : Instant.now(clock);
        Instant earliest = dive.surfacedAtUtc().plus(OBSERVATION);
        if (closedAt.isBefore(earliest)) {
            long minutesLeft = Duration.between(closedAt, earliest).toMinutes();
            throw new ApiException(409, "观察期须恰满 2 小时无症状方可结案，还差约 "
                    + Math.max(1, minutesLeft) + " 分钟");
        }
        if (dive.symptomatic()) {
            throw new ApiException(409, "观察期出现症状，结案冻结");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("closedAt", req.closedAt() == null ? null : req.closedAt().toString());

        return idempotency.execute(req.requestId(), "CLOSE", fields, () -> {
            Instant now = Instant.now(clock);
            dives.markClosed(req.diveRequestId(), closedAt, now);
            return IdempotencyService.FirstResult.ok(new ApplyResult.CloseResult(
                    req.diveRequestId(), "CLOSED", closedAt.toString(), true, false));
        });
    }

    // ------------------------------------------------------------------
    // 复算视图：剖面、速率告警、24 小时台账均可重算并与落库值核对
    // ------------------------------------------------------------------

    public Map<String, Object> recompute(String diveRequestId) {
        DiveRow dive = requireDive(diveRequestId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("requestId", dive.requestId());
        view.put("status", dive.status());
        view.put("teamKey", dive.teamKey());
        view.put("divers", List.of(dive.diverACode(), dive.diverBCode()));
        view.put("currentPlanVersion", dive.currentPlanVersion());
        view.put("surfacedAtUtc", dive.surfacedAtUtc() == null ? null
                : dive.surfacedAtUtc().toString());
        view.put("observationDueAtUtc", dive.surfacedAtUtc() == null ? null
                : dive.surfacedAtUtc().plus(OBSERVATION).toString());
        view.put("symptomatic", dive.symptomatic());
        view.put("closedAtUtc", dive.closedAtUtc() == null ? null
                : dive.closedAtUtc().toString());

        List<Map<String, Object>> planViews = new ArrayList<>();
        for (PlanRow p : plans.findAll(diveRequestId)) {
            Map<String, Object> pv = new LinkedHashMap<>();
            pv.put("version", p.version());
            pv.put("depthM", p.depthM());
            pv.put("bottomMin", p.bottomMin());
            pv.put("ndlLimitMin", p.ndlLimitMin());
            pv.put("withinNdl", p.bottomMin() <= p.ndlLimitMin());
            pv.put("frozen", p.frozen());
            planViews.add(pv);
        }
        view.put("plans", planViews);

        // 速率复算：深度差/历时与落库速率逐项核对
        List<Map<String, Object>> recordViews = new ArrayList<>();
        boolean allRatesConsistent = true;
        for (RecordRow r : records.findAllByDive(diveRequestId)) {
            BigDecimal recomputed = RecordRepository.rateOf(r.phase(), r.fromDepthM(),
                    r.toDepthM(), r.durationMin());
            boolean consistent = recomputed.compareTo(r.rateMMin()) == 0;
            allRatesConsistent &= consistent;
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("id", r.id());
            rv.put("planVersion", r.planVersion());
            rv.put("diverCode", r.diverCode());
            rv.put("phase", r.phase());
            rv.put("fromDepthM", r.fromDepthM());
            rv.put("toDepthM", r.toDepthM());
            rv.put("durationMin", r.durationMin());
            rv.put("storedRateMMin", r.rateMMin().toPlainString());
            rv.put("recomputedRateMMin", recomputed.toPlainString());
            rv.put("rateConsistent", consistent);
            rv.put("effective", r.effective());
            rv.put("frozen", r.frozen());
            recordViews.add(rv);
        }
        view.put("records", recordViews);
        view.put("allRatesConsistent", allRatesConsistent);

        view.put("alerts", alerts.findByDive(diveRequestId).stream().map(a -> {
            Map<String, Object> av = new LinkedHashMap<>();
            av.put("planVersion", a.planVersion());
            av.put("diverCode", a.diverCode());
            av.put("phase", a.phase());
            av.put("rateMMin", a.rateMMin().toPlainString());
            return av;
        }).toList());

        // 台账复算：以每个生效 BOTTOM 事件时刻为窗口右端（含端点），重算 24 小时滚动累计
        List<Map<String, Object>> ledgerViews = new ArrayList<>();
        for (RecordRow r : records.findAllByDive(diveRequestId)) {
            if (!Phase.BOTTOM.name().equals(r.phase()) || !r.effective()) {
                continue;
            }
            Instant end = r.eventTimeUtc();
            List<LedgerRow> window = records.findLedgerWindowInclusive(r.diverCode(),
                    Timestamp.from(end.minus(Duration.ofHours(24))), Timestamp.from(end));
            int sum = window.stream().mapToInt(LedgerRow::bottomMin).sum();
            Map<String, Object> lv = new LinkedHashMap<>();
            lv.put("diverCode", r.diverCode());
            lv.put("eventTimeUtc", end.toString());
            lv.put("windowBottomMin", sum);
            lv.put("withinLimit", sum <= MAX_BOTTOM_MIN_24H);
            ledgerViews.add(lv);
        }
        view.put("cumulativeRecompute", ledgerViews);

        view.put("events", events.findByDive(diveRequestId).stream().map(e -> {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", e.type());
            ev.put("payload", e.payload());
            ev.put("createdAtUtc", e.createdAtUtc().toString());
            return ev;
        }).toList());
        // 反复潜水虚构积分控制（受控方案冻结项、出水结算、历史更正差异）
        view.putAll(controlled.controlledRecompute(dive));
        return view;
    }

    private DiveRow requireDive(String requestId) {
        return dives.find(requestId).orElseThrow(() ->
                new ApiException(404, "潜次不存在: " + requestId));
    }

    private static String teamKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
