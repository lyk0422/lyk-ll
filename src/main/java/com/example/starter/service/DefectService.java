package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;

import com.example.starter.domain.CurtailmentReason;
import com.example.starter.domain.DefectLevel;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.web.dto.ApiDtos.DefectResponse;
import com.example.starter.web.dto.ApiDtos.InspectionResponse;
import com.example.starter.web.dto.ApiDtos.RegisterDefectRequest;
import com.example.starter.web.dto.ApiDtos.RegisterInspectionRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * 巡检与缺陷登记：缺陷按合成分级表定级；结构级缺陷立即限功率；
 * 同类缺陷 90 日内恰第 2 次复发触发根因评估并维持限功率。
 */
@Service
public class DefectService {

    /** 复发统计窗口：90 日。 */
    static final int RECURRENCE_WINDOW_DAYS = 90;

    private final JdbcClient jdbc;
    private final DbSupport db;
    private final GradingService gradingService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public DefectService(JdbcClient jdbc, DbSupport db, GradingService gradingService,
                         IdempotencyService idempotencyService, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.gradingService = gradingService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 登记巡检记录（按叶片与位置）。
     */
    public InspectionResponse registerInspection(RegisterInspectionRequest req) {
        String fingerprint = IdempotencyService.fingerprint(
                "inspection", req.requestId(), String.valueOf(req.bladeId()),
                req.position(), req.inspector());
        return idempotencyService.execute("inspection", req.requestId(), fingerprint,
                () -> doRegisterInspection(req), InspectionResponse.class);
    }

    /**
     * 登记缺陷：定级、结构级立即限功率、复发恰第 2 次触发根因评估。
     */
    public DefectResponse registerDefect(RegisterDefectRequest req) {
        String fingerprint = IdempotencyService.fingerprint(
                "defect", req.requestId(), String.valueOf(req.inspectionId()),
                String.valueOf(req.bladeId()), req.position(), req.defectType(),
                String.valueOf(req.lengthMm()), String.valueOf(req.depthRatio()));
        return idempotencyService.execute("defect", req.requestId(), fingerprint,
                () -> doRegisterDefect(req), DefectResponse.class);
    }

    private InspectionResponse doRegisterInspection(RegisterInspectionRequest req) {
        db.requireBlade(req.bladeId());
        LocalDateTime now = LocalDateTime.now(clock);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO inspection_record (request_id, blade_id, position, inspector, inspected_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .params(req.requestId(), req.bladeId(), req.position(), req.inspector(), now, now)
                .update(keys);
        long id = keys.getKey().longValue();
        return new InspectionResponse(id, req.bladeId(), req.position(), req.inspector(), now);
    }

    private DefectResponse doRegisterDefect(RegisterDefectRequest req) {
        db.requireBlade(req.bladeId());
        if (req.inspectionId() != null) {
            requireInspection(req.inspectionId());
        }
        DefectLevel level = gradingService.grade(req.defectType(), req.depthRatio());
        LocalDateTime now = LocalDateTime.now(clock);

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO defect (request_id, inspection_id, blade_id, position, defect_type,
                                            length_mm, depth_ratio, level, status, registered_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                        """)
                .params(req.requestId(), req.inspectionId(), req.bladeId(), req.position(), req.defectType(),
                        req.lengthMm(), req.depthRatio(), level.name(), now)
                .update(keys);
        long defectId = keys.getKey().longValue();

        Long curtailmentId = null;
        if (level == DefectLevel.STRUCTURAL) {
            curtailmentId = insertCurtailment(req.bladeId(), CurtailmentReason.STRUCTURAL_DEFECT,
                    defectId, null, now);
        }

        Long evaluationId = null;
        long recurrenceCount = countRecentSameType(req.bladeId(), req.defectType(), now);
        if (recurrenceCount == 2) {
            evaluationId = insertEvaluation(req.bladeId(), req.defectType(), defectId, now);
            insertCurtailment(req.bladeId(), CurtailmentReason.ROOT_CAUSE_EVAL,
                    null, evaluationId, now);
        }

        return new DefectResponse(defectId, req.bladeId(), req.position(), req.defectType(),
                level.name(), "OPEN", now, curtailmentId, evaluationId);
    }

    private void requireInspection(long inspectionId) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM inspection_record WHERE id = ?")
                .param(inspectionId)
                .query(Long.class)
                .single() > 0;
        if (!exists) {
            throw new com.example.starter.error.NotFoundException("巡检记录不存在: " + inspectionId);
        }
    }

    private long countRecentSameType(long bladeId, String defectType, LocalDateTime now) {
        LocalDateTime windowStart = now.minusDays(RECURRENCE_WINDOW_DAYS);
        return jdbc.sql("""
                        SELECT COUNT(*) FROM defect
                        WHERE blade_id = ? AND defect_type = ? AND registered_at >= ?
                        """)
                .params(bladeId, defectType, windowStart)
                .query(Long.class)
                .single();
    }

    private long insertCurtailment(long bladeId, CurtailmentReason reason,
                                   Long sourceDefectId, Long sourceEvaluationId, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO curtailment (blade_id, reason, source_defect_id, source_evaluation_id, started_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(bladeId, reason.name(), sourceDefectId, sourceEvaluationId, now)
                .update(keys);
        return keys.getKey().longValue();
    }

    private long insertEvaluation(long bladeId, String defectType, long triggerDefectId, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO root_cause_evaluation (blade_id, defect_type, trigger_defect_id, status, triggered_at)
                        VALUES (?, ?, ?, 'IN_PROGRESS', ?)
                        """)
                .params(bladeId, defectType, triggerDefectId, now)
                .update(keys);
        return keys.getKey().longValue();
    }
}
