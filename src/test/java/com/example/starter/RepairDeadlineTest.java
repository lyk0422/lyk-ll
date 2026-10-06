package com.example.starter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import com.example.starter.service.DefectService;
import com.example.starter.service.QueryService;
import com.example.starter.service.RepairService;
import com.example.starter.web.dto.ApiDtos.AddRepairRecordRequest;
import com.example.starter.web.dto.ApiDtos.CreateRepairOrderRequest;
import com.example.starter.web.dto.ApiDtos.DefectDetailResponse;
import com.example.starter.web.dto.ApiDtos.DefectResponse;
import com.example.starter.web.dto.ApiDtos.RegisterDefectRequest;
import com.example.starter.web.dto.ApiDtos.ReinspectRequest;
import com.example.starter.web.dto.ApiDtos.ReinspectResponse;
import com.example.starter.web.dto.ApiDtos.RepairOrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维修期限语义：恰第 7 日闭环有效，逾期升级并保留首次登记时刻与告警记录；
 * 复检合格才撤销限功率，不合格维持限功率且不得结案。
 */
class RepairDeadlineTest extends BaseIntegrationTest {

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

    private DefectResponse registerStructuralDefect(long bladeId) {
        return defectService.registerDefect(new RegisterDefectRequest(
                nextRequestId("req-defect"), null, bladeId, "叶尖30-35m",
                "CRACK", new BigDecimal("200.00"), new BigDecimal("0.60")));
    }

    private RepairOrderResponse createOrder(long defectId) {
        return repairService.createOrder(new CreateRepairOrderRequest(nextRequestId("req-order"), defectId));
    }

    @Test
    void 恰第7日闭环有效_不产生升级告警() {
        long blade = bladeId("BLADE-A01");
        DefectResponse defect = registerStructuralDefect(blade);
        RepairOrderResponse order = createOrder(defect.id());
        assertThat(order.deadlineAt()).isEqualTo(defect.registeredAt().plusDays(7));

        // 推进到首次登记 + 7 日整
        clock.plusDays(7);
        ReinspectResponse response = repairService.reinspect(order.id(),
                new ReinspectRequest(nextRequestId("req-reinspect"), "PASS", "inspector-li"));

        assertThat(response.orderStatus()).isEqualTo("CLOSED");
        assertThat(response.defectStatus()).isEqualTo("CLOSED");
        assertThat(response.curtailmentLifted()).isTrue();
        assertThat(response.escalated()).isFalse();
        assertThat(count("escalation_alert")).isZero();
        assertThat(queryService.bladeStatus(blade).curtailed()).isFalse();
    }

    @Test
    void 逾期闭环触发升级_保留首次登记时刻与告警记录() {
        long blade = bladeId("BLADE-A01");
        DefectResponse defect = registerStructuralDefect(blade);
        RepairOrderResponse order = createOrder(defect.id());

        // 首次登记 + 7 日 + 1 秒：逾期
        clock.plusDays(7);
        clock.plusSeconds(1);
        ReinspectResponse response = repairService.reinspect(order.id(),
                new ReinspectRequest(nextRequestId("req-reinspect"), "PASS", "inspector-li"));

        assertThat(response.escalated()).isTrue();
        assertThat(response.orderStatus()).isEqualTo("CLOSED");

        DefectDetailResponse detail = queryService.defectDetail(defect.id());
        assertThat(detail.escalations()).singleElement().satisfies(e -> {
            assertThat(e.firstRegisteredAt()).isEqualTo(defect.registeredAt());
            assertThat(e.escalatedAt()).isEqualTo(defect.registeredAt().plusDays(7).plusSeconds(1));
        });
        // 原缺陷登记时刻不被维修/升级覆盖
        assertThat(detail.registeredAt()).isEqualTo(defect.registeredAt());
        // 升级幂等：重复查询不新增告警
        assertThat(queryService.defectDetail(defect.id()).escalations()).hasSize(1);
        assertThat(count("escalation_alert")).isEqualTo(1);
    }

