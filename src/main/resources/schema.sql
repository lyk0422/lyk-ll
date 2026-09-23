-- 机器人模型编译缓存表（H2 MODE=MySQL 与 MySQL 8 均可执行）
CREATE TABLE IF NOT EXISTS robot_compile_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    cache_key VARCHAR(64) NOT NULL UNIQUE COMMENT '缓存键，入口与全部传递依赖内容摘要及规范化参数的 SHA-256 十六进制',
    entry_path VARCHAR(1024) NOT NULL COMMENT '入口 Xacro 相对受控工作目录的 POSIX 路径',
    urdf_text TEXT NOT NULL COMMENT '规范化展开后的 URDF 文本，UTF-8',
    urdf_sha256 VARCHAR(64) NOT NULL COMMENT 'URDF 字节的 SHA-256，十六进制小写',
    dependencies_json TEXT NOT NULL COMMENT '全部传递依赖清单 JSON，含相对路径与原始字节 SHA-256',
    expansion_chain_json TEXT NOT NULL COMMENT '展开链路 JSON，入口在前的 include 前序首次访问序列',
    parameters_json TEXT NOT NULL COMMENT '规范化有效参数 JSON，文件默认值经请求覆盖后的键值',
    created_at BIGINT NOT NULL COMMENT '行创建时间的 epoch 毫秒，仅用于运维排障，不参与缓存键、哈希或接口输出'
);
