package com.example.starter.web.dto;

import java.util.List;

/**
 * 分装响应：样本最新视图与新生成的 aliquot 视图。
 */
public record SplitResponse(
        boolean replayed,
        SpecimenView specimen,
        List<AliquotView> aliquots) {
}
