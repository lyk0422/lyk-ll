package com.example.starter.service;

import com.example.starter.dao.DiveRepository;
import com.example.starter.dao.DiveRow;
import com.example.starter.dao.DiverActiveSlotRepository;
import com.example.starter.dao.PlanRepository;
import com.example.starter.dao.PlanRow;
import com.example.starter.dao.RecordCorrectionRepository;
import com.example.starter.dao.RecordCorrectionRow;
import com.example.starter.dao.RecordRepository;
import com.example.starter.dao.RecordRow;
import com.example.starter.dao.ResidualAccountRepository;
import com.example.starter.dao.ResidualAccountRow;
import com.example.starter.dao.ResidualMovementRepository;
import com.example.starter.dao.ResidualMovementRow;
import com.example.starter.dao.ResidualSnapshotRepository;
import com.example.starter.dao.ResidualSnapshotRow;
import com.example.starter.dao.CatalogRepository;
import com.example.starter.domain.EventTypes;
import com.example.starter.support.Fingerprints;
import com.example.starter.support.ResidualRules;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ControlledPlanRequest;
import com.example.starter.web.dto.ControlledSurfaceRequest;
import com.example.starter.web.dto.RecordCorrectionRequest;
import com.example.starter.web.dto.ResidualResult;
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

/**
 * 反复潜水余量积分联锁（虚构模型，仅供软件验证，不用于真实潜水减压）。
 *
 * <p>与旧入口并存：未启用积分控制的旧潜次保持原语义，本服务只服务受控潜次。</p>
 * <ul>
 *   <li>受控方案：逐人从最近确认出水时刻计算当前积分（水面每满 30 分钟消减 5 分），
 *       冻结每人最近潜次版本、出水时刻、积分与账户版本；允许分钟数=NDL-当前积分，
 *       全队取两人较小值；间隔/观察/24 小时累计门禁不通过即拒绝；</li>
 *   <li>实际下潜（DESCENT 成对）前重查来源未变并获取每人占用槽，否则重做方案；</li>
 *   <li>受控出水：单事务原子更新两人积分账户、积分流水、潜次状态并释放占用槽；</li>
 *   <li>历史记录更正：只独立记差异，不追改已关闭潜次。</li>
 * </ul>
 */
@Service
public class ResidualService {

