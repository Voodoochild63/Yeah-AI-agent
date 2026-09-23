package com.yeah.yeahaiagent.service;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.kernel.colors.DeviceRgb;
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
import com.yeah.yeahaiagent.constant.FileConstant;
import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ParkingInfo;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.tools.PDFGenerationTool;
import com.yeah.yeahaiagent.tools.PDFGenerationTool.CjkFontPair;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 按需将当前行程方案导出为画册质感的本地 PDF，支持中英文排版与停车信息挂载
 */
@Service
public class ItineraryExportService {

    private static final String PDF_FILE_NAME = "itinerary.pdf";

    private final PoiRagService poiRagService;

    public ItineraryExportService(PoiRagService poiRagService) {
        this.poiRagService = poiRagService;
    }

    public File exportPlanToPdf(ItineraryPlan plan) {
        return exportPlanToPdf(plan, "zh");
    }

    public File exportPlanToPdf(ItineraryPlan plan, String lang) {
        if (plan == null) {
            throw new IllegalArgumentException("行程数据不能为空");
        }
        LanguageEnum language = LanguageEnum.fromCode(lang);
        PdfLabels labels = PdfLabels.of(language);
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        FileUtil.mkdir(fileDir);
        File pdfFile = new File(fileDir, PDF_FILE_NAME);
        try (PdfWriter writer = new PdfWriter(pdfFile.getAbsolutePath());
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {
            CjkFontPair fonts = PDFGenerationTool.loadFontPair(language);
            document.setMargins(PDFGenerationTool.PAGE_MARGIN, PDFGenerationTool.PAGE_MARGIN,
                    PDFGenerationTool.PAGE_MARGIN, PDFGenerationTool.PAGE_MARGIN);
            if (fonts.regular() != null) {
                document.setFont(fonts.regular());
            }
            document.setFontSize(PDFGenerationTool.FONT_SIZE_BODY)
                    .setFontColor(PDFGenerationTool.COLOR_INK);

            ExportContext ctx = new ExportContext(language, labels, new Styler(fonts));
            renderTitle(document, ctx, plan);
            renderSummary(document, ctx, plan);
            renderSchedule(document, ctx, plan);
            renderSunsetHighlight(document, ctx, plan);
            renderBackupSuggestions(document, ctx, plan);
        } catch (Exception e) {
            throw new IllegalStateException("PDF 生成失败: " + e.getMessage(), e);
        }
        return pdfFile;
    }

    /**
     * 第一模块：品牌标头 + 居中主标题 + 60% 宽居中短分割线
     */
    private void renderTitle(Document document, ExportContext ctx, ItineraryPlan plan) {
        document.add(ctx.styler().paragraph(ctx.labels().brandTitle())
                .setFontSize(PDFGenerationTool.FONT_SIZE_BADGE)
                .setFontColor(PDFGenerationTool.COLOR_TIME)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(4)
                .setKeepWithNext(true));
        document.add(ctx.styler().boldParagraph(fallback(plan.localizedTitle(ctx.language()), ctx.labels().fallbackTitle()))
                .setFontSize(PDFGenerationTool.FONT_SIZE_TITLE)
                .setFontColor(PDFGenerationTool.COLOR_INK)
                .setTextAlignment(TextAlignment.CENTER)
                .setMultipliedLeading(1.3f)
                .setMarginBottom(6)
                .setKeepWithNext(true));

        SolidLine line = new SolidLine(0.8f);
        line.setColor(PDFGenerationTool.COLOR_BORDER);
        document.add(new LineSeparator(line)
                .setWidth(UnitValue.createPercentValue(60))
                .setHorizontalAlignment(HorizontalAlignment.CENTER)
                .setMarginBottom(12)
                .setKeepWithNext(true));
    }

    /**
     * 第二模块：全宽淡色摘要卡片
     */
    private void renderSummary(Document document, ExportContext ctx, ItineraryPlan plan) {
        document.add(sectionHeading(ctx, ctx.labels().overview()));
        Div card = new Div()
                .setBackgroundColor(PDFGenerationTool.COLOR_CARD_BG)
                .setBorder(new SolidBorder(PDFGenerationTool.COLOR_BORDER, 0.8f))
                .setBorderRadius(new BorderRadius(6))
                .setPadding(PDFGenerationTool.CARD_PADDING)
                .setMarginBottom(PDFGenerationTool.CARD_MARGIN);
        card.add(ctx.styler().paragraph(fallback(plan.localizedSummary(ctx.language()), ctx.labels().emptySummary()))
                .setFirstLineIndent(10)
                .setMargin(0));
        document.add(card);
    }

    /**
     * 第三模块：时间轴卡片，每个地点严格分段
     */
    private void renderSchedule(Document document, ExportContext ctx, ItineraryPlan plan) {
        document.add(sectionHeading(ctx, ctx.labels().schedule()));
        List<ItineraryNode> schedule = plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            document.add(ctx.styler().paragraph(ctx.labels().emptySchedule()).setMarginBottom(PDFGenerationTool.CARD_MARGIN));
            return;
        }
        for (ItineraryNode node : schedule) {
            if (node == null) {
                continue;
            }
            document.add(buildNodeCard(ctx, node));
        }
    }

