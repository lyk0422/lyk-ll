package com.example.starter.trajectory.engine.checker;

import com.example.starter.trajectory.domain.TrajectoryPoint;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Vec;

import java.util.List;

/**
 * 将车辆初始状态（t=0）与规划轨迹点合并为统一采样序列。
 * 采样点 s0 为车辆初始状态，s1..sn 为轨迹点。
 */
final class SampledPath {

    final double[] times;
    final Vec[] positions;
    final double[] headings;

    private SampledPath(double[] times, Vec[] positions, double[] headings) {
        this.times = times;
        this.positions = positions;
        this.headings = headings;
    }

    static SampledPath of(ValidationRequest request) {
        List<TrajectoryPoint> trajectory = request.trajectory();
        int n = trajectory.size() + 1;
        double[] times = new double[n];
        Vec[] positions = new Vec[n];
        double[] headings = new double[n];
        times[0] = 0.0;
        positions[0] = request.vehicle().position();
        headings[0] = request.vehicle().heading();
        for (int i = 1; i < n; i++) {
            TrajectoryPoint p = trajectory.get(i - 1);
            times[i] = p.t();
            positions[i] = p.position();
            headings[i] = p.heading();
        }
        return new SampledPath(times, positions, headings);
    }

    /**
     * 相邻采样点间的平均速度，单位米/秒；时间间隔非正时返回 NaN 由调用方处理。
     */
    double segmentSpeed(int i) {
        double dt = times[i] - times[i - 1];
        if (!(dt > 0)) {
            return Double.NaN;
        }
        return positions[i - 1].distanceTo(positions[i]) / dt;
    }
}
