package com.example.starter.pump.testsupport;

import com.example.starter.pump.repo.PumpTaskRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 独立事务探针：每次调用另起事务，供多线程并发验证唯一索引。
 */
@Component
public class TaskInsertProbe {

    private final PumpTaskRepository taskRepository;

    public TaskInsertProbe(PumpTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /** 在全新独立事务中插入同站同时刻任务；冲突返回 null。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long insertInNewTransaction(Long sequenceId, Long pumpdownId, String stationCode,
                                       LocalDateTime at) {
        return taskRepository.insertTask(sequenceId, pumpdownId, stationCode, "START",
                at, "CONCURRENCY_PROBE");
    }
}
