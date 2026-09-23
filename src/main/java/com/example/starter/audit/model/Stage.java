package com.example.starter.audit.model;

/**
 * 自动驾驶任务阶段，按声明顺序构成严格依赖链：
 * 感知(PERCEPTION) -&gt; 定位(LOCALIZATION) -&gt; 规划(PLANNING) -&gt; 控制(CONTROL)。
 */
public enum Stage {

    PERCEPTION("perception"),
    LOCALIZATION("localization"),
    PLANNING("planning"),
    CONTROL("control");

    private final String code;

    Stage(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * @return 依赖链中的上一阶段；首个阶段返回 {@code null}。
     */
    public Stage upstream() {
        int index = ordinal() - 1;
        return index < 0 ? null : values()[index];
    }

    public static Stage fromCode(String code) {
        for (Stage stage : values()) {
            if (stage.code.equals(code)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("未知阶段: " + code);
    }
}
