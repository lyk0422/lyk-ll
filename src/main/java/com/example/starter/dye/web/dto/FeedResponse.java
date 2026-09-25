package com.example.starter.dye.web.dto;

import java.util.List;

/**
 * 投料结果：accepted=false 时整批拒绝（422），库存无任何扣减。
 */
public record FeedResponse(
        String requestId,
        String runNo,
        boolean accepted,
        String message,
        List<WeighResult> weighings) {
}
