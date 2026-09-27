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
import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.RegisterBatchRequest;
import com.example.starter.api.dto.ReviseFactorRequest;
import com.example.starter.api.dto.WeighingInput;
import com.example.starter.domain.ApprovalStatus;
import com.example.starter.domain.LotStatus;
import com.example.starter.support.ApiException;

/**
 * 染料批次效价补偿与受控投料服务。
 * 补偿目标=原目标/效价系数（线性模型，十进制计算后四舍五入三位小数）；
 * 批准冻结批次、系数与目标，不修改已发布基础配方；
 * 系数修订使未投料批准失效，已投料历史不追改；
 * 受控投料同时验证补偿版本、称量偏差（恰±0.5%合法）与批次有效期（now&lt;截止有效，恰在截止失效），
 * 原子扣减各染料批次余量并写称量链。
 */
@Service
public class CompensationService {

    /** 补偿目标小数位：三位。 */
    private static final int COMPENSATED_SCALE = 3;

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final DyeService dyeService;

    public CompensationService(JdbcTemplate jdbc, IdempotencyService idempotency,
                               Clock clock, DyeService dyeService) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.clock = clock;
        this.dyeService = dyeService;
    }

    private Timestamp nowTs() {
        return Timestamp.from(Instant.now(clock));
    }

    // ---------------------------------------------------------------------
    // 批次登记：染料代码 + 正数效价系数 + 可用克重 + 有效截止；版本从 1 开始
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult registerBatch(RegisterBatchRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchNo", req.batchNo());
        payload.put("dyeCode", req.dyeCode());
        payload.put("factor", req.factor());
        payload.put("availableG", req.availableG());
        payload.put("validUntil", req.validUntil().toString());
        return idempotency.execute(req.requestId(), "BATCH_REGISTER", payload, () -> {
            if (req.factor().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "效价系数须为正数有限值，拒绝零或非正系数");
            }
            Integer dyeExists = jdbc.query("SELECT 1 FROM dye WHERE code = ?",
                    rs -> rs.next() ? 1 : null, req.dyeCode());
            if (dyeExists == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "染化料 " + req.dyeCode() + " 不存在，拒绝登记批次");
            }
            Timestamp now = nowTs();
            try {
                jdbc.update("INSERT INTO dye_batch (batch_no, dye_code, factor, available_g, valid_until, "
                                + "version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                        req.batchNo(), req.dyeCode(), req.factor(), req.availableG(),
                        Timestamp.from(req.validUntil()), now, now);
            } catch (DuplicateKeyException dup) {
                throw new ApiException(HttpStatus.CONFLICT, "批次号 " + req.batchNo() + " 已存在");
            }
            Map<String, Object> body = batchView(req.batchNo());
            body.put("requestId", req.requestId());
            return ServiceResult.created(body);
        });
    }

    // ---------------------------------------------------------------------
    // 系数修订：expectedVersion 乐观校验，成功版本加 1；未投料批准失效，已投料不追改
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult reviseFactor(String batchNo, ReviseFactorRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchNo", batchNo);
        payload.put("expectedVersion", req.expectedVersion());
        payload.put("factor", req.factor());
        return idempotency.execute(req.requestId(), "BATCH_REVISE", payload, () -> {
            if (req.factor().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "效价系数须为正数有限值，拒绝零或非正系数");
            }
            Map<String, Object> batch = requireBatch(batchNo);
            int currentVersion = ((Number) batch.get("version")).intValue();
            if (currentVersion != req.expectedVersion()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "批次 " + batchNo + " 当前系数版本 " + currentVersion
                                + " 与 expectedVersion " + req.expectedVersion() + " 不一致，拒绝修订");
            }
            Timestamp now = nowTs();
            int newVersion = currentVersion + 1;
            jdbc.update("UPDATE dye_batch SET factor = ?, version = ?, updated_at = ? WHERE batch_no = ?",
                    req.factor(), newVersion, now, batchNo);
            // 系数修订使引用该批次的未投料批准失效；已投料(FED)历史不追改。
            int invalidated = jdbc.update("UPDATE compensation_approval SET status = ?, updated_at = ? "
                            + "WHERE status = ? AND id IN (SELECT approval_id FROM compensation_item "
                            + "WHERE batch_no = ?)",
                    ApprovalStatus.INVALID.name(), now, ApprovalStatus.VALID.name(), batchNo);

            Map<String, Object> body = batchView(batchNo);
            body.put("requestId", req.requestId());
            body.put("invalidatedApprovals", invalidated);
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------------
    // 补偿批准：为每种配方染料指定批次与系数版本，冻结批次/系数/补偿目标
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult approveCompensation(String lotNo, ApproveCompensationRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", lotNo);
        List<Map<String, Object>> itemPayload = new ArrayList<>();
        for (CompensationItemInput item : req.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dyeCode", item.dyeCode());
            entry.put("batchNo", item.batchNo());
            entry.put("factorVersion", item.factorVersion());
            itemPayload.add(entry);
        }
        itemPayload.sort((a, b) -> String.valueOf(a.get("dyeCode")).compareTo(String.valueOf(b.get("dyeCode"))));
        payload.put("items", itemPayload);

        return idempotency.execute(req.requestId(), "COMPENSATION_APPROVE", payload, () -> {
            Map<String, Object> lot = dyeService.requireLot(lotNo);
            // 锁定缸次行：并发审批同一缸次时串行化，保证至多一个有效批准。
            jdbc.queryForObject("SELECT id FROM lot WHERE lot_no = ? FOR UPDATE", Long.class, lotNo);
            lot = dyeService.requireLot(lotNo);
            if (!LotStatus.CREATED.name().equals(lot.get("status"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + lotNo + " 当前状态 " + lot.get("status") + "，仅待投料缸次可补偿审批");
            }
            Long validCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM compensation_approval WHERE lot_no = ? AND status = ?",
                    Long.class, lotNo, ApprovalStatus.VALID.name());
            if (validCount != null && validCount > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + lotNo + " 已存在有效补偿批准，拒绝重复审批");
            }

            Long recipeId = dyeService.recipeId((String) lot.get("recipe_code"),
                    ((Number) lot.get("recipe_version")).intValue());
            List<Map<String, Object>> recipeItems = dyeService.recipeItems(recipeId);
            BigDecimal ratio = (BigDecimal) lot.get("liquor_ratio");
            BigDecimal fabricKg = (BigDecimal) lot.get("fabric_weight_kg");

            Map<String, CompensationItemInput> requestByDye = new LinkedHashMap<>();
            for (CompensationItemInput item : req.items()) {
                if (requestByDye.put(item.dyeCode(), item) != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "染化料 " + item.dyeCode() + " 补偿项重复");
                }
            }
            Set<String> required = new LinkedHashSet<>();
            List<Map<String, Object>> frozen = new ArrayList<>();
            for (Map<String, Object> recipeItem : recipeItems) {
                String dyeCode = (String) recipeItem.get("dye_code");
                required.add(dyeCode);
                CompensationItemInput input = requestByDye.get(dyeCode);
                if (input == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "缺少染化料 " + dyeCode + " 的补偿批次指定，拒绝审批");
                }
                Map<String, Object> batch = requireBatch(input.batchNo());
                if (!batch.get("dye_code").equals(dyeCode)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.batchNo() + " 属于染化料 " + batch.get("dye_code")
                                    + "，与配方染料 " + dyeCode + " 代码错配，整单拒绝");
                }
                int batchVersion = ((Number) batch.get("version")).intValue();
                if (batchVersion != input.factorVersion()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.batchNo() + " 当前系数版本 " + batchVersion
                                    + " 与指定版本 " + input.factorVersion() + " 不一致，整单拒绝");
                }
                BigDecimal factor = (BigDecimal) batch.get("factor");
                if (factor.signum() <= 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.batchNo() + " 效价系数为零或非正，整单拒绝");
                }
                BigDecimal baseTarget = ((BigDecimal) recipeItem.get("dosage_gpl"))
                        .multiply(ratio).multiply(fabricKg).setScale(4, RoundingMode.HALF_UP);
                BigDecimal compensated = baseTarget.divide(factor, COMPENSATED_SCALE, RoundingMode.HALF_UP);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dyeCode", dyeCode);
                row.put("batchNo", input.batchNo());
                row.put("factorVersion", batchVersion);
                row.put("factor", factor);
                row.put("baseTargetG", baseTarget);
                row.put("compensatedTargetG", compensated);
                frozen.add(row);
            }
            if (!requestByDye.keySet().equals(required)) {
                Set<String> extra = new LinkedHashSet<>(requestByDye.keySet());
                extra.removeAll(required);
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "存在配方外染化料补偿项：" + extra + "，拒绝审批");
            }

            Timestamp now = nowTs();
            jdbc.update("INSERT INTO compensation_approval (request_id, lot_no, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    req.requestId(), lotNo, ApprovalStatus.VALID.name(), now, now);
            Long approvalId = jdbc.queryForObject(
                    "SELECT id FROM compensation_approval WHERE request_id = ?", Long.class, req.requestId());
            for (Map<String, Object> row : frozen) {
                jdbc.update("INSERT INTO compensation_item (approval_id, dye_code, batch_no, factor_version, "
                                + "factor, base_target_g, compensated_target_g) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        approvalId, row.get("dyeCode"), row.get("batchNo"), row.get("factorVersion"),
                        row.get("factor"), row.get("baseTargetG"), row.get("compensatedTargetG"));
            }
            Map<String, Object> body = approvalView(approvalId);
            body.put("requestId", req.requestId());
            return ServiceResult.created(body);
        });
    }

    // ---------------------------------------------------------------------
    // 受控投料：验证补偿版本/称量/有效期，原子扣减批次余量并写称量链
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult controlledFeed(FeedRequest req) {
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

        return idempotency.execute(req.requestId(), "CONTROLLED_FEED", payload, () -> {
            Map<String, Object> lot = dyeService.requireLot(req.lotNo());
            // 锁定缸次行：并发受控投料同一缸次时串行化，后到者看到 FED 状态拒绝。
            jdbc.queryForObject("SELECT id FROM lot WHERE lot_no = ? FOR UPDATE", Long.class, req.lotNo());
            lot = dyeService.requireLot(req.lotNo());
            if (!LotStatus.CREATED.name().equals(lot.get("status"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + req.lotNo() + " 当前状态 " + lot.get("status") + "，不允许受控投料");
            }
            Map<String, Object> approval = latestValidApproval(req.lotNo());
            if (approval == null) {
                Integer anyApproval = jdbc.query("SELECT 1 FROM compensation_approval WHERE lot_no = ? LIMIT 1",
                        rs -> rs.next() ? 1 : null, req.lotNo());
                if (anyApproval == null) {
                    throw new ApiException(HttpStatus.NOT_FOUND,
                            "缸次 " + req.lotNo() + " 无补偿批准，非受控缸次请走旧投料入口");
                }
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + req.lotNo() + " 补偿批准已失效（批次系数修订），须重新审批后再投料");
            }
            Long approvalId = ((Number) approval.get("id")).longValue();
            List<Map<String, Object>> items = jdbc.queryForList(
                    "SELECT dye_code, batch_no, factor_version, factor, base_target_g, compensated_target_g "
                            + "FROM compensation_item WHERE approval_id = ? ORDER BY dye_code", approvalId);

            Map<String, BigDecimal> actualByDye = new LinkedHashMap<>();
            for (WeighingInput w : req.weighings()) {
                if (actualByDye.put(w.dyeCode(), w.actualG()) != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "染化料 " + w.dyeCode() + " 称量记录重复");
                }
            }

            Timestamp now = nowTs();
            Set<String> required = new LinkedHashSet<>();
            List<Map<String, Object>> computed = new ArrayList<>();
            for (Map<String, Object> item : items) {
                String dyeCode = (String) item.get("dye_code");
                required.add(dyeCode);
                BigDecimal factor = (BigDecimal) item.get("factor");
                if (factor.signum() <= 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "染化料 " + dyeCode + " 冻结效价系数为零或非正，整单拒绝投料");
                }
                Map<String, Object> batch = requireBatch((String) item.get("batch_no"));
                if (!batch.get("dye_code").equals(dyeCode)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + item.get("batch_no") + " 与染化料 " + dyeCode + " 代码错配，整单拒绝投料");
                }
                int batchVersion = ((Number) batch.get("version")).intValue();
                int frozenVersion = ((Number) item.get("factor_version")).intValue();
                if (batchVersion != frozenVersion) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + item.get("batch_no") + " 系数版本已变更为 " + batchVersion
                                    + "（批准冻结 " + frozenVersion + "），补偿版本不一致，整单拒绝投料");
                }
                Timestamp validUntil = (Timestamp) batch.get("valid_until");
                if (!now.before(validUntil)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + item.get("batch_no") + " 有效截止 " + validUntil.toInstant()
                                    + " 已到（恰在截止失效），整单拒绝投料");
                }
                BigDecimal target = (BigDecimal) item.get("compensated_target_g");
                BigDecimal actual = actualByDye.get(dyeCode);
                if (actual == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "缺少染化料 " + dyeCode + " 的实际称量，拒绝投料");
                }
                BigDecimal deviation = actual.subtract(target)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(target, DyeService.DEVIATION_SCALE, RoundingMode.HALF_UP);
                if (deviation.abs().compareTo(DyeService.WEIGH_LIMIT_PCT) > 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "染化料 " + dyeCode + " 称量偏差 " + deviation
                                    + "% 超过 ±0.5%（按补偿目标，恰 0.5% 合法），整单拒绝投料且不扣批次余量");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dyeCode", dyeCode);
                row.put("batchNo", item.get("batch_no"));
                row.put("compensatedTargetG", target);
                row.put("actualG", actual);
                row.put("deviationPct", deviation);
                computed.add(row);
            }
            if (!actualByDye.keySet().equals(required)) {
                Set<String> extra = new LinkedHashSet<>(actualByDye.keySet());
                extra.removeAll(required);
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "存在补偿批准外染化料称量：" + extra + "，拒绝投料");
            }

            // 批次余量预检：任一染料不足整单回滚，第二种不足时第一种也不扣。
            for (Map<String, Object> row : computed) {
                BigDecimal available = jdbc.queryForObject(
                        "SELECT available_g FROM dye_batch WHERE batch_no = ?",
                        BigDecimal.class, row.get("batchNo"));
                if (available.compareTo((BigDecimal) row.get("actualG")) < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + row.get("batchNo") + " 可用余量 " + available
                                    + "g 不足 " + row.get("actualG") + "g，整单拒绝投料且不扣任何批次余量");
                }
            }

            jdbc.update("INSERT INTO feeding (request_id, lot_id, approval_id, fed_at) VALUES (?, ?, ?, ?)",
                    req.requestId(), lot.get("id"), approvalId, now);
            Long feedingId = jdbc.queryForObject(
                    "SELECT id FROM feeding WHERE request_id = ?", Long.class, req.requestId());
            for (Map<String, Object> row : computed) {
                jdbc.update("INSERT INTO weighing (feeding_id, dye_code, batch_no, target_g, actual_g, "
                                + "deviation_pct) VALUES (?, ?, ?, ?, ?, ?)",
                        feedingId, row.get("dyeCode"), row.get("batchNo"), row.get("compensatedTargetG"),
                        row.get("actualG"), row.get("deviationPct"));
                int deducted = jdbc.update("UPDATE dye_batch SET available_g = available_g - ?, updated_at = ? "
                                + "WHERE batch_no = ? AND available_g >= ?",
                        row.get("actualG"), now, row.get("batchNo"), row.get("actualG"));
                if (deducted != 1) {
                    // 并发扣减防护：与预检构成双保险，失败随事务整体回滚，不得超耗。
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + row.get("batchNo") + " 并发扣减失败，整单回滚");
                }
            }
            jdbc.update("UPDATE compensation_approval SET status = ?, updated_at = ? WHERE id = ?",
                    ApprovalStatus.FED.name(), now, approvalId);
            jdbc.update("UPDATE lot SET status = ?, updated_at = ? WHERE id = ?",
                    LotStatus.FED.name(), now, lot.get("id"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", req.requestId());
            body.put("lotNo", req.lotNo());
            body.put("approvalId", approvalId);
            body.put("weighings", computed);
            body.put("status", LotStatus.FED.name());
            body.put("fedAt", now.toInstant().toString());
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------------
    // 查询：批次视图与补偿审批/称量快照证据
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ServiceResult getBatch(String batchNo) {
        return ServiceResult.ok(batchView(batchNo));
    }

    @Transactional(readOnly = true)
    public ServiceResult getCompensationEvidence(String lotNo) {
        dyeService.requireLot(lotNo);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM compensation_approval WHERE lot_no = ? ORDER BY id DESC LIMIT 1", lotNo);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "缸次 " + lotNo + " 无补偿批准记录");
        }
        Long approvalId = ((Number) rows.get(0).get("id")).longValue();
        Map<String, Object> body = approvalView(approvalId);
        Long feedingId = jdbc.query("SELECT id FROM feeding WHERE approval_id = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, approvalId);
        if (feedingId != null) {
            List<Map<String, Object>> weighings = new ArrayList<>();
            jdbc.query("SELECT dye_code, batch_no, target_g, actual_g, deviation_pct FROM weighing "
                    + "WHERE feeding_id = ? ORDER BY id", rs -> {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("dyeCode", rs.getString("dye_code"));
                w.put("batchNo", rs.getString("batch_no"));
                w.put("compensatedTargetG", rs.getBigDecimal("target_g"));
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

    private Map<String, Object> latestValidApproval(String lotNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM compensation_approval WHERE lot_no = ? AND status = ? "
                        + "ORDER BY id DESC LIMIT 1", lotNo, ApprovalStatus.VALID.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> batchView(String batchNo) {
        Map<String, Object> batch = requireBatch(batchNo);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batchNo", batch.get("batch_no"));
        view.put("dyeCode", batch.get("dye_code"));
        view.put("factor", batch.get("factor"));
        view.put("availableG", batch.get("available_g"));
        view.put("validUntil", ((Timestamp) batch.get("valid_until")).toInstant().toString());
        view.put("version", batch.get("version"));
        view.put("createdAt", ((Timestamp) batch.get("created_at")).toInstant().toString());
        view.put("updatedAt", ((Timestamp) batch.get("updated_at")).toInstant().toString());
        return view;
    }

    private Map<String, Object> approvalView(Long approvalId) {
        Map<String, Object> approval = jdbc.queryForMap(
                "SELECT id, lot_no, status, created_at, updated_at FROM compensation_approval WHERE id = ?",
                approvalId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("approvalId", approval.get("id"));
        view.put("lotNo", approval.get("lot_no"));
        view.put("status", approval.get("status"));
        List<Map<String, Object>> items = new ArrayList<>();
        jdbc.query("SELECT dye_code, batch_no, factor_version, factor, base_target_g, compensated_target_g "
                + "FROM compensation_item WHERE approval_id = ? ORDER BY dye_code", rs -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dyeCode", rs.getString("dye_code"));
            item.put("batchNo", rs.getString("batch_no"));
            item.put("factorVersion", rs.getInt("factor_version"));
            item.put("factor", rs.getBigDecimal("factor"));
            item.put("baseTargetG", rs.getBigDecimal("base_target_g"));
            item.put("compensatedTargetG", rs.getBigDecimal("compensated_target_g"));
            items.add(item);
        }, approvalId);
        view.put("items", items);
        view.put("createdAt", ((Timestamp) approval.get("created_at")).toInstant().toString());
        view.put("updatedAt", ((Timestamp) approval.get("updated_at")).toInstant().toString());
        return view;
    }
}
