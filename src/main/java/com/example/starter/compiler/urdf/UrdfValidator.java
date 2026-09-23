package com.example.starter.compiler.urdf;

/**
 * 展开后的 URDF 校验器：检查名称唯一、根 Link、父子引用、关节环、数值与惯量等约束。
 */
public interface UrdfValidator {

    /**
     * 校验 URDF 文本；不合法时抛出 {@code CompilerException(INVALID_URDF)}。
     *
     * @param urdf 展开后的 URDF 文本
     */
    void validate(String urdf);
}
