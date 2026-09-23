package com.example.starter.robot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link XacroExpander} 单元测试：受限子集展开、参数优先级、越界、循环与不支持构造。
 */
class XacroExpanderTest {

    @TempDir
    Path workspace;

    private XacroExpander expander;

    @BeforeEach
    void setUp() {
        expander = new XacroExpander();
    }

    @Test
    void expandsIncludeAndPropertiesWithRequestOverride() throws IOException {
        write("robots/common.urdf.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="base_radius" value="0.1"/>
                  <link name="base_link">
                    <visual>
                      <geometry>
                        <sphere radius="${base_radius}"/>
                      </geometry>
                    </visual>
                  </link>
                </robot>
                """);
        write("robots/arm.urdf.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="common.urdf.xacro"/>
                  <xacro:property name="arm_length" value="0.5"/>
                  <link name="arm_link">
                    <visual>
                      <geometry>
                        <cylinder radius="${base_radius}" length="${arm_length}"/>
                      </geometry>
                    </visual>
                  </link>
                  <joint name="base_to_arm" type="fixed">
                    <parent link="base_link"/>
                    <child link="arm_link"/>
                  </joint>
                </robot>
                """);

        ExpansionResult result = expander.expand(workspace, "robots/arm.urdf.xacro",
                Map.of("arm_length", "0.9"));

        // 请求参数覆盖文件默认值
        assertThat(result.urdf()).contains("length=\"0.9\"");
        // 未覆盖参数取文件默认值
        assertThat(result.urdf()).contains("radius=\"0.1\"");
        // xacro 命名空间声明不得进入规范 URDF
        assertThat(result.urdf()).doesNotContain("xacro").doesNotContain("xmlns");
        // 依赖清单包含入口与传递依赖，按路径排序，入口标记正确
        assertThat(result.dependencies()).extracting(DependencyInfo::path)
                .containsExactly("robots/arm.urdf.xacro", "robots/common.urdf.xacro");
        assertThat(result.dependencies()).filteredOn(DependencyInfo::entry)
                .singleElement().extracting(DependencyInfo::path).isEqualTo("robots/arm.urdf.xacro");
        // 展开链路为 include 前序：入口在前
        assertThat(result.expansionChain())
                .containsExactly("robots/arm.urdf.xacro", "robots/common.urdf.xacro");
        assertThat(result.parameters()).containsEntry("arm_length", "0.9").containsEntry("base_radius", "0.1");
    }

    @Test
    void repeatedExpansionProducesIdenticalBytes() throws IOException {
        writeRobotWithInclude();
        ExpansionResult first = expander.expand(workspace, "main.xacro", Map.of("size", "1"));
        ExpansionResult second = expander.expand(workspace, "main.xacro", Map.of("size", "1"));
        assertThat(first.urdf()).isEqualTo(second.urdf());
        assertThat(first.dependencies()).isEqualTo(second.dependencies());
    }

    @Test
    void undefinedParameterReturns400() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="l"><visual><geometry><box size="${missing} 1 1"/></geometry></visual></link>
                </robot>
                """);
        assertThatThrownBy(() -> expander.expand(workspace, "main.xacro", Map.of()))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> assertThat(((CompileException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNDEFINED_PARAMETER));
    }

    @Test
    void missingEntryReturns400() {
        assertThatThrownBy(() -> expander.expand(workspace, "nope.xacro", Map.of()))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> assertThat(((CompileException) ex).errorCode())
                        .isEqualTo(ErrorCode.ENTRY_NOT_FOUND));
    }

    @Test
    void missingIncludeReturns400() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="ghost.xacro"/>
                </robot>
                """);
        assertThatThrownBy(() -> expander.expand(workspace, "main.xacro", Map.of()))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> assertThat(((CompileException) ex).errorCode())
                        .isEqualTo(ErrorCode.INCLUDE_NOT_FOUND));
    }

    @Test
    void outOfBoundsIncludeReturns400() throws IOException {
        Files.createDirectories(workspace.resolve("sub"));
        write("sub/main.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="../outside.xacro"/>
                </robot>
                """);
        write("outside.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro"/>
                """);
        // 受控根为 sub 时，../outside.xacro 落在根外
        assertThatThrownBy(() -> expander.expand(workspace.resolve("sub"), "main.xacro", Map.of()))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> assertThat(((CompileException) ex).errorCode())
                        .isEqualTo(ErrorCode.INCLUDE_OUT_OF_BOUNDS));
    }

    @Test
    void absoluteIncludeReturns400() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="/etc/passwd"/>
                </robot>
                """);
        assertThatThrownBy(() -> expander.expand(workspace, "main.xacro", Map.of()))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> assertThat(((CompileException) ex).errorCode())
                        .isEqualTo(ErrorCode.INCLUDE_OUT_OF_BOUNDS));
    }

    @Test
    void cyclicIncludeReturns422WithFullChain() throws IOException {
        write("a.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="b.xacro"/>
                </robot>
                """);
        write("b.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="c.xacro"/>
                </robot>
                """);
        write("c.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="a.xacro"/>
                </robot>
                """);
        assertThatThrownBy(() -> expander.expand(workspace, "a.xacro", Map.of()))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> {
                    CompileException ce = (CompileException) ex;
                    assertThat(ce.errorCode()).isEqualTo(ErrorCode.INCLUDE_CYCLE);
                    assertThat(ce.chain()).containsExactly("a.xacro", "b.xacro", "c.xacro", "a.xacro");
                });
    }

    @Test
    void unsupportedXacroConstructsAreRejected() throws IOException {
        write("if.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:if value="true"><link name="l"/></xacro:if>
                </robot>
                """);
        assertThatThrownBy(() -> expander.expand(workspace, "if.xacro", Map.of()))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> assertThat(((CompileException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_XACRO));

        write("macro.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:macro name="m"><link name="l"/></xacro:macro>
                </robot>
                """);
        assertThatThrownBy(() -> expander.expand(workspace, "macro.xacro", Map.of()))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> assertThat(((CompileException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_XACRO));

        write("expr.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="a" value="1"/>
                  <link name="l"><visual><geometry><box size="${a + 1} 1 1"/></geometry></visual></link>
                </robot>
                """);
        assertThatThrownBy(() -> expander.expand(workspace, "expr.xacro", Map.of()))
                .isInstanceOf(CompileException.class)
                .satisfies(ex -> assertThat(((CompileException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_XACRO));
    }

    @Test
    void diamondIncludesAreDeduplicatedInChainAndDependencies() throws IOException {
        write("top.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="left.xacro"/>
                  <xacro:include filename="right.xacro"/>
                </robot>
                """);
        write("left.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="shared.xacro"/>
                </robot>
                """);
        write("right.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="shared.xacro"/>
                </robot>
                """);
        write("shared.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="shared_value" value="7"/>
                </robot>
                """);
        ExpansionResult result = expander.expand(workspace, "top.xacro", Map.of());
        // 重复包含同一文件不报错（无环），依赖清单去重
        assertThat(result.dependencies()).hasSize(4);
        assertThat(result.expansionChain()).containsExactly(
                "top.xacro", "left.xacro", "shared.xacro", "right.xacro");
    }

    private void writeRobotWithInclude() throws IOException {
        write("inc.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="w" value="2"/>
                  <link name="base"/>
                </robot>
                """);
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="inc.xacro"/>
                  <link name="top"/>
                  <joint name="j" type="fixed"><parent link="base"/><child link="top"/></joint>
                </robot>
                """);
    }

    private void write(String relative, String content) throws IOException {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
