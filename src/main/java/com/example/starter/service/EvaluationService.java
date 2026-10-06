package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;

import com.example.starter.domain.EvaluationStatus;
import com.example.starter.domain.Rows.EvaluationRow;
import com.example.starter.error.BusinessConflictException;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.web.dto.ApiDtos.CompleteEvaluationRequest;
import com.example.starter.web.dto.ApiDtos.EvaluationResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 根因评估：评估期间叶片维持限功率，完成评估后撤销评估来源的限功率。
 */
@Service
public class EvaluationService {

    private final JdbcClient jdbc;
    private final DbSupport db;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public EvaluationService(JdbcClient jdbc, DbSupport db,
                             IdempotencyService idempotencyService, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 完成根因评估：同事务更新评估状态并撤销评估来源限功率。
     */
    public EvaluationResponse complete(long evaluationId, CompleteEvaluationRequest req) {
        String fingerprint = IdempotencyService.fingerprint(
                "evaluation", req.requestId(), String.valueOf(evaluationId), req.conclusion());
        return idempotencyService.execute("evaluation", req.requestId(), fingerprint,
                () -> doComplete(evaluationId, req), EvaluationResponse.class);
    }

    private EvaluationResponse doComplete(long evaluationId, CompleteEvaluationRequest req) {
        EvaluationRow evaluation = db.requireEvaluation(evaluationId);
        if (evaluation.status() == EvaluationStatus.COMPLETED) {
            throw new BusinessConflictException("根因评估已完成: " + evaluationId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.sql("""
                UPDATE root_cause_evaluation SET status = 'COMPLETED', completed_at = ?, conclusion = ? WHERE id = ?
                """)
                .params(now, req.conclusion(), evaluationId)
                .update();
        jdbc.sql("""
                UPDATE curtailment SET lifted_at = ?
                WHERE source_evaluation_id = ? AND lifted_at IS NULL
                """)
                .params(now, evaluationId)
                .update();
        return new EvaluationResponse(evaluationId, evaluation.bladeId(), evaluation.defectType(),
                evaluation.triggerDefectId(), EvaluationStatus.COMPLETED.name(),
                evaluation.triggeredAt(), now, req.conclusion());
    }
}
