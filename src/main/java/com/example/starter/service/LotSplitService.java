package com.example.starter.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

import com.example.starter.api.dto.SplitChildInput;
import com.example.starter.api.dto.SplitLotRequest;
import com.example.starter.domain.LotStatus;
import com.example.starter.support.ApiException;

/**
 * 分缸服务：把已成功投料（FED）且未色判、未归档、未曾分缸的缸次拆成 2-4 个子缸。
 *
 * <p>规则要点：
 * <ul>
 *   <li>子缸正公斤数最多三位小数，合计精确等于来源布重；目标缸占用/重复/重量不符整单拒绝。</li>
 *   <li>来源缸次转 SPLIT 只读并释放原缸，所有目标占用在同一事务建立；原配方、效价批次、
 *       补偿版本与实际称量保持冻结不变，批次余量与库存不再扣减。</li>
 *   <li>每种染料实际耗用按子缸布重比例分摊：先向下截到 0.0001g，剩余 0.0001g 单位
 *       按子缸编号升序循环分配；各子缸合计精确等于来源实际耗用。</li>
 *   <li>分缸与来源判色竞争由条件更新保证只有一方成功；同源分缸最多成功一次。</li>
 *   <li>分缸聚合版本新增从 1 起，expectedVersion 须为 1，旧版 409；requestId 幂等。</li>
 * </ul>
 */
@Service
public class LotSplitService {

