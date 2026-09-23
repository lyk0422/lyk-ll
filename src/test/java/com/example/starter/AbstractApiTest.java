package com.example.starter;

import com.example.starter.adapter.SyntheticPlayoutAdapter;
import com.example.starter.clock.SyntheticClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API 测试基类：共享 Spring 上下文与 H2 内存库，每个用例前清空数据并重置时钟/适配器。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDbConfig.class)
public abstract class AbstractApiTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected SyntheticClock clock;

    @Autowired
    protected SyntheticPlayoutAdapter adapter;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM as_run");
        jdbcTemplate.update("DELETE FROM break_request");
        jdbcTemplate.update("DELETE FROM playlist_item");
        jdbcTemplate.update("DELETE FROM playlist_version");
        jdbcTemplate.update("DELETE FROM channel_state");
        adapter.clear();
        clock.release();
    }

    protected MvcResult postJson(String url, Object body) throws Exception {
        String json = body instanceof String text ? text : objectMapper.writeValueAsString(body);
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    protected MvcResult getJson(String url) throws Exception {
        return mockMvc.perform(get(url)).andReturn();
    }

    protected JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    /**
     * 发布一条两项播单：itemA [0,1000)，itemB [1000,2000)。
     */
    protected JsonNode publishStandardPlaylist(String channelId) throws Exception {
        MvcResult result = postJson("/api/playlists", """
                {
                  "channelId": "%s",
                  "versionNo": 1,
                  "items": [
                    {"itemId": "itemA", "mediaDigest": "digest-a", "startMs": 0, "endMs": 1000},
                    {"itemId": "itemB", "mediaDigest": "digest-b", "startMs": 1000, "endMs": 2000}
                  ]
                }
                """.formatted(channelId));
        return bodyOf(result);
    }
}
