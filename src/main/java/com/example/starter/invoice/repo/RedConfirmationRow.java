package com.example.starter.invoice.repo;

import java.time.LocalDateTime;

import com.example.starter.invoice.domain.Opinion;
import com.example.starter.invoice.domain.Party;

/**
 * 红字信息表确认轨迹记录。
 *
 * @param id           主键
 * @param infoTableId  所属信息表主键
 * @param party        确认方
 * @param opinion      确认意见
 * @param comment      确认备注，可空
 * @param confirmedAt  确认时间
 */
public record RedConfirmationRow(
        long id,
        long infoTableId,
        Party party,
        Opinion opinion,
        String comment,
        LocalDateTime confirmedAt) {
}
