package com.example.starter.robot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编译接口 HTTP 端到端测试：状态码、错误体 requestId 与循环链路、确定性输出。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompileControllerIntegrationTest {

    @TempDir
    static Path workspace;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("robot.compiler.workspace-root", () -> workspace.toString());
        // 与服务层集成测试使用不同内存库，避免上下文间数据干扰
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:robot_compiler_web_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RobotCacheRepository cacheRepository;

    @org.junit.jupiter.api.AfterAll
    static void releaseDatabase(@Autowired javax.sql.DataSource dataSource) {
        // 释放 DB_CLOSE_DELAY=-1 的命名内存库，避免测试 JVM 内残留
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (Exception ignored) {
            // 库已随上下文关闭时无需处理
        }
    }

    @BeforeEach
    void clean() {
        cacheRepository.deleteAll();
    }

    @Test
    void compileSuccessReturnsCanonicalUrdf() throws IOException {
        write("inc.xacro", """
                <?xml version="1.0"?>
                <robot name="p" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="r" value="0.1"/>
                  <link name="base">
                    <visual><geometry><sphere radius="${r}"/></geometry></visual>
                  </link>
                </robot>
                """);
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="demo" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="inc.xacro"/>
                  <link name="top"/>
                  <joint name="j" type="fixed"><parent link="base"/><child link="top"/></joint>
                </robot>
                """);

        ResponseEntity<CompileResponse> response = restTemplate.postForEntity(
                "/api/robot/compile",
                new CompileRequest("main.xacro", Map.of("r", "0.2"), "web-1"),
                CompileResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CompileResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.requestId()).isEqualTo("web-1");
        assertThat(body.urdfSha256()).matches("[0-9a-f]{64}");
        assertThat(body.urdf()).contains("radius=\"0.2\"");
        // 确定性：输出不得混入工作目录绝对路径或 xacro 构造
        assertThat(body.urdf()).doesNotContain(workspace.toString()).doesNotContain("xacro");
        assertThat(body.dependencies()).hasSize(2);
        assertThat(body.expansionChain()).containsExactly("main.xacro", "inc.xacro");
    }

    @Test
    void undefinedParameterReturns400WithRequestId() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="l"><visual><geometry><box size="${nope} 1 1"/></geometry></visual></link>
                </robot>
                """);
        ResponseEntity<ErrorResponse> response = postError(
                new CompileRequest("main.xacro", Map.of(), "web-undef"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.UNDEFINED_PARAMETER.name());
        assertThat(response.getBody().requestId()).isEqualTo("web-undef");
        assertThat(cacheRepository.count()).isZero();
    }

    @Test
    void outOfBoundsReturns400() throws IOException {
        Files.createDirectories(workspace.resolve("sub"));
        write("sub/main.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="../../../evil.xacro"/>
                </robot>
                """);
        ResponseEntity<ErrorResponse> response = postError(
                new CompileRequest("sub/main.xacro", Map.of(), "web-oob"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INCLUDE_OUT_OF_BOUNDS.name());
    }

    @Test
    void cycleReturns422WithFullChain() throws IOException {
        write("a.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="b.xacro"/>
                </robot>
                """);
        write("b.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="a.xacro"/>
                </robot>
                """);
        ResponseEntity<ErrorResponse> response = postError(
                new CompileRequest("a.xacro", Map.of(), "web-cycle"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INCLUDE_CYCLE.name());
        assertThat(response.getBody().requestId()).isEqualTo("web-cycle");
        assertThat(response.getBody().chain()).containsExactly("a.xacro", "b.xacro", "a.xacro");
    }

    @Test
    void unsupportedXacroReturns422() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="d" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:macro name="m"/>
                </robot>
                """);
        ResponseEntity<ErrorResponse> response = postError(
                new CompileRequest("main.xacro", Map.of(), "web-unsup"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.UNSUPPORTED_XACRO.name());
    }

    @Test
    void invalidJointReturns400() throws IOException {
        write("main.xacro", """
                <?xml version="1.0"?>
                <robot name="d">
                  <link name="only"/>
                  <joint name="j" type="fixed">
                    <parent link="only"/><child link="ghost"/>
                  </joint>
                </robot>
                """);
        ResponseEntity<ErrorResponse> response = postError(
                new CompileRequest("main.xacro", Map.of(), "web-invalid"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_URDF.name());
    }

    @Test
    void missingEntryReturns400() {
        ResponseEntity<ErrorResponse> response = postError(
                new CompileRequest("missing.xacro", Map.of(), "web-missing"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.ENTRY_NOT_FOUND.name());
        assertThat(response.getBody().requestId()).isEqualTo("web-missing");
    }

    @Test
    void blankEntryReturns400() {
        ResponseEntity<ErrorResponse> response = postError(
                new CompileRequest("  ", Map.of(), "web-blank"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_REQUEST.name());
        assertThat(response.getBody().requestId()).isEqualTo("web-blank");
    }

    @Test
    void malformedJsonReturns400() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        org.springframework.http.HttpEntity<String> entity =
                new org.springframework.http.HttpEntity<>("{not-json", headers);
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/robot/compile", entity, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_REQUEST.name());
    }

    private ResponseEntity<ErrorResponse> postError(CompileRequest request) {
        return restTemplate.postForEntity("/api/robot/compile", request, ErrorResponse.class);
    }

    private void write(String relative, String content) throws IOException {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
