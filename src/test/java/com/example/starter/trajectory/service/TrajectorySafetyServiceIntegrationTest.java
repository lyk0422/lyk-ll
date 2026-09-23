package com.example.starter.trajectory.service;

import com.example.starter.trajectory.dto.CreateRuleVersionRequest;
import com.example.starter.trajectory.dto.FailureItem;
import com.example.starter.trajectory.dto.ObstaclePrediction;
import com.example.starter.trajectory.dto.ValidationRequest;
import com.example.starter.trajectory.dto.ValidationResult;
import com.example.starter.trajectory.exception.BadRequestException;
import com.example.starter.trajectory.exception.ConflictException;
import com.example.starter.trajectory.exception.NotFoundException;
import com.example.starter.trajectory.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 服务层 H2（MODE=MySQL）集成测试：版本化规则复核、幂等同参/异参、
 * 并发同键提交的唯一约束裁决，以及结果哈希的跨进程级稳定（重复计算一致）。
 */
@SpringBootTest
class TrajectorySafetyServiceIntegrationTest {

    @Autowired
    private TrajectorySafetyService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM validation_record");
        jdbcTemplate.update("DELETE FROM rule_version");
    }

    private void publish(String version, double maxInterval, double maxSpeed, double maxAccel,
                         double maxCurvature, double roadTolerance, double safetyDistance) {
        service.publishRuleVersion(new CreateRuleVersionRequest(version,
                TestFixtures.ruleConfig(maxInterval, maxSpeed, maxAccel,
                        maxCurvature, roadTolerance, safetyDistance)));
    }

    @Test
    @DisplayName("安全轨迹返回 safe=true、空失败列表、零计数与非空稳定哈希")
    void safeTrajectorySummary() {
        publish("v-safe", 2.0, 10, 5, 1.0, 0.5, 2.0);
        ValidationRequest request = TestFixtures.safeRequest("req-safe", "v-safe", null);

        ValidationResult result = service.validate(request);

        assertThat(result.safe()).isTrue();
        assertThat(result.failureCount()).isZero();
        assertThat(result.failures()).isEmpty();
        assertThat(result.failuresByRule())
                .containsEntry("R1", 0).containsEntry("R2", 0).containsEntry("R3", 0)
                .containsEntry("R4", 0).containsEntry("R5", 0).containsEntry("R6", 0);
        assertThat(result.resultHash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("多规则失败轨迹：失败项按 R1/R4/R6/R3/R5 固定排序且计数正确")
    void multiRuleFailureSummary() {
        publish("v-multi", 1.0, 10, 5, 0.1, 0.5, 2.0);
        ValidationRequest request = TestFixtures.multiFailureRequest("req-multi", "v-multi");

        ValidationResult result = service.validate(request);

        assertThat(result.safe()).isFalse();
        assertThat(result.failureCount()).isEqualTo(5);
        assertThat(result.failures()).extracting(FailureItem::ruleId)
                .containsExactly("R1", "R3", "R4", "R5", "R6");
        assertThat(result.failuresByRule())
                .containsEntry("R1", 1).containsEntry("R3", 1).containsEntry("R4", 1)
                .containsEntry("R5", 1).containsEntry("R6", 1).containsEntry("R2", 0);
    }

    @Test
    @DisplayName("规则版本不可原地修改：重复发布同版本号返回 409")
    void duplicateRuleVersionConflicts() {
        publish("v1", 2.0, 10, 5, 1.0, 0.5, 2.0);

        assertThatThrownBy(() -> publish("v1", 2.0, 11, 5, 1.0, 0.5, 2.0))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("v1");
    }

    @Test
    @DisplayName("规则版本切换：同一轨迹按不同版本复核得到不同结论，结果互不影响")
    void sameTrajectoryReviewedAgainstDifferentVersions() {
        publish("v-loose", 2.0, 20, 20, 1.0, 1.0, 2.0);
        publish("v-strict", 1.0, 4, 20, 1.0, 1.0, 2.0);
        ValidationRequest loose = TestFixtures.safeRequest("req-ver-loose", "v-loose", null);
        ValidationRequest strict = TestFixtures.safeRequest("req-ver-strict", "v-strict", null);

        ValidationResult looseResult = service.validate(loose);
        ValidationResult strictResult = service.validate(strict);

        assertThat(looseResult.safe()).isTrue();
        assertThat(strictResult.safe()).isFalse();
        assertThat(strictResult.failures()).extracting(FailureItem::ruleId).containsExactly("R2");
    }

    @Test
    @DisplayName("引用不存在的规则版本返回 404")
    void unknownRuleVersionIsNotFound() {
        ValidationRequest request = TestFixtures.safeRequest("req-missing", "nope", null);

        assertThatThrownBy(() -> service.validate(request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("同键同参：重复提交返回原结果，resultHash 完全一致；按 requestId 可读回")
    void sameKeySameParametersReturnsStoredResult() {
        publish("v-idem", 2.0, 10, 5, 1.0, 0.5, 2.0);
        ValidationRequest request = TestFixtures.safeRequest("idem-key", "v-idem", null);

        ValidationResult first = service.validate(request);
        ValidationResult second = service.validate(request);
        ValidationResult fetched = service.getByRequestId("idem-key");

        assertThat(second).isEqualTo(first);
        assertThat(fetched).isEqualTo(first);
        assertThat(second.resultHash()).isEqualTo(first.resultHash());
    }

    @Test
    @DisplayName("同键异参：相同 requestId 不同轨迹参数返回 409，原结果保持不变")
    void sameKeyDifferentParametersConflicts() {
        publish("v-conflict", 2.0, 10, 5, 1.0, 0.5, 2.0);
        ValidationRequest original = TestFixtures.safeRequest("dup-key", "v-conflict", null);
        ValidationResult first = service.validate(original);

        ValidationRequest tampered = new ValidationRequest(
                "dup-key", "v-conflict",
                original.initialState(),
                List.of(TestFixtures.tp(0, 1, 5), TestFixtures.tp(1, 9, 5)),
                original.obstacles(), original.roadBoundary());

        assertThatThrownBy(() -> service.validate(tampered))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("dup-key");
        assertThat(service.getByRequestId("dup-key")).isEqualTo(first);
    }

    @Test
    @DisplayName("同键换规则版本也属于异参：返回 409")
    void sameKeyDifferentRuleVersionConflicts() {
        publish("v-a", 2.0, 10, 5, 1.0, 0.5, 2.0);
        publish("v-b", 2.0, 10, 5, 1.0, 0.5, 2.0);
        ValidationRequest first = TestFixtures.safeRequest("ver-key", "v-a", null);
        service.validate(first);
        ValidationRequest otherVersion = TestFixtures.safeRequest("ver-key", "v-b", null);

        assertThatThrownBy(() -> service.validate(otherVersion))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("障碍物预测点时间非严格递增返回 400")
    void nonIncreasingObstacleTimesIsBadRequest() {
        publish("v-shape", 2.0, 10, 5, 1.0, 0.5, 2.0);
        ValidationRequest request = TestFixtures.safeRequest("shape-key", "v-shape",
                List.of(new ObstaclePrediction("dup-obs", List.of(
                        TestFixtures.tp(1, 3, 3), TestFixtures.tp(1, 4, 4)))));

        assertThatThrownBy(() -> service.validate(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dup-obs");
    }

    @Test
    @DisplayName("配置、请求与结果均持久化到 H2：规则行与请求 JSON/结果 JSON 均可读回")
    void configRequestAndResultArePersistedToH2() {
        publish("v-persist", 2.0, 10, 5, 1.0, 0.5, 2.0);
        ValidationRequest request = TestFixtures.safeRequest("persist-key", "v-persist", null);
        ValidationResult result = service.validate(request);

        Map<String, Object> ruleRow = jdbcTemplate.queryForMap(
                "SELECT * FROM rule_version WHERE rule_version = 'v-persist'");
        assertThat(ruleRow.get("max_speed")).isEqualTo(10.0);

        Map<String, Object> recordRow = jdbcTemplate.queryForMap(
                "SELECT * FROM validation_record WHERE request_id = 'persist-key'");
        String requestJson = (String) recordRow.get("request_json");
        String resultJson = (String) recordRow.get("result_json");
        assertThat(requestJson).contains("\"ruleVersion\":\"v-persist\"").contains("trajectory");
        assertThat(resultJson).contains(result.resultHash()).contains("\"safe\":true");
    }

    @Test
    @DisplayName("并行确定性：并发重复执行同一校验，摘要与结果哈希始终一致")
    void parallelRepeatedRunsAreDeterministic() {
        publish("v-parallel", 1.0, 10, 5, 0.1, 0.5, 2.0);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 3; round++) {
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch start = new CountDownLatch(1);
                Set<String> hashes = ConcurrentHashMap.newKeySet();
                Set<Integer> counts = ConcurrentHashMap.newKeySet();
                CountDownLatch done = new CountDownLatch(threads);
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        ValidationResult result = service.validate(
                                TestFixtures.multiFailureRequest("parallel-key", "v-parallel"));
                        hashes.add(result.resultHash());
                        counts.add(result.failureCount());
                        done.countDown();
                        return null;
                    });
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(hashes).hasSize(1);
                assertThat(counts).containsExactly(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发同键提交不同参数：恰好一个成功，其余全部 409，不允许两个结果共存")
    void concurrentSameKeyDistinctPayloadsHasSingleWinner() throws Exception {
        publish("v-race", 2.0, 10, 5, 1.0, 0.5, 2.0);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> winners = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int variant = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        ValidationRequest request = new ValidationRequest(
                                "race-key", "v-race",
                                TestFixtures.safeRequest("unused", "v-race", null).initialState(),
                                List.of(TestFixtures.tp(0, 1, 5), TestFixtures.tp(1, 1 + variant, 5)),
                                null, TestFixtures.rectangleRoad());
                        ValidationResult result = service.validate(request);
                        winners.add(result.resultHash());
                    } catch (ConflictException expected) {
                        // 同键异参的落败者
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners).hasSize(1);
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM validation_record WHERE request_id = 'race-key'", Integer.class);
        assertThat(stored).isEqualTo(1);
    }
}
