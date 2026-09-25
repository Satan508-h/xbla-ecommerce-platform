# ============================================================
# 应用镜像（Spring Boot）—— 阶段 8
#
# 【为什么是两段构建】
#   构建要 JDK + Maven + 全部依赖（几百 MB），
#   运行只要 JRE + 一个 jar（几十 MB）。
#   合并成一段的话，镜像里会带着一整套构建工具链 ——
#   那是白白多出来的攻击面和下载时间。
#
# 【★ 为什么用 ./mvnw 而不是 maven:3.9.16 那个镜像】
#   .mvn/wrapper/maven-wrapper.properties 钉死了 Maven 3.9.16，
#   而镜像 tag 对应的版本是【别人决定的】。用 wrapper 的话，
#   容器里跑的 Maven 和本地跑的【逐字相同】——
#   少一个「本地能构建、容器里构建失败」的可能。
#   代价是构建时多下载一次 Maven（约 10MB，可缓存）。
#
# 【★ 上下文要排除 .env 和 application-local.yml】
#   见根部 .dockerignore 的开头 —— 那里面装着数据库密码和两个模型供应商的
#   API Key，而 docker 和 git 是两套机制，.gitignore 管不到 docker。
# ============================================================

# ------------------------------------------------------------
# 第一段：构建
# ------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# ★ 先只拷「依赖清单」，把下载依赖这一层单独成层。
#   这样只改 src/ 时，`dependency:go-offline` 那一层直接命中缓存，
#   不用重新下几百 MB 的依赖。★ 分层顺序就是构建速度。
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# ★★★ 这一行是 2026-09-24 那次构建失败的修复，值得读完：
#
#   容器里的 Maven 【不读】你本地的 ~/.m2/settings.xml ——
#   那个文件在用户主目录，不在构建上下文里，COPY 不进去。
#   于是容器直奔 repo.maven.apache.org，而它在国内连不上：
#
#       Failed to read artifact descriptor for ... mybatis-plus-...
#       repo.maven.apache.org:443 failed to respond
#
#   ★ 而【同一份 pom、同一个仓库，在宿主机上构建是好的】——
#     因为宿主机的 Maven 走了 ~/.m2/settings.xml 里配的阿里云镜像。
#     ⇒ 这就是「我机器上能跑」的典型成因：
#       **不是网络不好，是两边的构建环境不是同一个。**
#
#   这份 deploy/maven-settings.xml 里【没有任何凭据】（阿里云公共仓库是匿名的），
#   所以可以进 git。要换源就改它里面的一个 URL。
COPY deploy/maven-settings.xml ./settings-docker.xml

# ★ chmod 不能省：从 Windows 检出时文件的可执行位可能丢失，
#   那时 `./mvnw` 会报 Permission denied。
#   （换行符不用管 —— .gitattributes 已经锁了 eol=lf，实测工作区是 LF。）
#
# ★ 不要把 `-q` 加回来：构建失败时它会把「哪个依赖没下下来」一起吞掉，
#   而这次正是靠那句 ERROR 才定位到 settings.xml 的问题。
RUN chmod +x mvnw && ./mvnw -s settings-docker.xml -B -DskipTests dependency:go-offline

COPY src/ src/

# ★ -DskipTests：测试需要 docker 里的 postgres 和 redis（阶段 6 起），
#   在构建镜像的环境里没有它们。
#   ⚠️ 这【不是】「不测了」—— 测试是 `./mvnw clean test` 那条路的职责，
#      在宿主机上跑、有 858 个用例。构建镜像只负责打包。
RUN ./mvnw -s settings-docker.xml -B -DskipTests package

# ------------------------------------------------------------
# 第二段：运行
# ------------------------------------------------------------
FROM eclipse-temurin:21-jre

WORKDIR /app

# 日志目录。★ 必须和 application-prod.yml 里的 logging.file.name 对上。
#   对不上的症状是「容器起来了但日志文件没生成」——
#   而日志恰恰是出问题时唯一要看的东西。
RUN mkdir -p /var/log/xbla

# ★ 用带名字的 glob，不要 COPY target/*.jar：
#   Spring Boot 的 repackage 会留下一个 `xxx.jar.original`，
#   而 `*.jar` 不会匹配它（它以 .original 结尾），所以其实是安全的 ——
#   但写成 xbla-rag-*.jar 意图更清楚，也不受将来多出别的 jar 影响。
COPY --from=builder /build/target/xbla-rag-*.jar /app/app.jar

# ★★ 这里【不】把端口映射到宿主机（compose 里也不映射）——
#   让容器只在 docker 网络里被 Nginx 访问。
#   这样宿主机的 8080 留给 `./mvnw spring-boot:run`，
#   两条路（本地开发 / 容器部署）能同时存在、不抢端口。
#   ★ 而阶段 7 的 eval_run.py / probe_*.py 全部假设 localhost:8080，
#     那一条链不能断。
EXPOSE 8080

# ★ 用 exec 形式（JSON 数组）而不是 shell 形式：
#   shell 形式会让 java 成为 sh 的子进程，SIGTERM 发给 sh 而不是 java，
#   于是 `docker stop` 要等超时才强杀 ——
#   而强杀会让排队的名额来不及释放（阶段 6 的名额泄漏那条路）。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
