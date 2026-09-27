package com.example.starter.service;

import com.example.starter.dao.CatalogRepository;
import com.example.starter.dao.ControlledPlanDiverRow;
import com.example.starter.dao.ControlledPlanRepository;
import com.example.starter.dao.ControlledPlanRow;
import com.example.starter.dao.DiveEventRepository;
import com.example.starter.dao.DiveRepository;
import com.example.starter.dao.DiveRow;
import com.example.starter.dao.DiveSlotRepository;
import com.example.starter.dao.DiveSlotRow;
import com.example.starter.dao.PlanRepository;
import com.example.starter.dao.PlanRow;
import com.example.starter.dao.RecordCorrectionRepository;
import com.example.starter.dao.RecordCorrectionRow;
import com.example.starter.dao.RecordRepository;
import com.example.starter.dao.RecordRow;
import com.example.starter.dao.ScoreAccountRepository;
import com.example.starter.dao.ScoreAccountRow;
import com.example.starter.dao.ScoreSettlementRepository;
import com.example.starter.dao.ScoreSettlementRow;
import com.example.starter.domain.EventTypes;
import com.example.starter.domain.Phase;
import com.example.starter.domain.ScoreRules;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ApplyResult;
import com.example.starter.web.dto.ControlledPlanRequest;
import com.example.starter.web.dto.ControlledPlanResult;
import com.example.starter.web.dto.CorrectRequest;
import com.example.starter.web.dto.CorrectionResult;
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
import java.util.TreeMap;

/**
 * 反复潜水虚构积分控制服务（软件验证模型，<b>不用于真实潜水减压</b>）。
 *
 * <p>在旧作业语义不变的前提下新增：受控方案（冻结每人最近潜次版本、出水时刻与积分）、
 * 实际下潜前来源重查与潜水员占用、受控出水原子结算两人积分账、历史更正独立差异。</p>
 *
 * <p>本服务的写动作均由 {@link DiveService} 既有幂等事务或本服务的幂等模板包裹，
 * 所有钩子方法加入调用方事务，不单独开事务。</p>
 */
@Service
public class ControlledDiveService {

