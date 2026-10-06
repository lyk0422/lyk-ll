package com.example.starter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.error.BusinessConflictException;
import com.example.starter.service.DefectService;
import com.example.starter.service.RepairService;
import com.example.starter.web.dto.ApiDtos.CreateRepairOrderRequest;
import com.example.starter.web.dto.ApiDtos.DefectResponse;
import com.example.starter.web.dto.ApiDtos.RegisterDefectRequest;
import com.example.starter.web.dto.ApiDtos.RepairOrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 同一叶片同时刻仅允许一张进行中维修单：串行预检与并发唯一约束双重验证。
 */
class RepairOrderUniquenessTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private RepairService repairService;

    private DefectResponse registerStructural(long bladeId, String requestId) {
        return defectService.registerDefect(new RegisterDefectRequest(
                requestId, null, bladeId, "叶中10-15m",
                "CRACK", new BigDecimal("150.00"), new BigDecimal("0.60")));
    }

    @Test
    void 串行重复建单被拒绝() {
        long blade = bladeId("BLADE-A01");
        DefectResponse first = registerStructural(blade, "req-uniq-d1");
        DefectResponse second = registerStructural(blade, "req-uniq-d2");

        repairService.createOrder(new CreateRepairOrderRequest("req-uniq-o1", first.id()));

        assertThatThrownBy(() -> repairService.createOrder(
                new CreateRepairOrderRequest("req-uniq-o2", second.id())))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("同一叶片同时刻只允许一张进行中维修单");
        assertThat(count("repair_order")).isEqualTo(1);
    }

    @Test
    void 并发建单唯一约束兜底_仅一张进行中() throws Exception {
        long blade = bladeId("BLADE-A01");
        DefectResponse first = registerStructural(blade, "req-conc-d1");
        DefectResponse second = registerStructural(blade, "req-conc-d2");

        int threads = 2;
        List<Long> defectIds = List.of(first.id(), second.id());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final long defectId = defectIds.get(i);
            final String requestId = "req-conc-o" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return repairService.createOrder(new CreateRepairOrderRequest(requestId, defectId));
                } catch (BusinessConflictException e) {
                    return e;
                }
            });
        }
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long successes = results.stream().filter(RepairOrderResponse.class::isInstance).count();
        long conflicts = results.stream().filter(BusinessConflictException.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        // 失败方事务回滚，无残留行
        assertThat(count("repair_order")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repair_order WHERE status = 'IN_PROGRESS'", Long.class))
                .isEqualTo(1);
    }
}
