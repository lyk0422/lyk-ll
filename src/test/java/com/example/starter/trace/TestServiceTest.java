package com.example.starter.trace;

import com.example.starter.trace.api.TraceDtos.ContributionItem;
import com.example.starter.trace.api.TraceDtos.HarvestBatchDetailResponse;
import com.example.starter.trace.api.TraceDtos.RapidTestRequest;
import com.example.starter.trace.api.TraceDtos.RapidTestResponse;
import com.example.starter.trace.api.TraceDtos.RegisterHarvestBatchRequest;
import com.example.starter.trace.api.TraceDtos.RetestRequest;
import com.example.starter.trace.api.TraceDtos.RetestResponse;
import com.example.starter.trace.service.BusinessException;
import com.example.starter.trace.service.HarvestService;
import com.example.starter.trace.service.TestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 药残检测链：疑似判定边界、冻结与复检、蜂场预警恰 3 批触发。
 */
class TestServiceTest extends BaseTraceTest {

    @Autowired
    private HarvestService harvestService;

    @Autowired
    private TestService testService;

    private Long apiaryId(String code) {
        return jdbc.queryForObject("SELECT id FROM apiary WHERE code = ?", Long.class, code);
    }

    private Long registerBatch(String requestId, String batchNo, String floralPeriod, String apiaryCode) {
        return harvestService.register(new RegisterHarvestBatchRequest(requestId, batchNo, floralPeriod,
                List.of(new ContributionItem(apiaryId(apiaryCode), new BigDecimal("100.00"))))).id();
    }

    private String batchStatus(Long batchId) {
        return jdbc.queryForObject("SELECT status FROM harvest_batch WHERE id = ?", String.class, batchId);
    }

    @Test
    void 抑制率4999_合格放行() {
        Long batchId = registerBatch("req-r1", "HB-R1", "2026-洋槐", "AP-001");

        RapidTestResponse response = testService.rapidTest(batchId,
                new RapidTestRequest("req-rt-1", "SMP-1", new BigDecimal("49.99")));

        assertThat(response.result()).isEqualTo("PASS");
        assertThat(response.batchStatus()).isEqualTo("RELEASED");
        assertThat(batchStatus(batchId)).isEqualTo("RELEASED");
    }

    @Test
    void 抑制率恰50_判疑似并冻结() {
        Long batchId = registerBatch("req-r2", "HB-R2", "2026-洋槐", "AP-001");

        RapidTestResponse response = testService.rapidTest(batchId,
                new RapidTestRequest("req-rt-2", "SMP-2", new BigDecimal("50.00")));

        assertThat(response.result()).isEqualTo("SUSPECT");
        assertThat(response.batchStatus()).isEqualTo("FROZEN");
        assertThat(batchStatus(batchId)).isEqualTo("FROZEN");
    }

