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
 * include 文件名参数化与畸形 XML 的补充测试。
 */
class IncludeFilenameExpansionTest {

    @TempDir
    Path workspace;

    private XacroExpander expander;

    @BeforeEach
    void setUp() {
        expander = new XacroExpander();
    }

    @Test
    void includeFilenameAcceptsParameterReference() throws IOException {
        write("parts/wheel.xacro", """
                <?xml version="1.0"?>
                <robot name="p" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="wheel_link"/>
                </robot>
                """);
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="part" value="wheel"/>
                  <xacro:include filename="parts/${part}.xacro"/>
                  <link name="base_link"/>
                  <joint name="j" type="fixed"><parent link="base_link"/><child link="wheel_link"/></joint>
                </robot>
                """);

        ExpansionResult result = expander.expand(workspace, "main.xacro", Map.of("part", "wheel"));
        assertThat(result.urdf()).contains("wheel_link");
        assertThat(result.dependencies()).extracting(DependencyInfo::path)
                .contains("parts/wheel.xacro");
        assertThat(result.expansionChain()).containsExactly("main.xacro", "parts/wheel.xacro");
    }

    @Test
    void malformedXmlReturns400() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="l">
                </robot>
                """);
        assertThatThrownBy(() -> expander.expand(workspace, "main.xacro", Map.of()))
                .isInstanceOfSatisfying(CompileException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void unsupportedIncludeAttributeIsRejected() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="x.xacro" optional="true"/>
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
