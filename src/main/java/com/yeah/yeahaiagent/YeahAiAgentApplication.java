package com.yeah.yeahaiagent;

import com.yeah.yeahaiagent.config.SerperProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties(SerperProperties.class)
@SpringBootApplication(exclude = {
        // 为了便于大家开发调试和部署，取消数据库自动配置，需要使用 PgVector 时把 DataSourceAutoConfiguration.class 删除
        DataSourceAutoConfiguration.class
})
public class YeahAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(YeahAiAgentApplication.class, args);
    }

}
