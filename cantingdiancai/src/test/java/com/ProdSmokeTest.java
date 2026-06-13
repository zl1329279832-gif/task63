package com;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 生产构建冒烟测试
 * <p>
 * 使用 Testcontainers 启动一个临时 MySQL，验证 Spring 上下文能完整加载。
 * CI 执行: mvn -Pprod verify
 * <p>
 * 前提: CI 环境需要 Docker daemon 可用 (GitHub Actions 默认自带)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev") // 复用 dev 的 multipart / 静态资源配置；数据源由 @DynamicPropertySource 覆盖
@Testcontainers
class ProdSmokeTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cantingdiancai_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("schema-init.sql");

    /**
     * 将 Testcontainers MySQL 连接信息注入 Spring Environment，
     * 覆盖 application-dev.yml 中的硬编码数据源。
     * DynamicPropertySource 优先级高于所有 YAML / properties 文件。
     */
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("应用上下文加载成功 (含 DictionaryServletContextListener 字典缓存初始化)")
    void contextLoads() {
        assertNotNull(applicationContext, "Spring ApplicationContext 不应为 null");
    }
}
