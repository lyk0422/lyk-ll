package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanSegmentInput;
import com.example.starter.dto.PlanVersionResponse;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * H2 事务集成测试：日前计划发布（96 段整版校验）、不可变版本与当前指针原子替换、
 * expectedVersion 乐观并发、幂等重放/异参 409/失败不占键、版本查询。
 */
@SpringBootTest
class PlanServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PlanService planService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private PlanPublishRequest publishReq(String requestId, int expectedVersion,
                                          List<PlanSegmentInput> segments) {
        return new PlanPublishRequest(requestId, FARM, PLAN_DATE, expectedVersion,
                localInstant(9, 0, 0), segments);
    }

    private PlanPublishRequest publishReqAt(String requestId, int expectedVersion,
                                            java.time.Instant operateAt,
                                            List<PlanSegmentInput> segments) {
        return new PlanPublishRequest(requestId, FARM, PLAN_DATE, expectedVersion,
                operateAt, segments);
    }

    @Test
    @DisplayName("首版发布：版本号从 1 开始，整版 96 段×3 机组=288 条，为当前版本")
    void firstPublishCreatesVersionOne() {
        PlanPublishResponse resp =
                planService.publish(publishReq("plan-1", 1, fullSegments(1000)));

        assertThat(resp.versionNo()).isEqualTo(1);
        assertThat(resp.current()).isTrue();
        assertThat(resp.segmentCount()).isEqualTo(96 * 3);
        assertThat(resp.turbineCount()).isEqualTo(3);
        assertThat(resp.replayed()).isFalse();

        PlanVersionResponse current = planService.getCurrentVersion(FARM, PLAN_DATE);
        assertThat(current.versionNo()).isEqualTo(1);
        assertThat(current.current()).isTrue();
        assertThat(current.segments()).hasSize(288);
        // 半开区间：第 0 段 [00:00, 00:15) UTC，第 95 段 [23:45, 24:00) UTC。
        assertThat(current.segments().get(0).segmentStart().toString())
                .isEqualTo("2026-09-27T00:00:00Z");
        assertThat(current.segments().get(0).segmentEnd().toString())
                .isEqualTo("2026-09-27T00:15:00Z");
        assertThat(current.segments().get(287).segmentStart().toString())
                .isEqualTo("2026-09-27T23:45:00Z");
    }

    @Test
    @DisplayName("同键同参重放首次冻结结果；同键异参（版本号变化）409")
    void publishReplayAndConflict() {
        PlanPublishRequest req = publishReq("plan-replay", 1, fullSegments(1000));
        PlanPublishResponse first = planService.publish(req);
        PlanPublishResponse replay = planService.publish(req);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.versionId()).isEqualTo(first.versionId());
        assertThat(replay.versionNo()).isEqualTo(1);

        PlanPublishRequest changed = publishReq("plan-replay", 2, fullSegments(1000));
        assertThatThrownBy(() -> planService.publish(changed))
                .isInstanceOf(ApiConflictException.class);
    }

    @Test
    @DisplayName("同键但精确发布时刻不同：结构化指纹含时刻，409")
    void differentPublishInstantConflicts() {
        planService.publish(publishReq("plan-time", 1, fullSegments(1000)));
        // 其余参数不变，仅请求携带的精确时刻推进 1 秒。
        assertThatThrownBy(() -> planService.publish(
                publishReqAt("plan-time", 1, localInstant(9, 0, 1), fullSegments(1000))))
                .isInstanceOf(ApiConflictException.class);
    }

    @Test
    @DisplayName("首版 expectedVersion 非 1：409，失败不占键；改传 1 后同键可成功")
    void wrongExpectedVersionConflictsAndDoesNotOccupyKey() {
        assertThatThrownBy(() ->
                planService.publish(publishReq("plan-exp", 2, fullSegments(1000))))
                .isInstanceOf(ApiConflictException.class);

        // 键未被占用：同 requestId 携带正确版本号可以受理。
        PlanPublishResponse resp =
                planService.publish(publishReq("plan-exp", 1, fullSegments(1000)));
        assertThat(resp.versionNo()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan_version", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("第二次发布：expectedVersion=1 生成 v2 并原子替换当前指针，v1 历史保留且不可变")
    void secondPublishSwapsCurrentPointerAtomically() {
        planService.publish(publishReq("plan-v1", 1, fullSegments(1000)));
        pinClock(11, 0, 0);
        PlanPublishResponse v2 =
                planService.publish(publishReq("plan-v2", 2, fullSegments(1200)));

        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.current()).isTrue();

        List<PlanVersionResponse> versions = planService.listVersions(FARM, PLAN_DATE).stream()
                .map(s -> planService.getVersion(FARM, PLAN_DATE, s.versionNo()))
                .toList();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(1);
        assertThat(versions.get(0).current()).isFalse();
        assertThat(versions.get(1).versionNo()).isEqualTo(2);
        assertThat(versions.get(1).current()).isTrue();
        // v1 明细冻结为 1000，未被 v2 改写。
        assertThat(versions.get(0).segments()).allSatisfy(s ->
                assertThat(s.powerKw()).isEqualTo(1000));
        assertThat(versions.get(1).segments()).allSatisfy(s ->
                assertThat(s.powerKw()).isEqualTo(1200));

        assertThat(planService.getCurrentVersion(FARM, PLAN_DATE).versionNo()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan_version WHERE current_flag = 'Y'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("第三版 expectedVersion 过期（传 1 而当前为 2）：409")
    void staleExpectedVersionConflicts() {
        planService.publish(publishReq("p-a", 1, fullSegments(1000)));
        pinClock(11, 0, 0);
        planService.publish(publishReq("p-b", 2, fullSegments(1100)));
        pinClock(12, 0, 0);
        assertThatThrownBy(() ->
                planService.publish(publishReq("p-c", 1, fullSegments(1200))))
                .isInstanceOf(ApiConflictException.class);
    }

    @Test
    @DisplayName("缺段（仅 95 个时段）：整版 422，失败不占键")
    void missingSegmentRejectedWholeVersion() {
        List<PlanSegmentInput> incomplete = fullSegments(1000).stream()
                .filter(s -> s.segmentIndex() != 95)
                .toList();
        assertThat(incomplete).hasSize(95 * 3);
        assertThatThrownBy(() ->
                planService.publish(publishReq("plan-miss", 1, incomplete)))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("缺段");

        // 无版本落库，键未占用：修正后同键成功。
        PlanPublishResponse resp =
                planService.publish(publishReq("plan-miss", 1, fullSegments(1000)));
        assertThat(resp.versionNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("段内机组重复：整版 422")
    void duplicateTurbineInSegmentRejected() {
        List<PlanSegmentInput> dup = new ArrayList<>(fullSegments(1000));
        dup.add(new PlanSegmentInput(0, "T-01", 900));
        assertThatThrownBy(() -> planService.publish(publishReq("plan-dup", 1, dup)))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("重复");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan_version", Integer.class)).isZero();
    }

    @Test
    @DisplayName("核定出力超过合成装机容量：整版 422")
    void powerAboveRatedRejected() {
        // T-01 额定 2000，第 10 段给 2001。
        List<PlanSegmentInput> over = fullSegmentsWith(10, java.util.Map.of("T-01", 2001), 1000);
        assertThatThrownBy(() -> planService.publish(publishReq("plan-over", 1, over)))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("装机");
    }

    @Test
    @DisplayName("计划含不属于该风场的机组：整版 422")
    void unknownTurbineRejected() {
        List<PlanSegmentInput> bad = new ArrayList<>(fullSegments(1000));
        bad.set(0, new PlanSegmentInput(0, "T-GHOST", 1000));
        assertThatThrownBy(() -> planService.publish(publishReq("plan-ghost", 1, bad)))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("不存在的机组");
    }

    @Test
    @DisplayName("段集合乱序提交：确定性规范化后正常受理，落库明细按段/机组排序且完整")
    void shuffledSegmentsAccepted() {
        List<PlanSegmentInput> shuffled = new ArrayList<>(fullSegments(1000));
        java.util.Collections.reverse(shuffled);
        PlanPublishResponse resp =
                planService.publish(publishReq("plan-shuffle", 1, shuffled));

        PlanVersionResponse v1 = planService.getVersion(FARM, PLAN_DATE, resp.versionNo());
        assertThat(v1.segments()).hasSize(288);
        // 落库按 (segment_index, turbine) 排序：前三条为第 0 段 T-01/T-02/T-03。
        assertThat(v1.segments().get(0).segmentIndex()).isZero();
        assertThat(v1.segments().get(0).turbineCode()).isEqualTo("T-01");
        assertThat(v1.segments().get(2).turbineCode()).isEqualTo("T-03");
        assertThat(v1.segments().get(287).segmentIndex()).isEqualTo(95);
        assertThat(v1.segments()).allSatisfy(s -> assertThat(s.powerKw()).isEqualTo(1000));
    }

    @Test
    @DisplayName("风场不存在：404")
    void farmNotFound() {
        PlanPublishRequest req = new PlanPublishRequest(
                "plan-x", "NO-FARM", PLAN_DATE, 1, localInstant(9, 0, 0), fullSegments(1000));
        assertThatThrownBy(() -> planService.publish(req))
                .isInstanceOf(ApiNotFoundException.class);
    }

    @Test
    @DisplayName("查询不存在的日期/版本号：404；过期版本仍可查询")
    void versionQueryNotFoundButHistoryReadable() {
        assertThatThrownBy(() -> planService.getCurrentVersion(FARM, PLAN_DATE))
                .isInstanceOf(ApiNotFoundException.class);
        planService.publish(publishReq("plan-h1", 1, fullSegments(1000)));
        pinClock(11, 0, 0);
        planService.publish(publishReq("plan-h2", 2, fullSegments(1100)));

        assertThat(planService.getVersion(FARM, PLAN_DATE, 1).current()).isFalse();
        assertThatThrownBy(() -> planService.getVersion(FARM, PLAN_DATE, 3))
                .isInstanceOf(ApiNotFoundException.class);
    }
}
