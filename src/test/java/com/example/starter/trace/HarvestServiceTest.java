package com.example.starter.trace;

import com.example.starter.trace.api.TraceDtos.ContributionItem;
import com.example.starter.trace.api.TraceDtos.HarvestBatchResponse;
import com.example.starter.trace.api.TraceDtos.RegisterHarvestBatchRequest;
import com.example.starter.trace.service.BusinessException;
import com.example.starter.trace.service.HarvestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采收批登记：贡献比例勾稽与写幂等。
 */
class HarvestServiceTest extends BaseTraceTest {

    @Autowired
    private HarvestService harvestService;

    private Long apiaryId(String code) {
        return jdbc.queryForObject("SELECT id FROM apiary WHERE code = ?", Long.class, code);
    }

    private RegisterHarvestBatchRequest request(String requestId, String batchNo, List<ContributionItem> items) {
        return new RegisterHarvestBatchRequest(requestId, batchNo, "2026-洋槐", items);
    }

    @Test
    void 登记成功_两个蜂场合计恰100() {
        RegisterHarvestBatchRequest request = request("req-reg-1", "HB-001", List.of(
                new ContributionItem(apiaryId("AP-001"), new BigDecimal("60.00")),
                new ContributionItem(apiaryId("AP-002"), new BigDecimal("40.00"))));

        HarvestBatchResponse response = harvestService.register(request);

        assertThat(response.id()).isNotNull();
        assertThat(response.status()).isEqualTo("REGISTERED");
        assertThat(response.contributions()).hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_contribution WHERE batch_id = ?",
                Integer.class, response.id())).isEqualTo(2);
    }

    @Test
    void 比例合计不等于100_拒绝() {
        RegisterHarvestBatchRequest request = request("req-reg-2", "HB-002", List.of(
                new ContributionItem(apiaryId("AP-001"), new BigDecimal("60.00")),
                new ContributionItem(apiaryId("AP-002"), new BigDecimal("39.99"))));

        assertThatThrownBy(() -> harvestService.register(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM harvest_batch", Integer.class)).isZero();
    }

    @Test
    void 比例超过两位小数_拒绝() {
        RegisterHarvestBatchRequest request = request("req-reg-3", "HB-003", List.of(
                new ContributionItem(apiaryId("AP-001"), new BigDecimal("33.333")),
                new ContributionItem(apiaryId("AP-002"), new BigDecimal("66.667"))));

        assertThatThrownBy(() -> harvestService.register(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void 同键同参_返回首次结果且不重复写入() {
        RegisterHarvestBatchRequest request = request("req-reg-4", "HB-004", List.of(
                new ContributionItem(apiaryId("AP-001"), new BigDecimal("100.00"))));

        HarvestBatchResponse first = harvestService.register(request);
        HarvestBatchResponse replay = harvestService.register(request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM harvest_batch", Integer.class)).isEqualTo(1);
    }

    @Test
    void 同键异参_拒绝409() {
        harvestService.register(request("req-reg-5", "HB-005", List.of(
                new ContributionItem(apiaryId("AP-001"), new BigDecimal("100.00")))));
        RegisterHarvestBatchRequest tampered = request("req-reg-5", "HB-005", List.of(
                new ContributionItem(apiaryId("AP-002"), new BigDecimal("100.00"))));

        assertThatThrownBy(() -> harvestService.register(tampered))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 批号重复_拒绝409() {
        harvestService.register(request("req-reg-6", "HB-006", List.of(
                new ContributionItem(apiaryId("AP-001"), new BigDecimal("100.00")))));

        assertThatThrownBy(() -> harvestService.register(request("req-reg-7", "HB-006", List.of(
                new ContributionItem(apiaryId("AP-001"), new BigDecimal("100.00"))))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }
}
