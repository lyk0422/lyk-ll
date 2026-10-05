package com.example.starter.evaluation;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.EvaluationDtos;
import com.example.starter.api.dto.StrategyDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评估封存为终态：封存后迟到下发的策略不影响已封存评估，重复封存仍返回首次结果。
 */
class LateStrategyAfterSealTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 12);
    private static final Instant T0 = DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");
        dispatch("S1", "REQ-D1", T0);
    }

    @Test
    @DisplayName("封存后迟到的策略正常下发，但已封存评估的达成率与能耗保持首次结果不变")
    void lateStrategyDoesNotAffectSealedEvaluation() {
        ingest(T0.plusSeconds(60), "24.00", "70");
        ingest(T0.plusSeconds(120), "24.50", "70");

        EvaluationDtos.EvaluationResponse sealed = evaluationService.seal(
                new EvaluationDtos.EvaluationRequest(ZONE, DAY));
        assertThat(sealed.status()).isEqualTo("SEALED");
        assertThat(sealed.totalCount()).isEqualTo(2);
        assertThat(sealed.achievementRate()).isEqualByComparingTo("1.0000");

        // 封存后迟到的策略：下发链路（含预演与仲裁）不受影响，正常生效。
        draftStrategy("S2", "25.00", "17.00", "70.00", "1.0", "5.0");
        StrategyDtos.StrategyResponse dispatched = dispatch("S2", "REQ-LATE", T0.plusSeconds(30 * 60));
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");
        assertThat(strategyService.rehearsals("S2")).hasSize(1);

        // 已封存评估保持首次结果：迟到策略不改变达成率、计数与封存时刻。
        EvaluationDtos.EvaluationResponse loaded = evaluationService.get(ZONE, DAY);
        assertThat(loaded.id()).isEqualTo(sealed.id());
        assertThat(loaded.totalCount()).isEqualTo(2);
        assertThat(loaded.inBandCount()).isEqualTo(2);
        assertThat(loaded.achievementRate()).isEqualByComparingTo("1.0000");
        assertThat(loaded.sealedAt()).isEqualTo(sealed.sealedAt());

        // 重复封存仍幂等返回首次结果。
        EvaluationDtos.EvaluationResponse replay = evaluationService.seal(
                new EvaluationDtos.EvaluationRequest(ZONE, DAY));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(sealed.id());
        assertThat(replay.achievementRate()).isEqualByComparingTo("1.0000");
    }
}
