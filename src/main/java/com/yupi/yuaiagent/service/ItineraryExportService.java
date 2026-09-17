package com.yupi.yuaiagent.service;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.LineSeparator;
import com.itextpdf.layout.element.ListItem;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.BorderRadius;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.yupi.yuaiagent.model.dto.ItineraryNode;
import com.yupi.yuaiagent.model.dto.ItineraryPlan;
import com.yupi.yuaiagent.model.dto.SunsetEvaluation;
import com.yupi.yuaiagent.constant.FileConstant;
import com.yupi.yuaiagent.tools.PDFGenerationTool;
import com.yupi.yuaiagent.tools.PDFGenerationTool.CjkFontPair;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 按需将当前行程方案导出为画册质感的本地 PDF
 */
@Service
public class ItineraryExportService {

    private static final String PDF_FILE_NAME = "itinerary.pdf";

    /**
     * ItineraryNode 暂未提供 openingHours，先以占位文案预留该行
     */
    private static final String OPENING_HOURS_PLACEHOLDER = "请参考当日营业状态";

    private final PoiRagService poiRagService;

    public ItineraryExportService(PoiRagService poiRagService) {
        this.poiRagService = poiRagService;
    }

    public File exportPlanToPdf(ItineraryPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("行程数据不能为空");
        }
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        FileUtil.mkdir(fileDir);
        File pdfFile = new File(fileDir, PDF_FILE_NAME);
        try (PdfWriter writer = new PdfWriter(pdfFile.getAbsolutePath());
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {
            CjkFontPair fonts = PDFGenerationTool.loadCjkFontPair();
            document.setMargins(PDFGenerationTool.PAGE_MARGIN, PDFGenerationTool.PAGE_MARGIN,
                    PDFGenerationTool.PAGE_MARGIN, PDFGenerationTool.PAGE_MARGIN);
            // 根节点统一常规体，所有子元素默认继承同一字体族
            if (fonts.regular() != null) {
                document.setFont(fonts.regular());
            }
            document.setFontSize(PDFGenerationTool.FONT_SIZE_BODY)
                    .setFontColor(PDFGenerationTool.COLOR_BODY);

            Styler styler = new Styler(fonts);
            renderTitle(document, styler, plan);
            renderSummary(document, styler, plan);
            renderSchedule(document, styler, plan);
            renderSunsetHighlight(document, styler, plan);
            renderBackupSuggestions(document, styler, plan);
        } catch (Exception e) {
            throw new IllegalStateException("PDF 生成失败: " + e.getMessage(), e);
        }
        return pdfFile;
    }

    /**
     * 第一模块：居中主标题 + 60% 宽居中短分割线
     */
    private void renderTitle(Document document, Styler styler, ItineraryPlan plan) {
        document.add(styler.boldParagraph(fallback(plan.getTitle(), "我的一日行程"))
                .setFontSize(PDFGenerationTool.FONT_SIZE_TITLE)
                .setFontColor(PDFGenerationTool.COLOR_INK)
                .setTextAlignment(TextAlignment.CENTER)
                .setMultipliedLeading(1.3f)
                .setMarginBottom(6));

        SolidLine line = new SolidLine(0.8f);
        line.setColor(PDFGenerationTool.COLOR_BORDER);
        document.add(new LineSeparator(line)
                .setWidth(UnitValue.createPercentValue(60))
                .setHorizontalAlignment(HorizontalAlignment.CENTER)
                .setMarginBottom(18));
    }

    /**
     * 第二模块：全宽淡色摘要卡片
     */
    private void renderSummary(Document document, Styler styler, ItineraryPlan plan) {
        Div card = new Div()
                .setBackgroundColor(PDFGenerationTool.COLOR_CARD_BG)
                .setBorder(new SolidBorder(PDFGenerationTool.COLOR_BORDER, 0.8f))
                .setBorderRadius(new BorderRadius(6))
                .setPadding(12)
                .setMarginBottom(20);
        card.add(styler.paragraph(fallback(plan.getSummary(), "暂无概要"))
                .setFirstLineIndent(10)
                .setMargin(0));
        document.add(card);
    }

