-- ============================================================
-- Testcontainers 冒烟测试: 最小建表脚本
-- 只需保证 DictionaryServletContextListener 启动查询不报错
-- ============================================================

CREATE TABLE IF NOT EXISTS dictionary (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    dic_code    VARCHAR(200),
    dic_name    VARCHAR(200),
    code_index  INT,
    index_name  VARCHAR(200),
    super_id    INT,
    beizhu      VARCHAR(200),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS token (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    userid        INT,
    username      VARCHAR(200),
    tablename     VARCHAR(200),
    role          VARCHAR(100),
    token         VARCHAR(500),
    expiratedtime DATETIME,
    addtime       DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
