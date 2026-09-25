package com.example.starter.strategy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编制校验：生育期窗口边界（恰在边界合法）、昼夜设定差恰 8℃ 合法。
 */
class StrategyDraftValidationTest extends AbstractIntegrationTest {

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
    }

    @Test
    @DisplayName("温湿度设定点恰在窗口边界（白天20/28、夜间14/20、湿度60/80）合法")
    void boundarySetpointsAreValid() {
        StrategyDtos.StrategyResponse low = draftStrategy("S-MIN", "20.00", "14.00", "60.00", "0.5", "5");
        StrategyDtos.StrategyResponse high = draftStrategy("S-MAX", "28.00", "20.00", "80.00", "0.5", "5");
        assertThat(low.status()).isEqualTo("VALIDATED");
        assertThat(high.status()).isEqualTo("VALIDATED");
    }

    @Test
    @DisplayName("白天温度低于窗口下界 0.01℃ 被拒绝（422）")
    void dayTempJustBelowMinRejected() {
        assertThatThrownBy(() -> draftStrategy("S-BAD-D-LO", "19.99", "15.00", "70.00", "0.5", "5"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("白天温度设定点");
    }

    @Test
    @DisplayName("白天温度高于窗口上界 0.01℃ 被拒绝")
    void dayTempJustAboveMaxRejected() {
        assertThatThrownBy(() -> draftStrategy("S-BAD-D-HI", "28.01", "20.00", "70.00", "0.5", "5"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("夜间温度与湿度超出窗口被拒绝")
    void nightAndHumidityOutsideWindowRejected() {
        assertThatThrownBy(() -> draftStrategy("S-BAD-N", "24.00", "13.99", "70.00", "0.5", "5"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("夜间温度设定点");
        assertThatThrownBy(() -> draftStrategy("S-BAD-H", "24.00", "16.00", "80.01", "0.5", "5"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("湿度设定点");
    }

    @Test
    @DisplayName("昼夜设定差恰为 8℃ 合法，超过 8℃ 被拒绝")
    void dayNightGapExactlyEightIsValid() {
        assertThat(draftStrategy("S-GAP-8", "28.00", "20.00", "70.00", "0.5", "5").status())
                .isEqualTo("VALIDATED");
        assertThatThrownBy(() -> draftStrategy("S-GAP-9", "28.00", "19.99", "70.00", "0.5", "5"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("超过上限 8℃");
    }

    @Test
    @DisplayName("引用不存在的生育期返回 404")
    void unknownStageReturnsNotFound() {
        assertThatThrownBy(() -> strategyService.draft(new StrategyDtos.DraftRequest(
                "S-X", ZONE, CROP, "UNKNOWN", bd("24"), bd("18"), bd("70"), bd("0.5"), bd("5"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("校验失败不落库，同编码可重新编制")
    void rejectedDraftIsNotPersisted() {
        assertThatThrownBy(() -> draftStrategy("S-RETRY", "28.01", "20.00", "70.00", "0.5", "5"))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(get("S-RETRY")).isNull();
    }

    private com.example.starter.api.dto.StrategyDtos.StrategyResponse get(String code) {
        try {
            return strategyService.get(code);
        } catch (NotFoundException e) {
            return null;
        }
    }
}
