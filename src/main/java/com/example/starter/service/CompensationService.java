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

import com.example.starter.api.dto.CompensationItemInput;
import com.example.starter.api.dto.CompensationRequest;
import com.example.starter.api.dto.RegisterDyeLotRequest;
import com.example.starter.api.dto.RevisePotencyRequest;
import com.example.starter.domain.ApprovalStatus;
import com.example.starter.domain.LotStatus;
import com.example.starter.support.ApiException;

/**
 * 染料批次效价补偿服务：批次登记、系数修订（expectedVersion 乐观锁）、补偿批准与证据查询。
 * 线性模型：补偿目标克重 = 放大原目标 / 效价系数，十进制计算后四舍五入到三位小数。
 * 有效期：now &lt; 截止有效，恰在截止失效。系数零/非有限数、代码错配、余量不足均整单拒绝。
 */
@Service
public class CompensationService {

    /** 补偿目标克重小数位（四舍五入到三位小数）。 */
    static final int COMP_TARGET_SCALE = 3;
    /** 放大原目标克重小数位（与既有称量链一致）。 */
    static final int BASE_TARGET_SCALE = 4;

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
    // 批次登记：染料代码 + 正数效价系数 + 可用克重 + 有效截止；版本从 1 开始
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult registerDyeLot(RegisterDyeLotRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotCode", req.lotCode());
        payload.put("dyeCode", req.dyeCode());
        payload.put("potency", req.potency());
        payload.put("availableG", req.availableG());
        payload.put("validUntil", req.validUntil());
        return idempotency.execute(req.requestId(), "DYE_LOT_REGISTER", payload, () -> {
            Integer dyeExists = jdbc.query("SELECT 1 FROM dye WHERE code = ?",
                    rs -> rs.next() ? 1 : null, req.dyeCode());
            if (dyeExists == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "染化料 " + req.dyeCode() + " 不存在");
            }
            requireFinitePositive(req.potency());
            if (!req.validUntil().isAfter(now())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "有效截止 " + req.validUntil() + " 不晚于当前时刻，批次登记即失效，拒绝登记");
            }
            Timestamp now = Timestamp.from(now());
            try {
                jdbc.update("INSERT INTO dye_lot (lot_code, dye_code, potency, potency_version, "
                                + "available_g, valid_until, version, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 1, ?, ?, 1, ?, ?)",
                        req.lotCode(), req.dyeCode(), req.potency(), req.availableG(),
                        Timestamp.from(req.validUntil()), now, now);
            } catch (DuplicateKeyException dup) {
                throw new ApiException(HttpStatus.CONFLICT, "染料批次 " + req.lotCode() + " 已存在");
            }
            return ServiceResult.created(dyeLotView(req.lotCode()));
        });
    }

    // ---------------------------------------------------------------------
    // 系数修订：expectedVersion 乐观锁；成功版本+1、系数版本+1；未投料批准失效
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult revisePotency(String lotCode, RevisePotencyRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotCode", lotCode);
        payload.put("expectedVersion", req.expectedVersion());
        payload.put("potency", req.potency());
        return idempotency.execute(req.requestId(), "DYE_LOT_REVISE", payload, () -> {
            Map<String, Object> lot = requireDyeLot(lotCode);
            requireFinitePositive(req.potency());
            int currentVersion = ((Number) lot.get("version")).intValue();
            if (currentVersion != req.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "染料批次 " + lotCode + " 当前版本 " + currentVersion
                                + " 与 expectedVersion=" + req.expectedVersion() + " 不一致，拒绝修订");
            }
            int newVersion = currentVersion + 1;
            int newPotencyVersion = ((Number) lot.get("potency_version")).intValue() + 1;
            Timestamp now = Timestamp.from(now());
            int updated = jdbc.update("UPDATE dye_lot SET potency = ?, potency_version = ?, version = ?, "
                            + "updated_at = ? WHERE id = ? AND version = ?",
                    req.potency(), newPotencyVersion, newVersion, now, lot.get("id"), currentVersion);
            if (updated != 1) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "染料批次 " + lotCode + " 并发修订冲突，请重取版本后重试");
            }
            // 系数修订使未投料批准失效；已投料（CONSUMED）历史不追改。
            int invalidated = jdbc.update("UPDATE comp_approval SET status = ? WHERE status = ? AND id IN "
                            + "(SELECT approval_id FROM comp_approval_item WHERE lot_code = ?)",
                    ApprovalStatus.INVALIDATED.name(), ApprovalStatus.ACTIVE.name(), lotCode);

            Map<String, Object> body = dyeLotView(lotCode);
            body.put("invalidatedApprovals", invalidated);
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------------
    // 补偿批准：受控缸次投料前冻结批次、系数与补偿目标；不修改已发布基础配方
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult createApproval(CompensationRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
        List<Map<String, Object>> itemPayload = new ArrayList<>();
        for (CompensationItemInput item : req.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dyeCode", item.dyeCode());
            entry.put("lotCode", item.lotCode());
            entry.put("potencyVersion", item.potencyVersion());
            itemPayload.add(entry);
        }
        itemPayload.sort((a, b) -> String.valueOf(a.get("dyeCode")).compareTo(String.valueOf(b.get("dyeCode"))));
        payload.put("items", itemPayload);

        return idempotency.execute(req.requestId(), "COMP_APPROVE", payload, () -> {
            Map<String, Object> lot = requireLot(req.lotNo());
            if (!LotStatus.CREATED.name().equals(lot.get("status"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + req.lotNo() + " 当前状态 " + lot.get("status") + "，仅待投料缸次可登记补偿批准");
            }
            Integer activeApprovals = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM comp_approval WHERE lot_no = ? AND status = ?",
                    Integer.class, req.lotNo(), ApprovalStatus.ACTIVE.name());
            if (activeApprovals != null && activeApprovals > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + req.lotNo() + " 已存在生效中的补偿批准，拒绝重复批准");
            }

            String recipeCode = (String) lot.get("recipe_code");
            int recipeVersion = ((Number) lot.get("recipe_version")).intValue();
            Long recipeId = jdbc.queryForObject(
                    "SELECT id FROM recipe WHERE recipe_code = ? AND version = ?",
                    Long.class, recipeCode, recipeVersion);
            List<Map<String, Object>> recipeItems = jdbc.queryForList(
                    "SELECT dye_code, dosage_gpl FROM recipe_item WHERE recipe_id = ? ORDER BY dye_code",
                    recipeId);

            Map<String, CompensationItemInput> requestByDye = new LinkedHashMap<>();
            for (CompensationItemInput item : req.items()) {
                if (requestByDye.put(item.dyeCode(), item) != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "染化料 " + item.dyeCode() + " 补偿项重复");
                }
            }
            Set<String> required = new LinkedHashSet<>();
            for (Map<String, Object> item : recipeItems) {
                required.add((String) item.get("dye_code"));
            }
            if (!requestByDye.keySet().equals(required)) {
                Set<String> missing = new LinkedHashSet<>(required);
                missing.removeAll(requestByDye.keySet());
                Set<String> extra = new LinkedHashSet<>(requestByDye.keySet());
                extra.removeAll(required);
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "补偿项与配方染化料代码错配，整单拒绝；缺少 " + missing + "，多出 " + extra);
            }

            BigDecimal ratio = (BigDecimal) lot.get("liquor_ratio");
            BigDecimal fabricKg = (BigDecimal) lot.get("fabric_weight_kg");
            Instant now = now();
            List<Map<String, Object>> computed = new ArrayList<>();
            for (Map<String, Object> recipeItem : recipeItems) {
                String dyeCode = (String) recipeItem.get("dye_code");
                CompensationItemInput input = requestByDye.get(dyeCode);
                Map<String, Object> dyeLot = requireDyeLot(input.lotCode());
                if (!dyeCode.equals(dyeLot.get("dye_code"))) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.lotCode() + " 属于染化料 " + dyeLot.get("dye_code")
                                    + "，与配方项 " + dyeCode + " 代码错配，整单拒绝");
                }
                int currentPotencyVersion = ((Number) dyeLot.get("potency_version")).intValue();
                if (currentPotencyVersion != input.potencyVersion()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.lotCode() + " 当前系数版本 " + currentPotencyVersion
                                    + " 与指定版本 " + input.potencyVersion() + " 不一致，整单拒绝");
                }
                BigDecimal potency = (BigDecimal) dyeLot.get("potency");
                requireFinitePositive(potency);
                Timestamp validUntil = (Timestamp) dyeLot.get("valid_until");
                if (!now.isBefore(validUntil.toInstant())) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.lotCode() + " 有效截止 " + validUntil.toInstant()
                                    + " 已到或已过（恰在截止失效），整单拒绝");
                }
                BigDecimal baseTarget = ((BigDecimal) recipeItem.get("dosage_gpl"))
                        .multiply(ratio).multiply(fabricKg)
                        .setScale(BASE_TARGET_SCALE, RoundingMode.HALF_UP);
                BigDecimal compTarget = baseTarget
                        .divide(potency, COMP_TARGET_SCALE, RoundingMode.HALF_UP);
                BigDecimal available = (BigDecimal) dyeLot.get("available_g");
                if (available.compareTo(compTarget) < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.lotCode() + " 可用余量 " + available + "g 不足补偿目标 "
                                    + compTarget + "g，整单拒绝");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dyeCode", dyeCode);
                row.put("lotCode", input.lotCode());
                row.put("potencyVersion", currentPotencyVersion);
                row.put("potency", potency);
                row.put("baseTargetG", baseTarget);
                row.put("compTargetG", compTarget);
                computed.add(row);
            }

            Timestamp nowTs = Timestamp.from(now);
            jdbc.update("INSERT INTO comp_approval (request_id, lot_no, recipe_code, recipe_version, "
                            + "status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    req.requestId(), req.lotNo(), recipeCode, recipeVersion,
                    ApprovalStatus.ACTIVE.name(), nowTs);
            Long approvalId = jdbc.queryForObject(
                    "SELECT id FROM comp_approval WHERE request_id = ?", Long.class, req.requestId());
            for (Map<String, Object> row : computed) {
                jdbc.update("INSERT INTO comp_approval_item (approval_id, dye_code, lot_code, "
                                + "potency_version, potency, base_target_g, comp_target_g) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        approvalId, row.get("dyeCode"), row.get("lotCode"), row.get("potencyVersion"),
                        row.get("potency"), row.get("baseTargetG"), row.get("compTargetG"));
            }
            return ServiceResult.created(approvalView(approvalId));
        });
    }

    // ---------------------------------------------------------------------
    // 查询：批次详情与补偿批准证据
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ServiceResult getDyeLot(String lotCode) {
        return ServiceResult.ok(dyeLotView(lotCode));
    }

    /**
     * 补偿证据查询：返回缸次最近一次补偿批准（任意状态）及冻结明细。
     */
    @Transactional(readOnly = true)
    public ServiceResult getCompensation(String lotNo) {
        requireLot(lotNo);
        List<Long> ids = jdbc.query(
                "SELECT id FROM comp_approval WHERE lot_no = ? ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), lotNo);
        if (ids.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "缸次 " + lotNo + " 无补偿批准记录（旧非受控缸次）");
        }
        return ServiceResult.ok(approvalView(ids.get(0)));
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    /** 效价系数必须为正有限十进制；零或非有限数整单拒绝。 */
    private void requireFinitePositive(BigDecimal potency) {
        if (potency == null || potency.signum() <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "效价系数须为正有限数，系数零或非有限数整单拒绝");
        }
    }

    private Map<String, Object> requireDyeLot(String lotCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM dye_lot WHERE lot_code = ?", lotCode);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "染料批次 " + lotCode + " 不存在");
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

    private Map<String, Object> dyeLotView(String lotCode) {
        Map<String, Object> lot = requireDyeLot(lotCode);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("lotCode", lot.get("lot_code"));
        view.put("dyeCode", lot.get("dye_code"));
        view.put("potency", lot.get("potency"));
        view.put("potencyVersion", lot.get("potency_version"));
        view.put("availableG", lot.get("available_g"));
        view.put("validUntil", ((Timestamp) lot.get("valid_until")).toInstant().toString());
        view.put("version", lot.get("version"));
        view.put("createdAt", ((Timestamp) lot.get("created_at")).toInstant().toString());
        view.put("updatedAt", ((Timestamp) lot.get("updated_at")).toInstant().toString());
        return view;
    }

    private Map<String, Object> approvalView(Long approvalId) {
        Map<String, Object> approval = jdbc.queryForMap(
                "SELECT * FROM comp_approval WHERE id = ?", approvalId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("approvalId", approval.get("id"));
        view.put("requestId", approval.get("request_id"));
        view.put("lotNo", approval.get("lot_no"));
        view.put("recipeCode", approval.get("recipe_code"));
        view.put("recipeVersion", approval.get("recipe_version"));
        view.put("status", approval.get("status"));
        view.put("createdAt", ((Timestamp) approval.get("created_at")).toInstant().toString());
        List<Map<String, Object>> items = new ArrayList<>();
        jdbc.query("SELECT dye_code, lot_code, potency_version, potency, base_target_g, comp_target_g "
                + "FROM comp_approval_item WHERE approval_id = ? ORDER BY dye_code", rs -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dyeCode", rs.getString("dye_code"));
            item.put("lotCode", rs.getString("lot_code"));
            item.put("potencyVersion", rs.getInt("potency_version"));
            item.put("potency", rs.getBigDecimal("potency"));
            item.put("baseTargetG", rs.getBigDecimal("base_target_g"));
            item.put("compTargetG", rs.getBigDecimal("comp_target_g"));
            items.add(item);
        }, approvalId);
        view.put("items", items);
        return view;
    }
}