    private Div buildNodeCard(ExportContext ctx, ItineraryNode node) {
        Styler styler = ctx.styler();
        PdfLabels labels = ctx.labels();
        Div card = new Div()
                .setBorder(new SolidBorder(PDFGenerationTool.COLOR_BORDER, 0.8f))
                .setBorderRadius(new BorderRadius(6))
                .setPadding(PDFGenerationTool.CARD_PADDING)
                .setMarginBottom(PDFGenerationTool.CARD_MARGIN);

        Paragraph header = new Paragraph()
                .setMargin(0)
                .setMarginBottom(8)
                .setMultipliedLeading(1.3f);
        header.add(styler.boldText(" " + fallback(node.getArrivalTime(), labels.timePending()) + " ")
                .setFontSize(PDFGenerationTool.FONT_SIZE_BADGE)
                .setFontColor(PDFGenerationTool.COLOR_TIME)
                .setBackgroundColor(PDFGenerationTool.COLOR_TIME_BG, 4, 3, 4, 3));
        header.add(styler.text("   "));
        header.add(styler.boldText(fallback(node.getPoiName(), labels.unnamedPlace()))
                .setFontSize(PDFGenerationTool.FONT_SIZE_EMPHASIS)
                .setFontColor(PDFGenerationTool.COLOR_INK));
        card.add(header);

        Div parkingCard = buildParkingCard(ctx, node.getParkingInfo());
        if (parkingCard != null) {
            card.add(parkingCard);
        }

        Paragraph attributes = new Paragraph()
                .setMargin(0)
                .setMarginBottom(6)
                .setMultipliedLeading(PDFGenerationTool.LEADING_BODY);
        attributes.add(styler.text(" " + labels.category() + fallback(node.localizedCategory(ctx.language()), labels.uncategorized()) + " ")
                .setFontSize(PDFGenerationTool.FONT_SIZE_BADGE)
                .setFontColor(PDFGenerationTool.COLOR_SLATE)
                .setBackgroundColor(PDFGenerationTool.COLOR_CARD_BG, 4, 3, 4, 3));
        attributes.add(styler.text("    "));
        attributes.add(styler.text(labels.openingHours() + labels.openingHoursPlaceholder())
                .setFontColor(PDFGenerationTool.COLOR_SLATE));
        card.add(attributes);

        card.add(styler.paragraph(labels.stay() + fallback(node.getStayDuration(), labels.stayFlexible()),
                        PDFGenerationTool.COLOR_SLATE)
                .setMargin(0)
                .setMarginBottom(8));

        Div transit = new Div()
                .setBackgroundColor(PDFGenerationTool.COLOR_TRANSIT_BG)
                .setBorder(new SolidBorder(PDFGenerationTool.COLOR_TRANSIT_BORDER, 0.8f))
                .setBorderLeft(new SolidBorder(PDFGenerationTool.COLOR_TRANSIT_BORDER, 3f))
                .setPadding(8)
                .setMarginBottom(8)
                .setFontColor(PDFGenerationTool.COLOR_TRANSIT_TEXT);
        transit.add(styler.paragraph(
                        labels.transit() + fallback(node.getTransitToNext(), labels.tripEnd()),
                        PDFGenerationTool.COLOR_TRANSIT_TEXT)
                .setMargin(0));
        card.add(transit);

        String personalTip = poiRagService.findPersonalTip(node.getPoiName());
        if (shouldRenderPersonalTip(personalTip, ctx.language())) {
            Div tip = new Div()
                    .setBackgroundColor(PDFGenerationTool.COLOR_TIP_BG)
                    .setBorderLeft(new SolidBorder(PDFGenerationTool.COLOR_TIP_TEXT, 2f))
                    .setBorderRadius(new BorderRadius(4))
                    .setPadding(8);
            tip.add(styler.paragraph(labels.privateNote() + personalTip, PDFGenerationTool.COLOR_TIP_TEXT)
                    .setMargin(0));
            card.add(tip);
        }
        return card;
    }

