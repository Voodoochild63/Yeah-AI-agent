package com.yeah.yeahaiagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WebScrapingToolTest {

    @Test
    void scrapeWebPage() {
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        String url = "https://www.codefather.cn";
        String result = webScrapingTool.scrapeWebPage(url);
        Assertions.assertNotNull(result);
    }
}