    /**
     * 第三模块：时间轴卡片，每个地点严格分段
     */
    private void renderSchedule(Document document, Styler styler, ItineraryPlan plan) {
        List<ItineraryNode> schedule = plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            document.add(styler.paragraph("暂无行程节点").setMarginBottom(16));
            return;
        }
        for (ItineraryNode node : schedule) {
            if (node == null) {
                continue;
            }
            document.add(buildNodeCard(styler, node));
        }
    }

    private Div buildNodeCard(Styler styler, ItineraryNode node) {
        Div card = new Div()
                .setBorder(new SolidBorder(PDFGenerationTool.COLOR_BORDER, 0.8f))
                .setBorderRadius(new BorderRadius(6))
                .setPadding(12)
                .setMarginBottom(16)
                .setKeepTogether(true);

        // 第 1 行：时间徽章 + 店名，两者均使用真实粗体
        Paragraph header = new Paragraph()
                .setMargin(0)
                .setMarginBottom(8)
                .setMultipliedLeading(1.3f);
        header.add(styler.boldText(" " + fallback(node.arrivalTime(), "待定") + " ")
                .setFontSize(PDFGenerationTool.FONT_SIZE_BADGE)
                .setFontColor(PDFGenerationTool.COLOR_BADGE_TEXT)
                .setBackgroundColor(PDFGenerationTool.COLOR_BADGE_BG, 4, 3, 4, 3));
        header.add(styler.text("   "));
        header.add(styler.boldText(fallback(node.poiName(), "未命名地点"))
                .setFontSize(PDFGenerationTool.FONT_SIZE_EMPHASIS)
                .setFontColor(PDFGenerationTool.COLOR_NAVY));
        card.add(header);

        // 第 2 行：类别药丸 + 营业时间
        Paragraph attributes = new Paragraph()
                .setMargin(0)
                .setMarginBottom(6)
                .setMultipliedLeading(PDFGenerationTool.LEADING_BODY);
        attributes.add(styler.text(" 🏷️ 类别：" + fallback(node.category(), "未分类") + " ")
                .setFontSize(PDFGenerationTool.FONT_SIZE_BADGE)
                .setFontColor(PDFGenerationTool.COLOR_BADGE_TEXT)
                .setBackgroundColor(PDFGenerationTool.COLOR_BADGE_BG, 4, 3, 4, 3));
        attributes.add(styler.text("    "));
        attributes.add(styler.text("🕒 营业时间：" + OPENING_HOURS_PLACEHOLDER));
        card.add(attributes);

        // 第 3 行：建议停留
        card.add(styler.paragraph("⏱️ 建议停留：" + fallback(node.stayDuration(), "机动安排"))
                .setMargin(0)
                .setMarginBottom(8));

        // 第 4 行：下一程通勤，灰底 + 左侧引导线
        Div transit = new Div()
                .setBackgroundColor(PDFGenerationTool.COLOR_CARD_BG)
                .setBorderLeft(new SolidBorder(PDFGenerationTool.COLOR_NAVY, 2f))
                .setPadding(8)
                .setMarginBottom(8);
        transit.add(styler.paragraph("🚶 下一程通勤：" + fallback(node.transitToNext(), "行程结束"))
                .setMargin(0));
        card.add(transit);

        // 第 5 行：私藏贴士，命中私域知识库时才渲染
        String personalTip = poiRagService.findPersonalTip(node.poiName());
        if (personalTip != null && !personalTip.isBlank()) {
            Div tip = new Div()
                    .setBackgroundColor(PDFGenerationTool.COLOR_TIP_BG)
                    .setBorderLeft(new SolidBorder(PDFGenerationTool.COLOR_TIP_TEXT, 2f))
                    .setBorderRadius(new BorderRadius(4))
                    .setPadding(8);
            tip.add(styler.paragraph("💡 私藏笔记：" + personalTip)
                    .setFontColor(PDFGenerationTool.COLOR_TIP_TEXT)
                    .setMargin(0));
            card.add(tip);
        }
        return card;
    }

    /**
     * 落日专题：条件达标时在时间轴之后、备选建议之前插入高亮卡片
     */
    private void renderSunsetHighlight(Document document, Styler styler, ItineraryPlan plan) {
        SunsetEvaluation evaluation = plan.getSunsetEvaluation();
        if (evaluation == null || !evaluation.recommended()) {
            return;
        }
        Div card = new Div()
                .setBackgroundColor(PDFGenerationTool.COLOR_SUNSET_BG)
                .setBorder(new SolidBorder(PDFGenerationTool.COLOR_SUNSET_BORDER, 1.2f))
                .setBorderRadius(new BorderRadius(6))
                .setPadding(12)
                .setMarginBottom(20)
                .setKeepTogether(true);
        card.add(styler.boldParagraph("🌅 黄金落日推荐 · %s（%s）".formatted(
                        fallback(evaluation.recommendedSpot(), "待定机位"),
                        fallback(evaluation.goldenHourWindow(), "以当日日落时间为准")))
                .setFontSize(12)
                .setFontColor(PDFGenerationTool.COLOR_SUNSET_TITLE)
                .setMargin(0)
                .setMarginBottom(8));
        card.add(styler.paragraph(fallback(evaluation.recommendationReason(), "今日落日条件良好"))
                .setFontColor(PDFGenerationTool.COLOR_TIP_TEXT)
                .setMargin(0)
                .setMarginBottom(4));
        card.add(styler.paragraph("拍摄指南：" + fallback(evaluation.photoTip(), "提前到场观察光线走向"))
                .setFontColor(PDFGenerationTool.COLOR_TIP_TEXT)
                .setMargin(0));
        document.add(card);
    }

    /**
     * 第四模块：备选方案与出行提示
     */
    private void renderBackupSuggestions(Document document, Styler styler, ItineraryPlan plan) {
        Div section = new Div()
                .setMarginTop(6)
                .setKeepTogether(true);
        section.add(styler.boldParagraph("✦ 灵感备选与出行须知")
                .setFontSize(12)
                .setFontColor(PDFGenerationTool.COLOR_INK)
                .setMargin(0)
                .setMarginBottom(8));

        List<String> backups = collectSuggestions(plan);
        if (backups.isEmpty()) {
            section.add(styler.paragraph("暂无备选方案").setMargin(0));
            document.add(section);
            return;
        }
        com.itextpdf.layout.element.List list = new com.itextpdf.layout.element.List()
                .setSymbolIndent(8)
                .setListSymbol(styler.text("•").setFontColor(PDFGenerationTool.COLOR_NAVY))
                .setFontColor(PDFGenerationTool.COLOR_BODY);
        for (String suggestion : backups) {
            if (suggestion == null || suggestion.isBlank()) {
                continue;
            }
            list.add((ListItem) new ListItem()
                    .add(styler.paragraph(suggestion)
                            .setMargin(0)
                            .setMultipliedLeading(1.5f))
                    .setMarginBottom(5));
        }
        section.add(list);
        document.add(section);
    }

    /**
     * 汇总备选建议，落日条件不佳时补一条柔和提示
     */
    private List<String> collectSuggestions(ItineraryPlan plan) {
        List<String> suggestions = new ArrayList<>();
        if (plan.getBackupSuggestions() != null) {
            plan.getBackupSuggestions().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .forEach(suggestions::add);
        }
        SunsetEvaluation evaluation = plan.getSunsetEvaluation();
        if (evaluation != null && !evaluation.recommended()) {
            suggestions.add("今日落日观赏指数不高（%s），不必特意为看日落调整路线；%s".formatted(
                    fallback(evaluation.recommendationReason(), "云量与降水条件一般"),
                    fallback(evaluation.photoTip(), "可把重心放在室内场景上")));
        }
        return suggestions;
    }

    private String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 统一套用同族字重并剔除字体不支持的字符，正文一律走常规体 10pt
     */
    private record Styler(CjkFontPair fonts) {

        private Text text(String value) {
            return applyFont(new Text(PDFGenerationTool.sanitize(value, fonts.regular())), fonts.regular())
                    .setFontSize(PDFGenerationTool.FONT_SIZE_BODY);
        }

        private Text boldText(String value) {
            return applyFont(new Text(PDFGenerationTool.sanitize(value, fonts.bold())), fonts.bold());
        }

        private Paragraph paragraph(String value) {
            return applyFont(new Paragraph(PDFGenerationTool.sanitize(value, fonts.regular())), fonts.regular())
                    .setFontSize(PDFGenerationTool.FONT_SIZE_BODY)
                    .setMultipliedLeading(PDFGenerationTool.LEADING_BODY);
        }

        private Paragraph boldParagraph(String value) {
            return applyFont(new Paragraph(PDFGenerationTool.sanitize(value, fonts.bold())), fonts.bold());
        }

        private <T extends com.itextpdf.layout.ElementPropertyContainer<T>> T applyFont(T element, PdfFont font) {
            return font == null ? element : element.setFont(font);
        }
    }
}