    @Test
    void 逾期未闭环时查询触发惰性升级() {
        long blade = bladeId("BLADE-A01");
        DefectResponse defect = registerStructuralDefect(blade);
        createOrder(defect.id());

        clock.plusDays(8);
        DefectDetailResponse detail = queryService.defectDetail(defect.id());

        assertThat(detail.status()).isEqualTo("OPEN");
        assertThat(detail.escalations()).singleElement()
                .satisfies(e -> assertThat(e.firstRegisteredAt()).isEqualTo(defect.registeredAt()));
    }

    @Test
    void 复检不合格_维持限功率且维修单不得结案_再次合格方可闭环() {
        long blade = bladeId("BLADE-A01");
        DefectResponse defect = registerStructuralDefect(blade);
        RepairOrderResponse order = createOrder(defect.id());

        clock.plusDays(1);
        ReinspectResponse failed = repairService.reinspect(order.id(),
                new ReinspectRequest(nextRequestId("req-reinspect"), "FAIL", "inspector-li"));

        assertThat(failed.orderStatus()).isEqualTo("IN_PROGRESS");
        assertThat(failed.defectStatus()).isEqualTo("OPEN");
        assertThat(failed.curtailmentLifted()).isFalse();
        assertThat(queryService.bladeStatus(blade).curtailed()).isTrue();

        clock.plusDays(1);
        ReinspectResponse passed = repairService.reinspect(order.id(),
                new ReinspectRequest(nextRequestId("req-reinspect"), "PASS", "inspector-li"));

        assertThat(passed.orderStatus()).isEqualTo("CLOSED");
        assertThat(passed.curtailmentLifted()).isTrue();
        assertThat(queryService.bladeStatus(blade).curtailed()).isFalse();
        // 复检记录追加式保留两条
        assertThat(count("reinspection_record")).isEqualTo(2);
    }

    @Test
    void 维修记录追加式保存_不覆盖原缺陷字段() {
        long blade = bladeId("BLADE-A01");
        DefectResponse defect = registerStructuralDefect(blade);
        RepairOrderResponse order = createOrder(defect.id());

        repairService.addRepairRecord(order.id(),
                new AddRepairRecordRequest(nextRequestId("req-repair"), "tech-wang", "打磨后灌注结构胶"));
        repairService.addRepairRecord(order.id(),
                new AddRepairRecordRequest(nextRequestId("req-repair"), "tech-wang", "二次补强铺层"));

        DefectDetailResponse detail = queryService.defectDetail(defect.id());
        assertThat(detail.level()).isEqualTo("STRUCTURAL");
        assertThat(detail.registeredAt()).isEqualTo(defect.registeredAt());
        assertThat(detail.status()).isEqualTo("OPEN");
        assertThat(count("repair_record")).isEqualTo(2);
        assertThat(queryService.repairLedger(blade)).singleElement()
                .satisfies(o -> assertThat(o.repairRecords()).hasSize(2));
    }

    @Test
    void 复检撤销与结案在同一事务_状态一致() {
        long blade = bladeId("BLADE-A01");
        DefectResponse defect = registerStructuralDefect(blade);
        RepairOrderResponse order = createOrder(defect.id());

        clock.plusDays(3);
        repairService.reinspect(order.id(),
                new ReinspectRequest(nextRequestId("req-reinspect"), "PASS", "inspector-li"));

        // 同事务写入的三个状态必须一致可读
        LocalDateTime closedAt = jdbcTemplate.queryForObject(
                "SELECT closed_at FROM repair_order WHERE id = ?", LocalDateTime.class, order.id());
        LocalDateTime defectClosedAt = jdbcTemplate.queryForObject(
                "SELECT closed_at FROM defect WHERE id = ?", LocalDateTime.class, defect.id());
        LocalDateTime liftedAt = jdbcTemplate.queryForObject(
                "SELECT lifted_at FROM curtailment WHERE source_defect_id = ?", LocalDateTime.class, defect.id());
        assertThat(closedAt).isNotNull();
        assertThat(defectClosedAt).isEqualTo(closedAt);
        assertThat(liftedAt).isEqualTo(closedAt);
    }
}