    /** 子缸数量下限。 */
    static final int MIN_CHILDREN = 2;
    /** 子缸数量上限。 */
    static final int MAX_CHILDREN = 4;
    /** 子缸布重允许的最大小数位数。 */
    static final int WEIGHT_SCALE = 3;
    /** 耗用分摊最小单位：0.0001g。 */
    static final int USAGE_SCALE = 4;

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public LotSplitService(JdbcTemplate jdbc, IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Timestamp nowTs() {
        return Timestamp.from(Instant.now(clock));
    }

    // ---------------------------------------------------------------------
    // 分缸：校验 → 建子缸与占用 → 分摊耗用 → 来源转 SPLIT，全部同一事务
    // ---------------------------------------------------------------------

    @Transactional
    public ServiceResult splitLot(SplitLotRequest req) {
        List<SplitChildInput> sorted = new ArrayList<>(req.children());
        sorted.sort(Comparator.comparing(SplitChildInput::lotNo));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceLotNo", req.sourceLotNo());
        payload.put("expectedVersion", req.expectedVersion());
        List<Map<String, Object>> childPayload = new ArrayList<>();
        for (SplitChildInput c : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("lotNo", c.lotNo());
            entry.put("vatNo", c.vatNo());
            entry.put("fabricWeightKg", c.fabricWeightKg());
            childPayload.add(entry);
        }
        payload.put("children", childPayload);

        return idempotency.execute(req.requestId(), "SPLIT", payload, () -> doSplit(req, sorted));
    }

    private ServiceResult doSplit(SplitLotRequest req, List<SplitChildInput> sortedChildren) {
        if (req.expectedVersion() != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "分缸版本冲突：新增分缸版本 1 起，expectedVersion 须为 1，当前=" + req.expectedVersion());
        }
        if (sortedChildren.size() < MIN_CHILDREN || sortedChildren.size() > MAX_CHILDREN) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "分缸子缸数量须为 " + MIN_CHILDREN + "-" + MAX_CHILDREN + " 个，当前 "
                            + sortedChildren.size() + " 个，整单拒绝");
        }

        Map<String, Object> source = requireLot(req.sourceLotNo());
        if (!LotStatus.FED.name().equals(source.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "缸次 " + req.sourceLotNo() + " 当前状态 " + source.get("status")
                            + "，仅已成功投料且未色判、未归档、未曾分缸的缸次可分缸");
        }

        Set<String> lotNos = new LinkedHashSet<>();
        Set<String> vatNos = new LinkedHashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (SplitChildInput c : sortedChildren) {
            if (c.lotNo().equals(req.sourceLotNo())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "子缸号不能与来源缸次 " + req.sourceLotNo() + " 相同");
            }
            if (!lotNos.add(c.lotNo())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "子缸号 " + c.lotNo() + " 重复，整单拒绝");
            }
            if (!vatNos.add(c.vatNo())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "目标染缸 " + c.vatNo() + " 重复，整单拒绝");
            }
            BigDecimal weight = c.fabricWeightKg();
            if (weight.stripTrailingZeros().scale() > WEIGHT_SCALE) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "子缸 " + c.lotNo() + " 布重 " + weight.toPlainString() + " 超过三位小数，整单拒绝");
            }
            sum = sum.add(weight);
        }
        BigDecimal sourceWeight = (BigDecimal) source.get("fabric_weight_kg");
        if (sum.compareTo(sourceWeight) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "子缸布重合计 " + sum.stripTrailingZeros().toPlainString()
                            + "kg 不等于来源布重 " + sourceWeight.stripTrailingZeros().toPlainString()
                            + "kg，整单拒绝");
        }

        // 目标缸占用预检（含来源原缸：分缸提交时刻仍被来源占用，须选其他空闲缸）。
        for (SplitChildInput c : sortedChildren) {
            Integer occupied = jdbc.query("SELECT 1 FROM vat_active WHERE vat_no = ?",
                    rs -> rs.next() ? 1 : null, c.vatNo());
            if (occupied != null) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "目标染缸 " + c.vatNo() + " 已被占用，整单拒绝");
            }
            Integer lotExists = jdbc.query("SELECT 1 FROM lot WHERE lot_no = ?",
                    rs -> rs.next() ? 1 : null, c.lotNo());
            if (lotExists != null) {
                throw new ApiException(HttpStatus.CONFLICT, "缸次号 " + c.lotNo() + " 已存在，整单拒绝");
            }
        }

        // 来源实际称量（原证据链保持冻结，不覆盖、不重扣）。
        List<Map<String, Object>> sourceWeighings = jdbc.queryForList(
                "SELECT w.dye_code, w.actual_g FROM weighing w "
                        + "JOIN feeding f ON f.id = w.feeding_id WHERE f.lot_id = ? ORDER BY w.dye_code",
                source.get("id"));
        if (sourceWeighings.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "缸次 " + req.sourceLotNo() + " 无投料称量记录，拒绝分缸");
        }

        Timestamp now = nowTs();
        Long splitId;
        try {
            jdbc.update("INSERT INTO lot_split (request_id, source_lot_id, version, created_at, updated_at) "
                    + "VALUES (?, ?, 1, ?, ?)", req.requestId(), source.get("id"), now, now);
            splitId = jdbc.queryForObject("SELECT id FROM lot_split WHERE request_id = ?",
                    Long.class, req.requestId());
        } catch (DuplicateKeyException dup) {
            // 唯一约束兜底：同源并发分缸或重复 requestId 直达时，最多一次成功。
            throw new ApiException(HttpStatus.CONFLICT,
                    "缸次 " + req.sourceLotNo() + " 已分缸，两次拆同源最多一次成功");
        }

        List<Long> childLotIds = new ArrayList<>();
        for (SplitChildInput c : sortedChildren) {
            jdbc.update("INSERT INTO lot (lot_no, vat_no, recipe_code, recipe_version, liquor_ratio, "
                    + "fabric_weight_kg, controlled, status, repair_count, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                    c.lotNo(), c.vatNo(), source.get("recipe_code"), source.get("recipe_version"),
                    source.get("liquor_ratio"), c.fabricWeightKg(),
                    Boolean.TRUE.equals(source.get("controlled")), LotStatus.FED.name(), now, now);
            Long childLotId = jdbc.queryForObject("SELECT id FROM lot WHERE lot_no = ?",
                    Long.class, c.lotNo());
            childLotIds.add(childLotId);
            try {
                jdbc.update("INSERT INTO vat_active (vat_no, lot_id, since) VALUES (?, ?, ?)",
                        c.vatNo(), childLotId, now);
            } catch (DuplicateKeyException dupVat) {
                // 并发占缸兜底：败方整单回滚，不残留任何子缸与占用。
                throw new ApiException(HttpStatus.CONFLICT,
                        "目标染缸 " + c.vatNo() + " 并发占用冲突，整单回滚");
            }
            jdbc.update("INSERT INTO lot_split_child (split_id, child_lot_id, fabric_weight_kg) "
                    + "VALUES (?, ?, ?)", splitId, childLotId, c.fabricWeightKg());
        }

        // 每种染料实际耗用按子缸布重比例分摊，子缸编号升序（sortedChildren 已按号排序）。
        List<BigDecimal> weights = new ArrayList<>();
        for (SplitChildInput c : sortedChildren) {
            weights.add(c.fabricWeightKg());
        }
        Map<String, List<BigDecimal>> allocationByDye = new LinkedHashMap<>();
        for (Map<String, Object> w : sourceWeighings) {
            String dyeCode = (String) w.get("dye_code");
            BigDecimal actual = (BigDecimal) w.get("actual_g");
            List<BigDecimal> shares = allocate(actual, weights);
            allocationByDye.put(dyeCode, shares);
            for (int i = 0; i < childLotIds.size(); i++) {
                jdbc.update("INSERT INTO lot_split_usage (split_id, child_lot_id, dye_code, allocated_g) "
                        + "VALUES (?, ?, ?, ?)", splitId, childLotIds.get(i), dyeCode, shares.get(i));
            }
        }

        // 来源转 SPLIT 只读：条件更新保证与判色竞争只有一方成功。
        int cas = jdbc.update("UPDATE lot SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                LotStatus.SPLIT.name(), now, source.get("id"), LotStatus.FED.name());
        if (cas != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "缸次 " + req.sourceLotNo() + " 判色与分缸竞争：来源状态已变化，分缸整单回滚");
        }
        // 释放原缸占用，目标占用已在同事务建立。
        jdbc.update("DELETE FROM vat_active WHERE lot_id = ?", source.get("id"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", req.requestId());
        body.put("sourceLotNo", req.sourceLotNo());
        body.put("sourceStatus", LotStatus.SPLIT.name());
        body.put("splitVersion", 1);
        List<Map<String, Object>> childViews = new ArrayList<>();
        for (int i = 0; i < sortedChildren.size(); i++) {
            SplitChildInput c = sortedChildren.get(i);
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("lotNo", c.lotNo());
            child.put("vatNo", c.vatNo());
            child.put("fabricWeightKg", c.fabricWeightKg());
            child.put("status", LotStatus.FED.name());
            List<Map<String, Object>> usages = new ArrayList<>();
            for (Map.Entry<String, List<BigDecimal>> e : allocationByDye.entrySet()) {
                Map<String, Object> usage = new LinkedHashMap<>();
                usage.put("dyeCode", e.getKey());
                usage.put("allocatedG", e.getValue().get(i));
                usages.add(usage);
            }
            child.put("usages", usages);
            childViews.add(child);
        }
        body.put("children", childViews);
        body.put("splitAt", now.toInstant().toString());
        return ServiceResult.created(body);
    }

    /**
     * 耗用分摊：totalG（精度 0.0001g）按各子缸布重比例分配。
     * 先向下截到 0.0001g，剩余 0.0001g 单位按子缸顺序（调用方按缸号升序传入）循环分配；
     * 返回各子缸分摊克重，合计精确等于 totalG。
     */
    static List<BigDecimal> allocate(BigDecimal totalG, List<BigDecimal> weightsKg) {
        BigInteger totalUnits = totalG.movePointRight(USAGE_SCALE).toBigIntegerExact();
        int n = weightsKg.size();
        BigInteger[] weightUnits = new BigInteger[n];
        BigInteger weightSum = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            weightUnits[i] = weightsKg.get(i).movePointRight(WEIGHT_SCALE).toBigIntegerExact();
            weightSum = weightSum.add(weightUnits[i]);
        }
        BigInteger[] shares = new BigInteger[n];
        BigInteger allocated = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            shares[i] = totalUnits.multiply(weightUnits[i]).divide(weightSum);
            allocated = allocated.add(shares[i]);
        }
        BigInteger remainder = totalUnits.subtract(allocated);
        int idx = 0;
        while (remainder.signum() > 0) {
            shares[idx % n] = shares[idx % n].add(BigInteger.ONE);
            remainder = remainder.subtract(BigInteger.ONE);
            idx++;
        }
        List<BigDecimal> result = new ArrayList<>(n);
        for (BigInteger share : shares) {
            result.add(new BigDecimal(share, USAGE_SCALE));
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // 谱系查询：来源缸次只查谱系；子缸可查其分缸来源与继承耗用
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ServiceResult getSplitLineage(String lotNo) {
        Map<String, Object> lot = requireLot(lotNo);
        Long lotId = ((Number) lot.get("id")).longValue();

        Map<String, Object> split = jdbc.queryForList(
                "SELECT id, request_id, version, created_at FROM lot_split WHERE source_lot_id = ?",
                lotId).stream().findFirst().orElse(null);
        if (split != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("role", "SOURCE");
            body.put("sourceLotNo", lotNo);
            body.put("sourceStatus", lot.get("status"));
            body.put("splitVersion", split.get("version"));
            body.put("splitAt", ((Timestamp) split.get("created_at")).toInstant().toString());
            body.put("children", childViews(((Number) split.get("id")).longValue()));
            return ServiceResult.ok(body);
        }

        Map<String, Object> childRow = jdbc.queryForList(
                "SELECT split_id, fabric_weight_kg FROM lot_split_child WHERE child_lot_id = ?",
                lotId).stream().findFirst().orElse(null);
        if (childRow != null) {
            Long splitId = ((Number) childRow.get("split_id")).longValue();
            Map<String, Object> sourceRow = jdbc.queryForMap(
                    "SELECT l.lot_no FROM lot l JOIN lot_split s ON s.source_lot_id = l.id WHERE s.id = ?",
                    splitId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("role", "CHILD");
            body.put("childLotNo", lotNo);
            body.put("splitFromLotNo", sourceRow.get("lot_no"));
            body.put("fabricWeightKg", childRow.get("fabric_weight_kg"));
            body.put("usages", usagesOf(splitId, lotId));
            return ServiceResult.ok(body);
        }

        throw new ApiException(HttpStatus.NOT_FOUND, "缸次 " + lotNo + " 无分缸谱系");
    }

    private List<Map<String, Object>> childViews(Long splitId) {
        List<Map<String, Object>> children = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.child_lot_id, c.fabric_weight_kg, l.lot_no, l.vat_no, l.status "
                        + "FROM lot_split_child c JOIN lot l ON l.id = c.child_lot_id "
                        + "WHERE c.split_id = ? ORDER BY l.lot_no", splitId);
        for (Map<String, Object> row : rows) {
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("lotNo", row.get("lot_no"));
            child.put("vatNo", row.get("vat_no"));
            child.put("status", row.get("status"));
            child.put("fabricWeightKg", row.get("fabric_weight_kg"));
            Long childLotId = ((Number) row.get("child_lot_id")).longValue();
            child.put("usages", usagesOf(splitId, childLotId));
            children.add(child);
        }
        return children;
    }

    private List<Map<String, Object>> usagesOf(Long splitId, Long childLotId) {
        List<Map<String, Object>> usages = new ArrayList<>();
        jdbc.query("SELECT dye_code, allocated_g FROM lot_split_usage "
                + "WHERE split_id = ? AND child_lot_id = ? ORDER BY dye_code", rs -> {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("dyeCode", rs.getString("dye_code"));
            usage.put("allocatedG", rs.getBigDecimal("allocated_g"));
            usages.add(usage);
        }, splitId, childLotId);
        return usages;
    }

    private Map<String, Object> requireLot(String lotNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM lot WHERE lot_no = ?", lotNo);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "缸次 " + lotNo + " 不存在");
        }
        return rows.get(0);
    }
}
