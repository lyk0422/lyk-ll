package com.example.starter.ros.service;

import com.example.starter.ros.RosIntegrationTestBase;
import com.example.starter.ros.bootstrap.DemoDataInitializer;
import com.example.starter.ros.dto.HotUpdateRequest;
import com.example.starter.ros.dto.HotUpdateResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实并发边界：两笔同版本事务并发提交，至多一笔成功，另一笔版本冲突全量回滚；
 * 最终版本严格为 1、事件仅一条、值只含获胜事务的内容。
 */
class ConcurrentTransactionIntegrationTest extends RosIntegrationTestBase {

    private final HotUpdateService hotUpdateService;

    ConcurrentTransactionIntegrationTest(@org.springframework.beans.factory.annotation.Autowired
                                         HotUpdateService hotUpdateService) {
        this.hotUpdateService = hotUpdateService;
    }

    private HotUpdateRequest request(String txId, int fps) {
        HotUpdateRequest.NodeChangeSet camera = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, 0L,
                Map.of("vision", Map.of("fps", fps)));
        return new HotUpdateRequest(txId, List.of(camera), 2000L);
    }

    @Test
    void concurrentSameVersionTransactionsYieldSingleCommit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<HotUpdateResponse> first = pool.submit(() -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return hotUpdateService.update(request("tx-con-1", 20));
        });
        Future<HotUpdateResponse> second = pool.submit(() -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return hotUpdateService.update(request("tx-con-2", 40));
        });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        HotUpdateResponse r1 = first.get(10, TimeUnit.SECONDS);
        HotUpdateResponse r2 = second.get(10, TimeUnit.SECONDS);

        long commits = java.util.stream.Stream.of(r1, r2)
                .filter(HotUpdateResponse::committed).count();
        assertThat(commits).as("并发同版本事务只有一笔提交").isEqualTo(1L);

        HotUpdateResponse winner = r1.committed() ? r1 : r2;
        HotUpdateResponse loser = r1.committed() ? r2 : r1;
        assertThat(winner.nodeResults().get(0).status())
                .isEqualTo(HotUpdateResponse.NodeResult.ACCEPTED);
        assertThat(loser.nodeResults().get(0).status())
                .isEqualTo(HotUpdateResponse.NodeResult.VERSION_CONFLICT);

        // 最终数据：版本严格为 1，只有获胜值
        assertThat(jdbc.queryForObject(
                "SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.CAMERA_NODE)).isEqualTo(1L);
        String storedFps = jdbc.queryForObject(
                "SELECT param_value FROM ros_param_value WHERE node = ? AND group_name = ? AND name = ?",
                String.class, DemoDataInitializer.CAMERA_NODE, "vision", "fps");
        String expected = winner.transactionId().equals("tx-con-1") ? "20" : "40";
        assertThat(storedFps).isEqualTo(expected);

        Integer events = jdbc.queryForObject("SELECT COUNT(*) FROM ros_param_event", Integer.class);
        assertThat(events).isEqualTo(1);

        // 输家事务不产生事件，但已登记终态；用相同参数重放得到原始“未提交”结果
        HotUpdateResponse replay = hotUpdateService.update(
                request(loser.transactionId(),
                        loser.transactionId().equals("tx-con-1") ? 20 : 40));
        assertThat(replay.committed()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ros_param_event", Integer.class))
                .isEqualTo(1);

        pool.shutdownNow();
    }
}