    @Test
    void 疑似后检测链未闭环_再次快检拒绝() {
        Long batchId = registerBatch("req-r3", "HB-R3", "2026-洋槐", "AP-001");
        testService.rapidTest(batchId, new RapidTestRequest("req-rt-3", "SMP-3", new BigDecimal("55.00")));

        assertThatThrownBy(() -> testService.rapidTest(batchId,
                new RapidTestRequest("req-rt-4", "SMP-4", new BigDecimal("10.00"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 复检使用同一样品_拒绝() {
        Long batchId = registerBatch("req-r4", "HB-R4", "2026-洋槐", "AP-001");
        testService.rapidTest(batchId, new RapidTestRequest("req-rt-5", "SMP-5", new BigDecimal("60.00")));

        assertThatThrownBy(() -> testService.retest(batchId,
                new RetestRequest("req-re-1", "SMP-5", "QUALIFIED")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(batchStatus(batchId)).isEqualTo("FROZEN");
    }

    @Test
    void 复检合格_解除冻结且原快检记录保留可查() {
        Long batchId = registerBatch("req-r5", "HB-R5", "2026-洋槐", "AP-001");
        testService.rapidTest(batchId, new RapidTestRequest("req-rt-6", "SMP-6", new BigDecimal("72.50")));

        RetestResponse response = testService.retest(batchId,
                new RetestRequest("req-re-2", "SMP-6-B", "QUALIFIED"));

        assertThat(response.batchStatus()).isEqualTo("RELEASED");
        assertThat(batchStatus(batchId)).isEqualTo("RELEASED");
        HarvestBatchDetailResponse detail = harvestService.detail(batchId);
        assertThat(detail.rapidTests()).hasSize(1);
        assertThat(detail.rapidTests().get(0).result()).isEqualTo("SUSPECT");
        assertThat(detail.retests()).hasSize(1);
    }

    @Test
    void 复检不合格_仍不得进入加工() {
        Long batchId = registerBatch("req-r6", "HB-R6", "2026-洋槐", "AP-001");
        testService.rapidTest(batchId, new RapidTestRequest("req-rt-7", "SMP-7", new BigDecimal("80.00")));

        RetestResponse response = testService.retest(batchId,
                new RetestRequest("req-re-3", "SMP-7-B", "UNQUALIFIED"));

        assertThat(response.batchStatus()).isEqualTo("REJECTED");
        assertThat(batchStatus(batchId)).isEqualTo("REJECTED");
    }

    @Test
    void 无疑似链时复检_拒绝() {
        Long batchId = registerBatch("req-r7", "HB-R7", "2026-洋槐", "AP-001");
        testService.rapidTest(batchId, new RapidTestRequest("req-rt-8", "SMP-8", new BigDecimal("10.00")));

        assertThatThrownBy(() -> testService.retest(batchId,
                new RetestRequest("req-re-4", "SMP-8-B", "QUALIFIED")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 同蜂场同花期恰3批疑似_触发一次预警() {
        Long apiary = apiaryId("AP-002");
        for (int i = 1; i <= 3; i++) {
            Long batchId = registerBatch("req-a" + i, "HB-A" + i, "2026-荆条", "AP-002");
            testService.rapidTest(batchId,
                    new RapidTestRequest("req-art-" + i, "SMP-A" + i, new BigDecimal("50.00")));
        }

        List<Integer> counts = jdbc.queryForList(
                "SELECT suspect_count FROM apiary_alert WHERE apiary_id = ? AND floral_period = '2026-荆条'",
                Integer.class, apiary);
        assertThat(counts).containsExactly(3);
    }

    @Test
    void 第4批疑似_不重复触发预警() {
        Long apiary = apiaryId("AP-002");
        for (int i = 1; i <= 4; i++) {
            Long batchId = registerBatch("req-b" + i, "HB-B" + i, "2026-椴树", "AP-002");
            testService.rapidTest(batchId,
                    new RapidTestRequest("req-brt-" + i, "SMP-B" + i, new BigDecimal("60.00")));
        }

        Integer alertCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM apiary_alert WHERE apiary_id = ? AND floral_period = '2026-椴树'",
                Integer.class, apiary);
        assertThat(alertCount).isEqualTo(1);
    }

    @Test
    void 不同花期不累计预警() {
        Long apiary = apiaryId("AP-003");
        for (int i = 1; i <= 2; i++) {
            Long batchId = registerBatch("req-c" + i, "HB-C" + i, "2026-洋槐", "AP-003");
            testService.rapidTest(batchId,
                    new RapidTestRequest("req-crt-" + i, "SMP-C" + i, new BigDecimal("60.00")));
        }
        Long otherPeriod = registerBatch("req-c3", "HB-C3", "2026-荆条", "AP-003");
        testService.rapidTest(otherPeriod,
                new RapidTestRequest("req-crt-3", "SMP-C3", new BigDecimal("60.00")));

        Integer alertCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM apiary_alert WHERE apiary_id = ?", Integer.class, apiary);
        assertThat(alertCount).isZero();
    }

    @Test
    void 快检幂等_同键同参返回首次结果() {
        Long batchId = registerBatch("req-r8", "HB-R8", "2026-洋槐", "AP-001");
        RapidTestRequest request = new RapidTestRequest("req-rt-9", "SMP-9", new BigDecimal("50.00"));

        RapidTestResponse first = testService.rapidTest(batchId, request);
        RapidTestResponse replay = testService.rapidTest(batchId, request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rapid_test", Integer.class)).isEqualTo(1);
    }

    @Test
    void 快检幂等_同键异参拒绝409() {
        Long batchId = registerBatch("req-r9", "HB-R9", "2026-洋槐", "AP-001");
        testService.rapidTest(batchId, new RapidTestRequest("req-rt-10", "SMP-10", new BigDecimal("50.00")));

        assertThatThrownBy(() -> testService.rapidTest(batchId,
                new RapidTestRequest("req-rt-10", "SMP-10", new BigDecimal("51.00"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }
}
