package com.example.starter.trajectory.testsupport;

import com.example.starter.trajectory.dto.ObstaclePrediction;
import com.example.starter.trajectory.dto.Point;
import com.example.starter.trajectory.dto.RoadBoundary;
import com.example.starter.trajectory.dto.RuleConfig;
import com.example.starter.trajectory.dto.TimedPoint;
import com.example.starter.trajectory.dto.ValidationRequest;
import com.example.starter.trajectory.dto.VehicleState;

import java.math.BigDecimal;
import java.util.List;

/**
 * 测试合成数据构造器：道路为 [0,10]×[0,10] 的矩形多边形。
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static RuleConfig ruleConfig(double maxInterval, double maxSpeed, double maxAccel,
                                        double maxCurvature, double roadTolerance, double safetyDistance) {
        return new RuleConfig(
                BigDecimal.valueOf(maxInterval),
                BigDecimal.valueOf(maxSpeed),
                BigDecimal.valueOf(maxAccel),
                BigDecimal.valueOf(maxCurvature),
                BigDecimal.valueOf(roadTolerance),
                BigDecimal.valueOf(safetyDistance));
    }

    public static RoadBoundary rectangleRoad() {
        return new RoadBoundary(List.of(
                new Point(0, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10)));
    }

    public static TimedPoint tp(double t, double x, double y) {
        return new TimedPoint(t, x, y);
    }

    /** 等速 5 m/s 直线安全轨迹，全程在道路中线 y=5。 */
    public static ValidationRequest safeRequest(String requestId, String ruleVersion,
                                                List<ObstaclePrediction> obstacles) {
        return new ValidationRequest(requestId, ruleVersion,
                new VehicleState(1, 5, 0, 5),
                List.of(tp(0, 1, 5), tp(1, 6, 5), tp(2, 9, 5)),
                obstacles, rectangleRoad());
    }

    /**
     * 多规则失败轨迹（阈值 maxInterval=1、maxSpeed=10、maxAccel=5、maxCurvature=0.1、
     * roadTolerance=0.5、safetyDistance=2 时）：
     * R3 t=1（起步加速度 10m/s²）、R1 t=3（间隔 2s）、R4 t=3（p1-p2-p3 直角弯）、
     * R6 t≈3.429（静止障碍物 obs-1 位于 (13,0)）、R5 t=4（点 (13,-2) 超出道路下边 1m）。
     * 排序后规则编号序列为 R1、R3、R4、R5、R6。
     */
    public static ValidationRequest multiFailureRequest(String requestId, String ruleVersion) {
        ObstaclePrediction stationary = new ObstaclePrediction("obs-1", List.of(
                tp(0, 13, 0), tp(1, 13, 0), tp(2, 13, 0),
                tp(3, 13, 0), tp(4, 13, 0)));
        RoadBoundary wideRoad = new RoadBoundary(List.of(
                new Point(0, -1), new Point(20, -1), new Point(20, 8), new Point(0, 8)));
        return new ValidationRequest(requestId, ruleVersion,
                new VehicleState(1, 5, 0, 0),
                List.of(
                        tp(0, 1, 5),
                        tp(1, 11, 5),
                        tp(3, 13, 5),
                        tp(4, 13, -2)),
                List.of(stationary), wideRoad);
    }
}
