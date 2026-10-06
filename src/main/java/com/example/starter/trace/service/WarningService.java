package com.example.starter.trace.service;

import com.example.starter.trace.dto.WarningResponse;
import com.example.starter.trace.repo.WarningRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 蜂场预警查询服务。
 */
@Service
public class WarningService {

    private final WarningRepository warningRepository;

    public WarningService(WarningRepository warningRepository) {
        this.warningRepository = warningRepository;
    }

    /**
     * 查询全部蜂场预警。
     */
    @Transactional(readOnly = true)
    public List<WarningResponse> listWarnings() {
        return warningRepository.findAll().stream()
                .map(w -> new WarningResponse(w.id(), w.apiaryId(), w.apiaryCode(), w.apiaryName(),
                        w.flowerPeriod(), w.suspectedCount(), w.createdAt()))
                .toList();
    }
}
