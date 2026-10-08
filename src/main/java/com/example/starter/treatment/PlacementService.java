package com.example.starter.treatment;

import com.example.starter.common.ApiException;
import com.example.starter.common.IdempotentExecutor;
import com.example.starter.pest.CaseRow;
import com.example.starter.pest.PestCaseService;
import com.example.starter.pesticide.BatchRow;
import com.example.starter.pesticide.BatchService;
import com.example.starter.pesticide.PesticideRow;
import com.example.starter.pesticide.PesticideService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * 治理投放服务：双人登记（第一登记人登记、第二登记人复核后生效），
 * 投放生效、药剂库存扣减、处置链进入监测期与采购建议生成在同一事务内完成。
 *
 * <p>批号管理：新投放可冻结批号；被召回投放保留为证据不覆写，
 * 补投一对一引用被召回投放、用量相等、双人签认并再次按真实用量扣减库存。
 */
@Service
public class PlacementService {

    /** 单人已登记，待复核，不生效。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 双人登记完成，已生效。 */
    public static final String STATUS_EFFECTIVE = "EFFECTIVE";

    /** 未召回。 */
    public static final String RECALL_NONE = "NONE";
    /** 批号已召回，待补投；证据保留，不得用于观察解除。 */
    public static final String RECALL_RECALLED = "RECALLED";
    /** 批号已召回且已完成补投；证据保留。 */
    public static final String RECALL_REPLACED = "REPLACED";

