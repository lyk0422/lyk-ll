package com.example.starter;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.example.starter.service.DefectService;
import com.example.starter.service.QueryService;
import com.example.starter.service.RepairService;
import com.example.starter.web.dto.ApiDtos.AddRepairRecordRequest;
import com.example.starter.web.dto.ApiDtos.CreateRepairOrderRequest;
import com.example.starter.web.dto.ApiDtos.CurtailmentView;
import com.example.starter.web.dto.ApiDtos.DefectResponse;
import com.example.starter.web.dto.ApiDtos.RegisterDefectRequest;
import com.example.starter.web.dto.ApiDtos.ReinspectRequest;
import com.example.starter.web.dto.ApiDtos.RepairLedgerView;
import com.example.starter.web.dto.ApiDtos.RepairOrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限功率链与维修台账可复算、重复查询结果稳定。
 */
class QueryStabilityTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private RepairService repairService;

    @Autowired
    private QueryService queryService;

    private final AtomicLong requestSeq = new AtomicLong();

    private String nextRequestId(String prefix) {
        return prefix + "-" + requestSeq.incrementAndGet();
    }

    @Test
    void 限功率链与维修台账重复查询稳定() {
        long blade = bladeId("BLADE-A01");
        DefectResponse defect = defectService.registerDefect(new RegisterDefectRequest(
                nextRequestId("req-defect"), null, blade, "叶中10-15m",
                "CRACK", new BigDecimal("180.00"), new BigDecimal("0.70")));
        RepairOrderResponse order = repairService.createOrder(
                new CreateRepairOrderRequest(nextRequestId("req-order"), defect.id()));
        repairService.addRepairRecord(order.id(),
                new AddRepairRecordRequest(nextRequestId("req-repair"), "tech-zhao", "裂纹打磨灌注"));
        clock.plusDays(2);
        repairService.reinspect(order.id(),
                new ReinspectRequest(nextRequestId("req-reinspect"), "PASS", "inspector-li"));

        List<CurtailmentView> chain1 = queryService.curtailmentChain(blade);
        List<CurtailmentView> chain2 = queryService.curtailmentChain(blade);
        assertThat(chain1).isEqualTo(chain2);
        assertThat(chain1).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo("STRUCTURAL_DEFECT");
            assertThat(c.liftedAt()).isNotNull();
        });

        List<RepairLedgerView> ledger1 = queryService.repairLedger(blade);
        List<RepairLedgerView> ledger2 = queryService.repairLedger(blade);
        assertThat(ledger1).isEqualTo(ledger2);
        assertThat(ledger1).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo("CLOSED");
            assertThat(o.repairRecords()).hasSize(1);
            assertThat(o.reinspections()).hasSize(1);
            assertThat(o.closedAt()).isNotNull();
        });
    }
}
