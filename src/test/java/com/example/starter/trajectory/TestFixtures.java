package com.example.starter.trajectory;

import com.example.starter.trajectory.domain.ObstaclePrediction;
import com.example.starter.trajectory.domain.PredictedPoint;
import com.example.starter.trajectory.domain.RoadBoundary;
import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleStatus;
import com.example.starter.trajectory.domain.TrajectoryPoint;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Vec;
import com.example.starter.trajectory.domain.VehicleState;

import java.util.List;

/**
 * 测试数据构造工具，全部使用合成数据。
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static RuleConfig rules(String version) {
        return new RuleConfig(version, 1.0, 10.0, 5.0, 0.1, 0.2, 2.0, RuleStatus.PUBLISHED);
    }

    public static VehicleState vehicleAt(double x, double y) {
        return new VehicleState(new Vec(x, y), 0.0, 0.0);
    }

    public static VehicleState vehicleAt(double x, double y, double speed) {
        return new VehicleState(new Vec(x, y), 0.0, speed);
    }

    public static TrajectoryPoint point(double t, double x, double y) {
        return new TrajectoryPoint(t, new Vec(x, y), 0.0);
    }

    public static PredictedPoint predicted(double t, double x, double y) {
        return new PredictedPoint(t, new Vec(x, y));
    }

    /**
     * 边长 100 米的正方形道路，范围 (0,0)-(100,100)。
     */
    public static RoadBoundary squareRoad() {
        return new RoadBoundary(List.of(
                new Vec(0, 0),
                new Vec(100, 0),
                new Vec(100, 100),
                new Vec(0, 100)));
    }

    public static ObstaclePrediction obstacle(String id, List<PredictedPoint> points) {
        return new ObstaclePrediction(id, points);
    }

    public static ValidationRequest request(String requestId,
                                            String ruleVersion,
                                            VehicleState vehicle,
                                            List<TrajectoryPoint> trajectory,
                                            List<ObstaclePrediction> obstacles,
                                            RoadBoundary road) {
        return new ValidationRequest(requestId, ruleVersion, vehicle, trajectory, obstacles, road);
    }
}