    /**
     * 时间节点下方的停车建议：贴士 + Google Maps 检索词，不写具体车库名。
     */
    private Div buildParkingCard(ExportContext ctx, ParkingInfo parkingInfo) {
        if (parkingInfo == null || !parkingInfo.hasParkingRecommendation()) {
            return null;
        }
        Styler styler = ctx.styler();
        PdfLabels labels = ctx.labels();
        String tip = fallback(parkingInfo.tip(), "");
        String keyword = fallback(parkingInfo.parkingSearchKeyword(), "");

        Div parking = new Div()
                .setBackgroundColor(PDFGenerationTool.COLOR_PARKING_BG)
                .setBorder(new SolidBorder(PDFGenerationTool.COLOR_PARKING_BORDER, 0.8f))
                .setBorderRadius(new BorderRadius(6))
                .setPadding(8)
                .setMarginBottom(8)
                .setFontColor(PDFGenerationTool.COLOR_PARKING_NAME);

        Paragraph headline = new Paragraph()
                .setMargin(0)
                .setMarginBottom(4)
                .setMultipliedLeading(PDFGenerationTool.LEADING_BODY)
                .setFontColor(PDFGenerationTool.COLOR_PARKING_NAME);
        headline.add(styler.boldText(labels.parkingPrefix() + " ", PDFGenerationTool.COLOR_PARKING_LABEL)
                .setFontSize(PDFGenerationTool.FONT_SIZE_BADGE));
        headline.add(styler.boldText(
                tip.isBlank() ? fallback(keyword, labels.unnamedPlace()) : tip,
                PDFGenerationTool.COLOR_PARKING_NAME)
                .setFontSize(PDFGenerationTool.FONT_SIZE_BADGE));
        parking.add(headline);

        if (!keyword.isBlank()) {
            parking.add(styler.paragraph(keyword, PDFGenerationTool.COLOR_PARKING_MUTED)
                    .setFontSize(PDFGenerationTool.FONT_SIZE_BADGE)
                    .setMargin(0));
        }
        return parking;
    }

    /**
     * 落日专题：条件达标时在时间轴之后、备选建议之前插入高亮卡片
     */
    private void renderSunsetHighlight(Document document, ExportContext ctx, ItineraryPlan plan) {
        SunsetEvaluation evaluation = plan.getSunsetEvaluation();
        if (evaluation == null || !evaluation.recommended()) {
            return;
        }
        document.add(sectionHeading(ctx, ctx.labels().sunset()));
        PdfLabels labels = ctx.labels();
        Styler styler = ctx.styler();
        Div card = new Div()
                .setBackgroundColor(PDFGenerationTool.COLOR_SUNSET_BG)
                .setBorder(new SolidBorder(PDFGenerationTool.COLOR_SUNSET_BORDER, 1.2f))
                .setBorderRadius(new BorderRadius(6))
                .setPadding(PDFGenerationTool.CARD_PADDING)
                .setMarginBottom(PDFGenerationTool.CARD_MARGIN);
        card.add(styler.boldParagraph(labels.sunsetHeadline().formatted(
                        fallback(evaluation.recommendedSpot(), labels.spotPending()),
                        fallback(evaluation.goldenHourWindow(), labels.sunsetWindowFallback())),
                        PDFGenerationTool.COLOR_SUNSET_TITLE)
                .setFontSize(12)
                .setMargin(0)
                .setMarginBottom(8));
        card.add(styler.paragraph(fallback(evaluation.recommendationReason(), labels.sunsetGood()),
                        PDFGenerationTool.COLOR_TIP_TEXT)
                .setMargin(0)
                .setMarginBottom(4));
        card.add(styler.paragraph(labels.photoGuide() + fallback(evaluation.photoTip(), labels.photoGuideFallback()),
                        PDFGenerationTool.COLOR_TIP_TEXT)
                .setMargin(0));
        document.add(card);
    }

