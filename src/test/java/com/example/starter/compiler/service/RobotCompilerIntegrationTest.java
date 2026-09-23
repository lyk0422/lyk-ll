package com.example.starter.compiler.service;

import com.example.starter.compiler.api.CompileRequest;
import com.example.starter.compiler.api.CompileResult;
import com.example.starter.compiler.core.CompilerException;
import com.example.starter.compiler.core.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 编译服务端到端测试（真实 Spring 上下文 + H2 MySQL 兼容内存库 + 真实文件）。
 * 覆盖 include+参数覆盖主流程、确定性哈希、重复/并发缓存复用、依赖变更失效、
 * 非法 Joint 不落成功缓存，以及 400/422 的 HTTP 语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RobotCompilerIntegrationTest {

    @TempDir
    static Path workspace;

    @DynamicPropertySource
    static void registerWorkspace(DynamicPropertyRegistry registry) {
        registry.add("robot.compiler.workspace", () -> workspace.toString());
    }

    @Autowired
    private RobotCompilerService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE robot_compile_cache");
    }

    private void write(String relativePath, String content) throws Exception {
        Path target = workspace.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static final String LINKS_XACRO = """
            <?xml version="1.0"?>
            <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
              <xacro:property name="arm_length" value="0.10"/>
              <link name="arm_link">
                <inertial>
                  <origin xyz="0 0 0" rpy="0 0 0"/>
                  <mass value="1.0"/>
                  <inertia ixx="0.01" iyy="0.01" izz="0.01" ixy="0.0" ixz="0.0" iyz="0.0"/>
                </inertial>
                <collision>
                  <origin xyz="0 0 0" rpy="0 0 0"/>
                  <geometry><box size="0.1 0.1 0.1"/></geometry>
                </collision>
              </link>
            </robot>
            """;

    private static final String MAIN_XACRO = """
            <?xml version="1.0"?>
            <robot name="arm" xmlns:xacro="http://www.ros.org/wiki/xacro">
              <xacro:include filename="sub/links.xacro"/>
              <xacro:property name="arm_length" value="0.42"/>
              <link name="base_link">
                <visual>
                  <origin xyz="0 0 0" rpy="0 0 0"/>
                  <geometry><cylinder radius="0.1" length="0.05"/></geometry>
                </visual>
              </link>
              <joint name="base_to_arm" type="fixed">
                <parent link="base_link"/>
                <child link="arm_link"/>
                <origin xyz="0 0 ${arm_length}" rpy="0 0 0"/>
              </joint>
            </robot>
            """;

    @Test
    void compilesIncludeWithParameterOverrideAndIsDeterministic() throws Exception {
        write("arm/main.xacro", MAIN_XACRO);
        write("arm/sub/links.xacro", LINKS_XACRO);

        CompileRequest request = new CompileRequest("arm/main.xacro", Map.of("arm_length", "0.77"), "req-1");
        CompileResult first = service.compile(request);

        assertThat(first.cacheHit()).isFalse();
        assertThat(first.requestId()).isEqualTo("req-1");
        assertThat(first.sha256()).matches("[0-9a-f]{64}");
        assertThat(first.urdf()).contains("name=\"arm\"").contains("base_link").contains("arm_link");
        assertThat(first.urdf()).contains("0 0 0.77");
        assertThat(first.urdf()).doesNotContain("0.42").doesNotContain(workspace.toString());
        assertThat(first.urdf()).doesNotContain("xacro");
        assertThat(first.dependencies()).extracting(d -> d.path())
                .containsExactly("arm/main.xacro", "arm/sub/links.xacro");
        assertThat(first.expansionChain()).containsExactly("arm/main.xacro", "arm/sub/links.xacro");

        // 不同 requestId、相同内容与参数：字节与哈希必须完全一致，且命中缓存。
        CompileResult second = service.compile(
                new CompileRequest("arm/main.xacro", Map.of("arm_length", "0.77"), "req-2"));
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.urdf()).isEqualTo(first.urdf());
        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(second.dependencies()).isEqualTo(first.dependencies());
        assertThat(second.expansionChain()).isEqualTo(first.expansionChain());

        // 清空缓存重新编译，结果仍逐字节一致（不含时间/临时路径）。
        jdbcTemplate.execute("TRUNCATE TABLE robot_compile_cache");
        CompileResult rebuilt = service.compile(
                new CompileRequest("arm/main.xacro", Map.of("arm_length", "0.77"), "req-3"));
        assertThat(rebuilt.urdf()).isEqualTo(first.urdf());
        assertThat(rebuilt.sha256()).isEqualTo(first.sha256());
    }

    @Test
    void fileDefaultAppliesWithoutOverride() throws Exception {
        write("arm/main.xacro", MAIN_XACRO);
        write("arm/sub/links.xacro", LINKS_XACRO);

        CompileResult result = service.compile(new CompileRequest("arm/main.xacro", Map.of(), "req"));
        assertThat(result.urdf()).contains("0 0 0.42");
    }

    @Test
    void changedParameterProducesDifferentHashAndCacheEntry() throws Exception {
        write("arm/main.xacro", MAIN_XACRO);
        write("arm/sub/links.xacro", LINKS_XACRO);

        CompileResult a = service.compile(new CompileRequest("arm/main.xacro", Map.of("arm_length", "0.5"), "r"));
        CompileResult b = service.compile(new CompileRequest("arm/main.xacro", Map.of("arm_length", "0.6"), "r"));

        assertThat(b.cacheHit()).isFalse();
        assertThat(a.sha256()).isNotEqualTo(b.sha256());
        assertThat(countRows()).isEqualTo(2);
    }

    @Test
    void changedDependencyContentInvalidatesCache() throws Exception {
        write("arm/main.xacro", MAIN_XACRO);
        write("arm/sub/links.xacro", LINKS_XACRO);
        CompileResult before = service.compile(new CompileRequest("arm/main.xacro", Map.of(), "r"));
        assertThat(before.cacheHit()).isFalse();

        // 修改传递依赖（即使入口不变），缓存必须失效。
        write("arm/sub/links.xacro", LINKS_XACRO.replace("<mass value=\"1.0\"/>", "<mass value=\"1.5\"/>"));
        CompileResult after = service.compile(new CompileRequest("arm/main.xacro", Map.of(), "r"));

        assertThat(after.cacheHit()).isFalse();
        assertThat(after.sha256()).isNotEqualTo(before.sha256());
        assertThat(after.urdf()).contains("1.5");
        assertThat(countRows()).isEqualTo(2);

        // 恢复依赖内容后重新命中旧条目（内容寻址）。
        write("arm/sub/links.xacro", LINKS_XACRO);
        CompileResult restored = service.compile(new CompileRequest("arm/main.xacro", Map.of(), "r"));
        assertThat(restored.cacheHit()).isTrue();
        assertThat(restored.sha256()).isEqualTo(before.sha256());
    }

    @Test
    void concurrentCompilesReuseSingleCacheEntry() throws Exception {
        write("arm/main.xacro", MAIN_XACRO);
        write("arm/sub/links.xacro", LINKS_XACRO);

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        var futureList = new java.util.ArrayList<Future<CompileResult>>();

        for (int i = 0; i < threads; i++) {
            final String requestId = "req-" + i;
            futureList.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return service.compile(new CompileRequest("arm/main.xacro", Map.of(), requestId));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        CompileResult reference = null;
        for (Future<CompileResult> future : futureList) {
            CompileResult result = future.get(30, TimeUnit.SECONDS);
            if (reference == null) {
                reference = result;
            }
            assertThat(result.urdf()).isEqualTo(reference.urdf());
            assertThat(result.sha256()).isEqualTo(reference.sha256());
        }
        // 并发下只允许一个编译结果落库。
        assertThat(countRows()).isEqualTo(1);
        executor.shutdownNow();
    }

    @Test
    void invalidJointIsRejectedAndNothingCached() {
        String invalid = """
                <?xml version="1.0"?>
                <robot name="bad">
                  <link name="a"/>
                  <link name="b"/>
                  <joint name="j" type="revolute">
                    <parent link="a"/>
                    <child link="ghost"/>
                  </joint>
                </robot>
                """;
        try {
            write("bad/main.xacro", invalid);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThatThrownBy(() -> service.compile(new CompileRequest("bad/main.xacro", Map.of(), "r")))
                .isInstanceOf(CompilerException.class)
                .satisfies(ex -> assertThat(((CompilerException) ex).errorCode())
                        .isEqualTo(ErrorCode.INVALID_URDF))
                .hasMessageContaining("child link 不存在: ghost");
        assertThat(countRows()).isZero();

        // 再次请求仍然重新报错而不是命中缓存。
        assertThatThrownBy(() -> service.compile(new CompileRequest("bad/main.xacro", Map.of(), "r")))
                .isInstanceOf(CompilerException.class);
        assertThat(countRows()).isZero();
    }

    @Test
    void cyclicIncludeIs422OverHttp() throws Exception {
        write("cyc/a.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="b.xacro"/>
                  <link name="a"/>
                </robot>
                """);
        write("cyc/b.xacro", """
                <?xml version="1.0"?>
                <robot xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="a.xacro"/>
                  <link name="b"/>
                </robot>
                """);

        mockMvc.perform(post("/api/robot-models/compilations")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompileRequest("cyc/a.xacro", Map.of(), "r"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CYCLIC_INCLUDE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "cyc/a.xacro -> cyc/b.xacro -> cyc/a.xacro")));
        assertThat(countRows()).isZero();
    }

    @Test
    void outsideReferenceIs400OverHttp() throws Exception {
        write("esc/main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="../../outside.xacro"/>
                </robot>
                """);

        mockMvc.perform(post("/api/robot-models/compilations")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompileRequest("esc/main.xacro", Map.of(), "r"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PATH_OUTSIDE_WORKSPACE"));
    }

    @Test
    void undefinedParameterIs400OverHttp() throws Exception {
        write("u/main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="${missing}"/>
                </robot>
                """);

        mockMvc.perform(post("/api/robot-models/compilations")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompileRequest("u/main.xacro", Map.of(), "r"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNDEFINED_PARAMETER"));
    }

    @Test
    void unsupportedXacroIs400OverHttp() throws Exception {
        write("m/main.xacro", """
                <?xml version="1.0"?>
                <robot name="r" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:macro name="x"/>
                </robot>
                """);

        mockMvc.perform(post("/api/robot-models/compilations")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompileRequest("m/main.xacro", Map.of(), "r"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_XACRO"));
    }

    @Test
    void invalidEntryPathIs400OverHttp() throws Exception {
        mockMvc.perform(post("/api/robot-models/compilations")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompileRequest("../outside/main.xacro", Map.of(), "r"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingRequestBodyFieldIs400OverHttp() throws Exception {
        mockMvc.perform(post("/api/robot-models/compilations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"entry\":\"arm/main.xacro\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM robot_compile_cache", Integer.class);
        return count == null ? 0 : count;
    }
}