    private static final RowMapper<PlacementRow> MAPPER = (rs, rowNum) -> new PlacementRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getLong("pesticide_id"),
            rs.getObject("batch_id", Long.class),
            rs.getBigDecimal("dosage"),
            rs.getString("placement_point"),
            rs.getString("status"),
            rs.getString("recall_status"),
            rs.getObject("replaces_placement_id", Long.class),
            rs.getInt("version"),
            rs.getString("first_registrar"),
            rs.getObject("first_registered_at", LocalDateTime.class),
            rs.getString("second_registrar"),
            rs.getObject("second_registered_at", LocalDateTime.class),
            rs.getObject("placed_at", LocalDateTime.class));

    private final JdbcClient jdbc;
    private final IdempotentExecutor idempotent;
    private final PestCaseService pestCaseService;
    private final PesticideService pesticideService;
    private final BatchService batchService;
    private final Clock clock;

    public PlacementService(JdbcClient jdbc, IdempotentExecutor idempotent,
            PestCaseService pestCaseService, PesticideService pesticideService,
            BatchService batchService, Clock clock) {
        this.jdbc = jdbc;
        this.idempotent = idempotent;
        this.pestCaseService = pestCaseService;
        this.pesticideService = pesticideService;
        this.batchService = batchService;
        this.clock = clock;
    }

    /**
     * 投放登记（第一登记人）：仅登记不生效；过期药剂拒绝登记。
     * 指定批号时校验批号属于该药剂、未召回且未过期，并冻结到投放记录。
     */
    public PlacementDtos.PlacementResponse register(long caseId, PlacementDtos.PlacementRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("pesticideId", request.pesticideId());
        payload.put("batchId", request.batchId());
        payload.put("dosage", request.dosage());
        payload.put("point", request.point());
        payload.put("registrar", request.registrar());
        return idempotent.execute(request.requestId(), payload, PlacementDtos.PlacementResponse.class, () -> {
            CaseRow pestCase = pestCaseService.mustFind(caseId);
            if (!PestCaseService.STATUS_CONFIRMED.equals(pestCase.status())
                    && !PestCaseService.STATUS_TREATED.equals(pestCase.status())) {
                throw ApiException.conflict("当前处置链状态不可登记投放");
            }
            PesticideRow pesticide = pesticideService.mustFind(request.pesticideId());
            if (pesticide.expiryDate().isBefore(LocalDate.now(clock))) {
                throw ApiException.badRequest("药剂已过期，拒绝登记投放");
            }
            if (request.batchId() != null) {
                BatchRow batch = batchService.mustFind(request.batchId());
                if (batch.pesticideId() != request.pesticideId()) {
                    throw ApiException.badRequest("批号不属于该药剂");
                }
                if (!BatchService.STATUS_ACTIVE.equals(batch.status())) {
                    throw ApiException.conflict("批号已召回，禁止该批号新投放");
                }
                if (batch.expiryDate().isBefore(LocalDate.now(clock))) {
                    throw ApiException.badRequest("批号已过期，拒绝登记投放");
                }
            }
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO treatment_placement (case_id, pesticide_id, batch_id, dosage,"
                            + " placement_point, status, recall_status, version,"
                            + " first_registrar, first_registered_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)")
                    .param(caseId).param(request.pesticideId()).param(request.batchId())
                    .param(request.dosage())
                    .param(request.point()).param(STATUS_PENDING).param(RECALL_NONE)
                    .param(request.registrar())
                    .param(LocalDateTime.now(clock))
                    .update(keyHolder, "id");
            return new PlacementDtos.PlacementResponse(keyHolder.getKey().longValue(), STATUS_PENDING);
        });
    }

    /**
     * 投放复核（第二登记人）：须与第一登记人不同且药剂、用量、点位一致；
     * 生效、库存扣减、进入监测期与采购建议在同一事务内完成，任一步失败整体回滚。
     * 冻结批号的投放在事务内重查批号状态，已召回批号不得扣库生效。
     */
    public PlacementDtos.PlacementResponse confirm(long placementId,
            PlacementDtos.PlacementConfirmRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("placementId", placementId);
        payload.put("registrar", request.registrar());
        payload.put("pesticideId", request.pesticideId());
        payload.put("dosage", request.dosage());
        payload.put("point", request.point());
        return idempotent.execute(request.requestId(), payload, PlacementDtos.PlacementResponse.class, () -> {
            PlacementRow placement = findForUpdate(placementId);
            if (!STATUS_PENDING.equals(placement.status())) {
                throw ApiException.conflict("投放记录已生效，请勿重复确认");
            }
            if (placement.firstRegistrar().equals(request.registrar())) {
                throw ApiException.badRequest("单人登记不生效，治理投放须双人分别登记");
            }
            if (placement.pesticideId() != request.pesticideId()
                    || placement.dosage().compareTo(request.dosage()) != 0
                    || !placement.placementPoint().equals(request.point())) {
                throw ApiException.badRequest("双人登记的药剂、用量或点位不一致");
            }
            CaseRow pestCase = pestCaseService.mustFindForUpdate(placement.caseId());
            boolean chainInDisposal = PestCaseService.STATUS_CONFIRMED.equals(pestCase.status())
                    || PestCaseService.STATUS_TREATED.equals(pestCase.status());
            if (!chainInDisposal
                    && !PestCaseService.STATUS_PENDING_REPLACEMENT.equals(pestCase.status())) {
                throw ApiException.conflict("当前处置链状态不可完成投放");
            }
            PesticideRow pesticide = pesticideService.findForUpdate(placement.pesticideId());
            if (pesticide.expiryDate().isBefore(LocalDate.now(clock))) {
                throw ApiException.badRequest("药剂已过期，拒绝登记投放");
            }
            BigDecimal remaining = pesticide.quantity().subtract(placement.dosage());
            if (remaining.signum() < 0) {
                throw ApiException.conflict("药剂库存不足，无法完成投放");
            }
            BatchRow batch = null;
            if (placement.batchId() != null) {
                batch = batchService.findForUpdate(placement.batchId());
                if (!BatchService.STATUS_ACTIVE.equals(batch.status())) {
                    throw ApiException.conflict("批号已召回，禁止该批号投放生效");
                }
                if (batch.expiryDate().isBefore(LocalDate.now(clock))) {
                    throw ApiException.badRequest("批号已过期，拒绝登记投放");
                }
                if (batch.quantity().compareTo(placement.dosage()) < 0) {
                    throw ApiException.conflict("批号库存不足，无法完成投放");
                }
            }
            LocalDateTime now = LocalDateTime.now(clock);
            jdbc.sql("UPDATE pesticide SET quantity = ? WHERE id = ?")
                    .param(remaining).param(pesticide.id())
                    .update();
            if (batch != null) {
                jdbc.sql("UPDATE pesticide_batch SET quantity = ? WHERE id = ?")
                        .param(batch.quantity().subtract(placement.dosage())).param(batch.id())
                        .update();
            }
            jdbc.sql("UPDATE treatment_placement SET status = ?, second_registrar = ?,"
                            + " second_registered_at = ?, placed_at = ? WHERE id = ?")
                    .param(STATUS_EFFECTIVE).param(request.registrar()).param(now).param(now)
                    .param(placementId)
                    .update();
            if (chainInDisposal) {
                jdbc.sql("UPDATE pest_case SET status = ?, treated_at = ? WHERE id = ?")
                        .param(PestCaseService.STATUS_TREATED).param(now).param(placement.caseId())
                        .update();
            }
            createSuggestionIfBelowSafetyLine(pesticide, remaining, placementId, now);
            return new PlacementDtos.PlacementResponse(placementId, STATUS_EFFECTIVE);
        });
    }

    /**
     * 补投：一对一替代本链被召回投放，用量沿用被召回投放（相等），双人签认；
     * 替代批号须同药剂、未召回、未过期且库存足够。旧药剂不退回、原记录不覆写，
     * 库存按真实用量再次扣减；全部补投完成后处置链回到监测期。
     */
    public PlacementDtos.PlacementResponse replace(long recalledPlacementId,
            PlacementDtos.ReplacementRequest request) {
        PlacementRow snapshot = mustFindPlacement(recalledPlacementId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recalledPlacementId", recalledPlacementId);
        payload.put("batchId", request.batchId());
        payload.put("dosage", snapshot.dosage());
        payload.put("firstRegistrar", request.firstRegistrar());
        payload.put("secondRegistrar", request.secondRegistrar());
        payload.put("recalledPlacedAt", snapshot.placedAt());
        payload.put("expectedVersion", request.expectedVersion());
        return idempotent.execute(request.requestId(), payload, PlacementDtos.PlacementResponse.class, () -> {
            PlacementRow recalled = findForUpdate(recalledPlacementId);
            if (!STATUS_EFFECTIVE.equals(recalled.status())
                    || !RECALL_RECALLED.equals(recalled.recallStatus())) {
                throw ApiException.conflict("该投放不是待补投的被召回投放");
            }
            if (recalled.version() != request.expectedVersion()) {
                throw ApiException.conflict("expectedVersion 已过期，请查询最新版本后重试");
            }
            CaseRow pestCase = pestCaseService.mustFindForUpdate(recalled.caseId());
            if (!PestCaseService.STATUS_PENDING_REPLACEMENT.equals(pestCase.status())) {
                throw ApiException.conflict("处置链当前状态不可补投");
            }
            if (request.firstRegistrar().equals(request.secondRegistrar())) {
                throw ApiException.badRequest("补投须双人分别签认，两人不得相同");
            }
            BigDecimal dosage = recalled.dosage();
            PesticideRow pesticide = pesticideService.findForUpdate(recalled.pesticideId());
            if (pesticide.expiryDate().isBefore(LocalDate.now(clock))) {
                throw ApiException.badRequest("药剂已过期，禁止用于补投");
            }
            BatchRow batch = batchService.findForUpdate(request.batchId());
            if (batch.pesticideId() != recalled.pesticideId()) {
                throw ApiException.badRequest("替代批号须与被召回投放同药剂");
            }
            if (!BatchService.STATUS_ACTIVE.equals(batch.status())) {
                throw ApiException.conflict("批号已召回，禁止用于补投");
            }
            if (batch.expiryDate().isBefore(LocalDate.now(clock))) {
                throw ApiException.badRequest("批号已过期，禁止用于补投");
            }
            if (batch.quantity().compareTo(dosage) < 0) {
                throw ApiException.conflict("批号库存不足，无法完成补投");
            }
            BigDecimal remaining = pesticide.quantity().subtract(dosage);
            if (remaining.signum() < 0) {
                throw ApiException.conflict("药剂库存不足，无法完成补投");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            jdbc.sql("UPDATE pesticide SET quantity = ? WHERE id = ?")
                    .param(remaining).param(pesticide.id())
                    .update();
            jdbc.sql("UPDATE pesticide_batch SET quantity = ? WHERE id = ?")
                    .param(batch.quantity().subtract(dosage)).param(batch.id())
                    .update();
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO treatment_placement (case_id, pesticide_id, batch_id, dosage,"
                            + " placement_point, status, recall_status, replaces_placement_id, version,"
                            + " first_registrar, first_registered_at, second_registrar,"
                            + " second_registered_at, placed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)")
                    .param(recalled.caseId()).param(recalled.pesticideId()).param(batch.id())
                    .param(dosage).param(recalled.placementPoint())
                    .param(STATUS_EFFECTIVE).param(RECALL_NONE).param(recalledPlacementId)
                    .param(request.firstRegistrar()).param(now)
                    .param(request.secondRegistrar()).param(now).param(now)
                    .update(keyHolder, "id");
            long newPlacementId = keyHolder.getKey().longValue();
            jdbc.sql("UPDATE treatment_placement SET recall_status = ?, version = version + 1"
                            + " WHERE id = ?")
                    .param(RECALL_REPLACED).param(recalledPlacementId)
                    .update();
            long pending = countPendingReplacements(recalled.caseId());
            if (pending == 0) {
                jdbc.sql("UPDATE pest_case SET status = ?, treated_at = ? WHERE id = ?")
                        .param(PestCaseService.STATUS_TREATED).param(now).param(recalled.caseId())
                        .update();
            } else {
                jdbc.sql("UPDATE pest_case SET treated_at = ? WHERE id = ?")
                        .param(now).param(recalled.caseId())
                        .update();
            }
            createSuggestionIfBelowSafetyLine(pesticide, remaining, newPlacementId, now);
            return new PlacementDtos.PlacementResponse(newPlacementId, STATUS_EFFECTIVE);
        });
    }

    /**
     * 投放详情：含批号、召回状态与补投对应关系。
     */
    public PlacementDtos.PlacementDetailResponse detail(long placementId) {
        PlacementRow p = mustFindPlacement(placementId);
        Long replacedBy = jdbc.sql("SELECT id FROM treatment_placement WHERE replaces_placement_id = ?")
                .param(placementId)
                .query(Long.class)
                .optional()
                .orElse(null);
        return new PlacementDtos.PlacementDetailResponse(p.id(), p.caseId(), p.pesticideId(),
                p.batchId(), p.dosage(), p.placementPoint(), p.status(), p.recallStatus(),
                p.replacesPlacementId(), replacedBy, p.version(), p.firstRegistrar(),
                p.firstRegisteredAt(), p.secondRegistrar(), p.secondRegisteredAt(), p.placedAt());
    }

    /**
     * 按主键查询投放记录，不存在时抛出 404。
     */
    public PlacementRow mustFindPlacement(long placementId) {
        return jdbc.sql("SELECT * FROM treatment_placement WHERE id = ?")
                .param(placementId)
                .query(MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("投放记录不存在: " + placementId));
    }

    /**
     * 统计处置链内待补投（已召回未补投）的投放数量。
     */
    public long countPendingReplacements(long caseId) {
        return jdbc.sql("SELECT COUNT(*) FROM treatment_placement"
                        + " WHERE case_id = ? AND recall_status = ?")
                .param(caseId).param(RECALL_RECALLED)
                .query(Long.class).single();
    }

    private PlacementRow findForUpdate(long placementId) {
        return jdbc.sql("SELECT * FROM treatment_placement WHERE id = ? FOR UPDATE")
                .param(placementId)
                .query(MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("投放记录不存在: " + placementId));
    }

    /**
     * 库存低于安全线时按原口径生成采购建议（补足至安全线两倍），与本次投放勾稽。
     */
    private void createSuggestionIfBelowSafetyLine(PesticideRow pesticide, BigDecimal remaining,
            long placementId, LocalDateTime now) {
        if (remaining.compareTo(pesticide.safetyLine()) < 0) {
            BigDecimal suggested = pesticide.safetyLine()
                    .multiply(BigDecimal.valueOf(2))
                    .subtract(remaining);
            jdbc.sql("INSERT INTO purchase_suggestion (pesticide_id, suggested_quantity, reason,"
                            + " placement_id, created_at) VALUES (?, ?, ?, ?, ?)")
                    .param(pesticide.id()).param(suggested)
                    .param("库存低于安全线，自动生成采购建议")
                    .param(placementId).param(now)
                    .update();
        }
    }
}