    private final DiveRepository dives;
    private final PlanRepository plans;
    private final RecordRepository records;
    private final ScoreAccountRepository accounts;
    private final DiveSlotRepository slots;
    private final ControlledPlanRepository controlledPlans;
    private final ScoreSettlementRepository settlements;
    private final RecordCorrectionRepository corrections;
    private final DiveEventRepository events;
    private final CatalogRepository catalog;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ControlledDiveService(DiveRepository dives, PlanRepository plans, RecordRepository records,
                                 ScoreAccountRepository accounts, DiveSlotRepository slots,
                                 ControlledPlanRepository controlledPlans,
                                 ScoreSettlementRepository settlements,
                                 RecordCorrectionRepository corrections,
                                 DiveEventRepository events, CatalogRepository catalog,
                                 IdempotencyService idempotency, Clock clock) {
        this.dives = dives;
        this.plans = plans;
        this.records = records;
        this.accounts = accounts;
        this.slots = slots;
        this.controlledPlans = controlledPlans;
        this.settlements = settlements;
        this.corrections = corrections;
        this.events = events;
        this.catalog = catalog;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 1. 受控方案：冻结每人最近潜次版本、出水时刻与积分
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<ControlledPlanResult> controlledPlan(ControlledPlanRequest req) {
        DiveRow dive = requireDive(req.diveRequestId());
        requireTeamDivers(dive, req.expectedVersions());
        Integer ndl = catalog.findNdlMinutes(req.depthM());
        if (ndl == null) {
            throw new ApiException(400, "合成减压表中无深度 " + req.depthM() + " 米的条目");
        }
        // 指纹素材：版本集合以嵌套结构化 Map 进入规范化文本，含分隔符的工号也不会串义
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("depthM", req.depthM());
        fields.put("bottomMin", req.bottomMin());
        fields.put("at", req.at().toString());
        fields.put("expectedVersions", new TreeMap<>(req.expectedVersions()));

        return idempotency.execute(req.requestId(), "CONTROLLED_PLAN", fields, () -> {
            DiveRow latest = requireDive(req.diveRequestId());
            if ("SURFACED".equals(latest.status()) || "CLOSED".equals(latest.status())
                    || "FROZEN".equals(latest.status()) || "DIVING".equals(latest.status())) {
                throw new ApiException(409, "潜次已进入" + latest.status()
                        + "阶段，受控方案不可变更");
            }
            if (latest.currentPlanVersion() > 0) {
                PlanRow current = plans.getVersion(latest.requestId(), latest.currentPlanVersion());
                if (!current.scoreControlled()) {
                    throw new ApiException(422, "该潜次已存在未启用积分控制的旧方案，保持旧语义不能改用受控方案");
                }
            }

            Instant now = Instant.now(clock);
            int newVersion = latest.currentPlanVersion() + 1;

            // 逐人读取来源账户并校验期望版本；先做全部门禁再写库
            List<DiverEval> evals = new ArrayList<>();
            int teamAllowed = Integer.MAX_VALUE;
            for (String diverCode : List.of(latest.diverACode(), latest.diverBCode())) {
                DiverEval eval = evaluateForPlan(latest, diverCode, req, ndl, now);
                evals.add(eval);
                teamAllowed = Math.min(teamAllowed, eval.allowed);
            }
            if (teamAllowed <= 0) {
                throw new ApiException(422, "两人允许分钟数较短值为 " + teamAllowed
                        + "（NDL-当前积分），小于等于 0，拒绝新方案");
            }
            if (req.bottomMin() > teamAllowed) {
                throw new ApiException(422, "计划水下 " + req.bottomMin() + " 分钟超过两人较短允许时长 "
                        + teamAllowed + " 分钟（恰用尽合法）");
            }
            for (DiverEval eval : evals) {
                int residualPredicted = ScoreRules.residualAfterDive(eval.current, req.bottomMin());
                if (!ScoreRules.residualWithinCap(residualPredicted)) {
                    throw new ApiException(422, "潜水员 " + eval.diverCode + " 出水残留积分预判 "
                            + residualPredicted + " 超过上限 " + ScoreRules.MAX_SCORE + "，方案拒绝");
                }
            }

            // 旧版本（含旧受控快照、旧台账）同事务冻结清退
            int frozenOld = 0;
            if (newVersion > 1) {
                frozenOld = plans.freezeAll(latest.requestId());
                records.freezeByDive(latest.requestId());
                records.deleteLedgerByDive(latest.requestId());
                controlledPlans.deleteByDive(latest.requestId());
                events.insert(latest.requestId(), EventTypes.PLAN_FROZEN,
                        "受控方案变更：冻结 v" + (newVersion - 1) + " 及其水下记录", now);
            }

            String profileJson = Profiles.buildJson(req.depthM(), req.bottomMin());
            String planFingerprint = Fingerprints.sha256(new TreeMap<>(fields) {{
                put("requestId", req.requestId());
            }});
            plans.insert(new PlanRow(null, latest.requestId(), newVersion, req.depthM(),
                    req.bottomMin(), ndl, DiveService.RATE_LIMIT, DiveService.RATE_LIMIT,
                    profileJson, false, true, planFingerprint, now));
            controlledPlans.insertPlan(new ControlledPlanRow(null, latest.requestId(), newVersion,
                    req.requestId(), req.at(), teamAllowed, now));
            for (DiverEval eval : evals) {
                controlledPlans.insertDiver(new ControlledPlanDiverRow(null, latest.requestId(),
                        newVersion, eval.diverCode, eval.expectedVersion, eval.lastSurfaced,
                        eval.residual, eval.current, eval.surfaceMinutes, eval.allowed, ndl, now));
                // 先抢占潜水员槽，再写账户：两组共享同一潜水员并发方案时在此被主键快速挡下（同潜次重做幂等）
                occupySlot(latest.requestId(), eval.diverCode, now);
                upsertAccountForPlan(eval, latest.requestId(), now);
            }
            plans.bumpDiveVersion(latest.requestId(), newVersion, now);

            List<ControlledPlanResult.DiverScoreView> views = evals.stream()
                    .map(e -> new ControlledPlanResult.DiverScoreView(e.diverCode, e.expectedVersion,
                            e.lastSurfaced == null ? null : e.lastSurfaced.toString(), e.residual,
                            e.surfaceMinutes, e.current, ndl, e.allowed, e.intervalPassed,
                            e.observationComplete))
                    .toList();
            return IdempotencyService.FirstResult.created(new ControlledPlanResult(
                    req.requestId(), latest.requestId(), newVersion, req.at().toString(),
                    req.depthM(), req.bottomMin(), teamAllowed, req.bottomMin() == teamAllowed,
                    views, frozenOld, false));
        });
    }

    /** 逐人来源读取、期望版本校验与全部门禁评估。 */
    private DiverEval evaluateForPlan(DiveRow dive, String diverCode, ControlledPlanRequest req,
                                      int ndl, Instant now) {
        Integer expectedVersion = req.expectedVersions().get(diverCode);
        if (expectedVersion == null || expectedVersion <= 0) {
            throw new ApiException(400, "expectedVersions 必须包含两名潜水员的正整数账户版本: 缺 "
                    + diverCode);
        }
        Optional<ScoreAccountRow> accountOpt = accounts.find(diverCode);
        ScoreAccountRow account;
        if (accountOpt.isPresent()) {
            account = accountOpt.get();
            if (account.version() != expectedVersion) {
                throw new ApiException(409, "潜水员 " + diverCode + " 积分账户版本已为 "
                        + account.version() + "，请求携带 expectedVersion=" + expectedVersion
                        + " 来源已变更，请重做受控方案");
            }
            if (account.activeDiveRequestId() != null
                    && !account.activeDiveRequestId().equals(dive.requestId())) {
                throw new ApiException(422, "潜水员 " + diverCode + " 已有进行中的受控潜次 "
                        + account.activeDiveRequestId() + "，不能再为其他潜次出方案");
            }
        } else {
            if (expectedVersion != 1) {
                throw new ApiException(409, "潜水员 " + diverCode + " 积分账户尚未建立（首次积分 0、版本 1），"
                        + "expectedVersion 必须为 1");
            }
            account = null;
        }

        Instant lastSurfaced = account == null ? null : account.lastSurfacedAtUtc();
        int residual = account == null ? 0 : account.residualScore();
        long surfaceMinutes = ScoreRules.surfaceMinutes(lastSurfaced, req.at());
        int current = ScoreRules.currentScore(residual, surfaceMinutes);
        boolean intervalPassed = lastSurfaced == null
                || ScoreRules.intervalPassed(surfaceMinutes);
        boolean observationComplete = lastSurfaced == null
                || !req.at().isBefore(lastSurfaced.plus(DiveService.OBSERVATION));
        int windowBottom = sumWindowBottom(diverCode, req.at());
        boolean cumulativePassed = windowBottom + req.bottomMin() <= DiveService.MAX_BOTTOM_MIN_24H;

        if (!intervalPassed) {
            throw new ApiException(422, "潜水员 " + diverCode + " 距上次出水仅 " + surfaceMinutes
                    + " 分钟，不足 " + ScoreRules.MIN_SURFACE_INTERVAL_MINUTES
                    + " 分钟禁止再潜（恰 60 分钟仅通过间隔门禁）");
        }
        if (!observationComplete) {
            throw new ApiException(422, "潜水员 " + diverCode + " 上次出水后的 2 小时观察尚未完成，"
                    + "受控方案拒绝");
        }
        if (!cumulativePassed) {
            throw new ApiException(422, "潜水员 " + diverCode + " 24 小时窗口已累计水下 "
                    + windowBottom + " 分钟，再潜 " + req.bottomMin() + " 分钟将超过上限 "
                    + DiveService.MAX_BOTTOM_MIN_24H + " 分钟");
        }

        int allowed = ScoreRules.allowedMinutes(ndl, current);
        return new DiverEval(diverCode, expectedVersion, account, lastSurfaced, residual,
                surfaceMinutes, current, allowed, intervalPassed, observationComplete);
    }

    private void upsertAccountForPlan(DiverEval eval, String diveRequestId, Instant now) {
        if (eval.account == null) {
            accounts.insert(new ScoreAccountRow(eval.diverCode, 1, 0, null, null,
                    diveRequestId, now, now));
        } else {
            accounts.markActive(eval.diverCode, diveRequestId, now);
        }
    }

    private int sumWindowBottom(String diverCode, Instant end) {
        Timestamp start = Timestamp.from(end.minus(Duration.ofHours(24)));
        return records.sumBottomMinutes(diverCode, start, Timestamp.from(end));
    }

    // ------------------------------------------------------------------
    // 2. 实际下潜前重查（成对 DESCENT 生效事务内调用）：来源未变 + 潜水员占用
    // ------------------------------------------------------------------

    /** 由 {@link DiveService} 在受控方案的成对下潜生效时调用，加入同一事务。 */
    public void onDescentPaired(DiveRow dive, PlanRow plan, RecordRow first, RecordRow second,
                                Instant now) {
        for (RecordRow rec : List.of(first, second)) {
            ControlledPlanDiverRow frozen = requireFrozenDiver(dive.requestId(), plan.version(),
                    rec.diverCode());
            ScoreAccountRow account = accounts.find(rec.diverCode())
                    .orElseThrow(() -> new ApiException(409, "潜水员 " + rec.diverCode()
                            + " 积分账户缺失，受控方案来源不可信，请重做方案"));
            // 实际下潜前重查：方案冻结的账户版本/出水时刻/积分必须未变，否则旧方案不能消费新积分
            verifySourceUnchanged(frozen, account);
            // 槽必须已由本潜次方案持有（并发下可能被另一组抢占）
            DiveSlotRow slot = slots.find(rec.diverCode())
                    .orElseThrow(() -> new ApiException(409, "潜水员 " + rec.diverCode()
                            + " 的受控下潜槽不存在，方案未完成占用，请重做方案"));
            if (!slot.diveRequestId().equals(dive.requestId())) {
                throw new ApiException(409, "潜水员 " + rec.diverCode() + " 已属于受控潜次 "
                        + slot.diveRequestId() + "，两组共享潜水员并发下潜最多一组成功");
            }
        }
    }

    /**
     * 旧流程成对下潜生效时的跨线占用检查：旧潜次不建立积分槽（旧-旧组之间保持原语义），
     * 但若任一组员已被进行中的<b>受控</b>潜次占用，则拒绝下潜，
     * 防止“两组共享同一潜水员并发下潜最多一组成功”被旧入口绕过。
     */
    public void onLegacyDescentPaired(DiveRow dive, RecordRow first, RecordRow second) {
        for (RecordRow rec : List.of(first, second)) {
            slots.find(rec.diverCode()).ifPresent(slot -> {
                if (!slot.diveRequestId().equals(dive.requestId())) {
                    throw new ApiException(409, "潜水员 " + rec.diverCode()
                            + " 已在受控潜次 " + slot.diveRequestId()
                            + " 中，旧流程潜次不能与其并发下潜（共享潜水员最多一组成功）");
                }
            });
        }
    }

    /** 由 {@link DiveService} 在受控方案的成对水底生效时调用：实际分钟不得超过冻结允许时长。 */
    public void onBottomPaired(DiveRow dive, PlanRow plan, RecordRow first, RecordRow second,
                               Instant now) {
        for (RecordRow rec : List.of(first, second)) {
            ControlledPlanDiverRow frozen = requireFrozenDiver(dive.requestId(), plan.version(),
                    rec.diverCode());
            if (rec.durationMin() > frozen.allowedMin()) {
                throw new ApiException(422, "潜水员 " + rec.diverCode() + " 实际水下 "
                        + rec.durationMin() + " 分钟超过受控方案冻结允许时长 "
                        + frozen.allowedMin() + " 分钟，成对记录回滚不生效");
            }
            Instant descentAt = findEffectivePhaseTime(dive.requestId(), plan.version(),
                    rec.diverCode(), Phase.DESCENT.name());
            int preDive = ScoreRules.currentScore(frozen.residualScore(),
                    ScoreRules.surfaceMinutes(frozen.lastSurfacedAtUtc(), descentAt));
            int residual = ScoreRules.residualAfterDive(preDive, rec.durationMin());
            if (!ScoreRules.residualWithinCap(residual)) {
                throw new ApiException(422, "潜水员 " + rec.diverCode() + " 实际出水残留积分将达 "
                        + residual + "，超过上限 " + ScoreRules.MAX_SCORE
                        + "，成对记录回滚不生效");
            }
        }
    }

    private void verifySourceUnchanged(ControlledPlanDiverRow frozen, ScoreAccountRow account) {
        if (account.version() != frozen.expectedAccountVersion()) {
            throw new ApiException(409, "潜水员 " + frozen.diverCode() + " 积分账户版本已由 "
                    + frozen.expectedAccountVersion() + " 变为 " + account.version()
                    + "，旧受控方案不能消费新出的积分，请重做方案");
        }
        boolean sameSurface = (frozen.lastSurfacedAtUtc() == null && account.lastSurfacedAtUtc() == null)
                || (frozen.lastSurfacedAtUtc() != null
                    && frozen.lastSurfacedAtUtc().equals(account.lastSurfacedAtUtc()));
        if (!sameSurface || account.residualScore() != frozen.residualScore()) {
            throw new ApiException(409, "潜水员 " + frozen.diverCode()
                    + " 的最近出水时刻或积分在方案后发生变化，旧方案不能消费新出的积分，请重做方案");
        }
    }

    private void occupySlot(String diveRequestId, String diverCode, Instant now) {
        Optional<DiveSlotRow> existing = slots.find(diverCode);
        if (existing.isPresent()) {
            if (existing.get().diveRequestId().equals(diveRequestId)) {
                return;
            }
            throw new ApiException(409, "潜水员 " + diverCode + " 正在另一受控潜次 "
                    + existing.get().diveRequestId() + " 中，两组共享潜水员并发下潜最多一组成功");
        }
        try {
            slots.insert(new DiveSlotRow(diverCode, diveRequestId, now));
        } catch (DuplicateKeyException dup) {
            // 胜方事务可能尚未提交、槽暂不可见：
            //  - 可见且为异潜次：两组共享潜水员，业务 409（本组事务回滚，不占幂等键）；
            //  - 不可见：交回幂等模板等待胜方提交——同 requestId 同参数将重放首次结果，
            //    异 requestId 等待超时后按并发冲突 409。
            DiveSlotRow winner = slots.find(diverCode).orElse(null);
            if (winner != null && !winner.diveRequestId().equals(diveRequestId)) {
                throw new ApiException(409, "潜水员 " + diverCode + " 已被并发受控潜次 "
                        + winner.diveRequestId() + " 占用，两组共享潜水员并发下潜最多一组成功");
            }
            if (winner == null) {
                throw dup;
            }
        }
    }

    private ControlledPlanDiverRow requireFrozenDiver(String diveRequestId, int planVersion,
                                                      String diverCode) {
        return controlledPlans.findDivers(diveRequestId, planVersion).stream()
                .filter(d -> d.diverCode().equals(diverCode))
                .findFirst()
                .orElseThrow(() -> new ApiException(409, "受控方案冻结项缺失: " + diveRequestId
                        + "#v" + planVersion + " " + diverCode + "，请重做方案"));
    }

    private Instant findEffectivePhaseTime(String diveRequestId, int planVersion, String diverCode,
                                           String phase) {
        return records.findAllByDive(diveRequestId).stream()
                .filter(r -> r.planVersion() == planVersion && r.diverCode().equals(diverCode)
                        && phase.equals(r.phase()) && r.effective() && !r.frozen())
                .map(RecordRow::eventTimeUtc)
                .findFirst()
                .orElseThrow(() -> new ApiException(409, "潜水员 " + diverCode + " 缺少生效的 "
                        + phase + " 记录，受控流程不完整"));
    }

    // ------------------------------------------------------------------
    // 3. 受控出水：原子更新两人积分账与本潜次记录（加入 surface 同一事务）
    // ------------------------------------------------------------------

    /** 由 {@link DiveService} 在出水登记事务内、状态置为 SURFACED 后调用。 */
    public void onSurface(DiveRow dive, PlanRow plan, Instant surfacedAt, Instant now) {
        List<ScoreSettlementRow> rows = new ArrayList<>();
        for (String diverCode : List.of(dive.diverACode(), dive.diverBCode())) {
            ControlledPlanDiverRow frozen = requireFrozenDiver(dive.requestId(), plan.version(),
                    diverCode);
            ScoreAccountRow account = accounts.find(diverCode)
                    .orElseThrow(() -> new ApiException(409, "潜水员 " + diverCode
                            + " 积分账户缺失，不能结算受控出水"));
            verifySourceUnchanged(frozen, account);

            RecordRow bottom = records.findAllByDive(dive.requestId()).stream()
                    .filter(r -> r.planVersion() == plan.version()
                            && r.diverCode().equals(diverCode)
                            && Phase.BOTTOM.name().equals(r.phase()) && r.effective() && !r.frozen())
                    .findFirst()
                    .orElseThrow(() -> new ApiException(409, "潜水员 " + diverCode
                            + " 缺少生效的 BOTTOM 记录，受控出水拒绝"));
            Instant descentAt = findEffectivePhaseTime(dive.requestId(), plan.version(), diverCode,
                    Phase.DESCENT.name());
            int preDive = ScoreRules.currentScore(frozen.residualScore(),
                    ScoreRules.surfaceMinutes(frozen.lastSurfacedAtUtc(), descentAt));
            int residual = ScoreRules.residualAfterDive(preDive, bottom.durationMin());
            if (!ScoreRules.residualWithinCap(residual)) {
                throw new ApiException(422, "潜水员 " + diverCode + " 出水残留积分 " + residual
                        + " 超过上限 " + ScoreRules.MAX_SCORE + "，出水登记回滚");
            }
            int newVersion = account.version() + 1;
            int updated = accounts.settle(diverCode, account.version(), residual, surfacedAt,
                    dive.requestId(), newVersion, now);
            if (updated == 0) {
                throw new ApiException(409, "潜水员 " + diverCode
                        + " 的积分账户已被并发结算推进，旧方案不能消费新出的积分，出水登记回滚");
            }
            rows.add(new ScoreSettlementRow(null, diverCode, dive.requestId(), plan.version(),
                    preDive, bottom.durationMin(), residual, surfacedAt, now));
        }
        for (ScoreSettlementRow row : rows) {
            settlements.insert(row);
        }
        int released = slots.releaseByDive(dive.requestId());
        events.insert(dive.requestId(), EventTypes.SCORE_SETTLED,
                "受控出水原子结算两人积分账，释放潜水员占用槽 " + released + " 个", now);
    }

    // ------------------------------------------------------------------
    // 4. 历史水下记录更正：不追改已关闭潜次，另记独立差异
    // ------------------------------------------------------------------

    public IdempotencyService.Outcome<CorrectionResult> correct(CorrectRequest req) {
        DiveRow dive = requireDive(req.diveRequestId());
        if (!"CLOSED".equals(dive.status())) {
            throw new ApiException(422, "仅可更正已关闭潜次的历史水下记录，当前状态 " + dive.status());
        }
        if (!req.diverCode().equals(dive.diverACode()) && !req.diverCode().equals(dive.diverBCode())) {
            throw new ApiException(400, "潜水员 " + req.diverCode() + " 不属于该潜次潜水组");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("diveRequestId", req.diveRequestId());
        fields.put("diverCode", req.diverCode());
        fields.put("oldBottomMin", req.oldBottomMin());
        fields.put("newBottomMin", req.newBottomMin());
        fields.put("correctedAt",
                (req.correctedAt() == null ? null : req.correctedAt().toString()));

        return idempotency.execute(req.requestId(), "CORRECT", fields, () -> {
            DiveRow latest = requireDive(req.diveRequestId());
            // 定位最终生效（未冻结）的水底记录：方案变更产生的冻结旧记录不是更正对象
            RecordRow original = records.findAllByDive(latest.requestId()).stream()
                    .filter(r -> req.diverCode().equals(r.diverCode())
                            && Phase.BOTTOM.name().equals(r.phase())
                            && r.effective() && !r.frozen())
                    .reduce((a, b) -> b)
                    .orElseThrow(() -> new ApiException(422,
                            "潜次中不存在潜水员 " + req.diverCode() + " 的生效水底记录，无可更正对象"));
            if (original.durationMin() != req.oldBottomMin()) {
                throw new ApiException(422, "申报原水下分钟 " + req.oldBottomMin()
                        + " 与库中原记录 " + original.durationMin() + " 不一致，拒绝更正");
            }
            Instant correctedAt = req.correctedAt() != null ? req.correctedAt() : Instant.now(clock);
            Instant now = Instant.now(clock);
            int delta = req.newBottomMin() - req.oldBottomMin();
            corrections.insert(new RecordCorrectionRow(null, req.diveRequestId(), req.diverCode(),
                    req.oldBottomMin(), req.newBottomMin(), delta, req.requestId(), correctedAt, now));
            events.insert(req.diveRequestId(), EventTypes.RECORD_CORRECTION,
                    "历史水下记录更正独立差异：" + req.diverCode() + " " + req.oldBottomMin()
                            + "→" + req.newBottomMin() + "（Δ=" + delta + "），不追改已关闭潜次", now);
            return IdempotencyService.FirstResult.ok(new CorrectionResult(
                    req.requestId(), req.diveRequestId(), req.diverCode(), req.oldBottomMin(),
                    req.newBottomMin(), delta, correctedAt.toString(), false));
        });
    }

    // ------------------------------------------------------------------
    // 5. 查询：积分账户（含按当前时钟派生的当前积分）、更正差异、受控潜次视图
    // ------------------------------------------------------------------

    public Map<String, Object> scoreView(String diverCode) {
        if (!catalog.diverExists(diverCode)) {
            throw new ApiException(404, "潜水员不在合成名册中: " + diverCode);
        }
        Instant now = Instant.now(clock);
        Optional<ScoreAccountRow> accountOpt = accounts.find(diverCode);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("diverCode", diverCode);
        if (accountOpt.isEmpty()) {
            // 首次积分 0：尚未建立账户时返回虚拟零账户视图
            view.put("accountVersion", 1);
            view.put("residualScore", 0);
            view.put("currentScore", 0);
            view.put("lastSurfacedAtUtc", null);
            view.put("lastDiveRequestId", null);
            view.put("activeDiveRequestId", null);
            view.put("surfaceMinutes", 0);
            view.put("established", false);
            return view;
        }
        ScoreAccountRow account = accountOpt.get();
        long surfaceMinutes = ScoreRules.surfaceMinutes(account.lastSurfacedAtUtc(), now);
        view.put("accountVersion", account.version());
        view.put("residualScore", account.residualScore());
        view.put("currentScore",
                ScoreRules.currentScore(account.residualScore(), surfaceMinutes));
        view.put("lastSurfacedAtUtc", account.lastSurfacedAtUtc() == null ? null
                : account.lastSurfacedAtUtc().toString());
        view.put("lastDiveRequestId", account.lastDiveRequestId());
        view.put("activeDiveRequestId", account.activeDiveRequestId());
        view.put("surfaceMinutes", surfaceMinutes);
        view.put("established", true);
        return view;
    }

    /** 复算视图追加：受控方案冻结项与出水结算。 */
    public Map<String, Object> controlledRecompute(DiveRow dive) {
        Map<String, Object> view = new LinkedHashMap<>();
        List<Map<String, Object>> planViews = new ArrayList<>();
        for (PlanRow p : plans.findAll(dive.requestId())) {
            if (!p.scoreControlled()) {
                continue;
            }
            Optional<ControlledPlanRow> head = controlledPlans.findPlan(dive.requestId(),
                    p.version());
            Map<String, Object> pv = new LinkedHashMap<>();
            pv.put("version", p.version());
            pv.put("frozen", p.frozen());
            pv.put("atUtc", head.map(h -> h.atUtc().toString()).orElse(null));
            pv.put("teamAllowedMin", head.map(ControlledPlanRow::teamAllowedMin).orElse(null));
            pv.put("divers", controlledPlans.findDivers(dive.requestId(), p.version()).stream()
                    .map(d -> {
                        Map<String, Object> dv = new LinkedHashMap<>();
                        dv.put("diverCode", d.diverCode());
                        dv.put("expectedAccountVersion", d.expectedAccountVersion());
                        dv.put("lastSurfacedAtUtc", d.lastSurfacedAtUtc() == null ? null
                                : d.lastSurfacedAtUtc().toString());
                        dv.put("residualScore", d.residualScore());
                        dv.put("currentScore", d.currentScore());
                        dv.put("surfaceMinutes", d.surfaceMinutes());
                        dv.put("allowedMin", d.allowedMin());
                        return dv;
                    }).toList());
            planViews.add(pv);
        }
        view.put("controlledPlans", planViews);
        view.put("scoreSettlements", settlements.findByDive(dive.requestId()).stream().map(s -> {
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("diverCode", s.diverCode());
            sv.put("planVersion", s.planVersion());
            sv.put("preDiveScore", s.preDiveScore());
            sv.put("bottomMin", s.bottomMin());
            sv.put("residualScore", s.residualScore());
            sv.put("surfacedAtUtc", s.lastSurfacedAtUtc().toString());
            return sv;
        }).toList());
        view.put("corrections", corrections.findByDive(dive.requestId()).stream().map(c -> {
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("diverCode", c.diverCode());
            cv.put("oldBottomMin", c.oldBottomMin());
            cv.put("newBottomMin", c.newBottomMin());
            cv.put("deltaMin", c.deltaMin());
            cv.put("correctedAtUtc", c.correctedAtUtc().toString());
            return cv;
        }).toList());
        return view;
    }

    private void requireTeamDivers(DiveRow dive, Map<String, Integer> expectedVersions) {
        if (expectedVersions == null || expectedVersions.isEmpty()) {
            throw new ApiException(400, "expectedVersions 必须携带两名潜水员的账户版本集合");
        }
        for (String diverCode : List.of(dive.diverACode(), dive.diverBCode())) {
            if (!expectedVersions.containsKey(diverCode)) {
                throw new ApiException(400, "expectedVersions 缺少组员版本: " + diverCode);
            }
        }
        if (expectedVersions.size() != 2) {
            throw new ApiException(400, "expectedVersions 只能包含本潜次两名组员，实际键数 "
                    + expectedVersions.size());
        }
    }

    private DiveRow requireDive(String requestId) {
        return dives.find(requestId).orElseThrow(() ->
                new ApiException(404, "潜次不存在: " + requestId));
    }

    /** 方案评估中间结果。 */
    private record DiverEval(
            String diverCode,
            int expectedVersion,
            ScoreAccountRow account,
            Instant lastSurfaced,
            int residual,
            long surfaceMinutes,
            int current,
            int allowed,
            boolean intervalPassed,
            boolean observationComplete) {
    }
}
