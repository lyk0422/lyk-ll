package com.example.starter.robot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编译服务与 H2（MODE=MySQL）缓存的集成测试：依赖感知失效、重复与并发复用、
 * 确定性哈希以及失败不写缓存。
 */
@SpringBootTest
class CompileServiceIntegrationTest {

    @TempDir
    static Path workspace;

    @DynamicPropertySource
    static void workspaceProperty(DynamicPropertyRegistry registry) {
        registry.add("robot.compiler.workspace-root", () -> workspace.toString());
    }

    @Autowired
    private CompileService compileService;

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
    void cleanCache() throws IOException {
        cacheRepository.deleteAll();
        Files.createDirectories(workspace.resolve("includes"));
        writeStandardRobot("0.1", "0.5");
    }

    @Test
    void compilesWithIncludeAndOverrideThenReusesCache() {
        CompileRequest request = request("req-1", Map.of("arm_length", "0.8"));

        CompileResponse first = compileService.compile(request);
        assertThat(first.cacheHit()).isFalse();
        assertThat(first.urdf()).contains("length=\"0.8\"").contains("radius=\"0.1\"");
        // 哈希为 64 位十六进制小写，且确为 URDF 字节的 SHA-256
        assertThat(first.urdfSha256()).matches("[0-9a-f]{64}");
        assertThat(first.urdfSha256()).isEqualTo(Digests.sha256Hex(first.urdf()));
        assertThat(first.cacheKey()).matches("[0-9a-f]{64}");
        assertThat(first.expansionChain()).containsExactly(
                "robots/main.urdf.xacro", "includes/base.urdf.xacro");
        assertThat(first.dependencies()).hasSize(2);
        assertThat(cacheRepository.count()).isEqualTo(1);

        CompileResponse second = compileService.compile(request("req-2", Map.of("arm_length", "0.8")));
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.urdfSha256()).isEqualTo(first.urdfSha256());
        assertThat(second.cacheKey()).isEqualTo(first.cacheKey());
        assertThat(second.urdf()).isEqualTo(first.urdf());
        assertThat(cacheRepository.count()).isEqualTo(1);
    }

    @Test
    void parameterInsertionOrderDoesNotChangeCacheKey() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("arm_length", "0.8");
        one.put("base_radius", "0.2");
        Map<String, String> two = new LinkedHashMap<>();
        two.put("base_radius", "0.2");
        two.put("arm_length", "0.8");

        CompileResponse first = compileService.compile(request("r1", one));
        CompileResponse second = compileService.compile(request("r2", two));
        assertThat(second.cacheKey()).isEqualTo(first.cacheKey());
        assertThat(second.urdfSha256()).isEqualTo(first.urdfSha256());
    }

    @Test
    void dependencyContentChangeInvalidatesCache() throws IOException {
        CompileResponse before = compileService.compile(request("r1", Map.of("arm_length", "0.8")));
        assertThat(before.cacheHit()).isFalse();

        // 修改传递依赖内容：base 半径默认值变化
        writeStandardRobot("0.2", "0.5");
        CompileResponse afterChange = compileService.compile(request("r2", Map.of("arm_length", "0.8")));
        assertThat(afterChange.cacheHit()).isFalse();
        assertThat(afterChange.cacheKey()).isNotEqualTo(before.cacheKey());
        assertThat(afterChange.urdfSha256()).isNotEqualTo(before.urdfSha256());
        assertThat(afterChange.urdf()).contains("radius=\"0.2\"");
        assertThat(cacheRepository.count()).isEqualTo(2);

        // 旧内容的缓存仍然可用
        writeStandardRobot("0.1", "0.5");
        CompileResponse oldAgain = compileService.compile(request("r3", Map.of("arm_length", "0.8")));
        assertThat(oldAgain.cacheHit()).isTrue();
        assertThat(oldAgain.cacheKey()).isEqualTo(before.cacheKey());
    }

    @Test
    void parameterOverrideChangeInvalidatesCache() {
        CompileResponse first = compileService.compile(request("r1", Map.of("arm_length", "0.8")));
        CompileResponse second = compileService.compile(request("r2", Map.of("arm_length", "0.9")));
        assertThat(second.cacheKey()).isNotEqualTo(first.cacheKey());
        assertThat(second.urdfSha256()).isNotEqualTo(first.urdfSha256());
        assertThat(second.urdf()).contains("length=\"0.9\"");
        assertThat(cacheRepository.count()).isEqualTo(2);
    }

    @Test
    void concurrentCompilesShareSingleCacheRow() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        Map<Integer, CompileResponse> responses = new ConcurrentHashMap<>();
        List<Throwable> failures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        responses.put(index,
                                compileService.compile(request("concurrent-" + index, Map.of("arm_length", "0.8"))));
                    } catch (Throwable t) {
                        synchronized (failures) {
                            failures.add(t);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).isEmpty();
        assertThat(responses).hasSize(threads);
        String expectedKey = responses.get(0).cacheKey();
        String expectedHash = responses.get(0).urdfSha256();
        String expectedUrdf = responses.get(0).urdf();
        for (CompileResponse response : responses.values()) {
            assertThat(response.cacheKey()).isEqualTo(expectedKey);
            assertThat(response.urdfSha256()).isEqualTo(expectedHash);
            assertThat(response.urdf()).isEqualTo(expectedUrdf);
        }
        long hits = responses.values().stream().filter(CompileResponse::cacheHit).count();
        assertThat(hits).isGreaterThanOrEqualTo(1);
        // 同键并发只允许一行缓存
        assertThat(cacheRepository.count()).isEqualTo(1);
    }

    @Test
    void validationFailureWritesNoCache() throws IOException {
        write("robots/broken.urdf.xacro", """
                <?xml version="1.0"?>
                <robot name="broken" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <link name="only_link"/>
                  <joint name="bad" type="fixed">
                    <parent link="only_link"/>
                    <child link="missing_link"/>
                  </joint>
                </robot>
                """);
        CompileRequest request = new CompileRequest("robots/broken.urdf.xacro", Map.of(), "bad-req");
        assertThatThrownBy(() -> compileService.compile(request))
                .isInstanceOfSatisfying(CompileException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_URDF);
                    assertThat(ex.requestId()).isEqualTo("bad-req");
                });
        assertThat(cacheRepository.count()).isZero();
    }

    @Test
    void cyclicIncludeProduces422ChainAndWritesNoCache() throws IOException {
        write("cyc/a.xacro", """
                <?xml version="1.0"?>
                <robot name="c" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="../cyc/b.xacro"/>
                </robot>
                """);
        write("cyc/b.xacro", """
                <?xml version="1.0"?>
                <robot name="c" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="../cyc/a.xacro"/>
                </robot>
                """);
        CompileRequest request = new CompileRequest("cyc/a.xacro", Map.of(), "cycle-req");
        assertThatThrownBy(() -> compileService.compile(request))
                .isInstanceOfSatisfying(CompileException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCLUDE_CYCLE);
                    assertThat(ex.chain()).containsExactly("cyc/a.xacro", "cyc/b.xacro", "cyc/a.xacro");
                });
        assertThat(cacheRepository.count()).isZero();
    }

    private CompileRequest request(String requestId, Map<String, String> parameters) {
        return new CompileRequest("robots/main.urdf.xacro", parameters, requestId);
    }

    private void writeStandardRobot(String baseRadius, String armLength) throws IOException {
        write("includes/base.urdf.xacro", """
                <?xml version="1.0"?>
                <robot name="parts" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:property name="base_radius" value="%s"/>
                  <link name="base_link">
                    <inertial>
                      <mass value="1.0"/>
                      <inertia ixx="0.01" ixy="0" ixz="0" iyy="0.01" iyz="0" izz="0.01"/>
                    </inertial>
                    <visual>
                      <geometry><sphere radius="${base_radius}"/></geometry>
                    </visual>
                    <collision>
                      <geometry><sphere radius="${base_radius}"/></geometry>
                    </collision>
                  </link>
                </robot>
                """.formatted(baseRadius));
        write("robots/main.urdf.xacro", """
                <?xml version="1.0"?>
                <robot name="demo" xmlns:xacro="http://www.ros.org/wiki/xacro">
                  <xacro:include filename="../includes/base.urdf.xacro"/>
                  <xacro:property name="arm_length" value="%s"/>
                  <link name="arm_link">
                    <inertial>
                      <mass value="0.5"/>
                      <inertia ixx="0.005" ixy="0" ixz="0" iyy="0.005" iyz="0" izz="0.005"/>
                    </inertial>
                    <visual>
                      <origin xyz="0 0 0.25" rpy="0 0 0"/>
                      <geometry><cylinder radius="0.05" length="${arm_length}"/></geometry>
                    </visual>
                  </link>
                  <joint name="base_to_arm" type="revolute">
                    <parent link="base_link"/>
                    <child link="arm_link"/>
                    <origin xyz="0 0 0" rpy="0 0 0"/>
                    <axis xyz="0 0 1"/>
                    <limit effort="10" velocity="1" lower="-3.14" upper="3.14"/>
                  </joint>
                </robot>
                """.formatted(armLength));
    }

    private void write(String relative, String content) throws IOException {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
