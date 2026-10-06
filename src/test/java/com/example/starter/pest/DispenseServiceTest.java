package com.example.starter.pest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.example.starter.common.ApiException;
import com.example.starter.pest.api.Requests.DispenseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * 治理投放：双人登记、过期拒绝、库存扣减与投放记录同事务、低库存采购建议勾稽。
 */
class DispenseServiceTest extends BaseIntegrationTest {

    @Test
    void 单人登记不生效且库存不扣减() {
        long chainId = reportAndConfirm("A", "d1").chainId();

        assertThatThrownBy(() -> treatmentService.dispense(chainId,
                new DispenseRequest("d1-x", 1L, new BigDecimal("10"), "墙角", "张三", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThat(stockOf(1L)).isEqualByComparingTo("100.00");
        assertThat(count("dispense_record")).isZero();
    }

    @Test
    void 两名登记人不得为同一人() {
        long chainId = reportAndConfirm("A", "d2").chainId();

        assertThatThrownBy(() -> treatmentService.dispense(chainId,
                new DispenseRequest("d2-x", 1L, new BigDecimal("10"), "墙角", "张三", "张三")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(count("dispense_record")).isZero();
    }

    @Test
    void 过期药剂拒绝登记投放() {
        long chainId = reportAndConfirm("A", "d3").chainId();

        assertThatThrownBy(() -> treatmentService.dispense(chainId,
                new DispenseRequest("d3-x", 2L, new BigDecimal("5"), "墙角", "张三", "李四")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThat(stockOf(2L)).isEqualByComparingTo("50.00");
        assertThat(count("dispense_record")).isZero();
    }

    @Test
    void 库存不足拒绝投放且库存不变() {
        long chainId = reportAndConfirm("A", "d4").chainId();

        assertThatThrownBy(() -> treatmentService.dispense(chainId,
                new DispenseRequest("d4-x", 1L, new BigDecimal("200"), "墙角", "张三", "李四")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThat(stockOf(1L)).isEqualByComparingTo("100.00");
    }

    @Test
    void 双人登记成功_扣减库存并进入监测期() {
        long chainId = reportAndConfirm("A", "d5").chainId();

        var result = treatmentService.dispense(chainId,
                new DispenseRequest("d5-x", 1L, new BigDecimal("30"), "东北角书架", "张三", "李四"));

        assertThat(result.remainingQuantity()).isEqualByComparingTo("70.00");
        assertThat(result.purchaseSuggestionId()).isNull();
        assertThat(stockOf(1L)).isEqualByComparingTo("70.00");
        assertThat(count("dispense_record")).isEqualTo(1);
        assertThat(treatmentService.getChain(chainId).status()).isEqualTo("MONITORING");
    }

    @Test
    void 库存低于安全线自动生成采购建议并与最近投放勾稽() {
        long chainId = reportAndConfirm("A", "d6").chainId();

        var result = treatmentService.dispense(chainId,
                new DispenseRequest("d6-x", 3L, new BigDecimal("5"), "门口", "张三", "李四"));

        assertThat(result.remainingQuantity()).isEqualByComparingTo("7.00");
        assertThat(result.purchaseSuggestionId()).isNotNull();

        record SuggestionRow(long pesticideId, BigDecimal suggested, long relatedDispenseId) {
        }
        var suggestion = jdbc.sql("SELECT * FROM purchase_suggestion WHERE id = :id")
                .param("id", result.purchaseSuggestionId())
                .query((rs, i) -> new SuggestionRow(
                        rs.getLong("pesticide_id"),
                        rs.getBigDecimal("suggested_quantity"),
                        rs.getLong("related_dispense_id")))
                .single();
        assertThat(suggestion.pesticideId()).isEqualTo(3L);
        // 补足至安全线两倍：10*2 - 7 = 13
        assertThat(suggestion.suggested()).isEqualByComparingTo("13.00");
        assertThat(suggestion.relatedDispenseId()).isEqualTo(result.dispenseId());
    }

    @Test
    void 投放记录失败时库存扣减与状态变更一并回滚() {
        long chainId = reportAndConfirm("A", "d7").chainId();
        String tooLongPoints = "点".repeat(300);

        assertThatThrownBy(() -> treatmentService.dispense(chainId,
                new DispenseRequest("d7-x", 1L, new BigDecimal("10"), tooLongPoints, "张三", "李四")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(stockOf(1L)).isEqualByComparingTo("100.00");
        assertThat(count("dispense_record")).isZero();
        assertThat(treatmentService.getChain(chainId).status()).isEqualTo("TREATING");
    }

    private BigDecimal stockOf(long pesticideId) {
        return jdbc.sql("SELECT quantity FROM pesticide WHERE id = :id")
                .param("id", pesticideId)
                .query(BigDecimal.class)
                .single();
    }
}
