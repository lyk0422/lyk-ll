package com.example.starter.lot.api;

import java.util.List;

/**
 * 谱系查询结果：直接与传递性上下游批次。
 */
public record GenealogyView(
        String lotId,
        List<String> ancestors,
        List<String> descendants
) {
}
