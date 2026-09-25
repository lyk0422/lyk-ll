package com.example.starter.pump.service;

import com.example.starter.pump.domain.PumpdownEvent;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.LevelReadingRepository;
import com.example.starter.pump.repo.MissContactOrderRepository;
import com.example.starter.pump.repo.PumpTaskRepository;
import com.example.starter.pump.repo.PumpdownEventRepository;
import com.example.starter.pump.web.dto.ReplayResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * 台账复算：水位曲线、启停序列、失联派单台账均可随时从持久化数据重新计算。
 */
@Service
public class ReplayService {

    private final PumpdownEventRepository eventRepository;
    private final LevelReadingRepository levelRepository;
    private final PumpTaskRepository taskRepository;
    private final MissContactOrderRepository missContactRepository;
    private final ObjectMapper objectMapper;

    public ReplayService(PumpdownEventRepository eventRepository,
                         LevelReadingRepository levelRepository,
                         PumpTaskRepository taskRepository,
                         MissContactOrderRepository missContactRepository,
                         ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.levelRepository = levelRepository;
        this.taskRepository = taskRepository;
        this.missContactRepository = missContactRepository;
        this.objectMapper = objectMapper;
    }

    /** 复算整个预降事件台账。 */
    public ReplayResult replay(Long pumpdownId) {
        PumpdownEvent event = eventRepository.findById(pumpdownId)
                .orElseThrow(() -> ApiException.unprocessable("PUMPDOWN_NOT_FOUND")
                        .raise("预降事件不存在: " + pumpdownId));
        return new ReplayResult(
                event.id(),
                event.zoneCode(),
                event.status(),
                event.startedAt(),
                event.archivedAt(),
                levelRepository.loadCurve(event.id()),
                taskRepository.loadTasks(event.id()),
                missContactRepository.loadOrders(event.id()));
    }

    /** 将复算台账序列化为复盘摘要JSON。 */
    public String toSummaryJson(ReplayResult replay) {
        try {
            return objectMapper.writeValueAsString(replay);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("复盘摘要序列化失败", e);
        }
    }
}
