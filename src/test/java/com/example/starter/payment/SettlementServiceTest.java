package com.example.starter.payment;

import com.example.starter.payment.service.PaymentService;
import com.example.starter.payment.service.PaymentService.PostCommand;
import com.example.starter.payment.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 封账：清算金额计算（交易额-手续费）、封账幂等（终态，不重新计算）。
 */
class SettlementServiceTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SettlementService settlementService;

    private void post(String requestId, String serialNo, String merchantNo, long amount, String txnTime) {
        paymentService.post(new PaymentService.PostCommand(requestId, serialNo, merchantNo,
                amount, Instant.parse(txnTime)));
    }

    @Test
    void closeComputesPerMerchantBills() {
        // M0001 费率 30bp：10000 -> fee 30；M0002 费率 50bp：20000 -> fee 100
        post("r1", "S1", "M0001", 10000, "2026-09-24T10:00:00Z");
        post("r2", "S2", "M0001", 10000, "2026-09-24T11:00:00Z");
        post("r3", "S3", "M0002", 20000, "2026-09-24T12:00:00Z");

        List<SettlementBill> bills = settlementService.close(LocalDate.of(2026, 9, 24));
        assertThat(bills).hasSize(2);

        SettlementBill m1 = bills.stream().filter(b -> b.merchantNo().equals("M0001")).findFirst().orElseThrow();
        assertThat(m1.txnCount()).isEqualTo(2);
        assertThat(m1.totalAmount()).isEqualTo(20000);
        assertThat(m1.totalFee()).isEqualTo(60);
        assertThat(m1.netAmount()).isEqualTo(19940);

        SettlementBill m2 = bills.stream().filter(b -> b.merchantNo().equals("M0002")).findFirst().orElseThrow();
        assertThat(m2.totalAmount()).isEqualTo(20000);
        assertThat(m2.totalFee()).isEqualTo(100);
        assertThat(m2.netAmount()).isEqualTo(19900);
    }

    @Test
    void repeatedCloseReturnsFirstBillsWithoutRecompute() {
        post("r1", "S1", "M0001", 10000, "2026-09-24T10:00:00Z");
        List<SettlementBill> first = settlementService.close(LocalDate.of(2026, 9, 24));
        assertThat(first).hasSize(1);

        // 封账后再次封账：返回首次清算单，不重新计算
        List<SettlementBill> second = settlementService.close(LocalDate.of(2026, 9, 24));
        assertThat(second).hasSize(1);
        assertThat(second.get(0).id()).isEqualTo(first.get(0).id());
        assertThat(countRows("settlement_bill")).isEqualTo(1);
    }

    @Test
    void suspendedTxnNotCountedInClosedBatch() {
        post("r1", "S1", "M0001", 10000, "2026-09-24T10:00:00Z");
        settlementService.close(LocalDate.of(2026, 9, 24));
        // 封账后到达的该批次交易进入挂账
        post("r2", "S2", "M0001", 5000, "2026-09-24T11:00:00Z");

        List<SettlementBill> bills = settlementService.close(LocalDate.of(2026, 9, 24));
        assertThat(bills).hasSize(1);
        assertThat(bills.get(0).totalAmount()).isEqualTo(10000);
    }
}
