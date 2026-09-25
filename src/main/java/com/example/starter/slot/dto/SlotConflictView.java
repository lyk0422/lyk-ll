package com.example.starter.slot.dto;

import java.util.List;

/**
 * 冲突明细：某一时刻某一周已被其他航司占用。
 *
 * @param slotTime 冲突时刻（HH:mm）
 * @param week     冲突周序号
 * @param holder   当前占有的航司二字码
 */
public record SlotConflictView(String slotTime, int week, String holder) {
}
