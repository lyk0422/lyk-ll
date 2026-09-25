package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.web.ApiException;

/**
 * 申请资格口径：禁伐区、生态公益林一律拒绝；主伐林龄恰达成熟龄合法、低于则拒绝；
 * 非主伐（抚育）不受成熟龄限制。
 */
class EligibilityTest extends AbstractForestryTest {

    private ApplyRequest req(String requestId, String subplotId, String harvestType) {
        return new ApplyRequest(requestId, "OP-E", subplotId,
                switch (subplotId) {
                    case "SP-001", "SP-003", "SP-005" -> "PINUS_MASSONIANA";
                    default -> "CUNNINGHAMIA";
                },
                harvestType, new BigDecimal("10.00"));
    }

    @Test
    void protectedZoneSubplotAlwaysRejected() {
        assertThatThrownBy(() -> service.apply(req("e-protected", "SP-005", "MAIN")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("PROTECTED_ZONE_FORBIDDEN");
                });
    }

    @Test
    void publicWelfareForestAlwaysRejected() {
        assertThatThrownBy(() -> service.apply(req("e-welfare", "SP-004", "TENDING")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("PUBLIC_WELFARE_FORBIDDEN");
                });
    }

    @Test
    void standAgeExactlyAtMatureAgeIsLegal() {
        // SP-002：林龄 20 恰达成熟龄 20
        var app = service.apply(req("e-mature-exact", "SP-002", "MAIN"));
        assertThat(app.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void standAgeBelowMatureAgeRejectedForMainHarvest() {
        // SP-003：林龄 10 < 成熟龄 25
        assertThatThrownBy(() -> service.apply(req("e-young", "SP-003", "MAIN")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("STAND_AGE_NOT_MATURE");
                });
    }

    @Test
    void tendingHarvestHasNoMatureAgeRequirement() {
        // 幼龄林抚育伐不受成熟龄限制（禁伐/公益林除外）
        var app = service.apply(req("e-tending", "SP-003", "TENDING"));
        assertThat(app.status()).isEqualTo("SUBMITTED");
    }
}
