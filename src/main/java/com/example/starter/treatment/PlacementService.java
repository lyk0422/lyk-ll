package com.example.starter.treatment;

import com.example.starter.common.ApiException;
import com.example.starter.common.IdempotentExecutor;
import com.example.starter.pest.CaseRow;
import com.example.starter.pest.PestCaseService;
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
 */
@Service
public class PlacementService {

    /** 单人已登记，待复核，不生效。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 双人登记完成，已生效。 */
    public static final String STATUS_EFFECTIVE = "EFFECTIVE";

    private static final RowMapper<PlacementRow> MAPPER = (rs, rowNum) -> new PlacementRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getLong("pesticide_id"),
            rs.getBigDecimal("dosage"),
            rs.getString("placement_point"),
            rs.getString("status"),
            rs.getString("first_registrar"),
            rs.getObject("first_registered_at", LocalDateTime.class),
            rs.getString("second_registrar"),
            rs.getObject("second_registered_at", LocalDateTime.class),
            rs.getObject("placed_at", LocalDateTime.class));

    private final JdbcClient jdbc;
    private final IdempotentExecutor idempotent;
    private final PestCaseService pestCaseService;
    private final PesticideService pesticideService;
    private final Clock clock;

    public PlacementService(JdbcClient jdbc, IdempotentExecutor idempotent,
            PestCaseService pestCaseService, PesticideService pesticideService, Clock clock) {
        this.jdbc = jdbc;
        this.idempotent = idempotent;
        this.pestCaseService = pestCaseService;
        this.pesticideService = pesticideService;
        this.clock = clock;
    }

    /**
     * 投放登记（第一登记人）：仅登记不生效；过期药剂拒绝登记。
     */
    public PlacementDtos.PlacementResponse register(long caseId, PlacementDtos.PlacementRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("pesticideId", request.pesticideId());
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
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO treatment_placement (case_id, pesticide_id, dosage, placement_point,"
                            + " status, first_registrar, first_registered_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .param(caseId).param(request.pesticideId()).param(request.dosage())
                    .param(request.point()).param(STATUS_PENDING).param(request.registrar())
                    .param(LocalDateTime.now(clock))
                    .update(keyHolder, "id");
            return new PlacementDtos.PlacementResponse(keyHolder.getKey().longValue(), STATUS_PENDING);
        });
    }

    /**
     * 投放复核（第二登记人）：须与第一登记人不同且药剂、用量、点位一致；
     * 生效、库存扣减、进入监测期与采购建议在同一事务内完成，任一步失败整体回滚。
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
            PesticideRow pesticide = pesticideService.findForUpdate(placement.pesticideId());
            if (pesticide.expiryDate().isBefore(LocalDate.now(clock))) {
                throw ApiException.badRequest("药剂已过期，拒绝登记投放");
            }
            BigDecimal remaining = pesticide.quantity().subtract(placement.dosage());
            if (remaining.signum() < 0) {
                throw ApiException.conflict("药剂库存不足，无法完成投放");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            jdbc.sql("UPDATE pesticide SET quantity = ? WHERE id = ?")
                    .param(remaining).param(pesticide.id())
                    .update();
            jdbc.sql("UPDATE treatment_placement SET status = ?, second_registrar = ?,"
                            + " second_registered_at = ?, placed_at = ? WHERE id = ?")
                    .param(STATUS_EFFECTIVE).param(request.registrar()).param(now).param(now)
                    .param(placementId)
                    .update();
            jdbc.sql("UPDATE pest_case SET status = ?, treated_at = ? WHERE id = ?")
                    .param(PestCaseService.STATUS_TREATED).param(now).param(placement.caseId())
                    .update();
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
            return new PlacementDtos.PlacementResponse(placementId, STATUS_EFFECTIVE);
        });
    }

    private PlacementRow findForUpdate(long placementId) {
        return jdbc.sql("SELECT * FROM treatment_placement WHERE id = ? FOR UPDATE")
                .param(placementId)
                .query(MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("投放记录不存在: " + placementId));
    }
}
