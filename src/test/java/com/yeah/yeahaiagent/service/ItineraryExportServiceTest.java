package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ParkingInfo;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF 渲染测试：验证 iText 9 能在无大模型、无外部网络的环境下，
 * 把内存中的 ItineraryPlan 渲染成合法 PDF 二进制。
 */
@DisplayName("ItineraryExportService - 行程 PDF 流式生成")
class ItineraryExportServiceTest {

    /**
     * PDF 协议魔数头
     */
    private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

    private ItineraryExportService exportService;

    @BeforeEach
    void setUp() {
        PoiRagService poiRagService = new PoiRagService();
        // 脱离 Spring 容器，手动触发 @PostConstruct 的私域数据装载
        poiRagService.initMockData();
        exportService = new ItineraryExportService(poiRagService);
    }

    @Test
    @DisplayName("中文行程导出为合法 PDF，带魔数头且体积合理")
    void testExportPdfSuccessfully() throws IOException {
        byte[] bytes = exportBytes(buildPlan(), "zh");

        assertThat(bytes).isNotNull();
        assertThat(bytes.length).isGreaterThan(1000);
        assertThat(Arrays.copyOf(bytes, 4)).isEqualTo(PDF_MAGIC);
    }

    @Test
    @DisplayName("英文行程走 Latin 字体分支，同样产出合法 PDF")
    void testExportEnglishPdf() throws IOException {
        byte[] bytes = exportBytes(buildPlan(), "en");

        assertThat(bytes.length).isGreaterThan(1000);
        assertThat(Arrays.copyOf(bytes, 4)).isEqualTo(PDF_MAGIC);
    }

    @Test
    @DisplayName("缺字段的残缺行程不应让导出崩溃")
    void testExportTolerateMissingFields() throws IOException {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(List.of(new ItineraryNode()));

        byte[] bytes = exportBytes(plan, "zh");

        assertThat(bytes.length).isGreaterThan(1000);
        assertThat(Arrays.copyOf(bytes, 4)).isEqualTo(PDF_MAGIC);
    }

    @Test
    @DisplayName("行程为 null 时快速失败，而不是产出空 PDF")
    void testExportRejectNullPlan() {
        assertThatThrownBy(() -> exportService.exportPlanToPdf(null, "zh"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] exportBytes(ItineraryPlan plan, String lang) throws IOException {
        File pdfFile = exportService.exportPlanToPdf(plan, lang);
        assertThat(pdfFile).isNotNull().exists().isFile();
        return Files.readAllBytes(pdfFile.toPath());
    }

    /**
     * 两个节点，均带 transitToNext 与 parkingInfo，并挂上推荐成立的落日评估
     */
    private ItineraryPlan buildPlan() {
        ItineraryNode first = new ItineraryNode(
                "node-1", "Single O Surry Hills", "Brunch/精品咖啡",
                "10:00", "60 分钟", "步行 8 分钟前往 The Record Store");
        first.setParkingInfo(new ParkingInfo(
                true,
                "Wilson Parking - Citipark Surry Hills",
                "2-38 Baptist St, Surry Hills NSW 2010",
                4.0,
                "步行约 4 分钟 (320m)",
                "距离 Crown St 咖啡带步行很近，周末建议 10 点前到",
                "parking near Single O Surry Hills"));

        ItineraryNode second = new ItineraryNode(
                "node-2", "The Record Store", "独立唱片店",
                "11:15", "45 分钟", "乘 Light Rail 15 分钟前往 Observatory Hill Park");
        second.setParkingInfo(new ParkingInfo(
                true,
                "Secure Parking - Riley Street",
                "102-104 Riley St, Surry Hills NSW 2010",
                3.9,
                "步行约 6 分钟 (480m)",
                "适合 Oxford St 一带，注意单行线进出，限高 2.0m",
                "parking near The Record Store Surry Hills"));

        ItineraryPlan plan = new ItineraryPlan(
                "悉尼 Surry Hills 黑胶与咖啡半日游",
                "从 Reservoir St 的手冲开始，穿过 Crown St 淘一张 Soul 黑胶，傍晚收在天文台山的落日机位。",
                List.of(first, second),
                List.of("若遇雨，可改去 White Rabbit Gallery 室内看展", "King St 路边车位紧张，优先进库"));
        plan.setSunsetEvaluation(new SunsetEvaluation(
                true,
                "18:05",
                "17:35 - 18:20",
                40,
                10,
                "今日多云微晴，晚霞概率高",
                "Observatory Hill Park",
                "建议提前 20 分钟到场，用海港大桥作前景压住画面右侧。"));
        return plan;
    }
}
