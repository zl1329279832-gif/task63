-- 冒烟测试最小 DDL：只需 dictionary 表（应用启动时 DictionaryServletContextListener 会查询）
CREATE TABLE IF NOT EXISTS `dictionary` (
  `id`          BIGINT(20)   NOT NULL AUTO_INCREMENT,
  `dic_code`    VARCHAR(200) DEFAULT NULL,
  `dic_name`    VARCHAR(200) DEFAULT NULL,
  `code_index`  INT(11)      DEFAULT NULL,
  `index_name`  VARCHAR(200) DEFAULT NULL,
  `super_id`    INT(11)      DEFAULT NULL,
  `beizhu`      VARCHAR(200) DEFAULT NULL,
  `create_time` TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
