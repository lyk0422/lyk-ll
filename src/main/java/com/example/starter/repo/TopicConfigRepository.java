package com.example.starter.repo;

import com.example.starter.domain.RetentionPolicy;
import com.example.starter.po.TopicConfig;

import java.util.List;
import java.util.Optional;

/** Topic 策略配置数据访问。 */
public interface TopicConfigRepository {

    /** 新增或更新 Topic 策略。 */
    TopicConfig upsert(String topic, RetentionPolicy policy);

    Optional<TopicConfig> findByTopic(String topic);

    List<TopicConfig> findAll();
}
