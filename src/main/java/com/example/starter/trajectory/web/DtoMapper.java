package com.example.starter.trajectory.web;

import com.example.starter.trajectory.domain.ObstaclePrediction;
import com.example.starter.trajectory.domain.PredictedPoint;
import com.example.starter.trajectory.domain.RoadBoundary;
import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleStatus;
import com.example.starter.trajectory.domain.TrajectoryPoint;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Vec;
import com.example.starter.trajectory.domain.VehicleState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Web DTO 与领域对象之间的转换。
 */
@Component
public class DtoMapper {

    public RuleConfig toDomain(RuleConfigRequest dto) {
        return new RuleConfig(
                dto.ruleVersion(),
                dto.maxTimeGapSeconds(),
                dto.maxSpeed(),
                dto.maxAcceleration(),
                dto.maxCurvature(),
                dto.roadTolerance(),
                dto.collisionDistance(),
                RuleStatus.DRAFT);
    }

    public ValidationRequest toDomain(ValidationRequestDto dto) {
        return new ValidationRequest(
                dto.requestId(),
                dto.ruleVersion(),
                toDomain(dto.vehicle()),
                dto.trajectory().stream().map(this::toDomain).toList(),
                dto.obstacles().stream().map(this::toDomain).toList(),
                new RoadBoundary(dto.road().stream().map(this::toDomain).toList()));
    }

    private VehicleState toDomain(VehicleStateDto dto) {
        return new VehicleState(toDomain(dto.position()), dto.heading(), dto.speed());
    }

    private TrajectoryPoint toDomain(TrajectoryPointDto dto) {
        return new TrajectoryPoint(dto.t(), toDomain(dto.position()), dto.heading());
    }

    private ObstaclePrediction toDomain(ObstaclePredictionDto dto) {
        List<PredictedPoint> points = dto.points().stream()
                .map(p -> new PredictedPoint(p.t(), toDomain(p.position())))
                .toList();
        return new ObstaclePrediction(dto.objectId(), points);
    }

    private Vec toDomain(PointDto dto) {
        return new Vec(dto.x(), dto.y());
    }
}
