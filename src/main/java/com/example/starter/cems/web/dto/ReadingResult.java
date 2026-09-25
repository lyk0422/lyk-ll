package com.example.starter.cems.web.dto;

import java.util.List;

/**
 * 读数上报处理结果：
 * late=true 表示归档后迟到数据（仅记迟到事件）；mark/order 为本次联动生成的标记与限排令（可能为 null）。
 */
public record ReadingResult(
        ReadingView reading,
        boolean late,
        MarkView mark,
        OrderView order,
        List<NoticeView> notices) {
}