    /**
     * 第四模块：备选方案与出行提示
     */
    private void renderBackupSuggestions(Document document, ExportContext ctx, ItineraryPlan plan) {
        document.add(sectionHeading(ctx, ctx.labels().alternatives()));
        Div section = new Div()
                .setMarginTop(0);

        List<String> backups = collectSuggestions(plan, ctx);
        if (backups.isEmpty()) {
            section.add(ctx.styler().paragraph(ctx.labels().emptyAlternatives()).setMargin(0));
            document.add(section);
            return;
        }
        com.itextpdf.layout.element.List list = new com.itextpdf.layout.element.List()
                .setSymbolIndent(8)
                .setListSymbol(ctx.styler().text("•").setFontColor(PDFGenerationTool.COLOR_TIME))
                .setFontColor(PDFGenerationTool.COLOR_SLATE);
        for (String suggestion : backups) {
            if (suggestion == null || suggestion.isBlank()) {
                continue;
            }
            list.add((ListItem) new ListItem()
                    .add(ctx.styler().paragraph(suggestion)
                            .setMargin(0)
                            .setMultipliedLeading(1.5f))
                    .setMarginBottom(5));
        }
        section.add(list);
        document.add(section);
    }

    private Paragraph sectionHeading(ExportContext ctx, String heading) {
        return ctx.styler().boldParagraph(heading)
                .setFontSize(PDFGenerationTool.FONT_SIZE_SECTION)
                .setFontColor(PDFGenerationTool.COLOR_INK)
                .setMargin(0)
                .setMarginBottom(8)
                .setKeepTogether(true)
                .setKeepWithNext(true);
    }

    /**
     * 汇总备选建议，落日条件不佳时补一条柔和提示
     */
    private List<String> collectSuggestions(ItineraryPlan plan, ExportContext ctx) {
        List<String> suggestions = new ArrayList<>();
        List<String> localized = plan.localizedBackupSuggestions(ctx.language());
        if (localized != null) {
            localized.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .forEach(suggestions::add);
        }
        SunsetEvaluation evaluation = plan.getSunsetEvaluation();
        if (evaluation != null && !evaluation.recommended()) {
            suggestions.add(ctx.labels().sunsetSoftTip().formatted(
                    fallback(evaluation.recommendationReason(), ctx.labels().sunsetFair()),
                    fallback(evaluation.photoTip(), ctx.labels().indoorFocus())));
        }
        return suggestions;
    }

