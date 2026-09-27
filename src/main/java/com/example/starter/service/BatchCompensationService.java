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
import com.example.starter.api.dto.RegisterBatchRequest;
import com.example.starter.api.dto.ReviseBatchRequest;
import com.example.starter.domain.CompensationStatus;
import com.example.starter.support.ApiException;

/**
 * 染料批次效价补偿服务。
 *
 * <p>线性模型：补偿目标克重 = 原放大目标 / 效价系数（十进制计算，四舍五入到三位小数）。
 * 批次系数按版本登记与修订；修订使旧版本失效，并使引用该批次的未投料补偿批准失效，
 * 已投料历史冻结快照不追改。补偿批准冻结批次、系数版本与补偿目标，不修改已发布基础配方。
 */
@Service
public class BatchCompensationService {

    /** 补偿目标保留三位小数（四舍五入）。 */
    static final int COMP_SCALE = 3;
    /** 原放大目标保留位数与旧投料语义一致。 */
    static final int BASE_SCALE = 4;

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public BatchCompensationService(JdbcTemplate jdbc, IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Timestamp nowTs() {
        return Timestamp.from(Instant.now(clock));
    }

    // ------------------------------------------------------------------
    // 批次登记：首个系数版本从 1 开始，携带 expectedVersion=1
    // ------------------------------------------------------------------

    @Transactional
    public ServiceResult registerBatch(RegisterBatchRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchNo", req.batchNo());
        payload.put("dyeCode", req.dyeCode());
        payload.put("potencyFactor", req.potencyFactor());
        payload.put("availableG", req.availableG());
        payload.put("expireAt", req.expireAt().toInstant());
        payload.put("expectedVersion", req.expectedVersion());

        return idempotency.execute(req.requestId(), "BATCH_REGISTER", payload, () -> {
            if (req.expectedVersion() == null || req.expectedVersion() != 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "批次登记为新建聚合，expectedVersion 必须为 1");
            }
            assertFinitePositive(req.potencyFactor(), "效价系数");
            if (req.availableG() == null || req.availableG().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "可用克重必须为正数");
            }
            Integer dyeExists = jdbc.query("SELECT 1 FROM dye WHERE code = ?",
                    rs -> rs.next() ? 1 : null, req.dyeCode());
            if (dyeExists == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "染料代码 " + req.dyeCode() + " 不存在，拒绝登记批次");
            }
            Timestamp now = nowTs();
            try {
                jdbc.update("INSERT INTO dye_batch (batch_no, dye_code, factor_version, potency_factor, "
                        + "available_g, expire_at, active, version, created_at, updated_at) "
                        + "VALUES (?, ?, 1, ?, ?, ?, TRUE, 1, ?, ?)",
                        req.batchNo(), req.dyeCode(), req.potencyFactor(), req.availableG(),
                        Timestamp.from(req.expireAt().toInstant()), now, now);
            } catch (DuplicateKeyException dup) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "染料批次 " + req.batchNo() + " 已登记；修订系数请使用修订接口");
            }
            return ServiceResult.created(batchView(req.batchNo()));
        });
    }

    // ------------------------------------------------------------------
    // 批次系数修订：旧版本失效、新版本号加 1；未投料补偿批准随之失效
    // ------------------------------------------------------------------

    @Transactional
    public ServiceResult reviseBatch(ReviseBatchRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchNo", req.batchNo());
        payload.put("potencyFactor", req.potencyFactor());
        payload.put("availableG", req.availableG());
        payload.put("expireAt", req.expireAt().toInstant());
        payload.put("expectedVersion", req.expectedVersion());

        return idempotency.execute(req.requestId(), "BATCH_REVISE", payload, () -> {
            assertFinitePositive(req.potencyFactor(), "效价系数");
            if (req.availableG() == null || req.availableG().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "可用克重必须为正数");
            }
            Map<String, Object> current = latestBatch(req.batchNo());
            int currentVersion = ((Number) current.get("version")).intValue();
            if (req.expectedVersion() == null || req.expectedVersion() != currentVersion) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "批次 " + req.batchNo() + " 版本冲突：expectedVersion=" + req.expectedVersion()
                                + "，当前版本=" + currentVersion);
            }
            int nextFactorVersion = ((Number) current.get("factor_version")).intValue() + 1;
            Timestamp now = nowTs();

            // 统一加锁顺序 compensation → dye_batch：先锁引用该批次的未投料批准，再改批次行，
            // 与受控投料（先锁补偿、再扣批次余量）保持一致，避免互锁死锁。
            List<Map<String, Object>> frozenRows = jdbc.queryForList(
                    "SELECT c.id, c.version FROM compensation c JOIN compensation_item ci ON ci.compensation_id = c.id "
                            + "WHERE ci.batch_no = ? AND c.status = ? FOR UPDATE",
                    req.batchNo(), CompensationStatus.FROZEN.name());

            jdbc.update("UPDATE dye_batch SET active = FALSE, updated_at = ? WHERE batch_no = ?",
                    now, req.batchNo());
            try {
                jdbc.update("INSERT INTO dye_batch (batch_no, dye_code, factor_version, potency_factor, "
                        + "available_g, expire_at, active, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)",
                        req.batchNo(), current.get("dye_code"), nextFactorVersion, req.potencyFactor(),
                        req.availableG(), Timestamp.from(req.expireAt().toInstant()),
                        currentVersion + 1, now, now);
            } catch (DuplicateKeyException dup) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "批次 " + req.batchNo() + " 系数版本 " + nextFactorVersion + " 已存在（并发修订）");
            }

            // 系数修订：引用该批次的未投料(FROZEN)补偿批准整单失效；FED 历史冻结不追改。
            for (Map<String, Object> row : frozenRows) {
                int updated = jdbc.update(
                        "UPDATE compensation SET status = ?, invalid_reason = ?, version = version + 1, updated_at = ? "
                                + "WHERE id = ? AND status = ? AND version = ?",
                        CompensationStatus.INVALID.name(),
                        "批次 " + req.batchNo() + " 系数修订至 v" + nextFactorVersion + "，未投料批准失效",
                        now, row.get("id"), CompensationStatus.FROZEN.name(), row.get("version"));
                if (updated == 0) {
                    // 并发下被投料或被其它修订抢先处理：整单回滚重试，避免错误宣告失效。
                    throw new ApiException(HttpStatus.CONFLICT,
                            "批次 " + req.batchNo() + " 修订与并发投料冲突，请重试");
                }
            }
            return ServiceResult.ok(batchView(req.batchNo()));
        });
    }

    // ------------------------------------------------------------------
    // 补偿审批：冻结批次/系数/补偿目标，不修改已发布基础配方
    // ------------------------------------------------------------------

    @Transactional
    public ServiceResult approveCompensation(CompensationRequest req) {
        List<Map<String, Object>> itemPayload = new ArrayList<>();
        for (CompensationItemInput item : req.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dyeCode", item.dyeCode());
            entry.put("batchNo", item.batchNo());
            entry.put("factorVersion", item.factorVersion());
            itemPayload.add(entry);
        }
        // 集合按染料代码排序后入指纹：交换提交顺序不产生异参冲突；JSON 结构编码保证含分隔符文本可区分。
        itemPayload.sort((a, b) -> String.valueOf(a.get("dyeCode")).compareTo(String.valueOf(b.get("dyeCode"))));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lotNo", req.lotNo());
        payload.put("expectedVersion", req.expectedVersion());
        payload.put("items", itemPayload);

        return idempotency.execute(req.requestId(), "COMPENSATION_APPROVE", payload, () -> {
            Map<String, Object> lot = requireLot(req.lotNo());
            if (!Boolean.TRUE.equals(lot.get("controlled"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "缸次 " + req.lotNo() + " 为旧非受控缸次，无需也不能办理效价补偿批准");
            }
            if (!"CREATED".equals(lot.get("status"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "受控缸次 " + req.lotNo() + " 当前状态 " + lot.get("status") + "，不能再办理补偿批准");
            }

            Long recipeVersionId = jdbc.queryForObject(
                    "SELECT id FROM recipe WHERE recipe_code = ? AND version = ?",
                    Long.class, lot.get("recipe_code"), lot.get("recipe_version"));
            List<Map<String, Object>> recipeItems = jdbc.queryForList(
                    "SELECT dye_code, dosage_gpl FROM recipe_item WHERE recipe_id = ? ORDER BY dye_code",
                    recipeVersionId);

            Set<String> requested = new LinkedHashSet<>();
            Map<String, CompensationItemInput> inputByDye = new LinkedHashMap<>();
            for (CompensationItemInput item : req.items()) {
                if (!requested.add(item.dyeCode())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "补偿清单中染料 " + item.dyeCode() + " 重复");
                }
                inputByDye.put(item.dyeCode(), item);
            }
            Set<String> required = new LinkedHashSet<>();
            for (Map<String, Object> ri : recipeItems) {
                required.add((String) ri.get("dye_code"));
            }
            if (!requested.equals(required)) {
                Set<String> missing = new LinkedHashSet<>(required);
                missing.removeAll(requested);
                Set<String> extra = new LinkedHashSet<>(requested);
                extra.removeAll(required);
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "补偿染料集合与已发布配方不一致，缺少 " + missing + "、多余 " + extra + "，整单拒绝");
            }

            BigDecimal ratio = (BigDecimal) lot.get("liquor_ratio");
            BigDecimal fabricKg = (BigDecimal) lot.get("fabric_weight_kg");
            Timestamp now = nowTs();
            List<Map<String, Object>> frozenItems = new ArrayList<>();
            for (Map<String, Object> ri : recipeItems) {
                String dyeCode = (String) ri.get("dye_code");
                CompensationItemInput input = inputByDye.get(dyeCode);
                Map<String, Object> batch = requireBatchVersion(input.batchNo(), input.factorVersion());
                if (!dyeCode.equals(batch.get("dye_code"))) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "染料代码错配：批次 " + input.batchNo() + " 属于 " + batch.get("dye_code")
                                    + "，不能用于配方染料 " + dyeCode + "，整单拒绝");
                }
                if (!Boolean.TRUE.equals(batch.get("active"))) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.batchNo() + " 系数版本 v" + input.factorVersion()
                                    + " 已被修订失效，请引用最新版本，整单拒绝");
                }
                BigDecimal factor = (BigDecimal) batch.get("potency_factor");
                assertFinitePositive(factor, "效价系数");
                Timestamp expireAt = (Timestamp) batch.get("expire_at");
                if (!nowTs().before(expireAt)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.batchNo() + " 已到/超过有效截止 " + expireAt.toInstant()
                                    + "（恰在截止即失效），整单拒绝");
                }
                BigDecimal baseTarget = ((BigDecimal) ri.get("dosage_gpl")).multiply(ratio).multiply(fabricKg)
                        .setScale(BASE_SCALE, RoundingMode.HALF_UP);
                BigDecimal compensated = baseTarget.divide(factor, COMP_SCALE, RoundingMode.HALF_UP);
                BigDecimal available = (BigDecimal) batch.get("available_g");
                if (available.compareTo(compensated) < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "批次 " + input.batchNo() + " 余量 " + available + "g 不足补偿目标 "
                                    + compensated + "g，整单拒绝");
                }
                Map<String, Object> frozen = new LinkedHashMap<>();
                frozen.put("dyeCode", dyeCode);
                frozen.put("batchNo", input.batchNo());
                frozen.put("factorVersion", input.factorVersion());
                frozen.put("potencyFactor", factor);
                frozen.put("baseTargetG", baseTarget);
                frozen.put("compensatedTargetG", compensated);
                frozenItems.add(frozen);
            }

            Map<String, Object> existing = jdbc.queryForList(
                    "SELECT id, status, version FROM compensation WHERE lot_id = ? FOR UPDATE", lot.get("id"))
                    .stream().findFirst().orElse(null);
            Long compensationId;
            int resultingVersion;
            if (existing == null) {
                if (req.expectedVersion() == null || req.expectedVersion() != 1) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "缸次 " + req.lotNo() + " 尚无补偿批准，expectedVersion 必须为 1");
                }
                try {
                    jdbc.update("INSERT INTO compensation (request_id, lot_id, status, invalid_reason, version, "
                            + "created_at, updated_at) VALUES (?, ?, ?, NULL, 1, ?, ?)",
                            req.requestId(), lot.get("id"), CompensationStatus.FROZEN.name(), now, now);
                } catch (DuplicateKeyException concurrentInsert) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "缸次 " + req.lotNo() + " 补偿批准并发创建，请携带当前版本重试");
                }
                compensationId = jdbc.queryForObject(
                        "SELECT id FROM compensation WHERE lot_id = ?", Long.class, lot.get("id"));
                resultingVersion = 1;
            } else {
                if (CompensationStatus.FED.name().equals(existing.get("status"))) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "缸次 " + req.lotNo() + " 已投料，补偿冻结为历史，不允许重新审批");
                }
                int expected = req.expectedVersion() == null ? -1 : req.expectedVersion();
                int currentVersion = ((Number) existing.get("version")).intValue();
                if (expected != currentVersion) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "补偿批准版本冲突：expectedVersion=" + expected + "，当前版本=" + currentVersion);
                }
                compensationId = ((Number) existing.get("id")).longValue();
                jdbc.update("DELETE FROM compensation_item WHERE compensation_id = ?", compensationId);
                resultingVersion = currentVersion + 1;
                int updated = jdbc.update("UPDATE compensation SET request_id = ?, status = ?, invalid_reason = NULL, "
                        + "version = ?, updated_at = ? WHERE id = ? AND version = ?",
                        req.requestId(), CompensationStatus.FROZEN.name(), resultingVersion, now,
                        compensationId, currentVersion);
                if (updated != 1) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "补偿批准 " + req.lotNo() + " 并发变更，版本已更新，请携带新版本重试");
                }
            }

            for (Map<String, Object> f : frozenItems) {
                jdbc.update("INSERT INTO compensation_item (compensation_id, dye_code, batch_no, factor_version, "
                        + "potency_factor, base_target_g, compensated_target_g) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        compensationId, f.get("dyeCode"), f.get("batchNo"), f.get("factorVersion"),
                        f.get("potencyFactor"), f.get("baseTargetG"), f.get("compensatedTargetG"));
            }
            return ServiceResult.created(compensationView(req.lotNo(), resultingVersion));
        });
    }

    // ------------------------------------------------------------------
    // 证据查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ServiceResult getBatch(String batchNo) {
        return ServiceResult.ok(batchView(batchNo));
    }

    @Transactional(readOnly = true)
    public ServiceResult getCompensation(String lotNo) {
        requireLot(lotNo);
        Map<String, Object> row = jdbc.queryForList(
                "SELECT id, request_id, status, invalid_reason, version, created_at, updated_at "
                        + "FROM compensation WHERE lot_id = (SELECT id FROM lot WHERE lot_no = ?)",
                lotNo).stream().findFirst().orElse(null);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "缸次 " + lotNo + " 尚无补偿批准");
        }
        int version = ((Number) row.get("version")).intValue();
        return ServiceResult.ok(compensationView(lotNo, version));
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private void assertFinitePositive(BigDecimal value, String label) {
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + "不能为空");
        }
        if (value.signum() <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    label + "必须为正数，零/负数整单拒绝；实际=" + value.toPlainString());
        }
    }

    private Map<String, Object> latestBatch(String batchNo) {
        Map<String, Object> row = jdbc.queryForList(
                "SELECT * FROM dye_batch WHERE batch_no = ? ORDER BY factor_version DESC LIMIT 1",
                batchNo).stream().findFirst().orElse(null);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "染料批次 " + batchNo + " 不存在");
        }
        return row;
    }

    private Map<String, Object> requireBatchVersion(String batchNo, int factorVersion) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM dye_batch WHERE batch_no = ? AND factor_version = ?", batchNo, factorVersion);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "染料批次 " + batchNo + " 系数版本 v" + factorVersion + " 不存在");
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
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM dye_batch WHERE batch_no = ? ORDER BY factor_version", batchNo);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "染料批次 " + batchNo + " 不存在");
        }
        Map<String, Object> current = rows.get(rows.size() - 1);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batchNo", current.get("batch_no"));
        view.put("dyeCode", current.get("dye_code"));
        view.put("factorVersion", current.get("factor_version"));
        view.put("potencyFactor", current.get("potency_factor"));
        view.put("availableG", current.get("available_g"));
        view.put("expireAt", ((Timestamp) current.get("expire_at")).toInstant().toString());
        view.put("active", current.get("active"));
        view.put("version", current.get("version"));
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("factorVersion", r.get("factor_version"));
            h.put("potencyFactor", r.get("potency_factor"));
            h.put("availableG", r.get("available_g"));
            h.put("expireAt", ((Timestamp) r.get("expire_at")).toInstant().toString());
            h.put("active", r.get("active"));
            history.add(h);
        }
        view.put("factorHistory", history);
        return view;
    }

    private Map<String, Object> compensationView(String lotNo, int version) {
        Map<String, Object> comp = jdbc.queryForMap(
                "SELECT * FROM compensation WHERE lot_id = (SELECT id FROM lot WHERE lot_no = ?) AND version = ?",
                lotNo, version);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("requestId", comp.get("request_id"));
        view.put("lotNo", lotNo);
        view.put("status", comp.get("status"));
        view.put("invalidReason", comp.get("invalid_reason"));
        view.put("version", comp.get("version"));
        view.put("createdAt", ((Timestamp) comp.get("created_at")).toInstant().toString());
        view.put("updatedAt", ((Timestamp) comp.get("updated_at")).toInstant().toString());
        List<Map<String, Object>> items = new ArrayList<>();
        jdbc.query("SELECT dye_code, batch_no, factor_version, potency_factor, base_target_g, compensated_target_g "
                + "FROM compensation_item WHERE compensation_id = ? ORDER BY dye_code", rs -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dyeCode", rs.getString("dye_code"));
            item.put("batchNo", rs.getString("batch_no"));
            item.put("factorVersion", rs.getInt("factor_version"));
            item.put("potencyFactor", rs.getBigDecimal("potency_factor"));
            item.put("baseTargetG", rs.getBigDecimal("base_target_g"));
            item.put("compensatedTargetG", rs.getBigDecimal("compensated_target_g"));
            items.add(item);
        }, comp.get("id"));
        view.put("items", items);

        // 实时有效性：FROZEN 且各冻结批次版本仍 active 且未到截止。
        boolean liveValid = CompensationStatus.FROZEN.name().equals(comp.get("status"));
        Timestamp now = nowTs();
        if (liveValid) {
            for (Map<String, Object> item : items) {
                Map<String, Object> batch = jdbc.queryForMap(
                        "SELECT active, expire_at FROM dye_batch WHERE batch_no = ? AND factor_version = ?",
                        item.get("batchNo"), item.get("factorVersion"));
                if (!Boolean.TRUE.equals(batch.get("active")) || !now.before((Timestamp) batch.get("expire_at"))) {
                    liveValid = false;
                    break;
                }
            }
        }
        view.put("currentlyValid", liveValid);
        return view;
    }
}
