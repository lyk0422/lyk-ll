package com.example.starter.trajectory.engine;

import com.example.starter.trajectory.dto.FailureItem;
import com.example.starter.trajectory.dto.ObstaclePrediction;
import com.example.starter.trajectory.dto.Point;
import com.example.starter.trajectory.dto.RoadBoundary;
import com.example.starter.trajectory.dto.RuleConfig;
import com.example.starter.trajectory.dto.TimedPoint;
import com.example.starter.trajectory.dto.ValidationRequest;
import com.example.starter.trajectory.dto.VehicleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全规则引擎单元测试：时间断层、速度/加速度/曲率边界、道路边界、
 * 恰好安全距离碰撞、首次证据保留与确定性排序。
 */
class SafetyValidationEngineTest {

    private final SafetyValidationEngine engine = new SafetyValidationEngine();

    private static RuleConfig config(double maxInterval, double maxSpeed, double maxAccel,
                                     double maxCurvature, double roadTolerance, double safetyDistance) {
        return new RuleConfig(
                BigDecimal.valueOf(maxInterval),
                BigDecimal.valueOf(maxSpeed),
                BigDecimal.valueOf(maxAccel),
                BigDecimal.valueOf(maxCurvature),
                BigDecimal.valueOf(roadTolerance),
                BigDecimal.valueOf(safetyDistance));
    }

    private static RoadBoundary rectangleRoad() {
        return new RoadBoundary(List.of(
                new Point(0, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10)));
    }

    private static ValidationRequest request(List<TimedPoint> trajectory, List<ObstaclePrediction> obstacles) {
        return new ValidationRequest("req-1", "v1",
                new VehicleState(0, 0, 0, 0), trajectory, obstacles, rectangleRoad());
    }

    private static TimedPoint tp(double t, double x, double y) {
        return new TimedPoint(t, x, y);
    }

