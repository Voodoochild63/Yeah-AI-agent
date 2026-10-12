package com.yeah.yeahaiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * CI 门禁：拉起完整 Spring 容器，拦截 Bean 注入 / Lombok 构造失败。
 * 使用 test profile 的 dummy key，不调用外部 API。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class YeahAiAgentApplicationTests {

    @Test
    void contextLoads() {
    }
}
