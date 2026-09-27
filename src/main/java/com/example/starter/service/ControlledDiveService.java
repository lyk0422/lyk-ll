package com.example.starter.service;

import com.example.starter.dao.ControlledFreezeRepository;
import com.example.starter.dao.ControlledFreezeRow;
import com.example.starter.dao.CatalogRepository;
import com.example.starter.dao.DiveEventRepository;
import com.example.starter.dao.DiveRepository;
import com.example.starter.dao.DiveRow;
import com.example.starter.dao.DiverSlotRepository;
import com.example.starter.dao.PlanRepository;
import com.example.starter.dao.PlanRow;
import com.example.starter.dao.PointsAccountRepository;
import com.example.starter.dao.PointsAccountRow;
import com.example.starter.dao.RecordCorrectionRepository;
import com.example.starter.dao.RecordCorrectionRow;
import com.example.starter.dao.RecordRepository;
import com.example.starter.dao.RecordRow;
import com.example.starter.domain.EventTypes;
import com.example.starter.domain.PointsRules;
import com.example.starter.domain.PointsRules.PointsRejectedException;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ControlledApplyRequest;
import com.example.starter.web.dto.ControlledPlanRequest;
import com.example.starter.web.dto.ControlledResults;
import com.example.starter.web.dto.ControlledResults.ControlledApplyResult;
import com.example.starter.web.dto.ControlledResults.ControlledPlanResult;
import com.example.starter.web.dto.ControlledResults.ControlledStartResult;
import com.example.starter.web.dto.ControlledResults.ControlledSurfaceResult;
import com.example.starter.web.dto.ControlledResults.CorrectionResult;
import com.example.starter.web.dto.ControlledResults.DiverPointsView;
import com.example.starter.web.dto.ControlledStartRequest;
import com.example.starter.web.dto.ControlledSurfaceRequest;
import com.example.starter.web.dto.RecordCorrectionRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 受控反复潜水服务：在旧流程之外新增积分控制流程（<b>虚构软件验证模型，不用于实际减压</b>）。
 *
 * <p>流程：受控申请 → 受控方案（积分/间隔/观察/24h 门禁并冻结每人来源）→ 实际下潜（重查来源、
 * 占潜水员级槽）→ 受控出水（原子更新两人积分账、台账与潜次记录）→ 复用旧的观察体征/结案。</p>
 *
 * <p>所有写操作经 {@link IdempotencyService}：业务、版本与幂等结果同事务；同键同参重放首次冻结结果，
 * 同键异参 409，失败不占键。</p>
 */
@Service
public class ControlledDiveService {

