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
import com.example.starter.api.dto.ScheduleLotRequest;
import com.example.starter.api.dto.WeighingInput;
import com.example.starter.domain.ApprovalStatus;
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

    // ---------------------------------------------------------------------
    // 并缸：须同配方（同编码同冻结版本）同浴比
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult mergeLots(MergeLotsRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
        payload.put("targetLotNo", req.targetLotNo());
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
            // 受控缸次并缸：补偿批次与目标须完全相同，否则拒绝。
            Map<String, Object> sourceApproval = activeApproval(req.lotNo());
            Map<String, Object> targetApproval = activeApproval(req.targetLotNo());
            if ((sourceApproval == null) != (targetApproval == null)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "受控缸次与非受控缸次补偿状态不一致，拒绝并缸");
            }
            if (sourceApproval != null
                    && !sameCompensation((Long) sourceApproval.get("id"), (Long) targetApproval.get("id"))) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "补偿批次与目标不完全相同，拒绝并缸");
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

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", req.requestId());
            body.put("mergedLotNo", req.lotNo());
            body.put("targetLotNo", req.targetLotNo());
            body.put("combinedFabricWeightKg", combinedWeight);
            body.put("status", LotStatus.MERGED.name());
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------------
    // 投料：放大换算 → 偏差校验 → 称量记录与库存扣减同一事务原子完成
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult feed(FeedRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
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
            requireStatus(lot, LotStatus.CREATED);

            Map<String, Object> approval = activeApproval(req.lotNo());
            if (approval != null) {
                // 受控缸次：补偿版本、称量偏差与批次有效期同时校验，原子扣减批次余量
                return controlledFeed(req, lot, approval);
            }

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
            body.put("liquorRatio", ratio);
            body.put("fabricWeightKg", fabricKg);
            body.put("weighings", computed);
            body.put("status", LotStatus.FED.name());
            body.put("fedAt", now.toInstant().toString());
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------------
    // 受控投料：补偿版本 + 称量偏差 + 批次有效期同时校验，原子扣减批次余量
    // ---------------------------------------------------------------------

    /**
     * 受控缸次投料：以补偿批准冻结的批次/系数/补偿目标为准。
     * 批次系数已修订（版本不匹配）或已过有效期（恰在截止失效）整单拒绝；
     * 任一染料余量不足时整单回滚，前面的染料也不扣减。
     */
    private ServiceResult controlledFeed(FeedRequest req, Map<String, Object> lot,
                                         Map<String, Object> approval) {
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM comp_approval_item WHERE approval_id = ? ORDER BY dye_code",
                approval.get("id"));

        Map<String, BigDecimal> actualByDye = new LinkedHashMap<>();
        for (WeighingInput w : req.weighings()) {
            if (actualByDye.put(w.dyeCode(), w.actualG()) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "染化料 " + w.dyeCode() + " 称量记录重复");
            }
        }
        Set<String> required = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            required.add((String) item.get("dye_code"));
        }
        if (!actualByDye.keySet().equals(required)) {
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(actualByDye.keySet());
            Set<String> extra = new LinkedHashSet<>(actualByDye.keySet());
            extra.removeAll(required);
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "称量与补偿批准染化料不一致，拒绝投料；缺少 " + missing + "，多出 " + extra);
        }

        Instant now = Instant.now(clock);
        List<Map<String, Object>> computed = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String dyeCode = (String) item.get("dye_code");
            String lotCode = (String) item.get("lot_code");
            Map<String, Object> dyeLot = jdbc.queryForMap(
                    "SELECT * FROM dye_lot WHERE lot_code = ?", lotCode);
            int frozenPotencyVersion = ((Number) item.get("potency_version")).intValue();
            int currentPotencyVersion = ((Number) dyeLot.get("potency_version")).intValue();
            if (currentPotencyVersion != frozenPotencyVersion) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "批次 " + lotCode + " 系数已修订（批准版本 " + frozenPotencyVersion
                                + "，当前版本 " + currentPotencyVersion + "），补偿批准失效，整单拒绝投料");
            }
            Timestamp validUntil = (Timestamp) dyeLot.get("valid_until");
            if (!now.isBefore(validUntil.toInstant())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "批次 " + lotCode + " 有效截止 " + validUntil.toInstant()
                                + " 已到或已过（恰在截止失效），整单拒绝投料");
            }
            BigDecimal compTarget = (BigDecimal) item.get("comp_target_g");
            BigDecimal actual = actualByDye.get(dyeCode);
            BigDecimal deviation = actual.subtract(compTarget)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(compTarget, DEVIATION_SCALE, RoundingMode.HALF_UP);
            if (deviation.abs().compareTo(WEIGH_LIMIT_PCT) > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "染化料 " + dyeCode + " 称量偏差 " + deviation
                                + "% 超过 ±0.5%（按补偿目标计算，恰 0.5% 合法），整单拒绝投料且不扣余量");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dyeCode", dyeCode);
            row.put("lotCode", lotCode);
            row.put("potency", item.get("potency"));
            row.put("targetG", compTarget);
            row.put("actualG", actual);
            row.put("deviationPct", deviation);
            computed.add(row);
        }

        // 余量预检：任一染料批次不足整单回滚，杜绝部分扣减。
        for (Map<String, Object> row : computed) {
            BigDecimal available = jdbc.queryForObject(
                    "SELECT available_g FROM dye_lot WHERE lot_code = ?",
                    BigDecimal.class, row.get("lotCode"));
            if (available.compareTo((BigDecimal) row.get("actualG")) < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "批次 " + row.get("lotCode") + " 可用余量 " + available + "g 不足实际称量 "
                                + row.get("actualG") + "g，整单拒绝投料且不扣余量");
            }
        }

        Timestamp nowTs = Timestamp.from(now);
        jdbc.update("INSERT INTO feeding (request_id, lot_id, fed_at) VALUES (?, ?, ?)",
                req.requestId(), lot.get("id"), nowTs);
        Long feedingId = jdbc.queryForObject(
                "SELECT id FROM feeding WHERE request_id = ?", Long.class, req.requestId());
        for (Map<String, Object> row : computed) {
            jdbc.update("INSERT INTO weighing (feeding_id, dye_code, target_g, actual_g, deviation_pct, "
                            + "dye_lot_code, potency) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    feedingId, row.get("dyeCode"), row.get("targetG"), row.get("actualG"),
                    row.get("deviationPct"), row.get("lotCode"), row.get("potency"));
            int deducted = jdbc.update(
                    "UPDATE dye_lot SET available_g = available_g - ?, updated_at = ? "
                            + "WHERE lot_code = ? AND available_g >= ?",
                    row.get("actualG"), nowTs, row.get("lotCode"), row.get("actualG"));
            if (deducted != 1) {
                // 并发扣减防护：与预检构成双保险，失败随事务整体回滚，两缸争同批余量不得超耗。
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "批次 " + row.get("lotCode") + " 并发扣减失败，整单回滚");
            }
        }
        jdbc.update("UPDATE lot SET status = ?, updated_at = ? WHERE id = ?",
                LotStatus.FED.name(), nowTs, lot.get("id"));
        // 已投料批准转为 CONSUMED：后续批次系数修订不追改该历史。
        jdbc.update("UPDATE comp_approval SET status = ? WHERE id = ?",
                ApprovalStatus.CONSUMED.name(), approval.get("id"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", req.requestId());
        body.put("lotNo", req.lotNo());
        body.put("controlled", true);
        body.put("approvalId", approval.get("id"));
        body.put("weighings", computed);
        body.put("status", LotStatus.FED.name());
        body.put("fedAt", nowTs.toInstant().toString());
        return ServiceResult.ok(body);
    }

    /**
     * 查询缸次生效中的补偿批准；无则返回 null（旧非受控缸次）。
     */
    private Map<String, Object> activeApproval(String lotNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM comp_approval WHERE lot_no = ? AND status = ? ORDER BY id DESC LIMIT 1",
                lotNo, ApprovalStatus.ACTIVE.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 比较两个补偿批准的冻结明细：每种染料的批次、系数与补偿目标须完全相同。
     */
    private boolean sameCompensation(Long approvalIdA, Long approvalIdB) {
        List<Map<String, Object>> a = jdbc.queryForList(
                "SELECT dye_code, lot_code, potency, comp_target_g FROM comp_approval_item "
                        + "WHERE approval_id = ? ORDER BY dye_code", approvalIdA);
        List<Map<String, Object>> b = jdbc.queryForList(
                "SELECT dye_code, lot_code, potency, comp_target_g FROM comp_approval_item "
                        + "WHERE approval_id = ? ORDER BY dye_code", approvalIdB);
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Map<String, Object> ra = a.get(i);
            Map<String, Object> rb = b.get(i);
            if (!ra.get("dye_code").equals(rb.get("dye_code"))
                    || !ra.get("lot_code").equals(rb.get("lot_code"))
                    || ((BigDecimal) ra.get("potency")).compareTo((BigDecimal) rb.get("potency")) != 0
                    || ((BigDecimal) ra.get("comp_target_g"))
                            .compareTo((BigDecimal) rb.get("comp_target_g")) != 0) {
                return false;
            }
        }
        return true;
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
            Map<String, Object> lot = requireLot(req.lotNo());
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
                    || LotStatus.REPAIRING.name().equals(status) || LotStatus.MERGED.name().equals(status)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "缸次 " + lotNo + " 状态 " + status + "，未出缸判定不得归档");
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
            List<Map<String, Object>> stored = jdbc.queryForList(
                    "SELECT dye_code, target_g, actual_g, deviation_pct, dye_lot_code, potency "
                            + "FROM weighing w "
                            + "JOIN feeding f ON f.id = w.feeding_id WHERE f.lot_id = ? ORDER BY w.id",
                    lot.get("id"));
            for (Map<String, Object> w : stored) {
                BigDecimal dosage = jdbc.queryForObject(
                        "SELECT dosage_gpl FROM recipe_item ri JOIN recipe r ON r.id = ri.recipe_id "
                                + "WHERE r.recipe_code = ? AND r.version = ? AND ri.dye_code = ?",
                        BigDecimal.class, recipeCode, lot.get("recipe_version"), w.get("dye_code"));
                BigDecimal baseTarget = dosage.multiply(ratio).multiply(fabric)
                        .setScale(4, RoundingMode.HALF_UP);
                BigDecimal target = baseTarget;
                BigDecimal frozenPotency = (BigDecimal) w.get("potency");
                if (frozenPotency != null) {
                    // 受控投料：按冻结系数复算补偿目标（原目标/系数，三位小数）
                    target = baseTarget.divide(frozenPotency, 3, RoundingMode.HALF_UP);
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
        view.put("status", lot.get("status"));
        view.put("repairCount", lot.get("repair_count"));
        view.put("createdAt", ((Timestamp) lot.get("created_at")).toInstant().toString());
        view.put("updatedAt", ((Timestamp) lot.get("updated_at")).toInstant().toString());

        Long feedingId = jdbc.query("SELECT id FROM feeding WHERE lot_id = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, lot.get("id"));
        if (feedingId != null) {
            List<Map<String, Object>> weighings = new ArrayList<>();
            jdbc.query("SELECT dye_code, target_g, actual_g, deviation_pct, dye_lot_code, potency "
                    + "FROM weighing WHERE feeding_id = ? ORDER BY id", rs -> {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("dyeCode", rs.getString("dye_code"));
                w.put("targetG", rs.getBigDecimal("target_g"));
                w.put("actualG", rs.getBigDecimal("actual_g"));
                w.put("deviationPct", rs.getBigDecimal("deviation_pct"));
                w.put("dyeLotCode", rs.getString("dye_lot_code"));
                w.put("potency", rs.getBigDecimal("potency"));
                weighings.add(w);
            }, feedingId);
            view.put("weighings", weighings);
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
        return view;
    }
}
