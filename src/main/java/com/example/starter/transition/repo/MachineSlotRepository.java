package com.example.starter.transition.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 纸机占用槽仓储：同一纸机同时刻只允许一张在途改品种单。
 * 依赖唯一索引 (paper_machine, active_flag)（active_flag 为 ACTIVE，释放后置 NULL）。
 */
@Repository
public class MachineSlotRepository {

    private final JdbcTemplate jdbc;

    public MachineSlotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占用纸机；唯一索引冲突表示该纸机已有执行中改品种单。
     *
     * @return true 占用成功；false 纸机忙
     */
    public boolean tryOccupy(String paperMachine, String orderNo) {
        try {
            jdbc.update("""
                            INSERT INTO machine_active_slot (paper_machine, order_no, active_flag)
                            VALUES (?, ?, 'ACTIVE')
                            """,
                    paperMachine, orderNo);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 释放纸机占用（终态后允许同机再次申请）。 */
    public void release(String paperMachine, String orderNo) {
        jdbc.update("""
                        UPDATE machine_active_slot
                        SET active_flag = NULL
                        WHERE paper_machine = ? AND order_no = ? AND active_flag = 'ACTIVE'
                        """,
                paperMachine, orderNo);
    }

    public boolean isOccupied(String paperMachine) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM machine_active_slot WHERE paper_machine = ? AND active_flag = 'ACTIVE'",
                Integer.class, paperMachine);
        return count != null && count > 0;
    }
}