    private boolean shouldRenderPersonalTip(String tip, LanguageEnum language) {
        if (tip == null || tip.isBlank()) {
            return false;
        }
        if (language.isZh()) {
            return true;
        }
        return tip.codePoints().noneMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record ExportContext(LanguageEnum language, PdfLabels labels, Styler styler) {
    }

    private record PdfLabels(
            String brandTitle,
            String fallbackTitle,
            String overview,
            String schedule,
            String alternatives,
            String sunset,
            String emptySummary,
            String emptySchedule,
            String emptyAlternatives,
            String timePending,
            String unnamedPlace,
            String category,
            String uncategorized,
            String openingHours,
            String openingHoursPlaceholder,
            String stay,
            String stayFlexible,
            String transit,
            String tripEnd,
            String privateNote,
            String parkingPrefix,
            String walkingUnknown,
            String address,
            String sunsetHeadline,
            String spotPending,
            String sunsetWindowFallback,
            String sunsetGood,
            String photoGuide,
            String photoGuideFallback,
            String sunsetSoftTip,
            String sunsetFair,
            String indoorFocus
    ) {
        static PdfLabels of(LanguageEnum language) {
            return language.isZh() ? ZH : EN;
        }

        private static final PdfLabels ZH = new PdfLabels(
                "悉尼生活与社交探索行程单",
                "我的一日行程",
                "行程摘要",
                "时间轴节点",
                "备选推荐",
                "落日与摄影评估",
                "暂无概要",
                "暂无行程节点",
                "暂无备选方案",
                "待定",
                "未命名地点",
                "类别：",
                "未分类",
                "营业时间：",
                "请参考当日营业状态",
                "建议停留：",
                "机动安排",
                "下一程通勤：",
                "行程结束",
                "私藏笔记：",
                "[停车建议]",
                "步行距离待确认",
                "地址：",
                "黄金落日推荐 · %s（%s）",
                "待定机位",
                "以当日日落时间为准",
                "今日落日条件良好",
                "拍摄指南：",
                "提前到场观察光线走向",
                "今日落日观赏指数不高（%s），不必特意为看日落调整路线；%s",
                "云量与降水条件一般",
                "可把重心放在室内场景上"
        );

        private static final PdfLabels EN = new PdfLabels(
                "Sydney Lifestyle & Social Itinerary",
                "My Day Plan",
                "Overview",
                "Schedule Timeline",
                "Alternative Spots",
                "Sunset & Golden Hour Forecast",
                "No overview yet",
                "No schedule nodes yet",
                "No alternatives yet",
                "TBD",
                "Untitled stop",
                "Category: ",
                "Uncategorized",
                "Hours: ",
                "Check today's opening hours",
                "Suggested stay: ",
                "Flexible",
                "Next transit: ",
                "End of trip",
                "Private note: ",
                "[Parking Assist]",
                "Walking time TBC",
                "Address: ",
                "Golden hour pick · %s (%s)",
                "Spot TBC",
                "Based on today's sunset",
                "Sunset conditions look good",
                "Photo guide: ",
                "Arrive early and watch the light",
                "Sunset viewing is not strong today (%s). No need to reshape the route for it. %s",
                "Cloud and rain are average",
                "Keep the focus on indoor stops"
        );
    }

    /**
     * 统一套用同族字重；颜色一律打在 Text 上，避免继承父 Div 的浅色
     */
    private record Styler(CjkFontPair fonts) {

        private Text text(String value) {
            return text(value, PDFGenerationTool.COLOR_INK);
        }

        private Text text(String value, DeviceRgb color) {
            return applyFont(new Text(PDFGenerationTool.sanitize(value, fonts.regular())), fonts.regular())
                    .setFontSize(PDFGenerationTool.FONT_SIZE_BODY)
                    .setFontColor(color);
        }

        private Text boldText(String value) {
            return boldText(value, PDFGenerationTool.COLOR_INK);
        }

        private Text boldText(String value, DeviceRgb color) {
            return applyFont(new Text(PDFGenerationTool.sanitize(value, fonts.bold())), fonts.bold())
                    .setFontColor(color);
        }

        private Paragraph paragraph(String value) {
            return paragraph(value, PDFGenerationTool.COLOR_INK);
        }

        private Paragraph paragraph(String value, DeviceRgb color) {
            return applyFont(new Paragraph(), fonts.regular())
                    .add(text(value, color))
                    .setFontSize(PDFGenerationTool.FONT_SIZE_BODY)
                    .setFontColor(color)
                    .setMultipliedLeading(PDFGenerationTool.LEADING_BODY);
        }

        private Paragraph boldParagraph(String value) {
            return boldParagraph(value, PDFGenerationTool.COLOR_INK);
        }

        private Paragraph boldParagraph(String value, DeviceRgb color) {
            return applyFont(new Paragraph(), fonts.bold())
                    .add(boldText(value, color))
                    .setFontColor(color);
        }

        private <T extends com.itextpdf.layout.ElementPropertyContainer<T>> T applyFont(T element, PdfFont font) {
            return font == null ? element : element.setFont(font);
        }
    }
}
