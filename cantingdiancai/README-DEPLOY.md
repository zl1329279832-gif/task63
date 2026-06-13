# 餐厅点餐管理系统 — 构建与部署指南

> 本文档覆盖：Vue 管理端打包进 JAR、Maven 分层构建、CI 流水线、nginx 反向代理配置。
> 业务代码（下单、退款等）不受影响，只涉及构建 / 配置 / CI。

---

## 1. 配置分层说明

| 文件 | 用途 | 激活方式 |
|---|---|---|
| `application.yml` | 公共配置 (端口、mybatis-plus 等) | 始终加载 |
| `application-dev.yml` | 本地/预发：SQL debug 日志、1000MB 上传 | 默认激活 |
| `application-prod.yml` | 生产：SQL warn、10MB 上传、环境变量注入数据源 | `SPRING_PROFILES_ACTIVE=prod` |

**生产环境变量 (必设):**

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL="jdbc:mysql://prod-host:3306/cantingdiancai?useUnicode=true&characterEncoding=utf-8&serverTimezone=GMT%2B8&useSSL=false"
export DB_USERNAME="app_user"
export DB_PASSWORD="***"        # 从密钥管理服务注入，禁止明文
export DB_POOL_SIZE=20          # HikariCP 最大连接数 (默认 20)
export DB_MIN_IDLE=5            # HikariCP 最小空闲 (默认 5)
```

---

## 2. Vue 管理端构建进 JAR

### 2.1 前置条件

- Node.js 12+ (推荐 14 LTS)
- cnpm 或 npm (镜像: `npm config set registry https://registry.npmmirror.com`)

### 2.2 构建步骤

```bash
# 进入 Vue 管理端目录
cd cantingdiancai/src/main/resources/admin/admin

# 安装依赖 (首次或 package.json 变更后)
cnpm install          # 或 npm install

# 生产构建
cnpm run build        # 或 npm run build
```

构建产物输出到 `dist/` 目录 (与 `index.html` 同级的 `css/`、`js/`、`img/`)。

### 2.3 打包进 JAR

Vue dist 目录已经位于 `src/main/resources/admin/admin/dist/`，Maven 打包时会
**自动**将其包含到 JAR 的 `BOOT-INF/classes/admin/admin/dist/` 路径下。

Spring Boot 的 `InterceptorConfig` 已将 `classpath:/admin/` 映射为静态资源，
因此管理端可通过以下 URL 访问：

```
http://<host>:8080/cantingdiancai/admin/admin/dist/index.html
```

> **注意:** 如果静态资源 404，检查 `vue.config.js` 中的 `publicPath`。
> 生产构建默认为 `././` (相对路径)，适用于 JAR 内部部署。
> 如需改为绝对路径，设为 `/cantingdiancai/admin/admin/dist/`。

### 2.4 前台 (Thymeleaf / 静态 HTML)

前台页面位于 `src/main/resources/front/front/`，是纯静态 HTML + Vue CDN + Layui，
不依赖构建步骤，直接随 JAR 打包。访问入口：

```
http://<host>:8080/cantingdiancai/front/front/index.html
```

---

## 3. Maven 构建

### 3.1 开发环境打包

```bash
cd cantingdiancai
mvn clean package -DskipTests
```

产出: `target/cantingdiancai-0.0.1-SNAPSHOT.jar`

### 3.2 生产环境打包 (含冒烟测试)

```bash
cd cantingdiancai
mvn clean package -Pprod verify
```

`-Pprod` 激活生产 profile，`verify` 阶段运行 Testcontainers MySQL 冒烟测试，
验证应用上下文能完整加载 (包括 DictionaryServletContextListener 字典缓存初始化)。

### 3.3 Layered JAR (Docker 镜像优化)

pom.xml 已配置 `spring-boot-maven-plugin` 的 `<layers>` 功能。
打包后的 JAR 内部分为四层，Docker 构建时可按层缓存：

| 层 | 内容 | 变化频率 |
|---|---|---|
| `dependencies` | release 版第三方 jar | 低 (仅依赖升级时变) |
| `spring-boot-loader` | Spring Boot 启动器 | 低 |
| `snapshot-dependencies` | SNAPSHOT 版依赖 | 中 |
| `application` | 业务 class + 静态资源 | 高 (每次代码变更) |

**提取分层目录:**

```bash
java -Djarmode=layertools -jar target/cantingdiancai-0.0.1-SNAPSHOT.jar extract
```

**推荐 Dockerfile:**

```dockerfile
FROM eclipse-temurin:8-jre AS builder
WORKDIR /app
COPY target/cantingdiancai-0.0.1-SNAPSHOT.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./
ENTRYPOINT ["java", \
  "-Dspring.profiles.active=prod", \
  "org.springframework.boot.loader.JarLauncher"]
```

> 依赖不变时，前三层命中 Docker cache，只有 `application` 层重建，
> 大幅缩短 CI 构建时间和推送体积。

---

## 4. CI 流水线 (GitHub Actions)

文件: `.github/workflows/ci.yml`

```
触发: push / pull_request → main, master, release/*
```

| 步骤 | 说明 |
|---|---|
| Checkout | 拉取代码 |
| JDK 11 | eclipse-temurin:11 (向下兼容 Java 8 字节码) |
| Docker | GitHub Actions runner 自带 Docker，Testcontainers 直接可用 |
| `mvn -Pprod verify` | 编译 + 单元测试 + Testcontainers 冒烟测试 + 打包 |
| Upload artifact | 上传 JAR 供后续部署 |

---

## 5. Nginx 配置

### 5.1 推荐配置 — 双 location (静态文件由 nginx 直出)

