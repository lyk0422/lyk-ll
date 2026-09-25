package com.example.starter.pump.testsupport;

import com.example.starter.pump.repo.PumpTaskRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 闸门原子性探测：在一个事务内先插启泵任务，再以非法开度（违反 CHECK）写闸门日志，
 * 用于验证 H2 约束触发时整事务回滚——不允许出现“开泵而闸门未到位”的半成品。
 */
@Component
public class GateAtomicityProbe {

    private final PumpTaskRepository taskRepository;

    public GateAtomicityProbe(PumpTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public void startPumpThenFailGate(Long sequenceId, Long pumpdownId, String stationCode,
                                     LocalDateTime at) {
        Long taskId = taskRepository.insertTask(sequenceId, pumpdownId, stationCode, "START",
                at, "PROBE_START");
        if (taskId == null) {
            throw new IllegalStateException("探测前置：启泵任务应插入成功");
        }
        // 150% 违反 chk_gate_opening，预期触发约束异常并回滚整个事务
        taskRepository.insertGateLog(taskId, stationCode, new BigDecimal("150"), at);
    }
}
