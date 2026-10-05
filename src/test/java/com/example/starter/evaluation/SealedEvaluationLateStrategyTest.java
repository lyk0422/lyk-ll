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
 * 评估封存（SEALED）为终态：封存后迟到的策略下发不改变已封存评估结果。
 */
class SealedEvaluationLateStrategyTest extends AbstractIntegrationTest {

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
    @DisplayName("封存后迟到的新策略下发成功，但已封存评估的计数、达成率与封存时刻均不变")
    void lateStrategyDoesNotAffectSealedEvaluation() {
        ingest(T0, "24.00", "70");
        ingest(T0.plusSeconds(60), "25.00", "70");
        ingest(T0.plusSeconds(120), "27.00", "70");

        EvaluationDtos.EvaluationResponse sealed = evaluationService.seal(
                new EvaluationDtos.EvaluationRequest(ZONE, DAY));
        assertThat(sealed.status()).isEqualTo("SEALED");
        assertThat(sealed.totalCount()).isEqualTo(3);
        assertThat(sealed.inBandCount()).isEqualTo(2);

        // 封存后迟到的策略：生效时刻落在已封存日期内，下发本身合法（间隔恰 30 分钟）。
        draftStrategy("S2", "26.00", "18.00", "70.00", "0.5", "5");
        StrategyDtos.StrategyResponse dispatched = dispatch("S2", "REQ-D2", T0.plusSeconds(30 * 60));
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");

        // 已封存评估不受迟到策略影响：逐字段与首次封存结果一致。
        EvaluationDtos.EvaluationResponse after = evaluationService.get(ZONE, DAY);
        assertThat(after.id()).isEqualTo(sealed.id());
        assertThat(after.inBandCount()).isEqualTo(2);
        assertThat(after.totalCount()).isEqualTo(3);
        assertThat(after.achievementRate()).isEqualByComparingTo(sealed.achievementRate());
        assertThat(after.sealedAt()).isEqualTo(sealed.sealedAt());

        // 重复封存仍幂等返回首次结果，不因迟到策略重算。
        EvaluationDtos.EvaluationResponse replay = evaluationService.seal(
                new EvaluationDtos.EvaluationRequest(ZONE, DAY));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.inBandCount()).isEqualTo(2);
        assertThat(replay.id()).isEqualTo(sealed.id());
    }
}