```nginx
server {
    listen       80;
    server_name  your-domain.com;

    # ① 前台 + 管理端静态文件 — nginx 直出，不经过 Spring Boot
    #    覆盖:
    #      /front/front/index.html   (用户前台)
    #      /admin/admin/dist/index.html (管理后台)
    #      /img/, /static/upload/    (图片资源)
    location /cantingdiancai/ {
        alias /opt/cantingdiancai/static/;   # JAR 旁边的静态文件目录
        index index.html;
        try_files $uri $uri/ =404;
    }

    # ② API 请求 — 反向代理到 Spring Boot
    #    覆盖: /cantingdiancai/shangpin/page, /cantingdiancai/yonghu/login 等全部 API
    location /cantingdiancai/ {
        # ⚠ 与上面 location 冲突时 nginx 取最长前缀匹配，
        #   实际使用中建议将 ① 拆为具体子路径，② 兜底。
        #   下方为完整可运行版本 ↓
    }
}
```

**完整可运行版本:**

```nginx
server {
    listen       80;
    server_name  your-domain.com;
    client_max_body_size 10m;                 # 与 application-prod.yml 保持一致

    # ── 前台 (用户端 HTML + Layui + Vue CDN) ──
    location /cantingdiancai/front/ {
        alias /opt/cantingdiancai/static/front/;
        index index.html;
        try_files $uri $uri/ =404;
    }

    # ── 管理后台 (Vue SPA dist) ──
    location /cantingdiancai/admin/admin/dist/ {
        alias /opt/cantingdiancai/static/admin/admin/dist/;
        index index.html;
        try_files $uri $uri/ /cantingdiancai/admin/admin/dist/index.html;
    }

    # ── 图片资源 ──
    location /cantingdiancai/img/ {
        alias /opt/cantingdiancai/static/img/;
        expires 30d;
    }
    location /cantingdiancai/static/upload/ {
        alias /opt/cantingdiancai/static/upload/;
        expires 7d;
    }

    # ── 全部 API 请求 → Spring Boot ──
    location /cantingdiancai/ {
        proxy_pass http://127.0.0.1:8080/cantingdiancai/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### 5.2 极简配置 — 单 location 全代理 (适合低流量 / 快速上线)

```nginx
server {
    listen       80;
    server_name  your-domain.com;
    client_max_body_size 10m;

    # 所有请求均由 Spring Boot 内部静态资源处理器兜底
    # 前台: /cantingdiancai/front/front/index.html
    # 管理: /cantingdiancai/admin/admin/dist/index.html
    # API:  /cantingdiancai/shangpin/page 等
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket 支持 (如后续加即时通讯)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

> **权衡:** 单 location 配置最简，但静态文件全部走 Spring Boot → JVM 内存，
> 高峰期吞吐不如 nginx 直出。生产推荐 §5.1 双 location 方案。

### 5.3 静态文件目录准备 (双 location 方案)

部署时从 JAR 中提取静态文件到 nginx 可访问的目录：

```bash
mkdir -p /opt/cantingdiancai/static
cd /opt/cantingdiancai/static

# 方式一: 直接从源码目录复制
cp -r <project>/cantingdiancai/src/main/resources/front ./
cp -r <project>/cantingdiancai/src/main/resources/admin ./
cp -r <project>/cantingdiancai/src/main/resources/img   ./

# 方式二: 从 JAR 中提取 (需要先解压)
jar xf cantingdiancai-0.0.1-SNAPSHOT.jar BOOT-INF/classes/front
jar xf cantingdiancai-0.0.1-SNAPSHOT.jar BOOT-INF/classes/admin
jar xf cantingdiancai-0.0.1-SNAPSHOT.jar BOOT-INF/classes/img
mv BOOT-INF/classes/front ./ && mv BOOT-INF/classes/admin ./ && mv BOOT-INF/classes/img ./
rm -rf BOOT-INF
```

---

## 6. 生产启动命令

```bash
SPRING_PROFILES_ACTIVE=prod \
DB_URL="jdbc:mysql://prod-host:3306/cantingdiancai?useUnicode=true&characterEncoding=utf-8&serverTimezone=GMT%2B8&useSSL=false" \
DB_USERNAME="app_user" \
DB_PASSWORD="***" \
java -jar cantingdiancai-0.0.1-SNAPSHOT.jar
```

或使用 systemd service：

```ini
[Unit]
Description=Cantingdiancai Restaurant Ordering System
After=network.target mysql.service

[Service]
Type=simple
User=app
Environment=SPRING_PROFILES_ACTIVE=prod
EnvironmentFile=/etc/cantingdiancai/env   # DB_URL, DB_USERNAME, DB_PASSWORD
ExecStart=/usr/bin/java -jar /opt/cantingdiancai/cantingdiancai.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

---

## 7. 关键配置对比

| 项目 | dev | prod |
|---|---|---|
| SQL 日志 | `com.dao: debug` (打印全量 SQL) | `com.dao: warn` (仅异常) |
| 单文件上传限制 | 1000 MB | 10 MB |
| 单次请求限制 | 1000 MB | 10 MB |
| 数据源 | 硬编码 127.0.0.1:3306 | 环境变量 `${DB_URL}` |
| DB 账号密码 | root/123456 | `${DB_USERNAME}` / `${DB_PASSWORD}` |
| HikariCP 最大连接 | 默认 10 | 20 (可通过 `DB_POOL_SIZE` 调整) |
| HTTP 压缩 | 未开启 | gzip (html/css/js/json, ≥2KB) |
| devtools | 包含 | prod profile 打包时排除 |
