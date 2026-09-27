package com.example.starter.cutover.dto;

import java.util.List;

/**
 * 依赖阻断明细视图：某被阻断申请及其“首个阻断来源集合”。
 * 传播阻断（间接后继）不新增来源，仍指向其直接失败前置集合。
 *
 * @param sourceRequests 首个阻断来源集合（进入回退/超期的直接前置），按编号排序
 */
public record BlockView(
        String requestId,
        List<String> sourceRequests
) {
}