    private final DiveRepository dives;
    private final PlanRepository plans;
    private final CatalogRepository catalog;
    private final PointsAccountRepository accounts;
    private final ControlledFreezeRepository freezes;
    private final DiverSlotRepository slots;
    private final RecordRepository records;
    private final RecordCorrectionRepository corrections;
    private final DiveEventRepository events;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ControlledDiveService(DiveRepository dives, PlanRepository plans, CatalogRepository catalog,
                                 PointsAccountRepository accounts, ControlledFreezeRepository freezes,
                                 DiverSlotRepository slots, RecordRepository records,
                                 RecordCorrectionRepository corrections, DiveEventRepository events,
                                 IdempotencyService idempotency, Clock clock) {
        this.dives = dives;
        this.plans = plans;
        this.catalog = catalog;
        this.accounts = accounts;
        this.freezes = freezes;
        this.slots = slots;
        this.records = records;
        this.corrections = corrections;
        this.events = events;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 1. 受控作业申请（points_controlled=TRUE；旧申请语义不变）
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ControlledApplyResult> controlledApply(ControlledApplyRequest req) {
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

        return idempotency.execute(req.requestId(), "CONTROLLED_APPLY", fields, () -> {
            Instant now = Instant.now(clock);
            String fingerprint = Fingerprints.sha256(withRequestId(fields, req.requestId()));
            DiveRow row = new DiveRow(req.requestId(), fingerprint, teamKey, teamKey,
                    req.diverACode(), req.diverBCode(), req.plannedDepthM(), req.plannedBottomMin(),
                    "APPLIED", 0, null, false, null, true, null, now, now);
            dives.insert(row);
            return IdempotencyService.FirstResult.created(new ControlledApplyResult(
                    req.requestId(), teamKey, req.diverACode(), req.diverBCode(),
                    req.plannedDepthM(), req.plannedBottomMin(), "APPLIED", true, false));
        });
    }

    // ------------------------------------------------------------------
    // 2. 受控方案：积分/间隔/观察/24h 门禁，冻结每人最近潜次版本、出水时刻、积分
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ControlledPlanResult> controlledPlan(ControlledPlanRequest req) {
        DiveRow dive = requireControlledDive(req.diveRequestId());
        Integer ndl = catalog.findNdlMinutes(req.depthM());
        if (ndl == null) {
            throw new ApiException(400, "合成减压表中无深度 " + req.depthM() + " 米的条目");
        }
        // 注意：expectedVersion 与阶段校验放在幂等动作内，保证同键重放先命中首次冻结结果，
        // 不会因版本已前进而误报 409。

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("depthM", req.depthM());
        fields.put("bottomMin", req.bottomMin());
        fields.put("plannedDescentAt", req.plannedDescentAt());

        return idempotency.execute(req.requestId(), "CONTROLLED_PLAN", fields, () -> {
            DiveRow latest = requireControlledDive(req.diveRequestId());
            if (req.expectedVersion() != latest.currentPlanVersion()) {
                throw new ApiException(409, "并发变更：方案版本已变化，请基于最新版本重提");
            }
            if (latest.currentPlanVersion() > 0
                    && !"APPLIED".equals(latest.status()) && !"PLANNED".equals(latest.status())) {
                throw new ApiException(409, "潜次已实际下潜，方案不可变更");
            }
            Instant now = Instant.now(clock);
            int newVersion = latest.currentPlanVersion() + 1;
            // 乐观并发：行锁内仅当版本仍为期望版本才提升；并发变更者在此被精确拒绝（整体回滚）。
            if (plans.bumpDiveVersionIfMatch(req.diveRequestId(), req.expectedVersion(),
                    newVersion, now) == 0) {
                throw new ApiException(409, "并发变更：方案版本已变化，请基于最新版本重提");
            }
            int frozenOld = 0;
            if (newVersion > 1) {
                frozenOld = plans.freezeAll(req.diveRequestId());
                freezes.deleteByDive(req.diveRequestId());
                events.insert(req.diveRequestId(), EventTypes.PLAN_FROZEN,
                        "受控方案变更：冻结 v" + (newVersion - 1) + " 并重做积分冻结", now);
            }

            List<DiverPointsView> views = new ArrayList<>();
            int teamAllowed = Integer.MAX_VALUE;
            for (String diverCode : List.of(latest.diverACode(), latest.diverBCode())) {
                FrozenSource src = resolveSource(diverCode);
                // 间隔门禁：恰 60 分钟仅通过间隔门禁
                PointsRules.checkSurfaceInterval(src.latestSurfacedAt, req.plannedDescentAt());
                // 原 2 小时观察门禁：最近出水潜次必须已结案（观察完成）
                if (src.latest != null && !"CLOSED".equals(src.latest.status())) {
                    throw new ApiException(422, "潜水员 " + diverCode + " 上一潜次尚未完成 2 小时观察结案（"
                            + src.latest.status() + "），禁止再潜");
                }
                // 24 小时累计门禁：窗口内已入账 + 本次计划不超过 360 分钟（恰 360 合法）
                Timestamp windowStart = Timestamp.from(req.plannedDescentAt().minus(Duration.ofHours(24)));
                Timestamp windowEnd = Timestamp.from(req.plannedDescentAt());
                int existed = records.sumBottomMinutes(diverCode, windowStart, windowEnd);
                if (existed + req.bottomMin() > DiveService.MAX_BOTTOM_MIN_24H) {
                    throw new ApiException(422, "潜水员 " + diverCode + " 24 小时累计水下时间 "
                            + (existed + req.bottomMin()) + " 分钟超过上限 "
                            + DiveService.MAX_BOTTOM_MIN_24H + " 分钟，方案拒绝");
                }
                int currentPoints = PointsRules.currentPoints(
                        src.account == null ? 0 : src.account.points(),
                        src.account == null ? null : src.account.anchorSurfacedAt(),
                        req.plannedDescentAt());
                // 允许分钟 = NDL - 当前积分；<=0 拒绝
                int allowed = PointsRules.allowedBottomMinutes(ndl, currentPoints);
                // 出水残留积分上限 100：本次分钟 + 下潜前积分超过即拒绝
                int predictedExit = PointsRules.checkExitCap(currentPoints, req.bottomMin());
                teamAllowed = Math.min(teamAllowed, allowed);

                freezes.insert(new ControlledFreezeRow(null, req.diveRequestId(), newVersion,
                        diverCode, src.latest == null ? null : src.latest.requestId(),
                        src.latest == null ? 0 : src.latest.currentPlanVersion(),
                        src.latestSurfacedAt, currentPoints, req.plannedDescentAt(),
                        null, false, now));
                views.add(new DiverPointsView(diverCode,
                        src.latest == null ? null : src.latest.requestId(),
                        src.latest == null ? 0 : src.latest.currentPlanVersion(),
                        src.latestSurfacedAt == null ? null : src.latestSurfacedAt.toString(),
                        currentPoints, allowed, predictedExit));
            }

            // 两人分别计算，取较短允许时长；恰用尽合法
            if (!PointsRules.withinAllowed(req.bottomMin(), teamAllowed)) {
                throw new ApiException(422, "计划水下 " + req.bottomMin() + " 分钟超过两人较短允许时长 "
                        + teamAllowed + " 分钟，方案拒绝");
            }

            String profileJson = Profiles.buildJson(req.depthM(), req.bottomMin());
            String planFingerprint = Fingerprints.sha256(withRequestId(fields, req.requestId()));
            plans.insert(new PlanRow(null, req.diveRequestId(), newVersion, req.depthM(),
                    req.bottomMin(), ndl, DiveService.RATE_LIMIT, DiveService.RATE_LIMIT,
                    profileJson, false, planFingerprint, now));

            return IdempotencyService.FirstResult.created(new ControlledPlanResult(
                    req.diveRequestId(), newVersion, req.depthM(), req.bottomMin(), ndl,
                    teamAllowed, req.plannedDescentAt().toString(), List.copyOf(views),
                    frozenOld, false));
        });
    }

    // ------------------------------------------------------------------
    // 3. 实际下潜：重查冻结来源未变（旧方案不能消费新出的积分），占潜水员级槽
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ControlledStartResult> controlledStart(ControlledStartRequest req) {
        requireControlledDive(req.diveRequestId());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("actualDescentAt", req.actualDescentAt() == null ? null : req.actualDescentAt().toString());

        return idempotency.execute(req.requestId(), "CONTROLLED_START", fields, () -> {
            DiveRow latest = requireControlledDive(req.diveRequestId());
            if (req.expectedVersion() != latest.currentPlanVersion() || latest.currentPlanVersion() == 0) {
                throw new ApiException(409, "期望版本 " + req.expectedVersion() + " 与当前方案版本 "
                        + latest.currentPlanVersion() + " 不一致，旧方案不能下潜，请重做方案");
            }
            if (!"PLANNED".equals(latest.status()) || latest.controlledStartedAtUtc() != null) {
                throw new ApiException(409, "潜次处于 " + latest.status() + "，不能重复实际下潜");
            }
            Instant actualDescentAt = req.actualDescentAt() != null ? req.actualDescentAt()
                    : Instant.now(clock);
            Instant now = Instant.now(clock);

            List<ControlledFreezeRow> frozen = freezes.findByDiveForUpdate(req.diveRequestId());
            if (frozen.size() != 2 || frozen.stream().anyMatch(f -> f.version() != latest.currentPlanVersion())) {
                throw new ApiException(409, "方案冻结快照缺失或版本不符，请重做方案");
            }

            PlanRow plan = plans.getVersion(req.diveRequestId(), latest.currentPlanVersion());

            // 先重查每人来源未变（旧方案不能消费新出的积分）；任一不符即整体回滚、不占潜水员槽
            List<ControlledStartResult.StartedDiver> started = new ArrayList<>();
            for (ControlledFreezeRow f : sortedByDiver(frozen)) {
                FrozenSource src = resolveSource(f.diverCode());
                if (!eq(src.latest == null ? null : src.latest.requestId(), f.sourceDiveRequestId())
                        || (src.latest == null ? 0 : src.latest.currentPlanVersion()) != f.sourceVersion()
                        || !eq(src.latestSurfacedAt, f.sourceSurfacedAt())) {
                    throw new ApiException(409, "潜水员 " + f.diverCode()
                            + " 的最近出水来源在方案后已变化（新积分已产生），旧方案不能消费新积分，请重做方案");
                }
                PointsRules.checkSurfaceInterval(src.latestSurfacedAt, actualDescentAt);
                int pointsNow = PointsRules.currentPoints(
                        src.account == null ? 0 : src.account.points(),
                        src.account == null ? null : src.account.anchorSurfacedAt(),
                        actualDescentAt);
                int allowedNow = PointsRules.allowedBottomMinutes(plan.ndlLimitMin(), pointsNow);
                if (!PointsRules.withinAllowed(plan.bottomMin(), allowedNow)) {
                    throw new ApiException(422, "实际下潜时刻允许水下时长 " + allowedNow
                            + " 分钟不足计划 " + plan.bottomMin() + " 分钟，请重做方案");
                }
                started.add(new ControlledStartResult.StartedDiver(f.diverCode(), pointsNow));
            }

            // 来源未变后占两名组员的潜水员级槽：两组共享任一潜水员并发下潜，唯一索引保证最多一组成功
            List<String> divers = started.stream()
                    .map(ControlledStartResult.StartedDiver::diverCode).toList();
            try {
                for (String diverCode : divers) {
                    slots.occupy(diverCode, req.diveRequestId(), now);
                }
            } catch (DuplicateKeyException dup) {
                throw new ApiException(409, "共享潜水员已在另一组实际下潜中，并发下潜最多一组成功");
            }

            for (ControlledStartResult.StartedDiver sd : started) {
                freezes.markStarted(req.diveRequestId(), sd.diverCode(), sd.pointsAtStart());
            }

            dives.markControlledStarted(req.diveRequestId(), actualDescentAt, now);
            return IdempotencyService.FirstResult.ok(new ControlledStartResult(
                    req.diveRequestId(), latest.currentPlanVersion(), "DIVING",
                    actualDescentAt.toString(), List.copyOf(started), false));
        });
    }

    // ------------------------------------------------------------------
    // 4. 受控出水：同事务原子更新两人积分账、24h 台账、潜水员槽与本潜次记录
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ControlledSurfaceResult> controlledSurface(
            ControlledSurfaceRequest req) {
        requireControlledDive(req.diveRequestId());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("bottomMin", req.bottomMin());
        fields.put("surfacedAt", req.surfacedAt() == null ? null : req.surfacedAt().toString());

        return idempotency.execute(req.requestId(), "CONTROLLED_SURFACE", fields, () -> {
            DiveRow latest = requireControlledDive(req.diveRequestId());
            if (req.expectedVersion() != latest.currentPlanVersion()) {
                throw new ApiException(409, "期望版本 " + req.expectedVersion() + " 与当前方案版本 "
                        + latest.currentPlanVersion() + " 不一致");
            }
            if (!"DIVING".equals(latest.status()) || latest.controlledStartedAtUtc() == null) {
                throw new ApiException(409, "潜次处于 " + latest.status()
                        + "，尚未实际下潜，不能登记受控出水");
            }
            Instant surfacedAt = req.surfacedAt() != null ? req.surfacedAt() : Instant.now(clock);
            if (surfacedAt.isBefore(latest.controlledStartedAtUtc())) {
                throw new ApiException(400, "出水时刻不能早于实际下潜时刻");
            }
            Instant now = Instant.now(clock);

            List<ControlledFreezeRow> frozen = freezes.findByDiveForUpdate(req.diveRequestId());
            if (frozen.size() != 2 || frozen.stream().anyMatch(f -> f.version() != latest.currentPlanVersion()
                    || !f.started() || f.pointsAtStart() == null)) {
                throw new ApiException(409, "下潜冻结状态缺失或未完成实际下潜重查，不能出水");
            }

            List<ControlledSurfaceResult.SettledDiver> settled = new ArrayList<>();
            List<String> divers = List.of(latest.diverACode(), latest.diverBCode()).stream()
                    .sorted().toList();
            for (String diverCode : divers) {
                ControlledFreezeRow f = frozen.stream()
                        .filter(x -> x.diverCode().equals(diverCode)).findFirst().orElseThrow();
                // 行锁两人积分账后再结算，串行化同潜水员并发出水
                PointsAccountRow account = accounts.findForUpdate(diverCode).orElse(null);
                int pointsBefore = f.pointsAtStart();
                // 24h 台账门禁（按实际底时间复算，恰 360 合法）
                Timestamp windowStart = Timestamp.from(latest.controlledStartedAtUtc().minus(Duration.ofHours(24)));
                Timestamp windowEnd = Timestamp.from(latest.controlledStartedAtUtc());
                int existed = records.sumBottomMinutes(diverCode, windowStart, windowEnd);
                if (existed + req.bottomMin() > DiveService.MAX_BOTTOM_MIN_24H) {
                    throw new ApiException(422, "潜水员 " + diverCode + " 24 小时累计水下时间 "
                            + (existed + req.bottomMin()) + " 分钟超过上限，出水拒绝且积分不更新");
                }
                // 出水残留积分 = 下潜前积分 + 本次整数水下分钟，上限 100，超过整体回滚
                int exitPoints = PointsRules.checkExitCap(pointsBefore, req.bottomMin());
                accounts.upsertAtExit(diverCode, exitPoints, surfacedAt, req.diveRequestId(), now);
                if (!records.ledgerExists(req.diveRequestId(), latest.currentPlanVersion(), diverCode)) {
                    records.insertLedger(new com.example.starter.dao.LedgerRow(null, diverCode,
                            req.diveRequestId(), latest.currentPlanVersion(), req.bottomMin(),
                            latest.controlledStartedAtUtc()));
                }
                settled.add(new ControlledSurfaceResult.SettledDiver(diverCode, pointsBefore, exitPoints));
            }

            dives.markSurfaced(req.diveRequestId(), surfacedAt, "SURFACED", now);
            slots.releaseByDive(req.diveRequestId());
            Instant due = surfacedAt.plus(DiveService.OBSERVATION);
            return IdempotencyService.FirstResult.ok(new ControlledSurfaceResult(
                    req.diveRequestId(), "SURFACED", surfacedAt.toString(), due.toString(),
                    req.bottomMin(), List.copyOf(settled), false));
        });
    }

    // ------------------------------------------------------------------
    // 5. 历史水下记录更正：不追改已关闭潜次，另记独立差异（不碰积分账/台账）
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<CorrectionResult> correct(RecordCorrectionRequest req) {
        if (!"DESCENT".equals(req.phase()) && !"BOTTOM".equals(req.phase()) && !"ASCENT".equals(req.phase())) {
            throw new ApiException(400, "phase 必须为 DESCENT/BOTTOM/ASCENT");
        }
        DiveRow dive = requireDive(req.diveRequestId());
        if (!"CLOSED".equals(dive.status())) {
            throw new ApiException(409, "仅可更正已关闭的历史潜次，当前为 " + dive.status()
                    + "，在案潜次不允许追改");
        }
        if (req.expectedVersion() != dive.currentPlanVersion()) {
            throw new ApiException(409, "期望版本 " + req.expectedVersion() + " 与潜次版本 "
                    + dive.currentPlanVersion() + " 不一致");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("diverCode", req.diverCode());
        fields.put("phase", req.phase());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("correctedDurationMin", req.correctedDurationMin());

        return idempotency.execute(req.requestId(), "CORRECT", fields, () -> {
            RecordRow target = records.findEffective(req.diveRequestId(), req.diverCode(), req.phase())
                    .orElseThrow(() -> new ApiException(404, "未找到潜水员 " + req.diverCode()
                            + " 在潜次 " + req.diveRequestId() + " 的生效 " + req.phase() + " 记录"));
            Instant now = Instant.now(clock);
            int delta = req.correctedDurationMin() - target.durationMin();
            String fingerprint = Fingerprints.sha256(withRequestId(fields, req.requestId()));
            corrections.insert(new RecordCorrectionRow(req.requestId(), req.diveRequestId(),
                    req.diverCode(), req.phase(), target.durationMin(), req.correctedDurationMin(),
                    delta, fingerprint, now));
            return IdempotencyService.FirstResult.ok(new CorrectionResult(
                    req.requestId(), req.diveRequestId(), req.diverCode(), req.phase(),
                    target.durationMin(), req.correctedDurationMin(), delta, false));
        });
    }

    // ------------------------------------------------------------------
    // 查询：受控积分视图与潜水员积分账
    // ------------------------------------------------------------------

    /** 列出某潜次的全部历史记录更正独立差异（不区分受控/旧语义，404 当潜次不存在）。 */
    public List<Map<String, Object>> listCorrections(String diveRequestId) {
        requireDive(diveRequestId);
        return corrections.findByDive(diveRequestId).stream().map(c -> {
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("requestId", c.requestId());
            cv.put("diveRequestId", c.diveRequestId());
            cv.put("diverCode", c.diverCode());
            cv.put("phase", c.phase());
            cv.put("recordedDurationMin", c.recordedDurationMin());
            cv.put("correctedDurationMin", c.correctedDurationMin());
            cv.put("deltaMin", c.deltaMin());
            cv.put("createdAtUtc", c.createdAtUtc().toString());
            return cv;
        }).toList();
    }

    /** 受控潜次积分视图：冻结快照、两人当前（按时钟消减后）积分与占用状态。 */
    public Map<String, Object> controlledPointsView(String diveRequestId) {
        DiveRow dive = requireControlledDive(diveRequestId);
        Instant now = Instant.now(clock);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("requestId", dive.requestId());
        view.put("status", dive.status());
        view.put("pointsControlled", true);
        view.put("currentPlanVersion", dive.currentPlanVersion());
        view.put("controlledStartedAtUtc", dive.controlledStartedAtUtc() == null ? null
                : dive.controlledStartedAtUtc().toString());
        view.put("surfacedAtUtc", dive.surfacedAtUtc() == null ? null : dive.surfacedAtUtc().toString());
        List<Map<String, Object>> diverViews = new ArrayList<>();
        for (ControlledFreezeRow f : freezes.findByDive(diveRequestId)) {
            PointsAccountRow acc = accounts.find(f.diverCode()).orElse(null);
            int currentNow = PointsRules.currentPoints(acc == null ? 0 : acc.points(),
                    acc == null ? null : acc.anchorSurfacedAt(), now);
            Map<String, Object> dv = new LinkedHashMap<>();
            dv.put("diverCode", f.diverCode());
            dv.put("freezeVersion", f.version());
            dv.put("pointsAtFreeze", f.pointsAtFreeze());
            dv.put("pointsAtStart", f.pointsAtStart());
            dv.put("started", f.started());
            dv.put("sourceDiveRequestId", f.sourceDiveRequestId());
            dv.put("sourceVersion", f.sourceVersion());
            dv.put("sourceSurfacedAtUtc",
                    f.sourceSurfacedAt() == null ? null : f.sourceSurfacedAt().toString());
            dv.put("accountPoints", acc == null ? 0 : acc.points());
            dv.put("currentPointsAtClock", currentNow);
            diverViews.add(dv);
        }
        view.put("divers", diverViews);
        view.put("corrections", corrections.findByDive(diveRequestId).stream().map(c -> {
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("requestId", c.requestId());
            cv.put("diverCode", c.diverCode());
            cv.put("phase", c.phase());
            cv.put("recordedDurationMin", c.recordedDurationMin());
            cv.put("correctedDurationMin", c.correctedDurationMin());
            cv.put("deltaMin", c.deltaMin());
            return cv;
        }).toList());
        return view;
    }

    /** 单个潜水员积分账查询：账面积分、最近出水锚点与按当前时钟消减后的积分。 */
    public Map<String, Object> diverPointsView(String diverCode) {
        if (!catalog.diverExists(diverCode)) {
            throw new ApiException(404, "潜水员不在合成名册中: " + diverCode);
        }
        Instant now = Instant.now(clock);
        Optional<PointsAccountRow> accOpt = accounts.find(diverCode);
        Optional<DiveRow> latest = dives.findLatestControlledSurfaced(diverCode);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("diverCode", diverCode);
        view.put("residualPoints", accOpt.map(PointsAccountRow::points).orElse(0));
        view.put("anchorSurfacedAtUtc", accOpt.isPresent() && accOpt.get().anchorSurfacedAt() != null
                ? accOpt.get().anchorSurfacedAt().toString() : null);
        view.put("lastDiveRequestId", accOpt.map(PointsAccountRow::lastDiveRequestId).orElse(null));
        view.put("currentPointsAtClock", PointsRules.currentPoints(
                accOpt.map(PointsAccountRow::points).orElse(0),
                accOpt.map(PointsAccountRow::anchorSurfacedAt).orElse(null), now));
        view.put("latestSurfacedDiveRequestId", latest.map(DiveRow::requestId).orElse(null));
        view.put("latestSurfacedAtUtc", latest.isPresent() && latest.get().surfacedAtUtc() != null
                ? latest.get().surfacedAtUtc().toString() : null);
        view.put("latestDiveStatus", latest.map(DiveRow::status).orElse(null));
        return view;
    }

    // ------------------------------------------------------------------

    /** 方案冻结来源：最近已确认出水的受控潜次 + 出水时刻 + 积分账。 */
    private FrozenSource resolveSource(String diverCode) {
        DiveRow latest = dives.findLatestControlledSurfaced(diverCode).orElse(null);
        Instant latestSurfacedAt = latest == null ? null : latest.surfacedAtUtc();
        PointsAccountRow account = accounts.find(diverCode).orElse(null);
        return new FrozenSource(latest, latestSurfacedAt, account);
    }

    private record FrozenSource(DiveRow latest, Instant latestSurfacedAt, PointsAccountRow account) {
    }

    private DiveRow requireDive(String requestId) {
        return dives.find(requestId).orElseThrow(() ->
                new ApiException(404, "潜次不存在: " + requestId));
    }

    private DiveRow requireControlledDive(String requestId) {
        DiveRow dive = requireDive(requestId);
        if (!dive.pointsControlled()) {
            throw new ApiException(422, "潜次 " + requestId + " 未启用反复潜水积分控制，保持旧语义");
        }
        return dive;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static List<ControlledFreezeRow> sortedByDiver(List<ControlledFreezeRow> rows) {
        List<ControlledFreezeRow> copy = new ArrayList<>(rows);
        copy.sort((x, y) -> x.diverCode().compareTo(y.diverCode()));
        return copy;
    }

    private static String teamKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static Map<String, Object> withRequestId(Map<String, Object> fields, String requestId) {
        Map<String, Object> all = new java.util.TreeMap<>(fields);
        all.put("requestId", requestId);
        return all;
    }
}
