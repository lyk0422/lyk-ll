package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.api.dto.ApproveCompensationRequest;
import com.example.starter.api.dto.CompensationItemInput;
import com.example.starter.api.dto.ControlledFeedRequest;
import com.example.starter.api.dto.RegisterBatchRequest;
import com.example.starter.api.dto.RevisePotencyRequest;
import com.example.starter.api.dto.WeighingInput;
import com.example.starter.domain.CompensationStatus;
import com.example.starter.domain.LotStatus;
import com.example.starter.support.ApiException;

/**
 * 染料批次效价补偿与受控投料服务。
 * 线性模型：补偿目标克重 = 放大配方原目标 / 效价系数，十进制计算后四舍五入到三位小数；
 * 批次 now&lt;有效截止 有效（恰在截止即失效）；称量偏差按补偿目标计算，恰 ±0.5% 合法。
 */
@Service
public class CompensationService {

    /** 称量偏差允许上限百分比（含边界，恰 0.5% 合法）。 */
    static final BigDecimal WEIGH_LIMIT_PCT = new BigDecimal("0.5");
    private static final int DEVIATION_SCALE = 6;
    private static final int BASE_TARGET_SCALE = 4;
    private static final int COMPENSATED_TARGET_SCALE = 3;

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CompensationService(JdbcTemplate jdbc, IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    // ---------------------------------------------------------------------
    // 染料批次：登记（版本从1开始）与系数修订（expectedVersion 乐观锁，成功加1）
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult registerBatch(RegisterBatchRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchNo", req.batchNo());
        payload.put("dyeCode", req.dyeCode());
        payload.put("potency", req.potency());
        payload.put("availableG", req.availableG());
        payload.put("validUntil", req.validUntil().toString());
        return idempotency.execute(req.requestId(), "BATCH_REGISTER", payload, () -> {
            Integer dyeExists = jdbc.query("SELECT 1 FROM dye WHERE code = ?",
                    rs -> rs.next() ? 1 : null, req.dyeCode());
            if (dyeExists == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "染料代码 " + req.dyeCode() + " 未登记");
            }
            Timestamp now = Timestamp.from(now());
            try {
                jdbc.update("INSERT INTO dye_batch (batch_no, dye_code, potency, potency_version, "
                                + "available_g, valid_until, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 1, ?, ?, ?, ?)",
                        req.batchNo(), req.dyeCode(), req.potency(), req.availableG(),
                        Timestamp.from(req.validUntil()), now, now);
            } catch (DuplicateKeyException dup) {
                throw new ApiException(HttpStatus.CONFLICT, "染料批次号 " + req.batchNo() + " 已存在");
            }
            return ServiceResult.created(batchView(req.batchNo()));
        });
    }

