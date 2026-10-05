package com.example.starter.flood.domain;

/**
 * 在途指令冲突替代建议的人工确认决策。
 */
public enum SuggestionDecision {
    /** 采纳新版本：按新方案版本另行下达指令（在途指令仍按原版本执行至闭环）。 */
    ADOPT_NEW_VERSION,
    /** 维持原版本：在途指令继续按创建时的方案版本执行。 */
    KEEP_ORIGINAL_VERSION
}
