package com.example.starter.pension.repo;

import com.example.starter.pension.po.InstructionRow;

import java.util.Optional;

/**
 * 支付指令表数据访问：requestId 唯一索引保证同键指令只记录首次结果。
 */
public interface InstructionRepository {

    /**
     * 插入指令首次执行记录；requestId 冲突时由唯一索引拒绝。
     */
    long insert(InstructionRow instruction);

    /** 按指令幂等键查询首次记录。 */
    Optional<InstructionRow> findByRequestId(String requestId);
}
