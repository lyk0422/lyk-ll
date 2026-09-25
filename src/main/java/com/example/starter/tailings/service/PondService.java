package com.example.starter.tailings.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.repo.PondRepository;

/**
 * 尾矿库基础信息维护：注册尾矿库及合成库容曲线。
 */
@Service
public class PondService {

    private final PondRepository pondRepository;

    public PondService(PondRepository pondRepository) {
        this.pondRepository = pondRepository;
    }

    /**
     * 库容曲线点入参。
     */
    public record CurveInput(BigDecimal waterLevel, BigDecimal capacity) {
    }

    /**
     * 尾矿库视图。
     */
    public record PondView(long pondId, String name, BigDecimal maxFloodLevel,
                           BigDecimal downstreamSafeDischarge, BigDecimal dryBeachMin,
                           BigDecimal phreaticLineMin, List<CurveInput> curve) {
    }

    @Transactional
    public PondView create(String name, BigDecimal maxFloodLevel, BigDecimal downstreamSafeDischarge,
                           BigDecimal dryBeachMin, BigDecimal phreaticLineMin, List<CurveInput> curve) {
        if (curve == null || curve.size() < 2) {
            throw BusinessException.badRequest("库容曲线至少需要两个离散点");
        }
        long pondId = pondRepository.insertPond(name, maxFloodLevel, downstreamSafeDischarge,
                dryBeachMin, phreaticLineMin);
        for (CurveInput point : curve) {
            pondRepository.insertCurvePoint(pondId, point.waterLevel(), point.capacity());
        }
        return get(pondId);
    }

    @Transactional(readOnly = true)
    public PondView get(long pondId) {
        PondRepository.PondRow pond = pondRepository.findPond(pondId)
                .orElseThrow(() -> BusinessException.notFound("尾矿库不存在: " + pondId));
        List<CurveInput> curve = pondRepository.curve(pondId).stream()
                .map(p -> new CurveInput(p.waterLevel(), p.capacity()))
                .toList();
        return new PondView(pond.id(), pond.name(), pond.maxFloodLevel(),
                pond.downstreamSafeDischarge(), pond.dryBeachMin(), pond.phreaticLineMin(), curve);
    }
}
