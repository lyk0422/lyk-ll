package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.api.dto.ColorCheckRequest;
import com.example.starter.api.dto.CreateRecipeRequest;
import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.JudgeRequest;
import com.example.starter.api.dto.LateColorRequest;
import com.example.starter.api.dto.MergeLotsRequest;
import com.example.starter.api.dto.RecipeItemInput;
import com.example.starter.api.dto.ScheduleControlledRequest;
import com.example.starter.api.dto.ScheduleLotRequest;
import com.example.starter.api.dto.SplitLotRequest;
import com.example.starter.api.dto.SplitLotTargetInput;
import com.example.starter.api.dto.WeighingInput;
import com.example.starter.domain.CompensationStatus;
import com.example.starter.domain.LotStatus;
import com.example.starter.domain.RecipeStatus;
import com.example.starter.domain.Verdict;
import com.example.starter.support.ApiException;

/**
 * 印染配方放大与缸差控制核心服务。
 * 阈值：对色 ΔE&lt;=0.8 放行；称量偏差绝对值&lt;=0.5% 合法；缸差&gt;0.5 须回修且仅一次。
 */
@Service
public class DyeService {

    /** 对色放行阈值（含边界，恰 0.8 合格）。 */
    static final BigDecimal COLOR_LIMIT = new BigDecimal("0.8");
    /** 称量偏差允许上限百分比（含边界，恰 0.5% 合法）。 */
    static final BigDecimal WEIGH_LIMIT_PCT = new BigDecimal("0.5");
    /** 缸差阈值：超过 0.5 须回修。 */
    static final BigDecimal LOT_DIFF_LIMIT = new BigDecimal("0.5");
    private static final int DEVIATION_SCALE = 6;

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public DyeService(JdbcTemplate jdbc, IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Timestamp nowTs() {
        return Timestamp.from(Instant.now(clock));
    }

    // ---------------------------------------------------------------------
    // 配方：草稿 → 发布冻结；修改已发布配方生成新版本并保留旧版本
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult createRecipe(CreateRecipeRequest req) {
        Integer exists = jdbc.query("SELECT 1 FROM recipe WHERE recipe_code = ?",
                rs -> rs.next() ? 1 : null, req.recipeCode());
        if (exists != null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "配方编码 " + req.recipeCode() + " 已存在；修改已发布配方须生成新版本");
        }
        validateItems(req.items());
        Timestamp now = nowTs();
        jdbc.update("INSERT INTO recipe (recipe_code, version, liquor_ratio, status, created_at) "
                + "VALUES (?, 1, ?, ?, ?)",
                req.recipeCode(), req.liquorRatio(), RecipeStatus.DRAFT.name(), now);
        Long recipeId = jdbc.queryForObject(
                "SELECT id FROM recipe WHERE recipe_code = ? AND version = 1", Long.class, req.recipeCode());
        insertItems(recipeId, req.items());
        return ServiceResult.created(recipeView(req.recipeCode(), 1));
    }

    @Transactional
    public ServiceResult publishRecipe(String recipeCode) {
        Map<String, Object> latest = latestRecipe(recipeCode);
        if (RecipeStatus.PUBLISHED.name().equals(latest.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "配方 " + recipeCode + " v" + latest.get("version") + " 已发布冻结，不能重复发布或修改");
        }
        jdbc.update("UPDATE recipe SET status = ? WHERE id = ?",
                RecipeStatus.PUBLISHED.name(), latest.get("id"));
        return ServiceResult.ok(recipeView(recipeCode, (Integer) latest.get("version")));
    }

    /**
     * 修改已发布配方：复制为下一草稿版本，旧版本原样保留。
     */
    @Transactional
    public ServiceResult reviseRecipe(String recipeCode, CreateRecipeRequest req) {
        Map<String, Object> latest = latestRecipe(recipeCode);
        if (!RecipeStatus.PUBLISHED.name().equals(latest.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "仅已发布冻结版本才能通过修订生成新版本；当前 v" + latest.get("version") + " 为草稿");
        }
        validateItems(req.items());
        int newVersion = (Integer) latest.get("version") + 1;
        Timestamp now = nowTs();
        jdbc.update("INSERT INTO recipe (recipe_code, version, liquor_ratio, status, created_at) "
                + "VALUES (?, ?, ?, ?, ?)",
                recipeCode, newVersion, req.liquorRatio(), RecipeStatus.DRAFT.name(), now);
        Long recipeId = jdbc.queryForObject(
                "SELECT id FROM recipe WHERE recipe_code = ? AND version = ?",
                Long.class, recipeCode, newVersion);
        insertItems(recipeId, req.items());
        return ServiceResult.created(recipeView(recipeCode, newVersion));
    }

