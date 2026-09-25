package com.example.starter.bonded;

import com.example.starter.bonded.dto.ApplyTransferRequest;
import com.example.starter.bonded.dto.ItemRequest;
import com.example.starter.bonded.dto.TransferView;
import com.example.starter.bonded.dto.VerifyItemRequest;
import com.example.starter.bonded.dto.VerifyRequest;
import com.example.starter.bonded.service.TransferService;
import com.example.starter.bonded.web.ApiBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 48 小时核销时限与超时异常测试（可控时钟驱动）。
 */
class TimeoutAbnormalTest extends BaseIntegrationTest {

    @Autowired
    private TransferService service;

    private void applyEndorseDepart(String requestId, String enterprise, long qty) {
        service.apply(new ApplyTransferRequest(requestId, enterprise, "WH-A", "WH-B",
                List.of(new ItemRequest("HS-1001", qty, new BigDecimal("10")))));
        service.endorse(requestId);
        service.depart(requestId);
    }

    @Test
    @DisplayName("48 小时边界：截止前一刻可核销；恰满 48 小时未核满自动转异常")
    void boundary48Hours() {
        applyEndorseDepart("REQ-T1", "ENT-001", 100);
        // 47:59:59 仍可核销
        clock.set(T0.plusSeconds(48 * 3600 - 1));
        service.verify("REQ-T1", new VerifyRequest("B-1", List.of(new VerifyItemRequest("HS-1001", 10L))));
        assertThat(statusOf("REQ-T1")).isEqualTo("IN_TRANSIT");

        // 恰满 48 小时：惰性检查触发异常
        clock.set(T0.plusSeconds(48 * 3600));
        assertThatThrownBy(() -> service.verify("REQ-T1", new VerifyRequest("B-2",
                List.of(new VerifyItemRequest("HS-1001", 10L)))))
                .isInstanceOf(ApiBusinessException.class)
                .hasMessageContaining("48");
        assertThat(statusOf("REQ-T1")).isEqualTo("ABNORMAL");
        assertThat(enterpriseFrozen("ENT-001")).isTrue();
    }

    @Test
    @DisplayName("恰满 48 小时前恰好核满：正常结案，不转异常")
    void fullyVerifiedBeforeDeadline() {
        applyEndorseDepart("REQ-T2", "ENT-001", 100);
        clock.set(T0.plusSeconds(48 * 3600 - 1));
        service.verify("REQ-T2", new VerifyRequest("B-1", List.of(new VerifyItemRequest("HS-1001", 100L))));
        assertThat(statusOf("REQ-T2")).isEqualTo("CLOSED");
        clock.set(T0.plusSeconds(48 * 3600));
        assertThat(service.sweepExpired()).isEmpty();
        assertThat(statusOf("REQ-T2")).isEqualTo("CLOSED");
        assertThat(enterpriseFrozen("ENT-001")).isFalse();
    }

    @Test
    @DisplayName("超时异常：企业全部在途单一并冻结，新申请拒绝，定时扫描与惰性检查结果一致")
    void abnormalFreezesEnterprise() {
        applyEndorseDepart("REQ-T3", "ENT-001", 100);
        applyEndorseDepart("REQ-T4", "ENT-001", 50);
        // 另一企业的在途单不受影响
        applyEndorseDepart("REQ-T5", "ENT-002", 10);

        clock.set(T0.plusSeconds(48 * 3600));
        List<String> marked = service.sweepExpired();
        // REQ-T4 在处理 REQ-T3 时被连带冻结，不再重复计入；ENT-002 的 REQ-T5 独立触发
        assertThat(marked).containsExactlyInAnyOrder("REQ-T3", "REQ-T5");

        assertThat(statusOf("REQ-T3")).isEqualTo("ABNORMAL");
        assertThat(statusOf("REQ-T4")).isEqualTo("ABNORMAL");
        assertThat(statusOf("REQ-T5")).isEqualTo("ABNORMAL");
        assertThat(enterpriseFrozen("ENT-001")).isTrue();
        assertThat(enterpriseFrozen("ENT-002")).isTrue();

        // 冻结企业新申请拒绝
        assertThatThrownBy(() -> service.apply(new ApplyTransferRequest("REQ-T6", "ENT-001",
                "WH-A", "WH-B", List.of(new ItemRequest("HS-1001", 1L, new BigDecimal("1"))))))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        // 异常单核销拒绝
        assertThatThrownBy(() -> service.verify("REQ-T3", new VerifyRequest("B-9",
                List.of(new VerifyItemRequest("HS-1001", 1L)))))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("异常解除须海关复核确认：解除后恢复在途并顺延时限，企业解冻，可继续核销")
    void resolveAbnormalByReview() {
        applyEndorseDepart("REQ-T7", "ENT-001", 100);
        clock.set(T0.plusSeconds(48 * 3600));
        service.sweepExpired();
        assertThat(statusOf("REQ-T7")).isEqualTo("ABNORMAL");

        // 非异常单不能复核解除
        applyEndorseDepart("REQ-T8", "ENT-002", 10);
        assertThatThrownBy(() -> service.resolveAbnormal("REQ-T8", "海关复核员甲"))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        // 复核确认解除
        Instant resolveTime = T0.plusSeconds(48 * 3600);
        TransferView view = service.resolveAbnormal("REQ-T7", "海关复核员甲");
        assertThat(view.status()).isEqualTo("IN_TRANSIT");
        assertThat(view.deadline()).isEqualTo(resolveTime.plusSeconds(48 * 3600));
        assertThat(enterpriseFrozen("ENT-001")).isFalse();

        // 解除后可在新时限内核销结案
        service.verify("REQ-T7", new VerifyRequest("B-1", List.of(new VerifyItemRequest("HS-1001", 100L))));
        assertThat(statusOf("REQ-T7")).isEqualTo("CLOSED");
        // 留痕包含异常与解除
        assertThat(service.get("REQ-T7").history())
                .extracting(TransferView.HistoryView::toStatus)
                .containsSubsequence("ABNORMAL", "IN_TRANSIT");
    }

    @Test
    @DisplayName("查询触发惰性超时：未运行扫描时，查询也会把恰满 48 小时的单标记为异常")
    void lazyTimeoutOnGet() {
        applyEndorseDepart("REQ-T9", "ENT-001", 100);
        clock.set(T0.plusSeconds(48 * 3600 + 60));
        TransferView view = service.get("REQ-T9");
        assertThat(view.status()).isEqualTo("ABNORMAL");
        assertThat(view.enterpriseFrozen()).isTrue();
    }
}
