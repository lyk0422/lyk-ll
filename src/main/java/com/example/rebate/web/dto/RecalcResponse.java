package com.example.rebate.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 复算结果：以存储输入重新计算，并与首次落库值逐项比对。
 *
 * @param consistent 全部项目复算值是否与落库值一致
 */
public record RecalcResponse(
        String subject,
        boolean consistent,
        List<Map<String, Object>> items) {
}
