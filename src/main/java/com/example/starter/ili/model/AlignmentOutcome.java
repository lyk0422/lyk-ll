package com.example.starter.ili.model;

import java.util.List;

/**
 * 对齐结果：匹配对、新发现（NEW 侧未匹配读数键）、已消失（OLD 侧未匹配读数键）。
 */
public record AlignmentOutcome(List<MatchResult> matches,
                               List<String> newFindings,
                               List<String> disappeared) {
}
