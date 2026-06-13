# 部署指南 — 餐厅点餐管理系统

## 1. 环境要求

| 组件   | 版本         |
|--------|-------------|
| JDK    | 1.8+        |
| Maven  | 3.6+        |
| MySQL  | 5.7 / 8.0   |
| Node   | 12+ (构建管理端) |
| nginx  | 1.18+       |

---

## 2. Spring Profile 说明

| Profile | 用途 | 激活方式 |
|---------|------|---------|
| `dev`   | 本地开发：SQL debug 日志、1000MB 上传、本地数据库 | 默认 |
| `prod`  | 生产：关闭 SQL 日志、10MB 上传限制、数据源密钥注入 | `--spring.profiles.active=prod` |

### 生产环境变量（prod profile）

```bash
export DB_HOST=your-mysql-host
export DB_PORT=3306
export DB_NAME=cantingdiancai
export DB_USER=app_user
export DB_PASSWORD=<从密钥管理服务获取>
```

---

## 3. Vue 管理端构建 → 打入 JAR

管理端源码位于 `src/main/resources/admin/admin/`，已有预编译的 `dist/` 目录。
如需重新构建：

```bash
# 1) 安装依赖并构建
cd cantingdiancai/src/main/resources/admin/admin
npm install
npm run build

# 2) 构建产物已在 dist/ 目录下
#    Spring Boot 通过 InterceptorConfig 中的 classpath:/admin/ 映射自动加载
#    无需手动复制——Maven 打包时 resources/ 下所有内容都会进入 JAR
```

> **注意**：如果修改了后端 API 地址，需同步修改 `src/utils/base.js` 中的 `baseUrl`。
> 生产部署时建议改为相对路径 `/cantingdiancai/`，由 nginx 反向代理。

---

## 4. Maven 打包

```bash
cd cantingdiancai

# 开发构建
mvn clean package -DskipTests

# 生产构建（运行冒烟测试，需要 Docker）
mvn -Pprod clean verify

# 生产打包（跳过测试）
mvn -Pprod clean package -DskipTests
```

产物：`target/cantingdiancai-0.0.1-SNAPSHOT.jar`

### Reproducible Build

`pom.xml` 已配置 `project.build.outputTimestamp`，同一源码多次构建产出相同 JAR。

### Layered JAR（升级路线）

当前 Spring Boot 2.2.2 不支持 layered jar。升级到 2.3+ 后在 `spring-boot-maven-plugin` 中添加：

```xml
<configuration>
    <layers><enabled>true</enabled></layers>
</configuration>
```

即可用 `java -Djarmode=layertools -jar app.jar extract` 分层提取，优化 Docker 镜像缓存。

---

## 5. 启动

```bash
# 生产启动
java -jar target/cantingdiancai-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod
```

---

## 6. nginx 配置

Thymeleaf 前台页面（front）、Vue 管理端（admin）和 `/cantingdiancai` 下的 API 在同一 `location` 中通过反向代理统一处理：

```nginx
server {
    listen       80;
    server_name  your-domain.com;

    # 所有 /cantingdiancai 路径统一代理到 Spring Boot
    # 包括：Thymeleaf 前台页面、admin 管理端、REST API、静态资源
    location /cantingdiancai {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;

        # 上传文件限制与 prod profile 一致
        client_max_body_size 10m;

        # WebSocket（如需要）
        proxy_http_version 1.1;
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection "upgrade";
    }
}
```

### 访问路径说明

| 路径 | 内容 |
|------|------|
| `/cantingdiancai/front/front/index.html` | 用户端（Thymeleaf + jQuery/Vue） |
| `/cantingdiancai/admin/admin/dist/index.html` | 管理端（Vue SPA） |
| `/cantingdiancai/shangpin/page` 等 | REST API |

> 由于 Spring Boot 的 `context-path` 已经是 `/cantingdiancai`，nginx 只需一条 `location /cantingdiancai` 即可涵盖所有请求——前台页面、管理端 SPA、REST API 和静态资源上传，无需拆分多个 location 块。

---

## 7. CI

项目已配置 GitHub Actions（`.github/workflows/ci.yml`）：

- 触发：push / PR 到 master
- 流程：`mvn -Pprod verify`
- 冒烟测试使用 Testcontainers MySQL，CI 环境需要 Docker（GitHub Actions 默认提供）