    /**
     * 修订批次系数：expectedVersion 须等于当前系数版本，成功加 1；
     * 引用该批次的未投料（ACTIVE）补偿批准全部失效，已投料历史不追改。
     */
    @Transactional
    public ServiceResult revisePotency(String batchNo, RevisePotencyRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchNo", batchNo);
        payload.put("potency", req.potency());
        payload.put("expectedVersion", req.expectedVersion());
        return idempotency.execute(req.requestId(), "BATCH_REVISE", payload, () -> {
            Map<String, Object> batch = requireBatch(batchNo);
            int currentVersion = ((Number) batch.get("potency_version")).intValue();
            if (req.expectedVersion() != currentVersion) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "批次 " + batchNo + " 当前系数版本 " + currentVersion
                                + "，与 expectedVersion " + req.expectedVersion() + " 不一致");
            }
            Timestamp now = Timestamp.from(now());
            int updated = jdbc.update("UPDATE dye_batch SET potency = ?, potency_version = potency_version + 1, "
                            + "updated_at = ? WHERE batch_no = ? AND potency_version = ?",
                    req.potency(), now, batchNo, req.expectedVersion());
            if (updated != 1) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "批次 " + batchNo + " 系数版本并发变更，修订失败");
            }
            // 未投料批准失效；已投料（CONSUMED）历史不追改。
            int invalidated = jdbc.update("UPDATE compensation SET status = ?, version = version + 1, "
                            + "updated_at = ? WHERE status = ? AND id IN "
                            + "(SELECT compensation_id FROM compensation_item WHERE batch_no = ?)",
                    CompensationStatus.INVALIDATED.name(), now, CompensationStatus.ACTIVE.name(), batchNo);

            Map<String, Object> body = batchView(batchNo);
            body.put("invalidatedApprovals", invalidated);
            return ServiceResult.ok(body);
        });
    }

    @Transactional(readOnly = true)
    public ServiceResult getBatch(String batchNo) {
        return ServiceResult.ok(batchView(batchNo));
    }

    // ---------------------------------------------------------------------
    // 补偿批准：冻结批次、系数与补偿目标；不修改已发布基础配方
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult approveCompensation(ApproveCompensationRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalNo", req.approvalNo());
        payload.put("lotNo", req.lotNo());
        List<Map<String, Object>> itemPayload = new ArrayList<>();
        for (CompensationItemInput item : req.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dyeCode", item.dyeCode());
            entry.put("batchNo", item.batchNo());
            entry.put("potencyVersion", item.potencyVersion());
            itemPayload.add(entry);
        }
        itemPayload.sort((a, b) -> String.valueOf(a.get("dyeCode")).compareTo(String.valueOf(b.get("dyeCode"))));
        payload.put("items", itemPayload);

        return idempotency.execute(req.requestId(), "COMP_APPROVE", payload, () -> {
            Map<String, Object> lot = requireLot(req.lotNo());
            if (!LotStatus.CREATED.name().equals(lot.get("status"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + req.lotNo() + " 当前状态 " + lot.get("status") + "，仅待投料缸次可申请补偿批准");
            }
            Integer activeExists = jdbc.query(
                    "SELECT 1 FROM compensation WHERE lot_id = ? AND status = ?",
                    rs -> rs.next() ? 1 : null, lot.get("id"), CompensationStatus.ACTIVE.name());
            if (activeExists != null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + req.lotNo() + " 已存在生效中的补偿批准，拒绝重复批准");
            }

            String recipeCode = (String) lot.get("recipe_code");
            int recipeVersion = ((Number) lot.get("recipe_version")).intValue();
            Long recipeId = jdbc.queryForObject(
                    "SELECT id FROM recipe WHERE recipe_code = ? AND version = ? AND status = 'PUBLISHED'",
                    Long.class, recipeCode, recipeVersion);
            if (recipeId == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次引用的配方 " + recipeCode + " v" + recipeVersion + " 非已发布冻结版本");
            }
            List<Map<String, Object>> recipeItems = jdbc.queryForList(
                    "SELECT dye_code, dosage_gpl FROM recipe_item WHERE recipe_id = ? ORDER BY dye_code",
                    recipeId);

            Map<String, CompensationItemInput> itemByDye = new LinkedHashMap<>();
            for (CompensationItemInput item : req.items()) {
                if (itemByDye.put(item.dyeCode(), item) != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "染料 " + item.dyeCode() + " 补偿明细重复");
                }
            }
            Set<String> required = new LinkedHashSet<>();
            for (Map<String, Object> ri : recipeItems) {
                required.add((String) ri.get("dye_code"));
            }
            if (!itemByDye.keySet().equals(required)) {
                Set<String> missing = new LinkedHashSet<>(required);
                missing.removeAll(itemByDye.keySet());
                Set<String> extra = new LinkedHashSet<>(itemByDye.keySet());
                extra.removeAll(required);
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "补偿明细须与配方染料一一对应，缺少 " + missing + "，多出 " + extra);
            }

            BigDecimal ratio = (BigDecimal) lot.get("liquor_ratio");
            BigDecimal fabricKg = (BigDecimal) lot.get("fabric_weight_kg");
            Instant nowInstant = now();
            List<Map<String, Object>> computed = new ArrayList<>();
            for (Map<String, Object> ri : recipeItems) {
                String dyeCode = (String) ri.get("dye_code");
                CompensationItemInput item = itemByDye.get(dyeCode);
                Map<String, Object> batch = requireBatch(item.batchNo());
                if (!dyeCode.equals(batch.get("dye_code"))) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + item.batchNo() + " 登记染料为 " + batch.get("dye_code")
                                    + "，与配方染料 " + dyeCode + " 错配，整单拒绝");
                }
                BigDecimal potency = (BigDecimal) batch.get("potency");
                if (potency == null || potency.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + item.batchNo() + " 效价系数为零或非有限数，整单拒绝");
                }
                int batchVersion = ((Number) batch.get("potency_version")).intValue();
                if (item.potencyVersion() != batchVersion) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + item.batchNo() + " 当前系数版本 " + batchVersion
                                    + "，与指定版本 " + item.potencyVersion() + " 不一致，整单拒绝");
                }
                Instant validUntil = ((Timestamp) batch.get("valid_until")).toInstant();
                if (!nowInstant.isBefore(validUntil)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + item.batchNo() + " 已过有效截止 " + validUntil
                                    + "（now<截止 有效，恰在截止失效），整单拒绝");
                }
                BigDecimal dosage = (BigDecimal) ri.get("dosage_gpl");
                BigDecimal baseTarget = dosage.multiply(ratio).multiply(fabricKg)
                        .setScale(BASE_TARGET_SCALE, RoundingMode.HALF_UP);
                BigDecimal compensatedTarget = baseTarget
                        .divide(potency, COMPENSATED_TARGET_SCALE, RoundingMode.HALF_UP);
                BigDecimal available = (BigDecimal) batch.get("available_g");
                if (available.compareTo(compensatedTarget) < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + item.batchNo() + " 可用余量 " + available
                                    + "g 不足补偿目标 " + compensatedTarget + "g，整单拒绝");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dyeCode", dyeCode);
                row.put("batchNo", item.batchNo());
                row.put("potency", potency);
                row.put("potencyVersion", batchVersion);
                row.put("baseTargetG", baseTarget);
                row.put("compensatedTargetG", compensatedTarget);
                computed.add(row);
            }

            Timestamp now = Timestamp.from(nowInstant);
            try {
                jdbc.update("INSERT INTO compensation (approval_no, request_id, lot_id, lot_no, recipe_code, "
                                + "recipe_version, version, status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)",
                        req.approvalNo(), req.requestId(), lot.get("id"), req.lotNo(), recipeCode, recipeVersion,
                        CompensationStatus.ACTIVE.name(), now, now);
            } catch (DuplicateKeyException dup) {
                throw new ApiException(HttpStatus.CONFLICT, "补偿批准号 " + req.approvalNo() + " 已存在");
            }
            Long compensationId = jdbc.queryForObject(
                    "SELECT id FROM compensation WHERE approval_no = ?", Long.class, req.approvalNo());
            for (Map<String, Object> row : computed) {
                jdbc.update("INSERT INTO compensation_item (compensation_id, dye_code, batch_no, potency, "
                                + "potency_version, base_target_g, compensated_target_g) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        compensationId, row.get("dyeCode"), row.get("batchNo"), row.get("potency"),
                        row.get("potencyVersion"), row.get("baseTargetG"), row.get("compensatedTargetG"));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", req.requestId());
            body.put("approvalNo", req.approvalNo());
            body.put("lotNo", req.lotNo());
            body.put("recipeCode", recipeCode);
            body.put("recipeVersion", recipeVersion);
            body.put("version", 1);
            body.put("status", CompensationStatus.ACTIVE.name());
            body.put("items", computed);
            return ServiceResult.created(body);
        });
    }

    // ---------------------------------------------------------------------
    // 受控投料：验证补偿版本/称量/批次有效期，原子扣减各批次余量并写称量链
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult controlledFeed(ControlledFeedRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
        payload.put("approvalNo", req.approvalNo());
        List<Map<String, Object>> weighingPayload = new ArrayList<>();
        for (WeighingInput w : req.weighings()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dyeCode", w.dyeCode());
            entry.put("actualG", w.actualG());
            weighingPayload.add(entry);
        }
        weighingPayload.sort((a, b) -> String.valueOf(a.get("dyeCode")).compareTo(String.valueOf(b.get("dyeCode"))));
        payload.put("weighings", weighingPayload);

        return idempotency.execute(req.requestId(), "CTRL_FEED", payload, () -> {
            Map<String, Object> lot = requireLot(req.lotNo());
            if (!LotStatus.CREATED.name().equals(lot.get("status"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + req.lotNo() + " 当前状态 " + lot.get("status") + "，不允许受控投料");
            }
            Map<String, Object> approval = requireCompensation(req.approvalNo());
            if (!approval.get("lot_id").equals(lot.get("id"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "补偿批准 " + req.approvalNo() + " 不属于缸次 " + req.lotNo() + "，整单拒绝");
            }
            String approvalStatus = (String) approval.get("status");
            if (CompensationStatus.INVALIDATED.name().equals(approvalStatus)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "补偿批准 " + req.approvalNo() + " 已因批次系数修订失效，须重新批准后再投料");
            }
            if (!CompensationStatus.ACTIVE.name().equals(approvalStatus)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "补偿批准 " + req.approvalNo() + " 状态 " + approvalStatus + "，不允许投料");
            }

            List<Map<String, Object>> items = jdbc.queryForList(
                    "SELECT dye_code, batch_no, potency, potency_version, compensated_target_g "
                            + "FROM compensation_item WHERE compensation_id = ? ORDER BY dye_code",
                    approval.get("id"));
            Map<String, BigDecimal> actualByDye = new LinkedHashMap<>();
            for (WeighingInput w : req.weighings()) {
                if (actualByDye.put(w.dyeCode(), w.actualG()) != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "染料 " + w.dyeCode() + " 称量记录重复");
                }
            }
            Set<String> required = new LinkedHashSet<>();
            Instant nowInstant = now();
            List<Map<String, Object>> computed = new ArrayList<>();
            for (Map<String, Object> item : items) {
                String dyeCode = (String) item.get("dye_code");
                String batchNo = (String) item.get("batch_no");
                required.add(dyeCode);
                BigDecimal actual = actualByDye.get(dyeCode);
                if (actual == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "缺少染料 " + dyeCode + " 的实际称量，拒绝投料");
                }
                Map<String, Object> batch = requireBatch(batchNo);
                int frozenVersion = ((Number) item.get("potency_version")).intValue();
                int currentVersion = ((Number) batch.get("potency_version")).intValue();
                if (frozenVersion != currentVersion) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + batchNo + " 系数版本已由 " + frozenVersion + " 修订为 " + currentVersion
                                    + "，补偿版本不一致，整单拒绝");
                }
                Instant validUntil = ((Timestamp) batch.get("valid_until")).toInstant();
                if (!nowInstant.isBefore(validUntil)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + batchNo + " 已过有效截止 " + validUntil + "，整单拒绝");
                }
                BigDecimal target = (BigDecimal) item.get("compensated_target_g");
                BigDecimal deviation = actual.subtract(target)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(target, DEVIATION_SCALE, RoundingMode.HALF_UP);
                if (deviation.abs().compareTo(WEIGH_LIMIT_PCT) > 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "染料 " + dyeCode + " 称量偏差 " + deviation
                                    + "% 超过 ±0.5%（按补偿目标，恰 0.5% 合法），整单拒绝投料且不扣批次余量");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dyeCode", dyeCode);
                row.put("batchNo", batchNo);
                row.put("potency", item.get("potency"));
                row.put("compensatedTargetG", target);
                row.put("actualG", actual);
                row.put("deviationPct", deviation);
                computed.add(row);
            }
            if (!actualByDye.keySet().equals(required)) {
                Set<String> extra = new LinkedHashSet<>(actualByDye.keySet());
                extra.removeAll(required);
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "存在补偿明细外染料称量：" + extra + "，拒绝投料");
            }

            // 批次余量预检：任一不足整单回滚，第二种不足时第一种也不扣。
            for (Map<String, Object> row : computed) {
                BigDecimal available = jdbc.queryForObject(
                        "SELECT available_g FROM dye_batch WHERE batch_no = ?",
                        BigDecimal.class, row.get("batchNo"));
                if (available.compareTo((BigDecimal) row.get("actualG")) < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + row.get("batchNo") + " 可用余量 " + available
                                    + "g 不足实际称量 " + row.get("actualG") + "g，整单拒绝且不扣任何批次");
                }
            }

            Timestamp now = Timestamp.from(nowInstant);
            jdbc.update("INSERT INTO controlled_feeding (request_id, lot_id, compensation_id, fed_at) "
                            + "VALUES (?, ?, ?, ?)",
                    req.requestId(), lot.get("id"), approval.get("id"), now);
            Long feedingId = jdbc.queryForObject(
                    "SELECT id FROM controlled_feeding WHERE request_id = ?", Long.class, req.requestId());
            for (Map<String, Object> row : computed) {
                jdbc.update("INSERT INTO batch_weighing (controlled_feeding_id, dye_code, batch_no, potency, "
                                + "compensated_target_g, actual_g, deviation_pct) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        feedingId, row.get("dyeCode"), row.get("batchNo"), row.get("potency"),
                        row.get("compensatedTargetG"), row.get("actualG"), row.get("deviationPct"));
                int deducted = jdbc.update("UPDATE dye_batch SET available_g = available_g - ?, updated_at = ? "
                                + "WHERE batch_no = ? AND available_g >= ?",
                        row.get("actualG"), now, row.get("batchNo"), row.get("actualG"));
                if (deducted != 1) {
                    // 并发扣减防护：与预检构成双保险，失败随事务整体回滚。
                    throw new ApiException(HttpStatus.CONFLICT,
                            "批次 " + row.get("batchNo") + " 并发扣减失败，整单回滚");
                }
            }
            int consumed = jdbc.update("UPDATE compensation SET status = ?, version = version + 1, "
                            + "updated_at = ? WHERE id = ? AND status = ?",
                    CompensationStatus.CONSUMED.name(), now, approval.get("id"),
                    CompensationStatus.ACTIVE.name());
            if (consumed != 1) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "补偿批准 " + req.approvalNo() + " 并发状态变更，整单回滚");
            }
            jdbc.update("UPDATE lot SET status = ?, updated_at = ? WHERE id = ?",
                    LotStatus.FED.name(), now, lot.get("id"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", req.requestId());
            body.put("lotNo", req.lotNo());
            body.put("approvalNo", req.approvalNo());
            body.put("weighings", computed);
            body.put("status", LotStatus.FED.name());
            body.put("fedAt", now.toInstant().toString());
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------------
    // 补偿审批及证据查询
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ServiceResult getCompensation(String approvalNo) {
        Map<String, Object> approval = requireCompensation(approvalNo);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvalNo", approval.get("approval_no"));
        body.put("requestId", approval.get("request_id"));
        body.put("lotNo", approval.get("lot_no"));
        body.put("recipeCode", approval.get("recipe_code"));
        body.put("recipeVersion", approval.get("recipe_version"));
        body.put("version", approval.get("version"));
        body.put("status", approval.get("status"));
        body.put("createdAt", ((Timestamp) approval.get("created_at")).toInstant().toString());
        body.put("updatedAt", ((Timestamp) approval.get("updated_at")).toInstant().toString());
        List<Map<String, Object>> items = new ArrayList<>();
        jdbc.query("SELECT dye_code, batch_no, potency, potency_version, base_target_g, compensated_target_g "
                        + "FROM compensation_item WHERE compensation_id = ? ORDER BY dye_code",
                rs -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("dyeCode", rs.getString("dye_code"));
                    item.put("batchNo", rs.getString("batch_no"));
                    item.put("potency", rs.getBigDecimal("potency"));
                    item.put("potencyVersion", rs.getInt("potency_version"));
                    item.put("baseTargetG", rs.getBigDecimal("base_target_g"));
                    item.put("compensatedTargetG", rs.getBigDecimal("compensated_target_g"));
                    items.add(item);
                }, approval.get("id"));
        body.put("items", items);

        Long feedingId = jdbc.query(
                "SELECT id FROM controlled_feeding WHERE compensation_id = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, approval.get("id"));
        if (feedingId != null) {
            List<Map<String, Object>> weighings = new ArrayList<>();
            jdbc.query("SELECT dye_code, batch_no, potency, compensated_target_g, actual_g, deviation_pct "
                            + "FROM batch_weighing WHERE controlled_feeding_id = ? ORDER BY id",
                    rs -> {
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("dyeCode", rs.getString("dye_code"));
                        w.put("batchNo", rs.getString("batch_no"));
                        w.put("potency", rs.getBigDecimal("potency"));
                        w.put("compensatedTargetG", rs.getBigDecimal("compensated_target_g"));
                        w.put("actualG", rs.getBigDecimal("actual_g"));
                        w.put("deviationPct", rs.getBigDecimal("deviation_pct"));
                        weighings.add(w);
                    }, feedingId);
            body.put("weighings", weighings);
        }
        return ServiceResult.ok(body);
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    private Map<String, Object> requireBatch(String batchNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM dye_batch WHERE batch_no = ?", batchNo);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "染料批次 " + batchNo + " 不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> requireCompensation(String approvalNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM compensation WHERE approval_no = ?", approvalNo);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "补偿批准 " + approvalNo + " 不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> requireLot(String lotNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM lot WHERE lot_no = ?", lotNo);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "缸次 " + lotNo + " 不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> batchView(String batchNo) {
        Map<String, Object> batch = requireBatch(batchNo);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batchNo", batch.get("batch_no"));
        view.put("dyeCode", batch.get("dye_code"));
        view.put("potency", batch.get("potency"));
        view.put("potencyVersion", batch.get("potency_version"));
        view.put("availableG", batch.get("available_g"));
        view.put("validUntil", ((Timestamp) batch.get("valid_until")).toInstant().toString());
        view.put("createdAt", ((Timestamp) batch.get("created_at")).toInstant().toString());
        view.put("updatedAt", ((Timestamp) batch.get("updated_at")).toInstant().toString());
        return view;
    }
}
