package com.example.starter.rosbridge.testsupport;

import com.example.starter.rosbridge.gateway.GatewayProperties;
import com.example.starter.rosbridge.gateway.GatewayService;
import com.example.starter.rosbridge.ros.GatewayClock;
import com.example.starter.rosbridge.ros.SyntheticRosAdapter;
import com.example.starter.rosbridge.store.GatewayRepository;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 组装“真实 H2 仓储 + 合成 ROS 适配器 + 可控时钟”的网关测试夹具，
 * 不启动 Spring 容器也能覆盖完整业务状态机。
 */
public final class GatewayHarness implements AutoCloseable {

    public final DataSource dataSource;
    public final JdbcTemplate jdbc;
    public final GatewayRepository repo;
    public final SyntheticRosAdapter adapter;
    public final GatewayClock clock;
    public final GatewayProperties props;
    public final GatewayService service;

    private GatewayHarness(DataSource ds, GatewayProperties props) {
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(ds);
        this.repo = new GatewayRepository(jdbc);
        this.adapter = new SyntheticRosAdapter();
        this.clock = new GatewayClock();
        this.props = props;
        this.service = new GatewayService(repo, adapter, clock, props);
    }

    public static GatewayHarness create() {
        return create(new GatewayProperties(
                "/rosbridge", 500L, 256, 100000, 16, 1000L));
    }

    public static GatewayHarness create(GatewayProperties props) {
        DataSource ds = TestDatasources.newH2("gw_test_" + System.nanoTime());
        return new GatewayHarness(ds, props);
    }

    public RecordingTransport connect(String clientId) {
        return connect(clientId, null, null);
    }

    public RecordingTransport connect(String clientId, Integer rate, Integer cap) {
        RecordingTransport transport = new RecordingTransport(clientId);
        service.connect(transport, rate, cap);
        return transport;
    }

    @Override
    public void close() {
        service.shutdown();
        repo.clearAll();
    }
}
