package com.example.starter.deicing.engine;

import com.example.starter.deicing.po.AssignmentPO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 半开区间排定引擎单元测试：不依赖 Spring 与数据库。
 */
class IntervalSchedulingEngineTest {

    private static final List<String> BAYS = List.of("B1", "B2");
    private static final List<String> TRUCKS = List.of("T1", "T2");
    private static final long DURATION = 20L * 60_000L;

    private final IntervalSchedulingEngine engine = new IntervalSchedulingEngine();

    private AssignmentPO occupied(String requestId, String bay, String truck, long start, long end) {
        AssignmentPO po = new AssignmentPO();
        po.setRequestId(requestId);
        po.setRoundNo(1);
        po.setBayId(bay);
        po.setTruckId(truck);
        po.setStartTs(start);
        po.setEndTs(end);
        po.setState(com.example.starter.deicing.model.AssignmentState.RESERVED);
        return po;
    }

    @Test
    void noExistingOccupancyStartsAtEarliest() {
        SchedulingEngine.SchedulePlan plan = engine.plan(
                "R1", 1, 2, DURATION, 100L, BAYS, TRUCKS, List.of());

        assertThat(plan.startTs()).isEqualTo(100L);
        assertThat(plan.endTs()).isEqualTo(100L + DURATION);
        assertThat(plan.assignments()).hasSize(2);
        assertThat(plan.assignments()).extracting(AssignmentPO::getBayId)
                .containsExactly("B1", "B2");
        assertThat(plan.assignments()).extracting(AssignmentPO::getTruckId)
                .containsExactly("T1", "T2");
    }

    @Test
    void touchingEndpointDoesNotConflict() {
        // 既有占用 [100, 100+duration)；新作业从终点 100+duration 开始应被允许（端点相接）
        long start = 100L;
        long end = start + DURATION;
        List<AssignmentPO> existing = List.of(
                occupied("OLD", "B1", "T1", start, end),
                occupied("OLD", "B2", "T2", start, end));

        SchedulingEngine.SchedulePlan plan = engine.plan(
                "R2", 1, 2, DURATION, start, BAYS, TRUCKS, existing);

        assertThat(plan.startTs()).isEqualTo(end);
        assertThat(plan.endTs()).isEqualTo(end + DURATION);
    }

    @Test
    void oneFreeBayAndTruckStartsImmediately() {
        // 仅 B1/T1 在 [100,end) 忙碌，B2/T2 空闲：需要 1 个车位时应在 100 立即开始并选 B2/T2
        long start = 100L;
        long end = start + DURATION;
        List<AssignmentPO> existing = List.of(
                occupied("OLD", "B1", "T1", start, end));

        SchedulingEngine.SchedulePlan plan = engine.plan(
                "R2", 1, 1, DURATION, start, BAYS, TRUCKS, existing);

        assertThat(plan.startTs()).isEqualTo(100L);
        assertThat(plan.assignments().get(0).getBayId()).isEqualTo("B2");
        assertThat(plan.assignments().get(0).getTruckId()).isEqualTo("T2");
    }

    @Test
    void allResourcesBusyPushesToNearestEnd() {
        // 两个车位在 [100,busyEnd) 均被占，需要 2 个车位的新作业必须等到 busyEnd
        long start = 100L;
        long busyEnd = start + 300L * 60_000L;
        List<AssignmentPO> existing = List.of(
                occupied("A", "B1", "T1", start, busyEnd),
                occupied("A", "B2", "T2", start, busyEnd));

        SchedulingEngine.SchedulePlan plan = engine.plan(
                "R2", 1, 2, DURATION, start, BAYS, TRUCKS, existing);

        assertThat(plan.startTs()).isEqualTo(busyEnd);
    }

    @Test
    void truckIsIndependentResourceFromBay() {
        // 两个车位都空闲，但两辆车都在服务别处：必须等到车辆释放
        long start = 100L;
        long busyEnd = start + 60L * 60_000L;
        List<AssignmentPO> existing = List.of(
                occupied("A", "BX", "T1", start, busyEnd),
                occupied("A", "BX", "T2", start, busyEnd));

        SchedulingEngine.SchedulePlan plan = engine.plan(
                "R2", 1, 2, DURATION, start, BAYS, TRUCKS, existing);

        assertThat(plan.startTs()).isEqualTo(busyEnd);
    }

    @Test
    void rejectsWhenCapacityExceedsResources() {
        assertThatThrownBy(() -> engine.plan(
                "R3", 1, 3, DURATION, 0L, BAYS, TRUCKS, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
