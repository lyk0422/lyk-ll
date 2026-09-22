package com.example.starter.ros.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.ros.AbstractIntegrationTest;
import com.example.starter.ros.TestDataCleaner;
import com.example.starter.ros.repository.EventJdbcRepository;
import com.example.starter.ros.repository.NodeJdbcRepository;
import com.example.starter.ros.repository.RequestRecordJdbcRepository;
import com.example.starter.ros.repository.ResourceJdbcRepository;
import com.example.starter.ros.web.dto.CheckpointResponse;
import com.example.starter.ros.web.dto.NodeOperationRequest;
import com.example.starter.ros.web.dto.NodeResponse;
import com.example.starter.ros.web.dto.RecoverRequest;
import com.example.starter.ros.web.dto.RegisterNodeRequest;
import com.example.starter.ros.web.dto.ResourceSpec;
import com.example.starter.ros.web.dto.TransitionRequest;
import com.example.starter.ros.web.error.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class NodeRegistryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NodeRegistryService service;
    @Autowired
    private EventJdbcRepository eventRepository;
    @Autowired
    private NodeJdbcRepository nodeRepository;
    @Autowired
    private ResourceJdbcRepository resourceRepository;
    @Autowired
    private RequestRecordJdbcRepository requestRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        TestDataCleaner.cleanAll(jdbcTemplate);
    }

    private RegisterNodeRequest registerRequest(String nodeId, String requestId,
                                                List<ResourceSpec> resources) {
        return new RegisterNodeRequest(nodeId, "/robot", "talker", resources, requestId);
    }

    private ResourceSpec service(String name) {
        return new ResourceSpec("SERVICE", name, "example_interfaces/srv/AddTwoInts");
    }

    private ResourceSpec topic(String name) {
        return new ResourceSpec("TOPIC", name, "std_msgs/msg/String");
    }

    @Test
    void registerNodeCreatesNodeResourcesAndEvent() {
        NodeResponse response = service.register(registerRequest("n1", "req-1",
                List.of(topic("chatter"), service("add"))));

        assertThat(response.state()).isEqualTo("UNCONFIGURED");
        assertThat(response.version()).isZero();
        assertThat(response.resources()).hasSize(2);
        assertThat(response.resources()).extracting(r -> r.fqName())
                .containsExactlyInAnyOrder("/robot/chatter", "/robot/add");

        assertThat(nodeRepository.findById("n1")).isPresent();
        assertThat(resourceRepository.findByNodeId("n1")).hasSize(2);
        assertThat(eventRepository.findAfter(0, 50)).hasSize(1);
        assertThat(eventRepository.findAfter(0, 50).get(0).eventType())
                .isEqualTo(com.example.starter.ros.domain.EventType.REGISTERED);
    }

    @Test
    void duplicateRegisterOfLiveNodeIsRejectedWithoutExtraEvent() {
        service.register(registerRequest("n1", "req-1", List.of(topic("chatter"))));

        assertThatThrownBy(() -> service.register(
                registerRequest("n1", "req-other", List.of(topic("chatter")))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("NODE_ALREADY_REGISTERED");

        assertThat(nodeRepository.findById("n1")).isPresent();
        assertThat(resourceRepository.findByNodeId("n1")).hasSize(1);
        assertThat(eventRepository.findAfter(0, 50)).hasSize(1);
        // 失败注册占用的 requestId 随事务回滚,不留痕
        assertThat(requestRepository.findById("req-other")).isEmpty();
    }

    @Test
    void sameRequestIdSameParamsReplaysOriginalResult() {
        RegisterNodeRequest request = registerRequest("n1", "req-1",
                List.of(topic("chatter")));
        NodeResponse first = service.register(request);
        NodeResponse replay = service.register(request);

        assertThat(replay).usingRecursiveComparison().isEqualTo(first);
        assertThat(resourceRepository.findByNodeId("n1")).hasSize(1);
        assertThat(eventRepository.findAfter(0, 50)).hasSize(1);
    }

    @Test
    void sameRequestIdDifferentParamsConflicts() {
        service.register(registerRequest("n1", "req-1", List.of(topic("chatter"))));

        RegisterNodeRequest changed = new RegisterNodeRequest("n1", "/robot", "talker",
                List.of(topic("different_topic")), "req-1");
        assertThatThrownBy(() -> service.register(changed))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(resourceRepository.findByNodeId("n1")).hasSize(1);
        assertThat(eventRepository.findAfter(0, 50)).hasSize(1);
    }

    @Test
    void topicsMayShareNameButServicesAreExclusive() {
        service.register(registerRequest("n1", "req-1",
                List.of(topic("shared"), service("svc"))));
        NodeResponse second = service.register(registerRequest("n2", "req-2",
                List.of(topic("shared"), topic("other"))));
        assertThat(second.resources()).hasSize(2);

        assertThatThrownBy(() -> service.register(registerRequest("n3", "req-3",
                List.of(service("svc")))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("RESOURCE_CONFLICT");
        assertThat(nodeRepository.findById("n3")).isEmpty();
        assertThat(eventRepository.findAfter(0, 50)).hasSize(2);
    }

    @Test
    void lifecycleHappyPathIncrementsVersionAndPublishesEvents() {
        service.register(registerRequest("n1", "req-1", List.of(topic("chatter"))));

        NodeResponse inactive = service.transition(new TransitionRequest(
                "n1", "INACTIVE", 0L, "req-2"));
        assertThat(inactive.state()).isEqualTo("INACTIVE");
        assertThat(inactive.version()).isEqualTo(1L);

        NodeResponse active = service.transition(new TransitionRequest(
                "n1", "ACTIVE", 1L, "req-3"));
        assertThat(active.state()).isEqualTo("ACTIVE");
        assertThat(active.version()).isEqualTo(2L);

        NodeResponse backToInactive = service.transition(new TransitionRequest(
                "n1", "INACTIVE", 2L, "req-4"));
        assertThat(backToInactive.state()).isEqualTo("INACTIVE");
        assertThat(backToInactive.version()).isEqualTo(3L);

        assertThat(eventRepository.findAfter(0, 50)).hasSize(4);
    }

    @Test
    void illegalTransitionIsDistinguishableFromVersionConflict() {
        service.register(registerRequest("n1", "req-1", List.of(topic("chatter"))));

        // UNCONFIGURED 不能直接跳到 ACTIVE
        assertThatThrownBy(() -> service.transition(
                new TransitionRequest("n1", "ACTIVE", 0L, "req-2")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ILLEGAL_TRANSITION");

        // 错误的期望版本
        assertThatThrownBy(() -> service.transition(
                new TransitionRequest("n1", "INACTIVE", 9L, "req-3")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("VERSION_CONFLICT");

        assertThat(nodeRepository.findById("n1").orElseThrow().version()).isZero();
    }

    @Test
    void finalizedNodeCannotTransition() {
        service.register(registerRequest("n1", "req-1", List.of()));
        service.transition(new TransitionRequest("n1", "FINALIZED", 0L, "req-2"));

        assertThatThrownBy(() -> service.transition(
                new TransitionRequest("n1", "ACTIVE", 1L, "req-3")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("NODE_FINALIZED");
    }

    @Test
    void deregisterCleansEverythingAndDuplicateIsIdempotent() {
        service.register(registerRequest("n1", "req-1",
                List.of(topic("chatter"), service("svc"))));
        NodeOperationRequest deregister = new NodeOperationRequest("n1", "req-9");
        NodeResponse first = service.deregister(deregister);
        assertThat(first.state()).isEqualTo("FINALIZED");

        assertThat(nodeRepository.findById("n1")).isEmpty();
        assertThat(resourceRepository.findByNodeId("n1")).isEmpty();
        assertThat(resourceRepository.findClaimHolder(
                com.example.starter.ros.domain.ResourceCategory.SERVICE, "/robot/svc")).isEmpty();

        // 重复注销:同 requestId 重放,不产生第二个事件
        NodeResponse replay = service.deregister(deregister);
        assertThat(replay).usingRecursiveComparison().isEqualTo(first);

        // 全新 requestId 对已不存在节点注销 -> 404
        assertThatThrownBy(() -> service.deregister(
                new NodeOperationRequest("n1", "req-10")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("NODE_NOT_FOUND");

        assertThat(eventRepository.findAfter(0, 50)).hasSize(2);
    }

    @Test
    void deregisterFreesExclusiveNameForReuse() {
        service.register(registerRequest("n1", "req-1", List.of(service("svc"))));
        service.deregister(new NodeOperationRequest("n1", "req-2"));

        NodeResponse recreated = service.register(registerRequest("n2", "req-3",
                List.of(service("svc"))));
        assertThat(recreated.nodeId()).isEqualTo("n2");
        assertThat(resourceRepository.findClaimHolder(
                com.example.starter.ros.domain.ResourceCategory.SERVICE, "/robot/svc"))
                .contains("n2");
    }

    @Test
    void crashCleansResourcesInOneTransactionAndPublishesSingleEvent() {
        service.register(registerRequest("n1", "req-1",
                List.of(topic("chatter"), service("svc"), service("svc2"))));
        NodeOperationRequest crash = new NodeOperationRequest("n1", "req-crash");
        NodeResponse response = service.crash(crash);

        assertThat(response.crashed()).isTrue();
        // 墓碑保留、版本不变、资源清空、互斥占位释放
        assertThat(nodeRepository.findById("n1").orElseThrow().crashed()).isTrue();
        assertThat(nodeRepository.findById("n1").orElseThrow().version()).isZero();
        assertThat(resourceRepository.findByNodeId("n1")).isEmpty();
        assertThat(resourceRepository.findClaimHolder(
                com.example.starter.ros.domain.ResourceCategory.SERVICE, "/robot/svc")).isEmpty();

        // 只有一个 CRASHED 事件,负载中携带全部被清理资源
        var events = eventRepository.findAfter(0, 50);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).eventType())
                .isEqualTo(com.example.starter.ros.domain.EventType.CRASHED);
        assertThat(events.get(1).payloadJson()).contains("/robot/chatter", "/robot/svc");

        // 重复异常退出请求返回原结果,不重复发布事件
        assertThat(service.crash(crash)).usingRecursiveComparison().isEqualTo(response);
        assertThat(eventRepository.findAfter(0, 50)).hasSize(2);

        // 第二次异常退出(新 requestId)被拒绝
        assertThatThrownBy(() -> service.crash(
                new NodeOperationRequest("n1", "req-crash-2")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("NODE_ALREADY_CRASHED");
    }

    @Test
    void crashedNodeFreesServiceNameButCannotTransition() {
        service.register(registerRequest("n1", "req-1", List.of(service("svc"))));
        service.crash(new NodeOperationRequest("n1", "req-crash"));

        service.register(registerRequest("n2", "req-2", List.of(service("svc"))));

        assertThatThrownBy(() -> service.transition(
                new TransitionRequest("n1", "INACTIVE", 0L, "req-t")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("NODE_CRASHED");
    }

    @Test
    void recoverRestoresStateVersionAndProcessedRequestsWithoutReplayingEvents() {
        service.register(registerRequest("n1", "req-1",
                List.of(topic("chatter"), service("svc"))));
        service.transition(new TransitionRequest("n1", "INACTIVE", 0L, "req-2"));
        service.transition(new TransitionRequest("n1", "ACTIVE", 1L, "req-3"));
        int eventsBeforeCrash = eventRepository.findAfter(0, 50).size();
        assertThat(eventsBeforeCrash).isEqualTo(3);

        service.crash(new NodeOperationRequest("n1", "req-crash"));
        int eventsAtRecover = eventRepository.findAfter(0, 50).size();

        NodeResponse recovered = service.recover(new RecoverRequest("n1", "req-recover"));
        assertThat(recovered.state()).isEqualTo("ACTIVE");
        assertThat(recovered.version()).isEqualTo(2L);
        assertThat(recovered.crashed()).isFalse();
        assertThat(recovered.resources()).hasSize(2);
        assertThat(resourceRepository.findClaimHolder(
                com.example.starter.ros.domain.ResourceCategory.SERVICE, "/robot/svc"))
                .contains("n1");

        // 恢复不重放历史事件:事件数与崩溃后一致(无新增 REGISTERED/STATE_CHANGED)
        assertThat(eventRepository.findAfter(0, 50)).hasSize(eventsAtRecover);

        // 已处理 requestId 重放仍返回原结果而不是报“节点已崩溃/重复注册”
        NodeResponse replayedRegister = service.register(
                registerRequest("n1", "req-1", List.of(topic("chatter"), service("svc"))));
        assertThat(replayedRegister.state()).isEqualTo("UNCONFIGURED");
        assertThat(eventRepository.findAfter(0, 50)).hasSize(eventsAtRecover);
    }

    @Test
    void recoverWithoutCheckpointFails() {
        service.register(registerRequest("n1", "req-1", List.of()));
        service.crash(new NodeOperationRequest("n1", "req-crash"));
        jdbcTemplate.update("DELETE FROM node_checkpoint");

        assertThatThrownBy(() -> service.recover(new RecoverRequest("n1", "req-recover")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CHECKPOINT_NOT_FOUND");
    }

    @Test
    void explicitCheckpointReflectsLatestState() {
        service.register(registerRequest("n1", "req-1", List.of(topic("a"))));
        service.transition(new TransitionRequest("n1", "INACTIVE", 0L, "req-2"));
        CheckpointResponse checkpoint = service.saveCheckpoint("n1");
        assertThat(checkpoint.state()).isEqualTo("INACTIVE");
        assertThat(checkpoint.version()).isEqualTo(1L);
        assertThat(checkpoint.resourceCount()).isEqualTo(1);
        assertThat(checkpoint.processedRequestCount()).isGreaterThanOrEqualTo(2);
    }
}
