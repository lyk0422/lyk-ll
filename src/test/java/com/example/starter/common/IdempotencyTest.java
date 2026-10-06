package com.example.starter.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.segment.SegmentDtos.CreateSegmentRequest;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.tree.TreeDtos.RegisterTreeRequest;
import com.example.starter.tree.TreeDtos.TreeResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 幂等规则测试：同键同参返回首次结果，同键异参 409，指纹覆盖全部业务字段。
 */
class IdempotencyTest extends BaseIntegrationTest {

    @Test
    void sameKeySameParamsReturnsFirstResult() {
        CreateSegmentRequest request = new CreateSegmentRequest("req-fixed-1", "中山路");
        SegmentResponse first = segmentService.create(request);
        SegmentResponse second = segmentService.create(request);

        assertThat(second).isEqualTo(first);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM road_segment WHERE name = '中山路'", Long.class);
        assertThat(count).isEqualTo(1);
        Long records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-fixed-1'", Long.class);
        assertThat(records).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        segmentService.create(new CreateSegmentRequest("req-fixed-2", "中山路"));

        assertThatThrownBy(() -> segmentService.create(new CreateSegmentRequest("req-fixed-2", "解放大道")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fingerprintCoversAllBusinessFields() {
        SegmentResponse segment = createSegment("中山路");
        RegisterTreeRequest base = new RegisterTreeRequest("req-fixed-3", segment.id(), "T-001",
                "悬铃木", 60, 35, 18.0, true);
        treeService.register(base);

        // 仅改动一个业务字段（健康度）即视为异参
        RegisterTreeRequest changed = new RegisterTreeRequest("req-fixed-3", segment.id(), "T-001",
                "悬铃木", 60, 36, 18.0, true);
        assertThatThrownBy(() -> treeService.register(changed))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void failedRequestIsNotCachedAndCanBeRetried() {
        // 首次因路段不存在而失败（404），不落幂等记录；修正参数后同键可成功
        RegisterTreeRequest bad = new RegisterTreeRequest("req-fixed-4", 999L, "T-001",
                "悬铃木", 60, 35, 18.0, true);
        assertThatThrownBy(() -> treeService.register(bad)).isInstanceOf(ApiException.class);

        SegmentResponse segment = createSegment("中山路");
        TreeResponse ok = treeService.register(new RegisterTreeRequest("req-fixed-4", segment.id(),
                "T-001", "悬铃木", 60, 35, 18.0, true));
        assertThat(ok.treeCode()).isEqualTo("T-001");
    }

    @Test
    void concurrentSameKeySameParamsBothGetFirstResult() throws Exception {
        SegmentResponse segment = createSegment("中山路");
        RegisterTreeRequest request = new RegisterTreeRequest("req-fixed-5", segment.id(), "T-001",
                "悬铃木", 60, 35, 18.0, true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<TreeResponse> task = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return treeService.register(request);
            };
            Future<TreeResponse> f1 = pool.submit(task);
            Future<TreeResponse> f2 = pool.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            TreeResponse r1 = f1.get(15, TimeUnit.SECONDS);
            TreeResponse r2 = f2.get(15, TimeUnit.SECONDS);
            assertThat(List.of(r1, r2)).allSatisfy(r -> assertThat(r.id()).isEqualTo(r1.id()));
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tree WHERE tree_code = 'T-001'", Long.class);
            assertThat(count).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