    private final DiveRepository dives;
    private final PlanRepository plans;
    private final RecordRepository records;
    private final CatalogRepository catalog;
    private final ResidualAccountRepository accounts;
    private final ResidualSnapshotRepository snapshots;
    private final DiverActiveSlotRepository slots;
    private final ResidualMovementRepository movements;
    private final RecordCorrectionRepository corrections;
    private final com.example.starter.dao.DiveEventRepository eventRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ResidualService(DiveRepository dives, PlanRepository plans, RecordRepository records,
                           CatalogRepository catalog, ResidualAccountRepository accounts,
                           ResidualSnapshotRepository snapshots,
                           DiverActiveSlotRepository slots,
                           ResidualMovementRepository movements,
                           RecordCorrectionRepository corrections,
                           com.example.starter.dao.DiveEventRepository eventRepository,
                           IdempotencyService idempotency, Clock clock) {
        this.dives = dives;
        this.plans = plans;
        this.records = records;
        this.catalog = catalog;
        this.accounts = accounts;
        this.snapshots = snapshots;
        this.slots = slots;
        this.movements = movements;
        this.corrections = corrections;
        this.eventRepository = eventRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 1. 受控方案：积分计算 + 来源冻结 + 三重门禁
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ResidualResult.Plan> controlledPlan(ControlledPlanRequest req) {
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
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("planAt", req.planAt() == null ? null : req.planAt().toString());

        return idempotency.executeStructured(req.requestId(), "RPLAN", fields, () -> {
            // 状态相关门禁只在首次受理时执行：同键同参重放直接返回首次冻结结果，
            // 不会因潜次后续流转或版本推进而误判
            DiveRow latest = requireDive(req.diveRequestId());
            rejectLegacyOrAdvanced(latest);
            if (latest.currentPlanVersion() != req.expectedVersion()) {
                throw new ApiException(409, "expectedVersion=" + req.expectedVersion()
                        + " 与潜次当前方案版本 " + latest.currentPlanVersion()
                        + " 不一致，请基于最新版本重做受控方案");
            }
            Instant now = Instant.now(clock);
            Instant planAt = req.planAt() != null ? req.planAt() : now;
            int newVersion = latest.currentPlanVersion() + 1;

            // 先逐人完成全部计算与门禁校验，任何拒绝都在冻结/写入之前抛出，整体不留痕
            List<ResidualSnapshotRow> snapshotRows = new ArrayList<>(2);
            List<ResidualResult.DiverPoints> diverViews = new ArrayList<>(2);
            int teamAllowed = Integer.MAX_VALUE;
            for (String diver : List.of(latest.diverACode(), latest.diverBCode())) {
                ComputedSource src = computeSource(diver, req.depthM(), ndl, req.bottomMin(), planAt);
                teamAllowed = Math.min(teamAllowed, src.allowedMinutes);
                snapshotRows.add(new ResidualSnapshotRow(null, req.diveRequestId(), newVersion, diver,
                        src.sourceDive, src.sourcePlanVersion, src.sourceSurfacedAt,
                        src.sourceResidual, src.sourceAccountVersion, src.currentPoints,
                        ndl, src.allowedMinutes, now));
                diverViews.add(new ResidualResult.DiverPoints(diver, src.firstDive,
                        src.sourceDive, src.sourcePlanVersion,
                        src.sourceSurfacedAt == null ? null : src.sourceSurfacedAt.toString(),
                        src.sourceResidual, src.intervalMinutes, src.currentPoints, ndl,
                        src.allowedMinutes, src.intervalPassed, src.observationPassed,
                        src.cumulativePassed));
            }

            if (req.bottomMin() > teamAllowed) {
                throw new ApiException(422, "方案底时间 " + req.bottomMin()
                        + " 分钟超过全队较短允许时长 " + teamAllowed
                        + " 分钟（NDL " + ndl + " 减去当前积分；恰用尽合法）");
            }

            if (newVersion > 1) {
                // 方案变更重做：旧方案、记录、台账与积分快照同事务清退冻结
                plans.freezeAll(req.diveRequestId());
                records.freezeByDive(req.diveRequestId());
                records.deleteLedgerByDive(req.diveRequestId());
                snapshots.deleteByDive(req.diveRequestId());
                insertEvent(req.diveRequestId(),
                        "受控方案变更：冻结 v" + (newVersion - 1) + " 及其水下记录与积分快照", now);
            }

            String profileJson = Profiles.buildJson(req.depthM(), req.bottomMin());
            String planFingerprint = Fingerprints.sha256Structured(
                    new java.util.TreeMap<>(fields) {{
                        put("requestId", req.requestId());
                    }});
            plans.insert(new PlanRow(null, req.diveRequestId(), newVersion, req.depthM(),
                    req.bottomMin(), ndl, DiveService.RATE_LIMIT, DiveService.RATE_LIMIT,
                    profileJson, false, planFingerprint, now));
            for (ResidualSnapshotRow row : snapshotRows) {
                snapshots.insert(row);
            }
            plans.bumpDiveVersion(req.diveRequestId(), newVersion, now);
            dives.markControlled(req.diveRequestId(), now);

            return IdempotencyService.FirstResult.created(new ResidualResult.Plan(
                    req.diveRequestId(), newVersion, req.depthM(), req.bottomMin(), ndl,
                    teamAllowed, diverViews, false));
        });
    }

    /** 单人积分来源计算与门禁；任一业务门禁失败抛 422，参数时刻非法抛 400。 */
    private ComputedSource computeSource(String diver, int depthM, int ndl, int plannedBottomMin,
                                         Instant planAt) {
        ComputedSource c = new ComputedSource();
        Timestamp windowStart = Timestamp.from(planAt.minus(Duration.ofHours(24)));
        int used24h = records.sumBottomMinutes(diver, windowStart, Timestamp.from(planAt));
        c.cumulativePassed = used24h + plannedBottomMin <= DiveService.MAX_BOTTOM_MIN_24H;
        c.projected24h = used24h + plannedBottomMin;

        var accountOpt = accounts.find(diver);
        if (accountOpt.isEmpty()) {
            // 首潜：下潜前积分 0，无间隔/观察门禁（仍受 24 小时累计门禁约束）
            c.firstDive = true;
            c.currentPoints = 0;
            c.intervalPassed = true;
            c.observationPassed = true;
            c.intervalMinutes = -1;
        } else {
            ResidualAccountRow acc = accountOpt.get();
            c.firstDive = false;
            c.sourceDive = acc.sourceDiveRequestId();
            c.sourcePlanVersion = acc.sourcePlanVersion();
            c.sourceSurfacedAt = acc.lastSurfacedAtUtc();
            c.sourceResidual = acc.residualPoints();
            c.sourceAccountVersion = acc.version();
            if (planAt.isBefore(acc.lastSurfacedAtUtc())) {
                throw new ApiException(400, "方案时刻早于潜水员 " + diver + " 最近确认出水时刻 "
                        + acc.lastSurfacedAtUtc());
            }
            Duration surface = Duration.between(acc.lastSurfacedAtUtc(), planAt);
            c.intervalMinutes = surface.toMinutes();
            c.intervalPassed = c.intervalMinutes >= ResidualRules.MIN_SURFACE_INTERVAL_MIN;
            c.observationPassed = !planAt.isBefore(
                    acc.lastSurfacedAtUtc().plus(DiveService.OBSERVATION));
            c.currentPoints = ResidualRules.decayedPoints(acc.residualPoints(), surface);
        }

        c.allowedMinutes = ResidualRules.allowedMinutes(ndl, c.currentPoints);

        if (!c.intervalPassed) {
            throw new ApiException(422, "潜水员 " + diver + " 距上次确认出水仅 "
                    + c.intervalMinutes + " 分钟，不足 60 分钟禁止再潜（恰 60 分钟仅通过间隔门禁）");
        }
        if (!c.observationPassed) {
            throw new ApiException(422, "潜水员 " + diver + " 上次出水后 2 小时观察尚未完成，"
                    + "恰 60 分钟间隔不能替代观察门禁");
        }
        if (!c.cumulativePassed) {
            throw new ApiException(422, "潜水员 " + diver + " 24 小时累计水下时间将达 "
                    + c.projected24h + " 分钟，超过上限 "
                    + DiveService.MAX_BOTTOM_MIN_24H + " 分钟");
        }
        if (ResidualRules.exceedsCap(c.currentPoints, plannedBottomMin)) {
            throw new ApiException(422, "潜水员 " + diver + " 出水残留积分将为 "
                    + (c.currentPoints + plannedBottomMin) + "，超过上限 "
                    + ResidualRules.MAX_POINTS + "，方案拒绝");
        }
        if (ResidualRules.noAllowedTime(ndl, c.currentPoints)) {
            throw new ApiException(422, "潜水员 " + diver + " 当前积分 " + c.currentPoints
                    + "，" + depthM + " 米允许分钟数 " + c.allowedMinutes
                    + " 小于等于 0，拒绝新方案");
        }
        return c;
    }

    private void rejectLegacyOrAdvanced(DiveRow dive) {
        if (!dive.controlled() && dive.currentPlanVersion() > 0) {
            throw new ApiException(409, "潜次 " + dive.requestId()
                    + " 是未启用积分控制的旧潜次，保持原语义，请使用原方案入口");
        }
        if ("DIVING".equals(dive.status()) || "SURFACED".equals(dive.status())
                || "CLOSED".equals(dive.status()) || "FROZEN".equals(dive.status())) {
            throw new ApiException(409, "潜次已进入" + dive.status()
                    + "阶段，受控方案不可变更");
        }
    }

    // ------------------------------------------------------------------
    // 2. 实际下潜前门禁：重查来源未变 + 获取每人占用槽（由 DiveService 成对时调用）
    // ------------------------------------------------------------------

    /**
     * DESCENT 成对生效前在同事务内调用：逐人重查冻结来源（潜次版本、出水时刻、账户版本）
     * 未变化，并重算间隔/观察/24 小时门禁；随后获取两人的下潜占用槽。
     * 来源已变抛 409（须重做方案）；共享潜水员被占用时唯一索引拒绝（409）。
     */
    public void verifyDescentAndAcquireSlots(DiveRow dive, PlanRow plan,
                                             RecordRow first, RecordRow second, Instant now) {
        if (!dive.controlled()) {
            return;
        }
        RecordRow[] pair = {first, second};
        for (RecordRow rec : pair) {
            ResidualSnapshotRow snap = snapshots.get(dive.requestId(), plan.version(),
                    rec.diverCode());
            var accountOpt = accounts.find(rec.diverCode());
            if (snap.sourceAccountVersion() == null) {
                if (accountOpt.isPresent()) {
                    throw new ApiException(409, "潜水员 " + rec.diverCode()
                            + " 在方案冻结后已产生新的受控出水积分，来源已变，请重做方案后再下潜");
                }
            } else {
                ResidualAccountRow acc = accountOpt.orElseThrow(() -> new ApiException(409,
                        "潜水员 " + rec.diverCode() + " 的冻结积分来源账户缺失，请重做方案"));
                if (acc.version() != snap.sourceAccountVersion()
                        || !acc.lastSurfacedAtUtc().equals(snap.sourceSurfacedAtUtc())) {
                    throw new ApiException(409, "潜水员 " + rec.diverCode()
                            + " 的最近潜次版本或出水时刻已变化（冻结 v"
                            + snap.sourceAccountVersion() + " / " + snap.sourceSurfacedAtUtc()
                            + "，当前 v" + acc.version() + " / " + acc.lastSurfacedAtUtc()
                            + "），旧方案不能消费新出的积分，请重做方案");
                }
            }
            // 以实际下潜记录时刻重算时间门禁（间隔只可能更宽松，但来源变化已在上面拦截）
            Instant descentAt = rec.eventTimeUtc();
            if (snap.sourceSurfacedAtUtc() != null) {
                long minutes = Duration.between(snap.sourceSurfacedAtUtc(), descentAt).toMinutes();
                if (minutes < ResidualRules.MIN_SURFACE_INTERVAL_MIN) {
                    throw new ApiException(422, "潜水员 " + rec.diverCode()
                            + " 实际下潜时刻距上次出水 " + minutes + " 分钟，不足 60 分钟");
                }
                if (descentAt.isBefore(snap.sourceSurfacedAtUtc().plus(DiveService.OBSERVATION))) {
                    throw new ApiException(422, "潜水员 " + rec.diverCode()
                            + " 上次出水 2 小时观察未完成即下潜，拒绝");
                }
            }
            Timestamp windowStart = Timestamp.from(descentAt.minus(Duration.ofHours(24)));
            int used = records.sumBottomMinutes(rec.diverCode(), windowStart,
                    Timestamp.from(descentAt));
            if (used + plan.bottomMin() > DiveService.MAX_BOTTOM_MIN_24H) {
                throw new ApiException(422, "潜水员 " + rec.diverCode()
                        + " 下潜前 24 小时累计 " + used + " 分钟，加本次 " + plan.bottomMin()
                        + " 分钟超过上限 " + DiveService.MAX_BOTTOM_MIN_24H);
            }
        }
        // 按工号升序加锁占用，避免两组互相等待死锁；唯一索引保证共享潜水员最多一组成功
        for (String diver : List.of(dive.diverACode(), dive.diverBCode()).stream().sorted().toList()) {
            try {
                slots.acquire(diver, dive.requestId(), plan.version(), now);
            } catch (DuplicateKeyException dup) {
                throw new ApiException(409, "潜水员 " + diver
                        + " 已在另一受控潜次中下潜，两组共享潜水员并发下潜最多一组成功");
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. 受控出水：原子更新两人积分账与本潜次记录，释放占用槽
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ResidualResult.Surface> controlledSurface(
            ControlledSurfaceRequest req) {
        DiveRow dive = requireDive(req.diveRequestId());
        if (!dive.controlled()) {
            throw new ApiException(409, "潜次 " + req.diveRequestId()
                    + " 未启用积分控制，请使用原出水登记入口");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("surfacedAt", req.surfacedAt() == null ? null : req.surfacedAt().toString());

        return idempotency.executeStructured(req.requestId(), "RSURFACE", fields, () -> {
            // 状态/版本门禁仅首次受理执行，重放返回首次冻结结果
            DiveRow latest = requireDive(req.diveRequestId());
            if (!"DIVING".equals(latest.status())) {
                throw new ApiException(409, "潜次处于 " + latest.status()
                        + " 阶段，不能登记受控出水（须先成对执行）");
            }
            if (latest.currentPlanVersion() != req.expectedVersion()) {
                throw new ApiException(409, "expectedVersion=" + req.expectedVersion()
                        + " 与潜次当前方案版本 " + latest.currentPlanVersion() + " 不一致");
            }
            Instant now = Instant.now(clock);
            Instant surfacedAt = req.surfacedAt() != null ? req.surfacedAt() : now;
            int version = latest.currentPlanVersion();

            List<RecordRow> bottoms = records.findEffectiveBottom(req.diveRequestId(), version);
            List<ResidualResult.SurfaceDiver> diverViews = new ArrayList<>(2);
            for (String diver : List.of(latest.diverACode(), latest.diverBCode())) {
                ResidualSnapshotRow snap = snapshots.get(req.diveRequestId(), version, diver);
                RecordRow bottom = bottoms.stream()
                        .filter(r -> r.diverCode().equals(diver)).findFirst()
                        .orElseThrow(() -> new ApiException(409,
                                "潜水员 " + diver + " 缺少成对生效的水底记录，不能登记出水"));
                // 实际水下分钟以成对生效的 BOTTOM 记录为准：不得超过方案冻结的允许分钟数，
                // 且加下潜前积分不得超过 100，否则出水整笔回滚（防止方案冻结小分钟而实际超量）
                if (bottom.durationMin() > snap.allowedMinutes()) {
                    throw new ApiException(422, "潜水员 " + diver + " 实际水下 "
                            + bottom.durationMin() + " 分钟超过受控方案冻结的允许分钟数 "
                            + snap.allowedMinutes() + "，出水拒绝且积分账不更新");
                }
                if (snap.frozenPoints() + bottom.durationMin() > ResidualRules.MAX_POINTS) {
                    throw new ApiException(422, "潜水员 " + diver + " 出水残留积分将为 "
                            + (snap.frozenPoints() + bottom.durationMin()) + "，超过上限 "
                            + ResidualRules.MAX_POINTS + "，出水拒绝且积分账不更新");
                }

                // 行锁 + 冻结来源版本双重校验：占用槽保证方案后账户不可能被他人更新，
                // 命中 0 行说明来源已变，整笔出水回滚（旧方案不能消费新出的积分）
                ResidualAccountRow locked = accounts.findForUpdate(diver).orElse(null);
                int residual = ResidualRules.exitResidual(snap.frozenPoints(),
                        bottom.durationMin());
                int newAccountVersion;
                if (snap.sourceAccountVersion() == null) {
                    if (locked != null) {
                        throw new ApiException(409, "潜水员 " + diver
                                + " 的积分来源在本潜次期间已变化，请重做方案后重新出水");
                    }
                    newAccountVersion = 1;
                    accounts.insert(new ResidualAccountRow(diver, surfacedAt, residual,
                            req.diveRequestId(), version, newAccountVersion, now, now));
                } else {
                    if (locked == null || locked.version() != snap.sourceAccountVersion()
                            || !locked.lastSurfacedAtUtc().equals(snap.sourceSurfacedAtUtc())) {
                        throw new ApiException(409, "潜水员 " + diver
                                + " 的冻结积分来源已被更新，旧方案不能消费新出的积分，请重做方案");
                    }
                    int hit = accounts.updateForSurface(diver, surfacedAt, residual,
                            req.diveRequestId(), version, locked.version(), now);
                    if (hit == 0) {
                        throw new ApiException(409, "潜水员 " + diver
                                + " 积分账户并发更新冲突，本次出水回滚");
                    }
                    newAccountVersion = locked.version() + 1;
                }
                try {
                    movements.insert(new ResidualMovementRow(null, diver, req.diveRequestId(),
                            version, snap.frozenPoints(), bottom.durationMin(), residual,
                            surfacedAt, now));
                } catch (DuplicateKeyException dup) {
                    throw new ApiException(409, "潜水员 " + diver
                            + " 本潜次积分流水已存在，不能重复出水记账");
                }
                diverViews.add(new ResidualResult.SurfaceDiver(diver, snap.frozenPoints(),
                        bottom.durationMin(), residual, newAccountVersion));
            }

            dives.markSurfaced(req.diveRequestId(), surfacedAt, "SURFACED", now);
            slots.releaseByDive(req.diveRequestId());
            Instant due = surfacedAt.plus(DiveService.OBSERVATION);
            return IdempotencyService.FirstResult.ok(new ResidualResult.Surface(
                    req.diveRequestId(), version, surfacedAt.toString(), due.toString(),
                    "SURFACED", diverViews, false));
        });
    }

    // ------------------------------------------------------------------
    // 4. 历史水下记录独立更正（不追改已关闭潜次）
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ResidualResult.Correction> correct(
            RecordCorrectionRequest req) {
        requireDive(req.diveRequestId());
        // 参数合法性/对象存在性可在幂等层外判定（同参重放结论一致）
        RecordRow target = records.findAllByDive(req.diveRequestId()).stream()
                .filter(r -> r.id() == req.recordId())
                .findFirst()
                .orElseThrow(() -> new ApiException(404,
                        "水下记录不存在或不属于潜次 " + req.diveRequestId() + ": id="
                                + req.recordId()));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("recordId", req.recordId());
        fields.put("correctedDurationMin", req.correctedDurationMin());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("correctedAt", req.correctedAt() == null ? null : req.correctedAt().toString());
        fields.put("reason", req.reason());

        return idempotency.executeStructured(req.requestId(), "CORRECT", fields, () -> {
            // expectedVersion 门禁仅首次受理执行：重放返回首次差异结果，不因版本推进而 409
            RecordRow latestTarget = records.getById(req.recordId());
            if (!latestTarget.diveRequestId().equals(req.diveRequestId())) {
                throw new ApiException(404, "水下记录不属于潜次 " + req.diveRequestId());
            }
            int seen = corrections.countByDive(req.diveRequestId());
            if (seen != req.expectedVersion()) {
                throw new ApiException(409, "expectedVersion=" + req.expectedVersion()
                        + " 与潜次当前更正版本 " + seen + " 不一致");
            }
            Instant now = Instant.now(clock);
            Instant correctedAt = req.correctedAt() != null ? req.correctedAt() : now;
            int newVersion = seen + 1;
            int delta = req.correctedDurationMin() - latestTarget.durationMin();
            long id;
            try {
                id = corrections.insert(new RecordCorrectionRow(null, req.diveRequestId(),
                        req.recordId(), latestTarget.planVersion(), latestTarget.diverCode(),
                        latestTarget.phase(), latestTarget.durationMin(),
                        req.correctedDurationMin(), delta, req.reason(), newVersion,
                        correctedAt, now));
            } catch (DuplicateKeyException dup) {
                throw new ApiException(409, "潜次 " + req.diveRequestId()
                        + " 更正版本并发冲突，请重试");
            }
            // 明确不回写 dive_record、bottom_time_ledger 与 residual_point_account
            return IdempotencyService.FirstResult.ok(new ResidualResult.Correction(
                    req.diveRequestId() + "#correct-" + id, req.diveRequestId(), req.recordId(),
                    latestTarget.planVersion(), latestTarget.diverCode(), latestTarget.phase(),
                    latestTarget.durationMin(), req.correctedDurationMin(), delta, newVersion,
                    correctedAt.toString(), false));
        });
    }

    // ------------------------------------------------------------------
    // 5. 查询
    // ------------------------------------------------------------------

    /** 潜水员积分账户视图：存储的出水残留积分，以及按查询时刻推导的当前积分。 */
    public Map<String, Object> accountView(String diverCode, Instant at) {
        ResidualAccountRow acc = accounts.find(diverCode)
                .orElseThrow(() -> new ApiException(404,
                        "潜水员尚无受控出水积分账户（首潜前积分视为 0）: " + diverCode));
        Instant viewAt = at != null ? at : Instant.now(clock);
        Duration surface = viewAt.isBefore(acc.lastSurfacedAtUtc())
                ? Duration.ZERO : Duration.between(acc.lastSurfacedAtUtc(), viewAt);
        int current = ResidualRules.decayedPoints(acc.residualPoints(), surface);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("diverCode", diverCode);
        view.put("lastSurfacedAtUtc", acc.lastSurfacedAtUtc().toString());
        view.put("exitResidualPoints", acc.residualPoints());
        view.put("currentPoints", current);
        view.put("surfaceIntervalMin", surface.toMinutes());
        view.put("sourceDiveRequestId", acc.sourceDiveRequestId());
        view.put("sourcePlanVersion", acc.sourcePlanVersion());
        view.put("version", acc.version());
        return view;
    }

    /** 潜次级积分视图：受控标记、各版本冻结快照、积分流水、占用槽与独立更正差异。 */
    public Map<String, Object> diveResidualView(String diveRequestId) {
        DiveRow dive = requireDive(diveRequestId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("requestId", dive.requestId());
        view.put("controlled", dive.controlled());
        view.put("currentPlanVersion", dive.currentPlanVersion());
        view.put("status", dive.status());

        List<Map<String, Object>> snapViews = new ArrayList<>();
        for (int v = 1; v <= dive.currentPlanVersion(); v++) {
            for (ResidualSnapshotRow s : snapshots.findByVersion(diveRequestId, v)) {
                Map<String, Object> sv = new LinkedHashMap<>();
                sv.put("planVersion", s.planVersion());
                sv.put("diverCode", s.diverCode());
                sv.put("sourceDiveRequestId", s.sourceDiveRequestId());
                sv.put("sourcePlanVersion", s.sourcePlanVersion());
                sv.put("sourceSurfacedAtUtc",
                        s.sourceSurfacedAtUtc() == null ? null : s.sourceSurfacedAtUtc().toString());
                sv.put("sourceResidualPoints", s.sourceResidualPoints());
                sv.put("sourceAccountVersion", s.sourceAccountVersion());
                sv.put("frozenPoints", s.frozenPoints());
                sv.put("ndlMinutes", s.ndlMinutes());
                sv.put("allowedMinutes", s.allowedMinutes());
                snapViews.add(sv);
            }
        }
        view.put("snapshots", snapViews);

        view.put("movements", movements.findByDive(diveRequestId).stream().map(m -> {
            Map<String, Object> mv = new LinkedHashMap<>();
            mv.put("diverCode", m.diverCode());
            mv.put("planVersion", m.planVersion());
            mv.put("preDivePoints", m.preDivePoints());
            mv.put("bottomMin", m.bottomMin());
            mv.put("residualPoints", m.residualPoints());
            mv.put("surfacedAtUtc", m.surfacedAtUtc().toString());
            return mv;
        }).toList());

        view.put("activeSlots", slots.existsForDive(diveRequestId));
        view.put("corrections", corrections.findByDive(diveRequestId).stream().map(c -> {
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("version", c.version());
            cv.put("recordId", c.targetRecordId());
            cv.put("planVersion", c.planVersion());
            cv.put("diverCode", c.diverCode());
            cv.put("phase", c.phase());
            cv.put("recordedDurationMin", c.recordedDurationMin());
            cv.put("correctedDurationMin", c.correctedDurationMin());
            cv.put("deltaMin", c.deltaMin());
            cv.put("reason", c.reason());
            cv.put("correctedAtUtc", c.correctedAtUtc().toString());
            return cv;
        }).toList());
        return view;
    }

    private DiveRow requireDive(String requestId) {
        return dives.find(requestId).orElseThrow(() ->
                new ApiException(404, "潜次不存在: " + requestId));
    }

    private void insertEvent(String diveRequestId, String payload, Instant now) {
        eventRepository.insert(diveRequestId, EventTypes.PLAN_FROZEN, payload, now);
    }

    /** 单人来源计算中间结果。 */
    private static final class ComputedSource {
        private boolean firstDive;
        private String sourceDive;
        private Integer sourcePlanVersion;
        private Instant sourceSurfacedAt;
        private Integer sourceResidual;
        private Integer sourceAccountVersion;
        private int currentPoints;
        private int allowedMinutes;
        private long intervalMinutes;
        private int projected24h;
        private boolean intervalPassed;
        private boolean observationPassed;
        private boolean cumulativePassed;
    }
}