    // ---------------------------------------------------------------------
    // 打小样对色
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult colorCheck(ColorCheckRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sampleId", req.sampleId());
        payload.put("deltaE", req.deltaE());
        return idempotency.execute(req.requestId(), "COLOR_CHECK", payload, () -> {
            boolean passed = req.deltaE().compareTo(COLOR_LIMIT) <= 0;
            jdbc.update("INSERT INTO color_check (request_id, sample_id, delta_e, passed, created_at) "
                    + "VALUES (?, ?, ?, ?, ?)",
                    req.requestId(), req.sampleId(), req.deltaE(), passed, nowTs());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", req.requestId());
            body.put("sampleId", req.sampleId());
            body.put("deltaE", req.deltaE());
            body.put("limit", COLOR_LIMIT);
            body.put("passed", passed);
            body.put("decision", passed ? "放行" : "不放行");
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------------
    // 排缸：冻结配方版本 + 染缸唯一占用（并发唯一受理）
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult scheduleLot(ScheduleLotRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
        payload.put("vatNo", req.vatNo());
        payload.put("recipeCode", req.recipeCode());
        payload.put("recipeVersion", req.recipeVersion());
        payload.put("fabricWeightKg", req.fabricWeightKg());
        return idempotency.execute(req.requestId(), "SCHEDULE", payload, () -> {
            Map<String, Object> recipe = requirePublishedRecipe(req.recipeCode(), req.recipeVersion());
            Timestamp now = nowTs();
            Long lotId;
            try {
                jdbc.update("INSERT INTO lot (lot_no, vat_no, recipe_code, recipe_version, liquor_ratio, "
                        + "fabric_weight_kg, status, repair_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                        req.lotNo(), req.vatNo(), req.recipeCode(), req.recipeVersion(),
                        recipe.get("liquor_ratio"), req.fabricWeightKg(), LotStatus.CREATED.name(), now, now);
                lotId = jdbc.queryForObject("SELECT id FROM lot WHERE lot_no = ?", Long.class, req.lotNo());
            } catch (DuplicateKeyException dupLot) {
                throw new ApiException(HttpStatus.CONFLICT, "缸次号 " + req.lotNo() + " 已存在");
            }
            try {
                // 行存在即占用：主键冲突保证同一染缸同时刻只有一个执行中缸次，并发排缸唯一受理。
                jdbc.update("INSERT INTO vat_active (vat_no, lot_id, since) VALUES (?, ?, ?)",
                        req.vatNo(), lotId, now);
            } catch (DuplicateKeyException dupVat) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "染缸 " + req.vatNo() + " 已有执行中缸次，并发排缸唯一受理");
            }
            return ServiceResult.created(lotView(req.lotNo()));
        });
    }

    /**
     * 受控排缸：与普通排缸同样冻结已发布配方版本并唯一占用染缸，但缸次标记 controlled=TRUE，
     * 投料前必须取得效价补偿批准。
     */
    @Transactional
    public ServiceResult scheduleControlledLot(ScheduleControlledRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
        payload.put("vatNo", req.vatNo());
        payload.put("recipeCode", req.recipeCode());
        payload.put("recipeVersion", req.recipeVersion());
        payload.put("fabricWeightKg", req.fabricWeightKg());
        payload.put("controlled", true);
        return idempotency.execute(req.requestId(), "SCHEDULE_CONTROLLED", payload, () -> {
            Map<String, Object> recipe = requirePublishedRecipe(req.recipeCode(), req.recipeVersion());
            Timestamp now = nowTs();
            Long lotId;
            try {
                jdbc.update("INSERT INTO lot (lot_no, vat_no, recipe_code, recipe_version, liquor_ratio, "
                        + "fabric_weight_kg, controlled, status, repair_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, 0, ?, ?)",
                        req.lotNo(), req.vatNo(), req.recipeCode(), req.recipeVersion(),
                        recipe.get("liquor_ratio"), req.fabricWeightKg(), LotStatus.CREATED.name(), now, now);
                lotId = jdbc.queryForObject("SELECT id FROM lot WHERE lot_no = ?", Long.class, req.lotNo());
            } catch (DuplicateKeyException dupLot) {
                throw new ApiException(HttpStatus.CONFLICT, "缸次号 " + req.lotNo() + " 已存在");
            }
            try {
                jdbc.update("INSERT INTO vat_active (vat_no, lot_id, since) VALUES (?, ?, ?)",
                        req.vatNo(), lotId, now);
            } catch (DuplicateKeyException dupVat) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "染缸 " + req.vatNo() + " 已有执行中缸次，并发排缸唯一受理");
            }
            return ServiceResult.created(lotView(req.lotNo()));
        });
    }

    // ---------------------------------------------------------------------
    // 并缸：须同配方（同编码同冻结版本）同浴比
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult mergeLots(MergeLotsRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
        payload.put("targetLotNo", req.targetLotNo());
        payload.put("expectedVersion", req.expectedVersion());
        return idempotency.execute(req.requestId(), "MERGE", payload, () -> {
            if (req.lotNo().equals(req.targetLotNo())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "缸次不能与自身并缸");
            }
            Map<String, Object> source = requireLot(req.lotNo());
            Map<String, Object> target = requireLot(req.targetLotNo());
            requireStatus(source, LotStatus.CREATED);
            requireStatus(target, LotStatus.CREATED);
            if (!source.get("recipe_code").equals(target.get("recipe_code"))
                    || ((Number) source.get("recipe_version")).intValue()
                        != ((Number) target.get("recipe_version")).intValue()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "不同配方拒绝并缸：" + source.get("recipe_code") + " v" + source.get("recipe_version")
                                + " 与 " + target.get("recipe_code") + " v" + target.get("recipe_version"));
            }
            if (((BigDecimal) source.get("liquor_ratio")).compareTo((BigDecimal) target.get("liquor_ratio")) != 0) {
                throw new ApiException(HttpStatus.CONFLICT, "浴比不同拒绝并缸");
            }

            boolean sourceControlled = Boolean.TRUE.equals(source.get("controlled"));
            boolean targetControlled = Boolean.TRUE.equals(target.get("controlled"));
            if (sourceControlled != targetControlled) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "受控缸次与旧非受控缸次不得并缸：" + req.lotNo() + " controlled=" + sourceControlled
                                + "，" + req.targetLotNo() + " controlled=" + targetControlled);
            }

            Map<String, Object> sourceComp = null;
            Map<String, Object> targetComp = null;
            if (sourceControlled) {
                // 受控并缸：补偿批次与补偿目标必须完全相同；按缸次主键升序加锁避免反向并缸互锁。
                Map<String, Object> firstLot = ((Number) source.get("id")).longValue()
                        < ((Number) target.get("id")).longValue() ? source : target;
                Map<String, Object> secondLot = firstLot == source ? target : source;
                requireFrozenCompensation(firstLot, String.valueOf(firstLot.get("lot_no")));
                requireFrozenCompensation(secondLot, String.valueOf(secondLot.get("lot_no")));
                sourceComp = requireFrozenCompensation(source, req.lotNo());
                targetComp = requireFrozenCompensation(target, req.targetLotNo());
                if (req.expectedVersion() == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "受控并缸须携带目标缸次补偿批准 expectedVersion");
                }
                int targetCompVersion = ((Number) targetComp.get("version")).intValue();
                if (req.expectedVersion() != targetCompVersion) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "补偿批准版本冲突：expectedVersion=" + req.expectedVersion()
                                    + "，目标缸当前版本=" + targetCompVersion);
                }
                assertSameCompensation(sourceComp, targetComp, req.lotNo(), req.targetLotNo());
            }

            Timestamp now = nowTs();
            BigDecimal combinedWeight = ((BigDecimal) source.get("fabric_weight_kg"))
                    .add((BigDecimal) target.get("fabric_weight_kg"));
            jdbc.update("UPDATE lot SET merged_into_lot_id = ?, status = ?, updated_at = ? WHERE id = ?",
                    target.get("id"), LotStatus.MERGED.name(), now, source.get("id"));
            jdbc.update("UPDATE lot SET fabric_weight_kg = ?, updated_at = ? WHERE id = ?",
                    combinedWeight, now, target.get("id"));
            // 被并缸次释放原染缸占用，织物并入目标缸同缸生产。
            jdbc.update("DELETE FROM vat_active WHERE vat_no = ? AND lot_id = ?",
                    source.get("vat_no"), source.get("id"));

            int targetCompVersionAfter = -1;
            if (sourceControlled) {
                // 源缸补偿批准随并缸失效（未投料）；目标缸按合并布重用同批次同系数重冻补偿，版本加 1。
                int sourceCompVersion = ((Number) sourceComp.get("version")).intValue();
                jdbc.update("UPDATE compensation SET status = ?, invalid_reason = ?, version = version + 1, "
                        + "updated_at = ? WHERE id = ? AND status = ? AND version = ?",
                        CompensationStatus.INVALID.name(),
                        "缸次已并入 " + req.targetLotNo() + "，未投料补偿随并缸失效",
                        now, sourceComp.get("id"), CompensationStatus.FROZEN.name(), sourceCompVersion);
                targetCompVersionAfter = refreezeCompensationForMergedWeight(
                        target, targetComp, combinedWeight, now);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", req.requestId());
            body.put("mergedLotNo", req.lotNo());
            body.put("targetLotNo", req.targetLotNo());
            body.put("combinedFabricWeightKg", combinedWeight);
            body.put("controlled", sourceControlled);
            if (sourceControlled) {
                body.put("compensationVersion", targetCompVersionAfter);
            }
            body.put("status", LotStatus.MERGED.name());
            return ServiceResult.ok(body);
        });
    }

    /** 读取缸次的补偿批准并锁定，要求仍处于 FROZEN（已失效/已投料/未批准均拒绝受控投料前操作）。 */
    private Map<String, Object> requireFrozenCompensation(Map<String, Object> lot, String lotNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status, version FROM compensation WHERE lot_id = ? FOR UPDATE", lot.get("id"));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "受控缸次 " + lotNo + " 尚未取得效价补偿批准，拒绝操作");
        }
        Map<String, Object> comp = rows.get(0);
        if (!CompensationStatus.FROZEN.name().equals(comp.get("status"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "受控缸次 " + lotNo + " 补偿批准状态为 " + comp.get("status") + "，须重新审批后再操作");
        }
        return comp;
    }

    /** 逐染料比较两份冻结补偿：染料集合、批次号、系数版本与补偿目标克重必须完全一致。 */
    private void assertSameCompensation(Map<String, Object> sourceComp, Map<String, Object> targetComp,
                                        String sourceLotNo, String targetLotNo) {
        List<Map<String, Object>> sourceItems = jdbc.queryForList(
                "SELECT dye_code, batch_no, factor_version, compensated_target_g FROM compensation_item "
                        + "WHERE compensation_id = ? ORDER BY dye_code", sourceComp.get("id"));
        List<Map<String, Object>> targetItems = jdbc.queryForList(
                "SELECT dye_code, batch_no, factor_version, compensated_target_g FROM compensation_item "
                        + "WHERE compensation_id = ? ORDER BY dye_code", targetComp.get("id"));
        if (sourceItems.size() != targetItems.size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "受控并缸拒绝：两缸补偿染料集合不同");
        }
        for (int i = 0; i < sourceItems.size(); i++) {
            Map<String, Object> a = sourceItems.get(i);
            Map<String, Object> b = targetItems.get(i);
            boolean same = a.get("dye_code").equals(b.get("dye_code"))
                    && a.get("batch_no").equals(b.get("batch_no"))
                    && ((Number) a.get("factor_version")).intValue()
                        == ((Number) b.get("factor_version")).intValue()
                    && ((BigDecimal) a.get("compensated_target_g"))
                            .compareTo((BigDecimal) b.get("compensated_target_g")) == 0;
            if (!same) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "受控并缸拒绝：染料 " + a.get("dye_code") + " 补偿不一致——"
                                + sourceLotNo + " 批次 " + a.get("batch_no") + " v" + a.get("factor_version")
                                + " 目标 " + a.get("compensated_target_g") + "g；"
                                + targetLotNo + " 批次 " + b.get("batch_no") + " v" + b.get("factor_version")
                                + " 目标 " + b.get("compensated_target_g") + "g");
            }
        }
    }

    /**
     * 并缸后按合并布重、沿用同批次同系数重冻目标缸补偿目标，返回复查后新版本号。
     * 先锁定并校验各批次（版本仍有效、未到截止、余量足以支撑合并目标），全部通过后才改写冻结项，
     * 任一批次不满足则整单回滚，源缸与目标缸都保持原状态。
     */
    private int refreezeCompensationForMergedWeight(Map<String, Object> targetLot,
                                                    Map<String, Object> targetComp,
                                                    BigDecimal combinedWeight, Timestamp now) {
        BigDecimal ratio = (BigDecimal) targetLot.get("liquor_ratio");
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT dye_code, batch_no, factor_version, potency_factor FROM compensation_item "
                        + "WHERE compensation_id = ? ORDER BY dye_code", targetComp.get("id"));

        List<Map<String, Object>> reFrozen = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String batchNo = (String) item.get("batch_no");
            int factorVersion = ((Number) item.get("factor_version")).intValue();
            List<Map<String, Object>> batchRows = jdbc.queryForList(
                    "SELECT dye_code, available_g, expire_at, active FROM dye_batch "
                            + "WHERE batch_no = ? AND factor_version = ? FOR UPDATE",
                    batchNo, factorVersion);
            if (batchRows.isEmpty() || !Boolean.TRUE.equals(batchRows.get(0).get("active"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "受控并缸拒绝：批次 " + batchNo + " 系数版本 v" + factorVersion + " 已失效，请重新审批");
            }
            Map<String, Object> batch = batchRows.get(0);
            Timestamp expireAt = (Timestamp) batch.get("expire_at");
            if (!now.before(expireAt)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "受控并缸拒绝：批次 " + batchNo + " 已到/超过有效截止（恰在截止即失效）");
            }
            BigDecimal dosage = jdbc.queryForObject(
                    "SELECT ri.dosage_gpl FROM recipe_item ri JOIN recipe r ON r.id = ri.recipe_id "
                            + "WHERE r.recipe_code = ? AND r.version = ? AND ri.dye_code = ?",
                    BigDecimal.class, targetLot.get("recipe_code"),
                    targetLot.get("recipe_version"), item.get("dye_code"));
            BigDecimal factor = (BigDecimal) item.get("potency_factor");
            BigDecimal baseTarget = dosage.multiply(ratio).multiply(combinedWeight)
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal compensated = baseTarget.divide(factor, 3, RoundingMode.HALF_UP);
            BigDecimal available = (BigDecimal) batch.get("available_g");
            if (available.compareTo(compensated) < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "受控并缸拒绝：批次 " + batchNo + " 余量 " + available + "g 不足合并后补偿目标 "
                                + compensated + "g，整单回滚");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dyeCode", item.get("dye_code"));
            row.put("batchNo", batchNo);
            row.put("factorVersion", factorVersion);
            row.put("potencyFactor", factor);
            row.put("baseTargetG", baseTarget);
            row.put("compensatedTargetG", compensated);
            reFrozen.add(row);
        }

        jdbc.update("DELETE FROM compensation_item WHERE compensation_id = ?", targetComp.get("id"));
        for (Map<String, Object> row : reFrozen) {
            jdbc.update("INSERT INTO compensation_item (compensation_id, dye_code, batch_no, factor_version, "
                    + "potency_factor, base_target_g, compensated_target_g) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    targetComp.get("id"), row.get("dyeCode"), row.get("batchNo"), row.get("factorVersion"),
                    row.get("potencyFactor"), row.get("baseTargetG"), row.get("compensatedTargetG"));
        }
        int currentVersion = ((Number) targetComp.get("version")).intValue();
        int nextVersion = currentVersion + 1;
        jdbc.update("UPDATE compensation SET version = ?, updated_at = ? WHERE id = ? AND version = ?",
                nextVersion, now, targetComp.get("id"), currentVersion);
        return nextVersion;
    }

    // ---------------------------------------------------------------------
    // 分缸：已投料来源缸只读拆分到 2~4 个空闲缸；耗用按布重比例守恒分摊，库存/补偿不重扣
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult splitLot(SplitLotRequest req) {
        // 目标提交顺序即子缸编号（余量按编号升序循环分配），顺序属于业务参数，指纹保序。
        List<Map<String, Object>> targetPayload = new ArrayList<>();
        for (SplitLotTargetInput t : req.targets()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("lotNo", t.lotNo());
            entry.put("vatNo", t.vatNo());
            entry.put("fabricWeightKg", t.fabricWeightKg());
            targetPayload.add(entry);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceLotNo", req.sourceLotNo());
        payload.put("expectedVersion", req.expectedVersion());
        payload.put("targets", targetPayload);

        return idempotency.execute(req.requestId(), "SPLIT", payload, () -> doSplit(req, targetPayload));
    }

    private ServiceResult doSplit(SplitLotRequest req, List<Map<String, Object>> targetPayload) {
        // 先锁来源缸次行：与出缸判定互斥，判色先成则分缸拒绝、分缸先成则来源判色拒绝。
        Map<String, Object> source = lockLot(req.sourceLotNo());
        int currentVersion = ((Number) source.get("split_version")).intValue();
        if (req.expectedVersion() == null || req.expectedVersion() != currentVersion) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "分缸版本冲突：expectedVersion=" + req.expectedVersion() + "，来源缸当前分缸版本=" + currentVersion);
        }
        if (!LotStatus.FED.name().equals(source.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "仅已成功投料（FED）缸次允许分缸；来源缸 " + req.sourceLotNo() + " 当前状态 "
                            + source.get("status"));
        }
        Long judgmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_judgment WHERE lot_id = ?", Long.class, source.get("id"));
        if (judgmentCount != null && judgmentCount > 0) {
            // 判色竞争中判色事务先提交：来源已色判，分缸拒绝。
            throw new ApiException(HttpStatus.CONFLICT,
                    "来源缸 " + req.sourceLotNo() + " 已色判，未色判缸次才允许分缸");
        }
        Long priorSplit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_split WHERE source_lot_id = ?", Long.class, source.get("id"));
        if (priorSplit != null && priorSplit > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "来源缸 " + req.sourceLotNo() + " 已分缸，同一来源最多成功分缸一次");
        }

        List<SplitLotTargetInput> targets = req.targets();
        if (targets.size() < 2 || targets.size() > 4) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "一次分缸须拆成 2 至 4 个目标缸，实际 " + targets.size());
        }
        Set<String> lotNos = new LinkedHashSet<>();
        Set<String> vatNos = new LinkedHashSet<>();
        BigDecimal weightSum = BigDecimal.ZERO;
        for (SplitLotTargetInput t : targets) {
            if (t.fabricWeightKg() == null || t.fabricWeightKg().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "子缸 " + t.lotNo() + " 布重必须为正公斤数");
            }
            if (t.fabricWeightKg().stripTrailingZeros().scale() > 3) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "子缸 " + t.lotNo() + " 布重最多三位小数，实际 " + t.fabricWeightKg().toPlainString());
            }
            if (!lotNos.add(t.lotNo())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "目标子缸缸次号重复：" + t.lotNo());
            }
            if (!vatNos.add(t.vatNo())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "目标染缸重复占用：" + t.vatNo());
            }
            weightSum = weightSum.add(t.fabricWeightKg());
        }
        BigDecimal sourceWeight = (BigDecimal) source.get("fabric_weight_kg");
        if (weightSum.compareTo(sourceWeight) != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "子缸布重合计 " + weightSum.toPlainString() + "kg 必须精确等于来源布重 "
                            + sourceWeight.toPlainString() + "kg，整单拒绝");
        }
        for (SplitLotTargetInput t : targets) {
            Integer lotExists = jdbc.query("SELECT 1 FROM lot WHERE lot_no = ?",
                    rs -> rs.next() ? 1 : null, t.lotNo());
            if (lotExists != null) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "目标子缸缸次号 " + t.lotNo() + " 已存在，整单拒绝");
            }
            // 行存在即占用：来源缸此刻仍占原缸，指向原缸也会在此被拦截。
            Integer vatBusy = jdbc.query("SELECT 1 FROM vat_active WHERE vat_no = ?",
                    rs -> rs.next() ? 1 : null, t.vatNo());
            if (vatBusy != null) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "目标染缸 " + t.vatNo() + " 已被占用，非空闲缸整单拒绝");
            }
        }

        // 来源实际称量即原证据链：分缸只按实际克重守恒分摊，不按效价重算，不触碰批次/染料库存。
        List<Map<String, Object>> sourceWeighings = jdbc.queryForList(
                "SELECT w.id, w.dye_code, w.target_g, w.actual_g, w.base_target_g, w.batch_no, "
                        + "w.factor_version, w.potency_factor "
                        + "FROM weighing w JOIN feeding f ON f.id = w.feeding_id "
                        + "WHERE f.lot_id = ? ORDER BY w.dye_code", source.get("id"));
        if (sourceWeighings.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "来源缸 " + req.sourceLotNo() + " 缺少实际称量证据，拒绝分缸");
        }
        List<BigDecimal> childWeights = new ArrayList<>();
        for (SplitLotTargetInput t : targets) {
            childWeights.add(t.fabricWeightKg());
        }
        // dyeCode -> 按子缸编号对齐的分摊克重
        Map<String, List<BigDecimal>> allocatedByDye = new LinkedHashMap<>();
        for (Map<String, Object> w : sourceWeighings) {
            String dyeCode = (String) w.get("dye_code");
            BigDecimal actual = (BigDecimal) w.get("actual_g");
            List<BigDecimal> parts = SplitAllocation.allocate(actual, childWeights);
            BigDecimal partsSum = parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (partsSum.compareTo(actual) != 0) {
                throw new IllegalStateException("染料 " + dyeCode + " 分摊合计 " + partsSum
                        + " 不等于来源实际耗用 " + actual);
            }
            allocatedByDye.put(dyeCode, parts);
        }

        Timestamp now = nowTs();
        try {
            jdbc.update("INSERT INTO lot_split (request_id, source_lot_id, source_split_version, "
                    + "source_fabric_weight_kg, created_at) VALUES (?, ?, ?, ?, ?)",
                    req.requestId(), source.get("id"), currentVersion, sourceWeight, now);
        } catch (DuplicateKeyException concurrentSplit) {
            // 并发两次拆同源：唯一约束保证最多一次成功，败方整单拒绝。
            throw new ApiException(HttpStatus.CONFLICT,
                    "来源缸 " + req.sourceLotNo() + " 并发分缸冲突，同一来源最多成功分缸一次");
        }
        Long splitId = jdbc.queryForObject(
                "SELECT id FROM lot_split WHERE request_id = ?", Long.class, req.requestId());

        for (int i = 0; i < targets.size(); i++) {
            SplitLotTargetInput t = targets.get(i);
            int seq = i + 1;
            try {
                jdbc.update("INSERT INTO lot (lot_no, vat_no, recipe_code, recipe_version, liquor_ratio, "
                        + "fabric_weight_kg, controlled, split_version, split_parent_lot_id, split_seq, "
                        + "status, repair_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 0, ?, ?)",
                        t.lotNo(), t.vatNo(), source.get("recipe_code"), source.get("recipe_version"),
                        source.get("liquor_ratio"), t.fabricWeightKg(), source.get("controlled"),
                        source.get("id"), seq, LotStatus.FED.name(), now, now);
            } catch (DuplicateKeyException dupLot) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "目标子缸缸次号 " + t.lotNo() + " 并发冲突，整单回滚");
            }
            Long childLotId = jdbc.queryForObject(
                    "SELECT id FROM lot WHERE lot_no = ?", Long.class, t.lotNo());
            try {
                // 所有目标占用同事务建立：主键冲突拦截并发占用同一空闲缸的竞态。
                jdbc.update("INSERT INTO vat_active (vat_no, lot_id, since) VALUES (?, ?, ?)",
                        t.vatNo(), childLotId, now);
            } catch (DuplicateKeyException dupVat) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "目标染缸 " + t.vatNo() + " 并发占用冲突，整单回滚");
            }
            jdbc.update("INSERT INTO lot_split_item (split_id, seq, child_lot_id, fabric_weight_kg, created_at) "
                    + "VALUES (?, ?, ?, ?, ?)", splitId, seq, childLotId, t.fabricWeightKg(), now);
        }

        for (Map<String, Object> w : sourceWeighings) {
            String dyeCode = (String) w.get("dye_code");
            List<BigDecimal> parts = allocatedByDye.get(dyeCode);
            for (int i = 0; i < targets.size(); i++) {
                Long childLotId = jdbc.queryForObject(
                        "SELECT id FROM lot WHERE lot_no = ?", Long.class, targets.get(i).lotNo());
                jdbc.update("INSERT INTO split_weighing (split_id, child_lot_id, seq, dye_code, "
                        + "source_weighing_id, actual_g, target_g, base_target_g, batch_no, factor_version, "
                        + "potency_factor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        splitId, childLotId, i + 1, dyeCode, w.get("id"), parts.get(i),
                        w.get("target_g"), w.get("base_target_g"), w.get("batch_no"),
                        w.get("factor_version"), w.get("potency_factor"));
            }
        }

        int newVersion = currentVersion + 1;
        // 持锁状态下再以状态+版本做条件更新：判色并发改状态或版本过期时更新 0 行，整单回滚。
        int updated = jdbc.update(
                "UPDATE lot SET status = ?, split_version = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ? AND split_version = ?",
                LotStatus.SPLIT.name(), newVersion, now,
                source.get("id"), LotStatus.FED.name(), currentVersion);
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "来源缸 " + req.sourceLotNo() + " 在分缸并发中状态/版本变化，整单回滚");
        }
        // 源缸转已分缸只读并释放原缸。
        jdbc.update("DELETE FROM vat_active WHERE vat_no = ? AND lot_id = ?",
                source.get("vat_no"), source.get("id"));

        return ServiceResult.ok(splitView(req.sourceLotNo(), splitId, newVersion, now, targets,
                sourceWeighings, allocatedByDye));
    }

    /** 组装分缸成功响应：子缸编号、布重、逐染料分摊耗用与守恒结果。 */
    private Map<String, Object> splitView(String sourceLotNo, Long splitId, int newVersion, Timestamp now,
                                          List<SplitLotTargetInput> targets,
                                          List<Map<String, Object>> sourceWeighings,
                                          Map<String, List<BigDecimal>> allocatedByDye) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceLotNo", sourceLotNo);
        body.put("splitVersion", newVersion);
        body.put("status", LotStatus.SPLIT.name());
        body.put("splitId", splitId);
        body.put("createdAt", now.toInstant().toString());
        List<Map<String, Object>> children = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            SplitLotTargetInput t = targets.get(i);
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("seq", i + 1);
            child.put("lotNo", t.lotNo());
            child.put("vatNo", t.vatNo());
            child.put("fabricWeightKg", t.fabricWeightKg());
            child.put("status", LotStatus.FED.name());
            List<Map<String, Object>> usage = new ArrayList<>();
            for (Map<String, Object> w : sourceWeighings) {
                String dyeCode = (String) w.get("dye_code");
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("dyeCode", dyeCode);
                u.put("actualG", allocatedByDye.get(dyeCode).get(i));
                if (w.get("batch_no") != null) {
                    u.put("batchNo", w.get("batch_no"));
                    u.put("factorVersion", w.get("factor_version"));
                }
                usage.add(u);
            }
            child.put("weighings", usage);
            children.add(child);
        }
        body.put("children", children);
        List<Map<String, Object>> conservation = new ArrayList<>();
        for (Map<String, Object> w : sourceWeighings) {
            String dyeCode = (String) w.get("dye_code");
            BigDecimal sum = allocatedByDye.get(dyeCode).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sourceActual = (BigDecimal) w.get("actual_g");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("dyeCode", dyeCode);
            c.put("sourceActualG", sourceActual);
            c.put("childrenSumG", sum);
            c.put("conserved", sum.compareTo(sourceActual) == 0);
            conservation.add(c);
        }
        body.put("conservation", conservation);
        body.put("batchStockChanged", false);
        return body;
    }

    /** 分缸谱系查询：来源只读，展示子缸编号、布重与各染料分摊耗用及守恒核对。 */
    @Transactional(readOnly = true)
    public ServiceResult getLineage(String lotNo) {
        Map<String, Object> lot = requireLot(lotNo);
        Long sourceLotId = (Long) lot.get("id");
        if (lot.get("split_parent_lot_id") != null) {
            // 子缸查询：回到其来源缸的谱系。
            sourceLotId = ((Number) lot.get("split_parent_lot_id")).longValue();
        }
        List<Map<String, Object>> splitRows = jdbc.queryForList(
                "SELECT * FROM lot_split WHERE source_lot_id = ?", sourceLotId);
        if (splitRows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "缸次 " + lotNo + " 无分缸谱系");
        }
        Map<String, Object> split = splitRows.get(0);
        Map<String, Object> source = jdbc.queryForMap("SELECT * FROM lot WHERE id = ?", sourceLotId);

        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT i.seq, i.fabric_weight_kg, l.lot_no, l.vat_no, l.status AS child_status "
                        + "FROM lot_split_item i JOIN lot l ON l.id = i.child_lot_id "
                        + "WHERE i.split_id = ? ORDER BY i.seq", split.get("id"));
        List<Map<String, Object>> sourceWeighings = jdbc.queryForList(
                "SELECT w.id, w.dye_code, w.actual_g FROM weighing w JOIN feeding f ON f.id = w.feeding_id "
                        + "WHERE f.lot_id = ? ORDER BY w.dye_code", sourceLotId);

        List<Map<String, Object>> children = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> child = new LinkedHashMap<>();
            int seq = ((Number) item.get("seq")).intValue();
            child.put("seq", seq);
            child.put("lotNo", item.get("lot_no"));
            child.put("vatNo", item.get("vat_no"));
            child.put("fabricWeightKg", item.get("fabric_weight_kg"));
            child.put("status", item.get("child_status"));
            List<Map<String, Object>> usage = new ArrayList<>();
            jdbc.query("SELECT dye_code, actual_g, target_g, base_target_g, batch_no, factor_version, "
                    + "potency_factor FROM split_weighing WHERE split_id = ? AND seq = ? ORDER BY dye_code", rs -> {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("dyeCode", rs.getString("dye_code"));
                u.put("actualG", rs.getBigDecimal("actual_g"));
                String batchNo = rs.getString("batch_no");
                if (batchNo != null) {
                    u.put("batchNo", batchNo);
                    u.put("factorVersion", rs.getInt("factor_version"));
                    u.put("potencyFactor", rs.getBigDecimal("potency_factor"));
                    u.put("targetG", rs.getBigDecimal("target_g"));
                    u.put("baseTargetG", rs.getBigDecimal("base_target_g"));
                }
                usage.add(u);
            }, split.get("id"), seq);
            child.put("weighings", usage);
            children.add(child);
        }

        List<Map<String, Object>> conservation = new ArrayList<>();
        for (Map<String, Object> w : sourceWeighings) {
            String dyeCode = (String) w.get("dye_code");
            BigDecimal childSum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(actual_g), 0) FROM split_weighing WHERE split_id = ? AND dye_code = ?",
                    BigDecimal.class, split.get("id"), dyeCode);
            BigDecimal sourceActual = (BigDecimal) w.get("actual_g");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("dyeCode", dyeCode);
            c.put("sourceActualG", sourceActual);
            c.put("childrenSumG", childSum);
            c.put("conserved", childSum.compareTo(sourceActual) == 0);
            conservation.add(c);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceLotNo", source.get("lot_no"));
        body.put("sourceStatus", source.get("status"));
        body.put("sourceFabricWeightKg", source.get("fabric_weight_kg"));
        body.put("splitVersion", source.get("split_version"));
        body.put("requestId", split.get("request_id"));
        body.put("createdAt", ((Timestamp) split.get("created_at")).toInstant().toString());
        body.put("queriedLotNo", lotNo);
        body.put("queriedIsChild", lot.get("split_parent_lot_id") != null);
        body.put("children", children);
        body.put("conservation", conservation);
        return ServiceResult.ok(body);
    }

    // ---------------------------------------------------------------------
    // 投料：放大换算 → 偏差校验 → 称量记录与库存扣减同一事务原子完成
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult feed(FeedRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
        payload.put("expectedVersion", req.expectedVersion());
        List<Map<String, Object>> weighingPayload = new ArrayList<>();
        for (WeighingInput w : req.weighings()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dyeCode", w.dyeCode());
            entry.put("actualG", w.actualG());
            weighingPayload.add(entry);
        }
        weighingPayload.sort((a, b) -> String.valueOf(a.get("dyeCode")).compareTo(String.valueOf(b.get("dyeCode"))));
        payload.put("weighings", weighingPayload);

        return idempotency.execute(req.requestId(), "FEED", payload, () -> {
            Map<String, Object> lot = requireLot(req.lotNo());
            if (lot.get("split_parent_lot_id") != null) {
                // 子缸继承来源已投料进度与证据链，禁止重新调用首次投料重复扣批次/染料余量。
                throw new ApiException(HttpStatus.CONFLICT,
                        "子缸 " + req.lotNo() + " 已继承来源投料进度，不允许重新首次投料（不得重复扣减余量）");
            }
            requireStatus(lot, LotStatus.CREATED);
            if (Boolean.TRUE.equals(lot.get("controlled"))) {
                return feedControlled(req, lot);
            }
            if (req.expectedVersion() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "旧非受控缸次投料不接受 expectedVersion，请使用原投料语义");
            }
            return feedLegacy(req, lot);
        });
    }

    /**
     * 受控投料：同时验证补偿版本（FROZEN + expectedVersion）、称量偏差（按补偿目标）与批次有效期，
     * 原子扣减各染料批次余量并写称量链；第二种不足时第一种也不扣。两缸争同批余量由条件更新防超耗。
     */
    private ServiceResult feedControlled(FeedRequest req, Map<String, Object> lot) {
        if (req.expectedVersion() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "受控缸次投料须携带补偿批准 expectedVersion");
        }
        Timestamp now = nowTs();

        // 锁定补偿批准：FROZEN 且版本匹配；修订/失效/已投料在此拦截。
        List<Map<String, Object>> compRows = jdbc.queryForList(
                "SELECT id, status, version FROM compensation WHERE lot_id = ? FOR UPDATE", lot.get("id"));
        if (compRows.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "受控缸次 " + req.lotNo() + " 尚未取得效价补偿批准，拒绝投料");
        }
        Map<String, Object> comp = compRows.get(0);
        int compVersion = ((Number) comp.get("version")).intValue();
        if (!CompensationStatus.FROZEN.name().equals(comp.get("status"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "受控缸次 " + req.lotNo() + " 补偿批准状态为 " + comp.get("status")
                            + "（系数修订或已失效），须重新审批后投料");
        }
        if (req.expectedVersion() != compVersion) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "补偿批准版本冲突：expectedVersion=" + req.expectedVersion() + "，当前版本=" + compVersion);
        }

        List<Map<String, Object>> frozenItems = jdbc.queryForList(
                "SELECT dye_code, batch_no, factor_version, potency_factor, base_target_g, compensated_target_g "
                        + "FROM compensation_item WHERE compensation_id = ? ORDER BY dye_code",
                comp.get("id"));

        Map<String, BigDecimal> actualByDye = new LinkedHashMap<>();
        for (WeighingInput w : req.weighings()) {
            if (actualByDye.put(w.dyeCode(), w.actualG()) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "染料 " + w.dyeCode() + " 称量记录重复");
            }
        }
        Set<String> required = new LinkedHashSet<>();
        List<Map<String, Object>> computed = new ArrayList<>();
        for (Map<String, Object> fi : frozenItems) {
            String dyeCode = (String) fi.get("dye_code");
            required.add(dyeCode);
            BigDecimal actual = actualByDye.get(dyeCode);
            if (actual == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "缺少染料 " + dyeCode + " 的实际称量，拒绝投料");
            }
            // 锁定具体批次系数版本：修订后 active=FALSE 或缺失即拒绝。
            List<Map<String, Object>> batchRows = jdbc.queryForList(
                    "SELECT dye_code, potency_factor, available_g, expire_at, active "
                            + "FROM dye_batch WHERE batch_no = ? AND factor_version = ? FOR UPDATE",
                    fi.get("batch_no"), fi.get("factor_version"));
            if (batchRows.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND,
                        "批次 " + fi.get("batch_no") + " 系数版本 v" + fi.get("factor_version") + " 不存在");
            }
            Map<String, Object> batch = batchRows.get(0);
            if (!dyeCode.equals(batch.get("dye_code"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "染料代码错配：批次 " + fi.get("batch_no") + " 属于 " + batch.get("dye_code")
                                + "，不能用于 " + dyeCode + "，整单拒绝");
            }
            if (!Boolean.TRUE.equals(batch.get("active"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "批次 " + fi.get("batch_no") + " 系数版本 v" + fi.get("factor_version")
                                + " 已被修订失效，未投料批准不得投料（须重新审批）");
            }
            Timestamp expireAt = (Timestamp) batch.get("expire_at");
            if (!now.before(expireAt)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "批次 " + fi.get("batch_no") + " 已到/超过有效截止 " + expireAt.toInstant()
                                + "（恰在截止即失效），整单拒绝投料");
            }
            BigDecimal factor = (BigDecimal) fi.get("potency_factor");
            if (factor.signum() <= 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "批次 " + fi.get("batch_no") + " 冻结系数非正，整单拒绝投料");
            }
            BigDecimal target = (BigDecimal) fi.get("compensated_target_g");
            BigDecimal deviation = actual.subtract(target)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(target, DEVIATION_SCALE, RoundingMode.HALF_UP);
            if (deviation.abs().compareTo(WEIGH_LIMIT_PCT) > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "染料 " + dyeCode + " 按补偿目标 " + target + "g 的称量偏差 " + deviation
                                + "% 超过 ±0.5%（恰 0.5% 合法），整单拒绝投料且不扣批次余量");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dyeCode", dyeCode);
            row.put("batchNo", fi.get("batch_no"));
            row.put("factorVersion", fi.get("factor_version"));
            row.put("potencyFactor", factor);
            row.put("baseTargetG", fi.get("base_target_g"));
            row.put("targetG", target);
            row.put("actualG", actual);
            row.put("deviationPct", deviation);
            row.put("availableG", batch.get("available_g"));
            computed.add(row);
        }
        if (!actualByDye.keySet().equals(required)) {
            Set<String> extra = new LinkedHashSet<>(actualByDye.keySet());
            extra.removeAll(required);
            throw new ApiException(HttpStatus.BAD_REQUEST, "存在补偿清单外染料称量：" + extra + "，拒绝投料");
        }

        // 余量预检：任一批次不足整单回滚，杜绝部分扣减。
        for (Map<String, Object> row : computed) {
            BigDecimal available = (BigDecimal) row.get("availableG");
            BigDecimal actual = (BigDecimal) row.get("actualG");
            if (available.compareTo(actual) < 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "批次 " + row.get("batchNo") + " 余量 " + available + "g 不足实际称量 " + actual
                                + "g，整单拒绝投料且不扣任何批次余量");
            }
        }

        jdbc.update("INSERT INTO feeding (request_id, lot_id, fed_at) VALUES (?, ?, ?)",
                req.requestId(), lot.get("id"), now);
        Long feedingId = jdbc.queryForObject(
                "SELECT id FROM feeding WHERE request_id = ?", Long.class, req.requestId());
        for (Map<String, Object> row : computed) {
            jdbc.update("INSERT INTO weighing (feeding_id, dye_code, target_g, actual_g, deviation_pct, "
                    + "batch_no, factor_version, potency_factor, base_target_g) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    feedingId, row.get("dyeCode"), row.get("targetG"), row.get("actualG"),
                    row.get("deviationPct"), row.get("batchNo"), row.get("factorVersion"),
                    row.get("potencyFactor"), row.get("baseTargetG"));
            int deducted = jdbc.update(
                    "UPDATE dye_batch SET available_g = available_g - ? WHERE batch_no = ? AND factor_version = ? "
                            + "AND available_g >= ?",
                    row.get("actualG"), row.get("batchNo"), row.get("factorVersion"), row.get("actualG"));
            if (deducted != 1) {
                // 并发扣减防护：两缸争同批余量时败方整单回滚，不超耗。
                throw new ApiException(HttpStatus.CONFLICT,
                        "批次 " + row.get("batchNo") + " 并发余量扣减失败，整单回滚不超耗");
            }
        }
        int fedCompVersion = compVersion + 1;
        int casComp = jdbc.update(
                "UPDATE compensation SET status = ?, invalid_reason = NULL, version = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ? AND version = ?",
                CompensationStatus.FED.name(), fedCompVersion, now,
                comp.get("id"), CompensationStatus.FROZEN.name(), compVersion);
        if (casComp != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "补偿批准 " + req.lotNo() + " 状态在投料并发中变化，整单回滚");
        }
        jdbc.update("UPDATE lot SET status = ?, updated_at = ? WHERE id = ?",
                LotStatus.FED.name(), now, lot.get("id"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", req.requestId());
        body.put("lotNo", req.lotNo());
        body.put("controlled", true);
        body.put("liquorRatio", lot.get("liquor_ratio"));
        body.put("fabricWeightKg", lot.get("fabric_weight_kg"));
        List<Map<String, Object>> weighingView = new ArrayList<>();
        for (Map<String, Object> row : computed) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("dyeCode", row.get("dyeCode"));
            w.put("batchNo", row.get("batchNo"));
            w.put("factorVersion", row.get("factorVersion"));
            w.put("potencyFactor", row.get("potencyFactor"));
            w.put("baseTargetG", row.get("baseTargetG"));
            w.put("targetG", row.get("targetG"));
            w.put("actualG", row.get("actualG"));
            w.put("deviationPct", row.get("deviationPct"));
            weighingView.add(w);
        }
        body.put("weighings", weighingView);
        body.put("compensationVersion", fedCompVersion);
        body.put("status", LotStatus.FED.name());
        body.put("fedAt", now.toInstant().toString());
        return ServiceResult.ok(body);
    }

    /** 旧非受控缸次投料语义：按放大配方目标校验偏差并扣染化料总库存，不涉及批次/补偿。 */
    private ServiceResult feedLegacy(FeedRequest req, Map<String, Object> lot) {
        Long versionRecipeId = recipeId((String) lot.get("recipe_code"),
                ((Number) lot.get("recipe_version")).intValue());
        List<Map<String, Object>> items = recipeItems(versionRecipeId);
        BigDecimal ratio = (BigDecimal) lot.get("liquor_ratio");
        BigDecimal fabricKg = (BigDecimal) lot.get("fabric_weight_kg");

        Map<String, BigDecimal> actualByDye = new LinkedHashMap<>();
        for (WeighingInput w : req.weighings()) {
            if (actualByDye.put(w.dyeCode(), w.actualG()) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "染化料 " + w.dyeCode() + " 称量记录重复");
            }
        }
        Set<String> required = new LinkedHashSet<>();
        List<Map<String, Object>> computed = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String dyeCode = (String) item.get("dye_code");
            BigDecimal dosage = (BigDecimal) item.get("dosage_gpl");
            required.add(dyeCode);
            BigDecimal target = dosage.multiply(ratio).multiply(fabricKg).setScale(4, RoundingMode.HALF_UP);
            BigDecimal actual = actualByDye.get(dyeCode);
            if (actual == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "缺少染化料 " + dyeCode + " 的实际称量，拒绝投料");
            }
            BigDecimal deviation = actual.subtract(target)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(target, DEVIATION_SCALE, RoundingMode.HALF_UP);
            if (deviation.abs().compareTo(WEIGH_LIMIT_PCT) > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "染化料 " + dyeCode + " 称量偏差 " + deviation + "% 超过 ±0.5%（恰 0.5% 合法），整单拒绝投料且不扣库存");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dyeCode", dyeCode);
            row.put("targetG", target);
            row.put("actualG", actual);
            row.put("deviationPct", deviation);
            computed.add(row);
        }
        if (!actualByDye.keySet().equals(required)) {
            Set<String> extra = new LinkedHashSet<>(actualByDye.keySet());
            extra.removeAll(required);
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "存在配方外染化料称量：" + extra + "，拒绝投料");
        }

        // 库存预检：任一染化料不足整单回滚，杜绝部分扣减。
        for (Map<String, Object> row : computed) {
            BigDecimal stock = jdbc.queryForObject("SELECT stock_g FROM dye WHERE code = ?",
                    BigDecimal.class, row.get("dyeCode"));
            if (stock == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "染化料 " + row.get("dyeCode") + " 不存在，拒绝投料");
            }
            if (stock.compareTo((BigDecimal) row.get("actualG")) < 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "染化料 " + row.get("dyeCode") + " 库存不足，拒绝投料且不扣库存");
            }
        }

        Timestamp now = nowTs();
        jdbc.update("INSERT INTO feeding (request_id, lot_id, fed_at) VALUES (?, ?, ?)",
                req.requestId(), lot.get("id"), now);
        Long feedingId = jdbc.queryForObject(
                "SELECT id FROM feeding WHERE request_id = ?", Long.class, req.requestId());
        for (Map<String, Object> row : computed) {
            jdbc.update("INSERT INTO weighing (feeding_id, dye_code, target_g, actual_g, deviation_pct) "
                    + "VALUES (?, ?, ?, ?, ?)",
                    feedingId, row.get("dyeCode"), row.get("targetG"), row.get("actualG"),
                    row.get("deviationPct"));
            int deducted = jdbc.update("UPDATE dye SET stock_g = stock_g - ? WHERE code = ? AND stock_g >= ?",
                    row.get("actualG"), row.get("dyeCode"), row.get("actualG"));
            if (deducted != 1) {
                // 并发扣减防护：与预检构成双保险，失败随事务整体回滚。
                throw new ApiException(HttpStatus.CONFLICT,
                        "染化料 " + row.get("dyeCode") + " 并发扣减失败，整单回滚");
            }
        }
        jdbc.update("UPDATE lot SET status = ?, updated_at = ? WHERE id = ?",
                LotStatus.FED.name(), now, lot.get("id"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", req.requestId());
        body.put("lotNo", req.lotNo());
        body.put("controlled", false);
        body.put("liquorRatio", ratio);
        body.put("fabricWeightKg", fabricKg);
        body.put("weighings", computed);
        body.put("status", LotStatus.FED.name());
        body.put("fedAt", now.toInstant().toString());
        return ServiceResult.ok(body);
    }

    // ---------------------------------------------------------------------
    // 出缸判定：同配方缸差 > 0.5 回修一次，回修后仍超差降级
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult judge(JudgeRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
        payload.put("deltaE", req.deltaE());
        payload.put("referenceLotNo", req.referenceLotNo() == null ? "" : req.referenceLotNo());
        return idempotency.execute(req.requestId(), "JUDGE", payload, () -> {
            // 锁定被判定缸次：与分缸竞争来源缸行锁，分缸先成则状态为 SPLIT，判色拒绝。
            Map<String, Object> lot = lockLot(req.lotNo());
            String status = (String) lot.get("status");
            int round;
            if (LotStatus.FED.name().equals(status)) {
                round = 0;
            } else if (LotStatus.REPAIRING.name().equals(status)) {
                round = ((Number) lot.get("repair_count")).intValue();
            } else {
                throw new ApiException(HttpStatus.CONFLICT,
                        "缸次 " + req.lotNo() + " 当前状态 " + status + "，不允许出缸判定（须已投料或回修中）");
            }

            Map<String, Object> reference = null;
            if (req.referenceLotNo() != null && !req.referenceLotNo().isBlank()) {
                reference = requireLot(req.referenceLotNo());
                if (!reference.get("recipe_code").equals(lot.get("recipe_code"))
                        || ((Number) reference.get("recipe_version")).intValue()
                        != ((Number) lot.get("recipe_version")).intValue()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "对比缸必须同配方同冻结版本");
                }
            } else {
                reference = latestJudgedSibling(lot);
            }

            BigDecimal refDelta = null;
            BigDecimal diff = null;
            if (reference != null) {
                refDelta = latestDelta((Long) reference.get("id"));
                if (refDelta == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "对比缸 " + reference.get("lot_no") + " 尚无出缸测色记录，无法计算缸差");
                }
                diff = req.deltaE().subtract(refDelta).abs().setScale(4, RoundingMode.HALF_UP);
            }

            Verdict verdict;
            LotStatus nextStatus;
            boolean over = diff != null && diff.compareTo(LOT_DIFF_LIMIT) > 0;
            if (!over) {
                verdict = Verdict.PASS;
                nextStatus = LotStatus.PASSED;
            } else if (round == 0) {
                verdict = Verdict.REPAIR;
                nextStatus = LotStatus.REPAIRING;
            } else {
                verdict = Verdict.DOWNGRADE;
                nextStatus = LotStatus.DOWNGRADED;
            }

            Timestamp now = nowTs();
            jdbc.update("INSERT INTO lot_judgment (request_id, lot_id, round, delta_e, reference_lot_id, "
                    + "reference_delta_e, diff, verdict, judged_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    req.requestId(), lot.get("id"), round, req.deltaE(),
                    reference == null ? null : reference.get("id"), refDelta, diff, verdict.name(), now);
            int newRepairCount = ((Number) lot.get("repair_count")).intValue()
                    + (verdict == Verdict.REPAIR ? 1 : 0);
            jdbc.update("UPDATE lot SET status = ?, repair_count = ?, updated_at = ? WHERE id = ?",
                    nextStatus.name(), newRepairCount, now, lot.get("id"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", req.requestId());
            body.put("lotNo", req.lotNo());
            body.put("round", round);
            body.put("deltaE", req.deltaE());
            body.put("referenceLotNo", reference == null ? null : reference.get("lot_no"));
            body.put("referenceDeltaE", refDelta);
            body.put("diff", diff);
            body.put("limit", LOT_DIFF_LIMIT);
            body.put("verdict", verdict.name());
            body.put("status", nextStatus.name());
            body.put("repairCount", newRepairCount);
            body.put("note", verdictNote(verdict));
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------------
    // 判定归档（终态）与迟到测色
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult archiveLot(String lotNo) {
        Map<String, Object> lot = requireLot(lotNo);
        String status = (String) lot.get("status");
        boolean replayed = LotStatus.ARCHIVED.name().equals(status);
        if (!replayed) {
            if (LotStatus.CREATED.name().equals(status) || LotStatus.FED.name().equals(status)
                    || LotStatus.REPAIRING.name().equals(status) || LotStatus.MERGED.name().equals(status)
                    || LotStatus.SPLIT.name().equals(status)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "缸次 " + lotNo + " 状态 " + status + "，不允许归档（已分缸来源只读，仅查谱系）");
            }
            Timestamp now = nowTs();
            jdbc.update("UPDATE lot SET status = ?, updated_at = ? WHERE id = ?",
                    LotStatus.ARCHIVED.name(), now, lot.get("id"));
            jdbc.update("DELETE FROM vat_active WHERE lot_id = ?", lot.get("id"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lotNo", lotNo);
        body.put("status", LotStatus.ARCHIVED.name());
        body.put("terminal", true);
        body.put("replayed", replayed);
        return ServiceResult.ok(body);
    }

    @Transactional
    public ServiceResult receiveLateColor(String lotNo, LateColorRequest req) {
        Map<String, Object> lot = requireLot(lotNo);
        Timestamp now = nowTs();
        boolean archived = LotStatus.ARCHIVED.name().equals(lot.get("status"));
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO late_color_event (lot_id, delta_e, note, received_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, lot.get("id"));
            ps.setBigDecimal(2, req.deltaE());
            ps.setString(3, req.note());
            ps.setTimestamp(4, now);
            return ps;
        }, keyHolder);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", keyHolder.getKey());
        body.put("lotNo", lotNo);
        body.put("deltaE", req.deltaE());
        body.put("archived", archived);
        body.put("recordedOnly", archived);
        body.put("message", archived
                ? "缸次已判定归档（终态），迟到测色仅登记事件，不改变判定"
                : "测色事件已登记");
        return ServiceResult.ok(body);
    }

    // ---------------------------------------------------------------------
    // 查询与复算
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ServiceResult getLot(String lotNo) {
        return ServiceResult.ok(lotView(lotNo));
    }

    /**
     * 复算配方版本、称量链与缸差台账：重算目标克重/偏差/缸差并与库存与台账逐笔比对。
     */
    @Transactional(readOnly = true)
    public ServiceResult recalculate(String recipeCode) {
        List<Map<String, Object>> versions = new ArrayList<>();
        List<Map<String, Object>> versionRows = jdbc.queryForList(
                "SELECT id, version, liquor_ratio, status FROM recipe WHERE recipe_code = ? ORDER BY version",
                recipeCode);
        for (Map<String, Object> v : versionRows) {
            Map<String, Object> view = new LinkedHashMap<>(v);
            view.put("items", recipeItems(((Number) v.get("id")).longValue()));
            versions.add(view);
        }
        if (versions.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "配方 " + recipeCode + " 不存在");
        }

        List<Map<String, Object>> weighingMismatches = new ArrayList<>();
        int weighingChecked = 0;
        List<Map<String, Object>> lotRows = jdbc.queryForList(
                "SELECT * FROM lot WHERE recipe_code = ? ORDER BY id", recipeCode);
        for (Map<String, Object> lot : lotRows) {
            BigDecimal ratio = (BigDecimal) lot.get("liquor_ratio");
            BigDecimal fabric = (BigDecimal) lot.get("fabric_weight_kg");
            boolean controlledLot = Boolean.TRUE.equals(lot.get("controlled"));
            List<Map<String, Object>> stored = jdbc.queryForList(
                    "SELECT dye_code, target_g, actual_g, deviation_pct FROM weighing w "
                            + "JOIN feeding f ON f.id = w.feeding_id WHERE f.lot_id = ? ORDER BY w.id",
                    lot.get("id"));
            for (Map<String, Object> w : stored) {
                BigDecimal target;
                if (controlledLot) {
                    // 受控缸称量目标冻结于补偿批准（原目标/系数，三位小数），已投料历史不随批次修订改变。
                    target = jdbc.queryForObject(
                            "SELECT ci.compensated_target_g FROM compensation_item ci "
                                    + "JOIN compensation c ON c.id = ci.compensation_id "
                                    + "WHERE c.lot_id = ? AND ci.dye_code = ?",
                            BigDecimal.class, lot.get("id"), w.get("dye_code"));
                } else {
                    BigDecimal dosage = jdbc.queryForObject(
                            "SELECT dosage_gpl FROM recipe_item ri JOIN recipe r ON r.id = ri.recipe_id "
                                    + "WHERE r.recipe_code = ? AND r.version = ? AND ri.dye_code = ?",
                            BigDecimal.class, recipeCode, lot.get("recipe_version"), w.get("dye_code"));
                    target = dosage.multiply(ratio).multiply(fabric).setScale(4, RoundingMode.HALF_UP);
                }
                BigDecimal deviation = ((BigDecimal) w.get("actual_g")).subtract(target)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(target, DEVIATION_SCALE, RoundingMode.HALF_UP);
                weighingChecked++;
                if (target.compareTo((BigDecimal) w.get("target_g")) != 0
                        || deviation.compareTo((BigDecimal) w.get("deviation_pct")) != 0) {
                    Map<String, Object> mismatch = new LinkedHashMap<>();
                    mismatch.put("lotNo", lot.get("lot_no"));
                    mismatch.put("dyeCode", w.get("dye_code"));
                    mismatch.put("controlled", controlledLot);
                    mismatch.put("storedTargetG", w.get("target_g"));
                    mismatch.put("recomputedTargetG", target);
                    mismatch.put("storedDeviationPct", w.get("deviation_pct"));
                    mismatch.put("recomputedDeviationPct", deviation);
                    weighingMismatches.add(mismatch);
                }
            }
        }

        List<Map<String, Object>> judgmentRows = jdbc.queryForList(
                "SELECT j.*, l.lot_no AS lot_no FROM lot_judgment j JOIN lot l ON l.id = j.lot_id "
                        + "WHERE l.recipe_code = ? ORDER BY j.id", recipeCode);
        List<Map<String, Object>> judgmentMismatches = new ArrayList<>();
        for (Map<String, Object> j : judgmentRows) {
            BigDecimal storedDiff = (BigDecimal) j.get("diff");
            Verdict storedVerdict = Verdict.valueOf((String) j.get("verdict"));
            BigDecimal recomputedDiff = null;
            if (j.get("reference_lot_id") != null) {
                Map<String, Object> refRow = jdbc.queryForMap(
                        "SELECT delta_e FROM lot_judgment WHERE lot_id = ? ORDER BY id DESC LIMIT 1",
                        j.get("reference_lot_id"));
                recomputedDiff = ((BigDecimal) j.get("delta_e")).subtract((BigDecimal) refRow.get("delta_e"))
                        .abs().setScale(4, RoundingMode.HALF_UP);
            }
            int round = ((Number) j.get("round")).intValue();
            boolean over = recomputedDiff != null && recomputedDiff.compareTo(LOT_DIFF_LIMIT) > 0;
            Verdict expectedVerdict;
            if (!over) {
                expectedVerdict = Verdict.PASS;
            } else if (round == 0) {
                expectedVerdict = Verdict.REPAIR;
            } else {
                expectedVerdict = Verdict.DOWNGRADE;
            }
            boolean diffOk = storedDiff == null ? recomputedDiff == null
                    : recomputedDiff != null && storedDiff.compareTo(recomputedDiff) == 0;
            if (!diffOk || storedVerdict != expectedVerdict) {
                Map<String, Object> mismatch = new LinkedHashMap<>();
                mismatch.put("lotNo", j.get("lot_no"));
                mismatch.put("storedDiff", storedDiff);
                mismatch.put("recomputedDiff", recomputedDiff);
                mismatch.put("storedVerdict", storedVerdict.name());
                mismatch.put("expectedVerdict", expectedVerdict.name());
                judgmentMismatches.add(mismatch);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recipeCode", recipeCode);
        report.put("versions", versions);
        report.put("weighingChecked", weighingChecked);
        report.put("weighingMismatches", weighingMismatches);
        report.put("judgmentChecked", judgmentRows.size());
        report.put("judgmentMismatches", judgmentMismatches);
        report.put("consistent", weighingMismatches.isEmpty() && judgmentMismatches.isEmpty());
        return ServiceResult.ok(report);
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    private void validateItems(List<RecipeItemInput> items) {
        Set<String> codes = new LinkedHashSet<>();
        for (RecipeItemInput item : items) {
            if (!codes.add(item.dyeCode())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "配方内染化料 " + item.dyeCode() + " 重复");
            }
            Integer dyeExists = jdbc.query("SELECT 1 FROM dye WHERE code = ?",
                    rs -> rs.next() ? 1 : null, item.dyeCode());
            if (dyeExists == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "染化料 " + item.dyeCode() + " 不存在");
            }
        }
    }

    private void insertItems(Long recipeId, List<RecipeItemInput> items) {
        for (RecipeItemInput item : items) {
            jdbc.update("INSERT INTO recipe_item (recipe_id, dye_code, dosage_gpl) VALUES (?, ?, ?)",
                    recipeId, item.dyeCode(), item.dosageGpl());
        }
    }

    private Map<String, Object> latestRecipe(String recipeCode) {
        Map<String, Object> row = jdbc.queryForList(
                "SELECT id, version, liquor_ratio, status FROM recipe WHERE recipe_code = ? "
                        + "ORDER BY version DESC LIMIT 1", recipeCode)
                .stream().findFirst().orElse(null);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "配方 " + recipeCode + " 不存在");
        }
        return row;
    }

    private Map<String, Object> requirePublishedRecipe(String code, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, liquor_ratio, status FROM recipe WHERE recipe_code = ? AND version = ?",
                code, version);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "配方 " + code + " v" + version + " 不存在");
        }
        Map<String, Object> row = rows.get(0);
        if (!RecipeStatus.PUBLISHED.name().equals(row.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "配方 " + code + " v" + version + " 未发布冻结，禁止排缸投料");
        }
        return row;
    }

    private Long recipeId(String code, int version) {
        return jdbc.queryForObject("SELECT id FROM recipe WHERE recipe_code = ? AND version = ?",
                Long.class, code, version);
    }

    private List<Map<String, Object>> recipeItems(Long recipeId) {
        return jdbc.queryForList(
                "SELECT dye_code, dosage_gpl FROM recipe_item WHERE recipe_id = ? ORDER BY dye_code",
                recipeId);
    }

    private Map<String, Object> requireLot(String lotNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM lot WHERE lot_no = ?", lotNo);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "缸次 " + lotNo + " 不存在");
        }
        return rows.get(0);
    }

    /** 读取并锁定缸次行（FOR UPDATE）：分缸与来源判色借此互斥，先成交者让后者拒绝。 */
    private Map<String, Object> lockLot(String lotNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM lot WHERE lot_no = ? FOR UPDATE", lotNo);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "缸次 " + lotNo + " 不存在");
        }
        return rows.get(0);
    }

    private void requireStatus(Map<String, Object> lot, LotStatus expected) {
        if (!expected.name().equals(lot.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "缸次 " + lot.get("lot_no") + " 当前状态 " + lot.get("status")
                            + "，要求状态 " + expected.name());
        }
    }

    private Map<String, Object> latestJudgedSibling(Map<String, Object> lot) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT l.* FROM lot l JOIN lot_judgment j ON j.lot_id = l.id "
                        + "WHERE l.recipe_code = ? AND l.recipe_version = ? AND l.id <> ? "
                        + "GROUP BY l.id ORDER BY MAX(j.id) DESC LIMIT 1",
                lot.get("recipe_code"), lot.get("recipe_version"), lot.get("id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal latestDelta(Long lotId) {
        List<BigDecimal> values = jdbc.query(
                "SELECT delta_e FROM lot_judgment WHERE lot_id = ? ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getBigDecimal("delta_e"), lotId);
        return values.isEmpty() ? null : values.get(0);
    }

    private String verdictNote(Verdict verdict) {
        return switch (verdict) {
            case PASS -> "缸差不超过 0.5，合格放行";
            case REPAIR -> "缸差超过 0.5，须回修（仅允许一次）";
            case DOWNGRADE -> "回修后缸差仍超过 0.5，转降级处置";
        };
    }

    private Map<String, Object> recipeView(String code, int version) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT recipe_code, version, liquor_ratio, status FROM recipe WHERE recipe_code = ? AND version = ?",
                code, version);
        Long id = recipeId(code, version);
        row.put("items", recipeItems(id));
        return row;
    }

    private Map<String, Object> lotView(String lotNo) {
        Map<String, Object> lot = requireLot(lotNo);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("lotNo", lot.get("lot_no"));
        view.put("vatNo", lot.get("vat_no"));
        view.put("recipeCode", lot.get("recipe_code"));
        view.put("recipeVersion", lot.get("recipe_version"));
        view.put("liquorRatio", lot.get("liquor_ratio"));
        view.put("fabricWeightKg", lot.get("fabric_weight_kg"));
        view.put("mergedIntoLotId", lot.get("merged_into_lot_id"));
        view.put("controlled", lot.get("controlled"));
        view.put("splitVersion", lot.get("split_version"));
        view.put("splitSeq", lot.get("split_seq"));
        view.put("status", lot.get("status"));
        view.put("repairCount", lot.get("repair_count"));
        if (lot.get("split_parent_lot_id") != null) {
            Map<String, Object> parent = jdbc.queryForMap(
                    "SELECT lot_no FROM lot WHERE id = ?", lot.get("split_parent_lot_id"));
            view.put("splitParentLotNo", parent.get("lot_no"));
        }
        view.put("createdAt", ((Timestamp) lot.get("created_at")).toInstant().toString());
        view.put("updatedAt", ((Timestamp) lot.get("updated_at")).toInstant().toString());

        Long feedingId = jdbc.query("SELECT id FROM feeding WHERE lot_id = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, lot.get("id"));
        if (feedingId != null) {
            List<Map<String, Object>> weighings = new ArrayList<>();
            jdbc.query("SELECT dye_code, target_g, actual_g, deviation_pct, batch_no, factor_version, "
                    + "potency_factor, base_target_g FROM weighing "
                    + "WHERE feeding_id = ? ORDER BY id", rs -> {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("dyeCode", rs.getString("dye_code"));
                w.put("targetG", rs.getBigDecimal("target_g"));
                w.put("actualG", rs.getBigDecimal("actual_g"));
                w.put("deviationPct", rs.getBigDecimal("deviation_pct"));
                String batchNo = rs.getString("batch_no");
                if (batchNo != null) {
                    w.put("batchNo", batchNo);
                    w.put("factorVersion", rs.getInt("factor_version"));
                    w.put("potencyFactor", rs.getBigDecimal("potency_factor"));
                    w.put("baseTargetG", rs.getBigDecimal("base_target_g"));
                }
                weighings.add(w);
            }, feedingId);
            view.put("weighings", weighings);
        }
        if (feedingId == null && lot.get("split_parent_lot_id") != null) {
            // 子缸无独立投料：展示从来源证据链按布重守恒分摊继承的实际耗用（不重新扣减余量）。
            List<Map<String, Object>> inherited = new ArrayList<>();
            jdbc.query("SELECT dye_code, actual_g, target_g, base_target_g, batch_no, factor_version, "
                    + "potency_factor FROM split_weighing WHERE child_lot_id = ? ORDER BY dye_code", rs -> {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("dyeCode", rs.getString("dye_code"));
                u.put("actualG", rs.getBigDecimal("actual_g"));
                String batchNo = rs.getString("batch_no");
                if (batchNo != null) {
                    u.put("batchNo", batchNo);
                    u.put("factorVersion", rs.getInt("factor_version"));
                    u.put("potencyFactor", rs.getBigDecimal("potency_factor"));
                    u.put("targetG", rs.getBigDecimal("target_g"));
                    u.put("baseTargetG", rs.getBigDecimal("base_target_g"));
                }
                inherited.add(u);
            }, lot.get("id"));
            if (!inherited.isEmpty()) {
                view.put("inheritedWeighings", inherited);
            }
        }
        Map<String, Object> compRow = jdbc.queryForList(
                "SELECT id, request_id, status, invalid_reason, version FROM compensation WHERE lot_id = ?",
                lot.get("id")).stream().findFirst().orElse(null);
        if (compRow != null) {
            Map<String, Object> compView = new LinkedHashMap<>();
            compView.put("requestId", compRow.get("request_id"));
            compView.put("status", compRow.get("status"));
            compView.put("invalidReason", compRow.get("invalid_reason"));
            compView.put("version", compRow.get("version"));
            List<Map<String, Object>> compItems = new ArrayList<>();
            jdbc.query("SELECT dye_code, batch_no, factor_version, potency_factor, base_target_g, "
                    + "compensated_target_g FROM compensation_item WHERE compensation_id = ? ORDER BY dye_code", rs -> {
                Map<String, Object> ci = new LinkedHashMap<>();
                ci.put("dyeCode", rs.getString("dye_code"));
                ci.put("batchNo", rs.getString("batch_no"));
                ci.put("factorVersion", rs.getInt("factor_version"));
                ci.put("potencyFactor", rs.getBigDecimal("potency_factor"));
                ci.put("baseTargetG", rs.getBigDecimal("base_target_g"));
                ci.put("compensatedTargetG", rs.getBigDecimal("compensated_target_g"));
                compItems.add(ci);
            }, compRow.get("id"));
            compView.put("items", compItems);
            view.put("compensation", compView);
        }
        List<Map<String, Object>> judgments = new ArrayList<>();
        jdbc.query("SELECT round, delta_e, reference_lot_id, reference_delta_e, diff, verdict, judged_at "
                + "FROM lot_judgment WHERE lot_id = ? ORDER BY id", rs -> {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("round", rs.getInt("round"));
            j.put("deltaE", rs.getBigDecimal("delta_e"));
            j.put("referenceLotId", rs.getObject("reference_lot_id"));
            j.put("referenceDeltaE", rs.getBigDecimal("reference_delta_e"));
            j.put("diff", rs.getBigDecimal("diff"));
            j.put("verdict", rs.getString("verdict"));
            j.put("judgedAt", rs.getTimestamp("judged_at").toInstant().toString());
            judgments.add(j);
        }, lot.get("id"));
        view.put("judgments", judgments);

        List<Map<String, Object>> childRows = jdbc.queryForList(
                "SELECT i.seq, l.lot_no, l.vat_no, i.fabric_weight_kg, l.status AS child_status "
                        + "FROM lot_split s JOIN lot_split_item i ON i.split_id = s.id "
                        + "JOIN lot l ON l.id = i.child_lot_id WHERE s.source_lot_id = ? ORDER BY i.seq",
                lot.get("id"));
        if (!childRows.isEmpty()) {
            // 已分缸来源只读：lot 视图只附谱系指针，耗用明细走 /lineage。
            view.put("splitChildren", childRows);
        }
        return view;
    }
}
