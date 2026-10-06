package com.example.starter.web.dto;

import java.util.List;

import com.example.starter.domain.DirectiveRevision;
import com.example.starter.domain.PowerDirective;

/**
 * 发射机功率状态视图：当前指令、全部轨迹与未结事件数，支撑轨迹可复算。
 *
 * @param directive        当前（执行中或最近一条）指令；从未下达为 null
 * @param revisions        该指令的全部轨迹
 * @param unresolvedEvents 未结事件数
 */
public record PowerView(PowerDirective directive, List<DirectiveRevision> revisions, int unresolvedEvents) {
}
