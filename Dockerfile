# 使用预装 Maven 和 JDK21 的镜像
FROM maven:3.9-amazoncorretto-21
WORKDIR /app

# 只复制必要的源代码和配置文件
COPY pom.xml .
COPY src ./src

# 使用 Maven 执行打包，跳过测试
RUN mvn clean package -DskipTests

# 本地默认端口；Render 等平台会注入 PORT，启动时优先使用它
EXPOSE 8123

# 通配符匹配 /app/target 下的 yeah-ai-agent-*.jar，并监听 $PORT
CMD ["sh", "-c", "exec java -jar /app/target/yeah-ai-agent-*.jar --spring.profiles.active=prod --server.port=${PORT:-8123}"]