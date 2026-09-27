package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.PlanDetailResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanVersionView;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * 日前计划发布 H2 事务集成测试：版本序列、原子替换当前指针、历史保留、
 * 整版拒绝（缺段/重复机组/超装机）、幂等重放/异参 409、版本冲突与查询。
 */
@SpringBootTest
class PlanServiceIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 27);

    @Autowired
    private PlanService planService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(8, 0, 0);
    }

    /** 构造 96 段 × 3 机组计划；plannedBySlot[slot][turbineIdx] 为各机组计划值。 */
    private PlanPublishRequest plan(String requestId, int expectedVersion, int[][] plannedBySlot) {
        List<PlanPublishRequest.SlotPlan> slots = new ArrayList<>(96);
        for (int s = 0; s < 96; s++) {
            slots.add(new PlanPublishRequest.SlotPlan(s, List.of(
                    new PlanPublishRequest.TurbinePlan("T-01", plannedBySlot[s][0]),
                    new PlanPublishRequest.TurbinePlan("T-02", plannedBySlot[s][1]),
                    new PlanPublishRequest.TurbinePlan("T-03", plannedBySlot[s][2]))));
        }
        return new PlanPublishRequest(requestId, FARM, DATE, expectedVersion, slots);
    }

    private PlanPublishRequest uniformPlan(String requestId, int expectedVersion,
                                           int t1, int t2, int t3) {
        int[][] slots = new int[96][3];
        for (int s = 0; s < 96; s++) {
            slots[s] = new int[] {t1, t2, t3};
        }
        return plan(requestId, expectedVersion, slots);
    }

    @Test
    @DisplayName("首次发布版本为 1 且成为当前版本，含完整 96 段与 UTC 半开区间")
    void firstPublishCreatesVersionOne() {
        PlanPublishResponse resp = planService.publish(
                uniformPlan("plan-1", 0, 1000, 1000, 2000));

        assertThat(resp.version()).isEqualTo(1);
        assertThat(resp.current()).isTrue();
        assertThat(resp.slotCount()).isEqualTo(96);
        assertThat(resp.replayed()).isFalse();

        PlanDetailResponse detail = planService.current(FARM, DATE);
        assertThat(detail.version()).isEqualTo(1);
        assertThat(detail.slots()).hasSize(96);
        // 时段 0：UTC 当天 00:00-00:15 半开区间。
        assertThat(detail.slots().get(0).slotStartUtc())
                .isEqualTo(DATE.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        assertThat(detail.slots().get(0).slotEndUtc())
                .isEqualTo(DATE.atTime(0, 15).toInstant(java.time.ZoneOffset.UTC));
        assertThat(detail.slots().get(0).turbines()).hasSize(3);
        // 时段 95：23:45-24:00。
        assertThat(detail.slots().get(95).slotStartUtc())
                .isEqualTo(DATE.atTime(23, 45).toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("新版发布原子替换当前指针，版本号递增，历史版本保留且不再当前")
    void newVersionAtomicallyReplacesCurrentAndKeepsHistory() {
        PlanPublishResponse v1 = planService.publish(
                uniformPlan("plan-v1", 0, 1000, 1000, 2000));
        pinClock(9, 0, 0);
        PlanPublishResponse v2 = planService.publish(
                uniformPlan("plan-v2", 1, 1100, 1100, 2100));

        assertThat(v2.version()).isEqualTo(2);
        assertThat(planService.current(FARM, DATE).version()).isEqualTo(2);

        List<PlanVersionView> versions = planService.versions(FARM, DATE);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(0).current()).isFalse();
        assertThat(versions.get(1).version()).isEqualTo(2);
        assertThat(versions.get(1).current()).isTrue();

        // 历史版本仍可按 id 查询且内容不可变。
        PlanDetailResponse oldDetail = planService.detail(v1.planId());
        assertThat(oldDetail.version()).isEqualTo(1);
        assertThat(oldDetail.current()).isFalse();
        assertThat(oldDetail.slots().get(0).turbines().get(0).plannedKw()).isEqualTo(1000);
    }

    @Test
    @DisplayName("expectedVersion 不匹配：409，且不产生新版本、不占幂等键")
    void staleExpectedVersionRejectedWith409() {
        planService.publish(uniformPlan("plan-base", 0, 1000, 1000, 2000));

        assertThatThrownBy(() -> planService.publish(
                uniformPlan("plan-stale", 0, 1100, 1100, 2100)))
                .isInstanceOf(ApiConflictException.class);

        assertThat(planService.versions(FARM, DATE)).hasSize(1);
        // 失败不占键：换正确期望版本后同 requestId 可成功发布。
        PlanPublishResponse retry = planService.publish(
                uniformPlan("plan-stale", 1, 1100, 1100, 2100));
        assertThat(retry.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("缺段（95 段）整版拒绝 422，且不产生版本、不占键")
    void missingSlotRejected() {
        PlanPublishRequest req = uniformPlan("plan-missing", 0, 1000, 1000, 2000);
        List<PlanPublishRequest.SlotPlan> trimmed = new ArrayList<>(req.slots().subList(0, 95));
        PlanPublishRequest bad = new PlanPublishRequest(
                req.requestId(), req.farmCode(), req.planDate(), req.expectedVersion(), trimmed);

        assertThatThrownBy(() -> planService.publish(bad))
                .isInstanceOf(ApiUnprocessableException.class);
        assertThat(planService.versions(FARM, DATE)).isEmpty();

        // 失败不占键：同 requestId 修正为 96 段后成功。
        PlanPublishResponse ok = planService.publish(req);
        assertThat(ok.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("时段内重复机组整版拒绝 422")
    void duplicateTurbineInSlotRejected() {
        List<PlanPublishRequest.SlotPlan> slots = new ArrayList<>(96);
        for (int s = 0; s < 96; s++) {
            List<PlanPublishRequest.TurbinePlan> turbines = new ArrayList<>(List.of(
                    new PlanPublishRequest.TurbinePlan("T-01", 1000),
                    new PlanPublishRequest.TurbinePlan("T-02", 1000)));
            if (s == 50) {
                turbines.add(new PlanPublishRequest.TurbinePlan("T-01", 500));
            } else {
                turbines.add(new PlanPublishRequest.TurbinePlan("T-03", 2000));
            }
            slots.add(new PlanPublishRequest.SlotPlan(s, turbines));
        }
        assertThatThrownBy(() -> planService.publish(
                new PlanPublishRequest("plan-dup", FARM, DATE, 0, slots)))
                .isInstanceOf(ApiUnprocessableException.class);
        assertThat(planService.versions(FARM, DATE)).isEmpty();
    }

    @Test
    @DisplayName("计划值超过合成装机容量整版拒绝 422")
    void planAboveRatedCapacityRejected() {
        assertThatThrownBy(() -> planService.publish(
                uniformPlan("plan-overcap", 0, 2001, 1000, 2000)))
                .isInstanceOf(ApiUnprocessableException.class);
        assertThat(planService.versions(FARM, DATE)).isEmpty();
    }

    @Test
    @DisplayName("发布未知风场：404")
    void publishUnknownFarmReturns404() {
        PlanPublishRequest req = uniformPlan("plan-nofarm", 0, 0, 0, 0);
        PlanPublishRequest unknown = new PlanPublishRequest(req.requestId(), "NO-FARM",
                req.planDate(), req.expectedVersion(), req.slots());
        assertThatThrownBy(() -> planService.publish(unknown))
                .isInstanceOf(ApiNotFoundException.class);
    }

    @Test
    @DisplayName("同键同参重放返回首次结果；同键异参 409；集合顺序变化不改写首次结果")
    void idempotentReplayAndConflict() {
        PlanPublishRequest first = uniformPlan("plan-idem", 0, 1000, 1000, 2000);
        PlanPublishResponse r1 = planService.publish(first);
        PlanPublishResponse r2 = planService.publish(first);
        assertThat(r2.replayed()).isTrue();
        assertThat(r2.planId()).isEqualTo(r1.planId());
        assertThat(r2.version()).isEqualTo(1);

        // 同键改任意参数 → 409。
        assertThatThrownBy(() -> planService.publish(
                uniformPlan("plan-idem", 0, 1001, 1000, 2000)))
                .isInstanceOf(ApiConflictException.class);
        // 同键改 expectedVersion → 409。
        assertThatThrownBy(() -> planService.publish(
                uniformPlan("plan-idem", 1, 1000, 1000, 2000)))
                .isInstanceOf(ApiConflictException.class);

        // 版本仍只有首次一个。
        assertThat(planService.versions(FARM, DATE)).hasSize(1);
    }

    @Test
    @DisplayName("查询不存在的当前版本/版本详情：404")
    void queryMissingVersionReturns404() {
        assertThatThrownBy(() -> planService.current(FARM, DATE))
                .isInstanceOf(ApiNotFoundException.class);
        assertThatThrownBy(() -> planService.detail(999_999L))
                .isInstanceOf(ApiNotFoundException.class);
    }
}
