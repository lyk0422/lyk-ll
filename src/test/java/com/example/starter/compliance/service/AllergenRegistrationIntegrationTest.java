package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 配方版本过敏原登记 H2 测试：版本从 1 开始、expectedVersion 乐观锁、
 * 目录校验、404/400 语义与 requestId 幂等。
 */
class AllergenRegistrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ComplianceService service;

    @Test
    void createStartsAtVersionOneAndUpdateIncrements() {
        long formulaId = submitFormula("P-ALG-1");

        Results.AllergenView created =
                service.upsertAllergens(Fixtures.allergens(formulaId, List.of("MILK", "EGG"), null))
                        .body();
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.allergens()).containsExactly("EGG", "MILK");

        Results.AllergenView updated = service.upsertAllergens(
                Fixtures.allergens(formulaId, List.of("MILK", "PEANUT"), 1)).body();
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.allergens()).containsExactly("MILK", "PEANUT");

        Results.AllergenView queried = service.getAllergens(formulaId);
        assertThat(queried.version()).isEqualTo(2);
        assertThat(queried.allergens()).containsExactly("MILK", "PEANUT");
    }

    @Test
    void staleExpectedVersionConflicts() {
        long formulaId = submitFormula("P-ALG-2");
        service.upsertAllergens(Fixtures.allergens(formulaId, List.of("MILK"), null));

        // 版本不一致 409；登记保持原版本与原集合。
        assertThatThrownBy(() -> service.upsertAllergens(
                Fixtures.allergens(formulaId, List.of("EGG"), 3)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("VERSION_CONFLICT");
                });
        assertThat(service.getAllergens(formulaId).version()).isEqualTo(1);
        assertThat(service.getAllergens(formulaId).allergens()).containsExactly("MILK");
    }

    @Test
    void updateWithoutRegistrationOrCreateOverExistingConflicts() {
        long formulaId = submitFormula("P-ALG-3");
        // 未登记时携带 expectedVersion 变更：409。
        assertThatThrownBy(() -> service.upsertAllergens(
                Fixtures.allergens(formulaId, List.of("EGG"), 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("VERSION_CONFLICT"));

        service.upsertAllergens(Fixtures.allergens(formulaId, List.of("EGG"), null));
        // 已登记时按首次登记（expectedVersion=null）重复创建：409。
        assertThatThrownBy(() -> service.upsertAllergens(
                Fixtures.allergens(formulaId, List.of("EGG"), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("VERSION_CONFLICT"));
        assertThat(service.getAllergens(formulaId).version()).isEqualTo(1);
    }

    @Test
    void invalidAllergenParamsRejected() {
        long formulaId = submitFormula("P-ALG-4");
        // 目录外过敏原 400。
        assertThatThrownBy(() -> service.upsertAllergens(
                Fixtures.allergens(formulaId, List.of("UNKNOWN"), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getCode()).isEqualTo("ALLERGEN_NOT_FOUND");
                });
        // 重复编码 400。
        assertThatThrownBy(() -> service.upsertAllergens(
                Fixtures.allergens(formulaId, List.of("MILK", "MILK"), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        // 集合缺失 400。
        assertThatThrownBy(() -> service.upsertAllergens(
                Fixtures.allergens(formulaId, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        // 失败不占键：以上失败均未写入登记。
        assertThatThrownBy(() -> service.getAllergens(formulaId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("ALLERGEN_REGISTRATION_NOT_FOUND"));
    }

    @Test
    void missingFormulaReturns404() {
        assertThatThrownBy(() -> service.upsertAllergens(
                Fixtures.allergens(999999L, List.of("MILK"), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getCode()).isEqualTo("FORMULA_NOT_FOUND");
                });
    }

    @Test
    void idempotentReplayAndParamConflict() {
        long formulaId = submitFormula("P-ALG-5");
        String requestId = Fixtures.requestId();

        Results.AllergenView first = service.upsertAllergens(
                Fixtures.allergens(requestId, formulaId, List.of("MILK"), null)).body();
        Results.AllergenView replay = service.upsertAllergens(
                Fixtures.allergens(requestId, formulaId, List.of("MILK"), null)).body();
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        // 重放首次冻结结果：版本不重复递增。
        assertThat(replay.version()).isEqualTo(1);
        assertThat(service.getAllergens(formulaId).version()).isEqualTo(1);

        // 同键异参 409。
        assertThatThrownBy(() -> service.upsertAllergens(
                Fixtures.allergens(requestId, formulaId, List.of("EGG"), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("REQUEST_PARAM_CONFLICT");
                });
    }

    private long submitFormula(String productCode) {
        return service.submitFormula(Fixtures.formula(productCode, 1,
                List.of(Fixtures.additive("E202", "0.2")), List.of())).body().formulaId();
    }
}
