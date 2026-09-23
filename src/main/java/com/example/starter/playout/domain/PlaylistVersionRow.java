package com.example.starter.playout.domain;

import java.time.Instant;

/**
 * 播单版本行。
 *
 * @param id          版本ID
 * @param channel     频道
 * @param versionLabel 版本号
 * @param publishedAt 发布时间(UTC)
 */
public record PlaylistVersionRow(long id, String channel, String versionLabel, Instant publishedAt) {
}
