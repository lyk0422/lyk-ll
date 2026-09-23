package com.example.starter.robot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 参数解析边界：属性值中的嵌套引用、自引用保护与请求覆盖穿透。
 */
class NestedPropertyExpansionTest {

    @TempDir
    Path workspace;

    private XacroExpander expander;

    @BeforeEach
    void setUp() {
        expander = new XacroExpander();
    }

    @Test
    void expandsNestedPropertyReferences() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="prefix" value="body"/>
                  <xacro:property name="frame" value="${prefix}_link"/>
                  <link name="${frame}"/>
                </robot>
                """);
        ExpansionResult result = expander.expand(workspace, "main.xacro", Map.of());
        assertThat(result.urdf()).contains("name=\"body_link\"");
    }

    @Test
    void requestOverrideFlowsThroughNestedReference() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="prefix" value="body"/>
                  <xacro:property name="frame" value="${prefix}_link"/>
                  <link name="${frame}"/>
                </robot>
                """);
        ExpansionResult result = expander.expand(workspace, "main.xacro", Map.of("prefix", "head"));
        assertThat(result.urdf()).contains("name=\"head_link\"");
    }

    @Test
    void selfReferencingPropertyIsRejected() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="loop" value="${loop}"/>
                  <link name="${loop}"/>
                </robot>
                """);
        assertThatThrownBy(() -> expander.expand(workspace, "main.xacro", Map.of()))
                .isInstanceOfSatisfying(CompileException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_XACRO));
    }

    private void write(String relative, String content) throws IOException {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