    private FailureItem find(List<FailureItem> failures, String ruleId) {
        return failures.stream().filter(f -> f.ruleId().equals(ruleId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("安全轨迹：等速直线、边界内行驶、无障碍物，不产生任何失败项")
    void safeTrajectoryProducesNoFailures() {
        RuleConfig config = config(2.0, 10, 5, 1.0, 0.5, 2.0);
        ValidationRequest request = request(List.of(
                tp(0, 0, 5), tp(1, 5, 5), tp(2, 10, 5)), null);

        List<FailureItem> failures = engine.validate(config, request);

        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("时间断层：相邻间隔超过最大阈值即失败，证据为相邻两点")
    void timeGapExceedingThresholdFails() {
        RuleConfig config = config(2.0, 20, 20, 10, 0.5, 2.0);
        ValidationRequest request = request(List.of(
                tp(0, 0, 5), tp(1, 5, 5), tp(4, 8, 5)), null);

        List<FailureItem> failures = engine.validate(config, request);

        FailureItem r1 = find(failures, "R1");
        assertThat(r1.firstTriggerTime()).isEqualTo(4.0);
        assertThat(r1.threshold()).isEqualTo(2.0);
        assertThat(r1.actualValue()).isEqualTo(3.0);
        assertThat(r1.evidence().vehiclePoints()).containsExactly(tp(1, 5, 5), tp(4, 8, 5));
    }

    @Test
    @DisplayName("时间严格递增：间隔恰好等于阈值合法；间隔为 0（非递增）失败")
    void timeStrictlyIncreasingAndIntervalBoundary() {
        RuleConfig config = config(2.0, 20, 20, 10, 0.5, 2.0);

        List<FailureItem> atBoundary = engine.validate(config, request(List.of(
                tp(0, 0, 5), tp(2, 5, 5), tp(4, 10, 5)), null));
        assertThat(atBoundary).noneMatch(f -> f.ruleId().equals("R1"));

        List<FailureItem> nonIncreasing = engine.validate(config, request(List.of(
                tp(0, 0, 5), tp(1, 5, 5), tp(1, 6, 5)), null));
        FailureItem r1 = find(nonIncreasing, "R1");
        assertThat(r1.firstTriggerTime()).isEqualTo(1.0);
        assertThat(r1.actualValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("速度边界：分段速度恰好等于阈值合法，严格超出失败并保留首次证据")
    void speedBoundaryAndFirstEvidenceKept() {
        RuleConfig atLimit = config(2.0, 10, 20, 10, 0.5, 2.0);
        ValidationRequest boundary = request(List.of(
                tp(0, 0, 5), tp(1, 10, 5), tp(2, 20, 5)), null);
        assertThat(engine.validate(atLimit, boundary)).noneMatch(f -> f.ruleId().equals("R2"));

        RuleConfig strict = config(2.0, 9, 20, 10, 0.5, 2.0);
        // 第一段超速到 10（首次，较轻），第二段超速到 12（更严重，不得替换首次证据）
        ValidationRequest over = request(List.of(
                tp(0, 0, 5), tp(1, 10, 5), tp(2, 22, 5)), null);
        List<FailureItem> failures = engine.validate(strict, over);

        FailureItem r2 = find(failures, "R2");
        assertThat(r2.firstTriggerTime()).isEqualTo(1.0);
        assertThat(r2.actualValue()).isEqualTo(10.0);
        assertThat(r2.threshold()).isEqualTo(9.0);
        assertThat(r2.evidence().vehiclePoints()).containsExactly(tp(0, 0, 5), tp(1, 10, 5));
    }

    @Test
    @DisplayName("初始速度超限时在首个轨迹点时间触发 R2")
    void initialSpeedOverLimitFailsAtFirstPoint() {
        RuleConfig config = config(2.0, 9, 20, 10, 0.5, 2.0);
        ValidationRequest request = new ValidationRequest("req-1", "v1",
                new VehicleState(0, 0, 0, 10),
                List.of(tp(0, 0, 5), tp(1, 5, 5)), null, rectangleRoad());

        FailureItem r2 = find(engine.validate(config, request), "R2");

        assertThat(r2.firstTriggerTime()).isZero();
        assertThat(r2.actualValue()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("加速度边界：|a| 恰好等于阈值合法，严格超出失败")
    void accelerationBoundary() {
        RuleConfig atLimit = config(2.0, 30, 10, 10, 0.5, 2.0);
        // 初始速度 10，第一段末速度 20：a=(20-10)/1=10，恰好阈值
        ValidationRequest boundary = new ValidationRequest("req-1", "v1",
                new VehicleState(0, 0, 0, 10),
                List.of(tp(0, 0, 5), tp(1, 20, 5)), null, rectangleRoad());
        assertThat(engine.validate(atLimit, boundary)).noneMatch(f -> f.ruleId().equals("R3"));

        RuleConfig strict = config(2.0, 30, 9, 10, 0.5, 2.0);
        FailureItem r3 = find(engine.validate(strict, boundary), "R3");
        assertThat(r3.firstTriggerTime()).isEqualTo(1.0);
        assertThat(r3.actualValue()).isEqualTo(10.0);
        assertThat(r3.threshold()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("曲率边界：半径 10 圆弧上的三点曲率恰好 0.1 合法，阈值 0.09 失败")
    void curvatureBoundaryOnCircle() {
        List<TimedPoint> arc = List.of(
                tp(0, 10, 0),
                tp(1, 7.0710678118654755, 7.0710678118654755),
                tp(2, 0, 10));

        RuleConfig atLimit = config(2.0, 30, 30, 0.1, 0.5, 2.0);
        assertThat(engine.validate(atLimit, request(arc, null)))
                .noneMatch(f -> f.ruleId().equals("R4"));

        RuleConfig strict = config(2.0, 30, 30, 0.09, 0.5, 2.0);
        FailureItem r4 = find(engine.validate(strict, request(arc, null)), "R4");
        assertThat(r4.firstTriggerTime()).isEqualTo(1.0);
        assertThat(r4.actualValue()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(r4.threshold()).isEqualTo(0.09);
        assertThat(r4.evidence().vehiclePoints()).hasSize(3);
    }

    @Test
    @DisplayName("道路边界：点在多边形边上合法；容差内的外部点合法；超出容差失败")
    void roadBoundaryEdgeToleranceAndViolation() {
        RuleConfig config = config(2.0, 30, 30, 10, 0.5, 2.0);

        // 点恰在下边 y=0 上
        ValidationRequest onEdge = request(List.of(tp(0, 5, 0), tp(1, 6, 0)), null);
        assertThat(engine.validate(config, onEdge)).noneMatch(f -> f.ruleId().equals("R5"));

        // 外部 0.2 米，容差 0.5，仍合法
        ValidationRequest withinTolerance = request(
                List.of(tp(0, 5, 5), tp(1, 5, -0.2)), null);
        assertThat(engine.validate(config, withinTolerance)).noneMatch(f -> f.ruleId().equals("R5"));

        // 外部 1 米，超出容差，失败且只保留首个越界点
        ValidationRequest outside = request(
                List.of(tp(0, 5, 5), tp(1, 5, -1), tp(2, 5, -2)), null);
        FailureItem r5 = find(engine.validate(config, outside), "R5");
        assertThat(r5.firstTriggerTime()).isEqualTo(1.0);
        assertThat(r5.actualValue()).isEqualTo(1.0);
        assertThat(r5.threshold()).isEqualTo(0.5);
        assertThat(r5.evidence().vehiclePoints()).containsExactly(tp(1, 5, -1));
    }

    @Test
    @DisplayName("碰撞：车与静止障碍物距离恰好等于安全距离即触发，首次时间为接触中点")
    void collisionAtExactlySafetyDistanceFails() {
        RuleConfig config = config(2.0, 30, 30, 10, 0.5, 2.0);
        ObstaclePrediction stationary = new ObstaclePrediction("obs-1", List.of(
                tp(0, 5, 2), tp(10, 5, 2)));
        ValidationRequest request = request(List.of(
                tp(0, 0, 0), tp(10, 10, 0)), List.of(stationary));

        List<FailureItem> failures = engine.validate(config, request);

        FailureItem r6 = find(failures, "R6");
        assertThat(r6.objectId()).isEqualTo("obs-1");
        assertThat(r6.threshold()).isEqualTo(2.0);
        assertThat(r6.actualValue()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r6.firstTriggerTime()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r6.evidence().obstaclePoints()).hasSize(2);
        assertThat(r6.evidence().vehiclePoints()).hasSize(2);
    }

    @Test
    @DisplayName("碰撞：全程最小距离严格大于安全距离时不触发")
    void noCollisionWhenAlwaysFartherThanSafetyDistance() {
        RuleConfig config = config(2.0, 30, 30, 10, 0.5, 1.99);
        ObstaclePrediction stationary = new ObstaclePrediction("obs-1", List.of(
                tp(0, 5, 2), tp(10, 5, 2)));
        ValidationRequest request = request(List.of(
                tp(0, 0, 0), tp(10, 10, 0)), List.of(stationary));

        assertThat(engine.validate(config, request)).noneMatch(f -> f.ruleId().equals("R6"));
    }

    @Test
    @DisplayName("碰撞：移动障碍物横穿静止车辆，在安全距离边界时刻触发，证据含相邻预测点")
    void collisionWithMovingObstacleAtSafetyDistanceBoundary() {
        RuleConfig config = config(2.0, 30, 30, 10, 0.5, 2.0);
        ObstaclePrediction crossing = new ObstaclePrediction("obs-mov", List.of(
                tp(0, 10, 0), tp(10, -10, 0)));
        ValidationRequest request = request(List.of(
                tp(0, 0, 0), tp(10, 0, 0)), List.of(crossing));

        FailureItem r6 = find(engine.validate(config, request), "R6");

        // 障碍物 x=10-2t，距车辆 |10-2t|，等于 2 时 t=4
        assertThat(r6.objectId()).isEqualTo("obs-mov");
        assertThat(r6.firstTriggerTime()).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r6.actualValue()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r6.threshold()).isEqualTo(2.0);
        assertThat(r6.evidence().obstaclePoints()).containsExactly(tp(0, 10, 0), tp(10, -10, 0));
    }

    @Test
    @DisplayName("碰撞：时间域仅在单个时刻相接（障碍物预测在轨迹起点结束）不越界且按该时刻判定")
    void collisionWhenTimeRangesMeetAtSingleInstant() {
        RuleConfig config = config(2.0, 30, 30, 10, 0.5, 2.0);
        ObstaclePrediction endingAtStart = new ObstaclePrediction("obs-edge", List.of(
                tp(-1, 2, 0), tp(0, 1, 0)));
        ValidationRequest request = request(List.of(
                tp(0, 0, 0), tp(1, 0, 0)), List.of(endingAtStart));

        FailureItem r6 = find(engine.validate(config, request), "R6");
        assertThat(r6.firstTriggerTime()).isZero();
        assertThat(r6.actualValue()).isEqualTo(1.0);
        assertThat(r6.objectId()).isEqualTo("obs-edge");
    }

    @Test
    @DisplayName("确定性：多障碍物失败项按规则编号、首次触发时间、对象 ID 排序，重复执行结果一致")
    void deterministicOrderingAcrossRepeatedRuns() {
        RuleConfig config = config(2.0, 9, 9, 0.09, 0.5, 2.0);
        // 第一段速度 10 触发 R2；第二段急减速（a=-9.5）触发 R3；
        // 障碍物 B 在 x=5 处 t=0.5 接触，障碍物 A 在 x=6 处 t=0.6 接触；
        // 列表中故意把 B 放在 A 前面，验证最终不按输入顺序排列
        ObstaclePrediction earlier = new ObstaclePrediction("obs-B", List.of(
                tp(0, 5, 2), tp(10, 5, 2)));
        ObstaclePrediction later = new ObstaclePrediction("obs-A", List.of(
                tp(0, 6, 2), tp(10, 6, 2)));
        ValidationRequest request = request(List.of(
                tp(0, 0, 0), tp(1, 10, 0), tp(2, 10.5, 0)), List.of(earlier, later));

        List<FailureItem> firstRun = engine.validate(config, request);
        List<FailureItem> secondRun = engine.validate(config, request);

        assertThat(firstRun).isEqualTo(secondRun);
        assertThat(firstRun).extracting(FailureItem::ruleId).containsExactly("R2", "R3", "R6", "R6");
        List<FailureItem> collisions = firstRun.stream().filter(f -> f.ruleId().equals("R6")).toList();
        assertThat(collisions).extracting(FailureItem::objectId).containsExactly("obs-B", "obs-A");
        assertThat(collisions.get(0).firstTriggerTime()).isLessThan(collisions.get(1).firstTriggerTime());
    }
}
