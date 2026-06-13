package com;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 冒烟测试：使用 Testcontainers MySQL 验证 prod 配置能正常启动
 * 运行方式：mvn -Pprod verify
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = SmokeTest.MysqlInitializer.class)
@ActiveProfiles("prod")
@Testcontainers
class SmokeTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:5.7")
            .withDatabaseName("cantingdiancai")
            .withInitScript("schema-smoke.sql");

    static class MysqlInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            TestPropertyValues.of(
                    "spring.datasource.url=" + mysql.getJdbcUrl()
                            + "&useUnicode=true&characterEncoding=utf-8&serverTimezone=GMT%2B8&useSSL=false",
                    "spring.datasource.username=" + mysql.getUsername(),
                    "spring.datasource.password=" + mysql.getPassword(),
                    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
            ).applyTo(ctx.getEnvironment());
        }
    }

    @Test
    void contextLoads() {
        // prod profile 下应用上下文能正常启动即通过
    }
}
