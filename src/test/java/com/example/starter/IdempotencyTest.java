package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.dto.CreateDefectRequest;
import com.example.starter.dto.CreateRepairOrderRequest;
import com.example.starter.dto.DefectResponse;
import com.example.starter.dto.RepairOrderView;
import com.example.starter.error.ApiException;
import com.example.starter.model.DefectLevel;
import com.example.starter.service.DefectService;
import com.example.starter.service.RepairService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 幂等测试：同键同参返回首次结果，同键异参 409，并发同键仅生效一次。
 */
class IdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private RepairService repairService;

    @Test
    void sameKeySameParamsReturnsFirstResult() {
        CreateDefectRequest request = new CreateDefectRequest(
                "req-idem-1", 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null);
        DefectResponse first = defectService.register(request);
        DefectResponse second = defectService.register(request);

        assertThat(second).isEqualTo(first);
        Integer defectCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM defect_record", Integer.class);
        assertThat(defectCount).isEqualTo(1);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-idem-1'",
                Integer.class);
        assertThat(idemCount).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        defectService.register(new CreateDefectRequest(
                "req-idem-2", 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null));

        assertThatThrownBy(() -> defectService.register(new CreateDefectRequest(
                "req-idem-2", 1L, "叶尖", DefectLevel.SURFACE, "EROSION", null)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        // 冲突请求不产生业务数据
        Integer defectCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM defect_record", Integer.class);
        assertThat(defectCount).isEqualTo(1);
    }

    @Test
    void sameKeyAcrossDifferentEndpointsReturns409() {
        DefectResponse defect = defectService.register(new CreateDefectRequest(
                "req-idem-3", 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null));

        assertThatThrownBy(() -> repairService.createOrder(
                new CreateRepairOrderRequest("req-idem-3", defect.id())))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void repairOrderCreationIsIdempotent() {
        DefectResponse defect = defectService.register(new CreateDefectRequest(
                "req-idem-4a", 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null));
        CreateRepairOrderRequest request = new CreateRepairOrderRequest("req-idem-4b", defect.id());

        RepairOrderView first = repairService.createOrder(request);
        RepairOrderView second = repairService.createOrder(request);
        assertThat(second).isEqualTo(first);
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_order", Integer.class);
        assertThat(orderCount).isEqualTo(1);
    }

    @Test
    void concurrentSameKeySameParamsExecutesOnce() throws Exception {
        CreateDefectRequest request = new CreateDefectRequest(
                "req-idem-concurrent", 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DefectResponse>> futures = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return defectService.register(request);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<DefectResponse> responses = new CopyOnWriteArrayList<>();
            for (Future<DefectResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            // 全部并发请求返回首次结果，且业务只生效一次
            assertThat(responses).hasSize(threads);
            assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(responses.get(0)));
            Integer defectCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM defect_record", Integer.class);
            assertThat(defectCount).isEqualTo(1);
            Integer idemCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_record"
                            + " WHERE request_id = 'req-idem-concurrent'",
                    Integer.class);
            assertThat(idemCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
