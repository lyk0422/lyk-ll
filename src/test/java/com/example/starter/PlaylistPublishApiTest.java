package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播单发布：时段端点规则、发布失败回滚、版本唯一性。
 */
class PlaylistPublishApiTest extends AbstractApiTest {

    @Test
    void 端点相接的半开时段发布成功() throws Exception {
        MvcResult result = postJson("/api/playlists", """
                {
                  "channelId": "ch-touch",
                  "versionNo": 1,
                  "items": [
                    {"itemId": "b", "mediaDigest": "d-b", "startMs": 1000, "endMs": 2000},
                    {"itemId": "a", "mediaDigest": "d-a", "startMs": 0, "endMs": 1000}
                  ]
                }
                """);
        assertEquals(201, statusOf(result));
        JsonNode body = bodyOf(result);
        assertEquals(1, body.get("versionNo").asInt());
        assertEquals(2, body.get("itemCount").asInt());
        // 返回按开始时间排序
        assertEquals("a", body.get("items").get(0).get("itemId").asText());
        assertEquals("b", body.get("items").get(1).get("itemId").asText());
    }

    @Test
    void 正长度重叠拒绝且不替换当前版本() throws Exception {
        publishStandardPlaylist("ch-overlap");
        Integer versionsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM playlist_version WHERE channel_id = 'ch-overlap'", Integer.class);

        MvcResult rejected = postJson("/api/playlists", """
                {
                  "channelId": "ch-overlap",
                  "versionNo": 2,
                  "items": [
                    {"itemId": "x", "mediaDigest": "d-x", "startMs": 0, "endMs": 1500},
                    {"itemId": "y", "mediaDigest": "d-y", "startMs": 1000, "endMs": 2000}
                  ]
                }
                """);
        assertEquals(422, statusOf(rejected));

        // 发布失败整体回滚：没有写入版本 2，频道绑定仍是版本 1
        Integer versionsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM playlist_version WHERE channel_id = 'ch-overlap'", Integer.class);
        assertEquals(versionsBefore, versionsAfter);
        Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM playlist_item WHERE channel_id = 'ch-overlap'", Integer.class);
        assertEquals(2, itemCount);

        // tick 验证当前版本仍是版本 1 的素材
        MvcResult tick = postJson("/api/ticks", """
                {"channelId": "ch-overlap", "atMs": 100}
                """);
        assertEquals(200, statusOf(tick));
        assertEquals("digest-a", bodyOf(tick).get("onAir").get("mediaDigest").asText());
    }

    @Test
    void 非有限时长拒绝() throws Exception {
        MvcResult zeroLength = postJson("/api/playlists", """
                {
                  "channelId": "ch-dur",
                  "items": [
                    {"itemId": "a", "mediaDigest": "d-a", "startMs": 500, "endMs": 500}
                  ]
                }
                """);
        assertEquals(422, statusOf(zeroLength));

        MvcResult negative = postJson("/api/playlists", """
                {
                  "channelId": "ch-dur",
                  "items": [
                    {"itemId": "a", "mediaDigest": "d-a", "startMs": 900, "endMs": 100}
                  ]
                }
                """);
        assertEquals(422, statusOf(negative));

        Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM playlist_version WHERE channel_id = 'ch-dur'", Integer.class);
        assertEquals(0, versions);
    }

    @Test
    void 版本内itemId重复拒绝() throws Exception {
        MvcResult result = postJson("/api/playlists", """
                {
                  "channelId": "ch-dup",
                  "items": [
                    {"itemId": "a", "mediaDigest": "d-1", "startMs": 0, "endMs": 100},
                    {"itemId": "a", "mediaDigest": "d-2", "startMs": 100, "endMs": 200}
                  ]
                }
                """);
        assertEquals(422, statusOf(result));
    }

    @Test
    void 重复版本号返回409() throws Exception {
        publishStandardPlaylist("ch-ver");
        MvcResult result = postJson("/api/playlists", """
                {
                  "channelId": "ch-ver",
                  "versionNo": 1,
                  "items": [
                    {"itemId": "c", "mediaDigest": "d-c", "startMs": 0, "endMs": 100}
                  ]
                }
                """);
        assertEquals(409, statusOf(result));
    }

    @Test
    void 缺省版本号自动递增() throws Exception {
        MvcResult first = postJson("/api/playlists", """
                {
                  "channelId": "ch-auto",
                  "items": [
                    {"itemId": "a", "mediaDigest": "d-a", "startMs": 0, "endMs": 100}
                  ]
                }
                """);
        assertEquals(201, statusOf(first));
        assertEquals(1, bodyOf(first).get("versionNo").asInt());

        MvcResult second = postJson("/api/playlists", """
                {
                  "channelId": "ch-auto",
                  "items": [
                    {"itemId": "b", "mediaDigest": "d-b", "startMs": 0, "endMs": 100}
                  ]
                }
                """);
        assertEquals(201, statusOf(second));
        assertEquals(2, bodyOf(second).get("versionNo").asInt());
        assertTrue(bodyOf(second).get("publishedAtMs").isNumber());
    }
}
