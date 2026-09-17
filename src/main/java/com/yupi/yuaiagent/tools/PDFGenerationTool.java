package com.yupi.yuaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PDF 生成工具，同时承载 PDF 排版的字体与配色规范
 */
public class PDFGenerationTool {

    /**
     * 全局页边距
     */
    public static final float PAGE_MARGIN = 36f;

    /**
     * 正文统一字号，不再使用人工加粗制造字重差异
     */
    public static final float FONT_SIZE_BODY = 10f;

    /**
     * 徽章等小号 UI 文字
     */
    public static final float FONT_SIZE_BADGE = 9f;

    /**
     * 店名等强调文字
     */
    public static final float FONT_SIZE_EMPHASIS = 14f;

    /**
     * 主标题
     */
    public static final float FONT_SIZE_TITLE = 20f;

    /**
     * 正文行距倍数
     */
    public static final float LEADING_BODY = 1.4f;

    /**
     * 主标题与重要文字：深墨黑 #1A1A1A
     */
    public static final DeviceRgb COLOR_INK = new DeviceRgb(26, 26, 26);

    /**
     * 地点与主视觉强调：深海军蓝 #1E3A8A
     */
    public static final DeviceRgb COLOR_NAVY = new DeviceRgb(30, 58, 138);

    /**
     * 徽章背景：浅灰蓝 #EFF6FF
     */
    public static final DeviceRgb COLOR_BADGE_BG = new DeviceRgb(239, 246, 255);

    /**
     * 徽章文字：亮宝蓝 #1D4ED8
     */
    public static final DeviceRgb COLOR_BADGE_TEXT = new DeviceRgb(29, 78, 216);

    /**
     * 正文与次级文字：炭灰色 #4B5563
     */
    public static final DeviceRgb COLOR_BODY = new DeviceRgb(75, 85, 99);

    /**
     * 卡片边框与分割线：浅灰 #E5E7EB
     */
    public static final DeviceRgb COLOR_BORDER = new DeviceRgb(229, 231, 235);

    /**
     * 卡片底色：极浅灰 #F9FAFB
     */
    public static final DeviceRgb COLOR_CARD_BG = new DeviceRgb(249, 250, 251);

    /**
     * 贴士块背景：柔和暖黄 #FFFBEB
     */
    public static final DeviceRgb COLOR_TIP_BG = new DeviceRgb(255, 251, 235);

    /**
     * 贴士块文字：暖棕 #B45309
     */
    public static final DeviceRgb COLOR_TIP_TEXT = new DeviceRgb(180, 83, 9);

    /**
     * 落日专题卡片边框：琥珀橙 #F59E0B
     */
    public static final DeviceRgb COLOR_SUNSET_BORDER = new DeviceRgb(245, 158, 11);

    /**
     * 落日专题卡片底色：暖橙 #FFF7ED
     */
    public static final DeviceRgb COLOR_SUNSET_BG = new DeviceRgb(255, 247, 237);

    /**
     * 落日专题标题文字：深橙棕 #C2410C
     */
    public static final DeviceRgb COLOR_SUNSET_TITLE = new DeviceRgb(194, 65, 12);

    /**
     * 同族字重对：常规体与真实粗体取自同一字体文件，避免跨字体族混排
     */
    public record CjkFontPair(PdfFont regular, PdfFont bold) {
    }

    /**
     * 中文字体族候选文件，按优先级排列
     */
    private static final List<String> FONT_FAMILY_FILES = List.of(
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/Hiragino Sans GB.ttc",
            "/Library/Fonts/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Songti.ttc"
    );

    /**
     * ttc 字体集合内的最大探测索引，不同 macOS 版本的字重顺序并不一致
     */
    private static final int MAX_TTC_FACE_INDEX = 9;

    /**
     * 判定粗体字面的名称特征，W6 为苹果黑体系的中粗字重
     */
    private static final List<String> BOLD_NAME_MARKERS =
            List.of("semibold", "demibold", "medium", "bold", "-w6", "heavy");

    /**
     * 用于校验字体是否真的带中文字形，部分 macOS 字体可加载但取不到 CJK 字形
     */
    private static final String CJK_PROBE_CHARS = "悉行程类停";

    /**
     * 英文、数字与常用标点，确保拉丁字符不会回退到笔画过细的默认字体
     */
    private static final String LATIN_PROBE_CHARS = "Sa0&:/（，。";

    /**
     * 可嵌入的中文字体普遍不含 emoji，按顺序降级为几何符号，最后才丢弃
     */
    private static final Map<Integer, String[]> DECORATION_FALLBACKS = buildDecorationFallbacks();

    /**
     * 仅缓存解析出的字体规格；PdfFont 与具体 PdfDocument 绑定，不可跨文档复用
     */
    private static volatile FontSpecPair cachedSpecPair;

    @Tool(description = "Generate a PDF file with given content", returnDirect = false)
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        try {
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 创建 PdfWriter 和 PdfDocument 对象
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                document.setMargins(PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN);
                // 根节点统一字体，所有子元素继承同一字体族
                PdfFont font = loadCjkFontPair().regular();
                if (font != null) {
                    document.setFont(font);
                }
                document.setFontSize(FONT_SIZE_BODY).setFontColor(COLOR_BODY);
                // 按换行拆分，保证多段落排版可读
                for (String line : safeText(content).split("\n", -1)) {
                    if (line.isBlank()) {
                        document.add(new Paragraph("\n").setMarginBottom(4));
                    } else {
                        document.add(new Paragraph(sanitize(line, font)).setMarginBottom(2)
                                .setMultipliedLeading(LEADING_BODY));
                    }
                }
            }
            return "PDF generated successfully to: " + filePath;
        } catch (Exception e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }

    /**
     * 加载同族的常规体与真实粗体。逐个字体族探测，要求同时覆盖中文与拉丁字符；
     * 同族粗体缺失时沿用常规体，靠字号而非人工加粗制造对比。
     *
     * @return 字重对；极端情况下两者均为内置 Helvetica
     */
    public static CjkFontPair loadCjkFontPair() {
        FontSpecPair specPair = cachedSpecPair;
        if (specPair == null) {
            specPair = resolveSpecPair();
            cachedSpecPair = specPair;
        }
        PdfFont regular = createFont(specPair.regular());
        if (regular == null) {
            PdfFont helvetica = createStandardFont();
            return new CjkFontPair(helvetica, helvetica);
        }
        PdfFont bold = specPair.regular().equals(specPair.bold()) ? regular : createFont(specPair.bold());
        return new CjkFontPair(regular, bold == null ? regular : bold);
    }

    /**
     * 剔除字体不支持的字符，装饰符号优先降级为同义几何符号，避免渲染成空白方块
     *
     * @param text 原始文本
     * @param font 实际使用的字体，为 null 时原样返回
     * @return 可安全渲染的文本
     */
    public static String sanitize(String text, PdfFont font) {
        String source = safeText(text);
        if (font == null || source.isEmpty()) {
            return source;
        }
        StringBuilder builder = new StringBuilder(source.length());
        source.codePoints().forEach(codePoint -> {
            if (Character.isWhitespace(codePoint) || containsGlyph(font, codePoint)) {
                builder.appendCodePoint(codePoint);
                return;
            }
            for (String fallback : DECORATION_FALLBACKS.getOrDefault(codePoint, new String[0])) {
                if (containsGlyph(font, fallback.codePointAt(0))) {
                    builder.append(fallback);
                    return;
                }
            }
        });
        // 不做空格压缩与 trim，否则会吃掉徽章与标题之间用于留白的间隔文本
        return builder.toString();
    }

    /**
     * 空值防护，避免向 Paragraph/Text 传入 null
     */
    public static String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * 枚举各字体族内的字面，挑出常规体与粗体的组合
     */
    private static FontSpecPair resolveSpecPair() {
        for (String familyFile : FONT_FAMILY_FILES) {
            if (!FileUtil.exist(familyFile)) {
                continue;
            }
            List<FontFace> faces = loadUsableFaces(familyFile);
            if (faces.isEmpty()) {
                continue;
            }
            FontFace regular = faces.stream()
                    .filter(face -> !isBoldName(face.name()))
                    .findFirst()
                    .orElse(faces.get(0));
            FontFace bold = faces.stream()
                    .filter(face -> isBoldName(face.name()))
                    .findFirst()
                    .orElse(regular);
            return new FontSpecPair(regular.spec(), bold.spec());
        }
        return new FontSpecPair(StandardFonts.HELVETICA, StandardFonts.HELVETICA);
    }

    /**
     * 载入字体文件内所有可用字面，ttc 集合需逐索引探测
     */
    private static List<FontFace> loadUsableFaces(String familyFile) {
        List<FontFace> faces = new ArrayList<>();
        boolean isCollection = familyFile.toLowerCase(Locale.ROOT).endsWith(".ttc");
        int maxIndex = isCollection ? MAX_TTC_FACE_INDEX : 0;
        for (int index = 0; index <= maxIndex; index++) {
            String spec = isCollection ? familyFile + "," + index : familyFile;
            PdfFont font = createFont(spec);
            // 索引越界或授权受限时跳过，继续探测同族其它字重
            if (font == null || !coversRequiredGlyphs(font)) {
                continue;
            }
            String name = font.getFontProgram().getFontNames().getFontName();
            // 以点号开头的是系统内部界面字面，排版度量不可控
            if (name == null || name.startsWith(".")) {
                continue;
            }
            faces.add(new FontFace(name, spec));
        }
        return faces;
    }

    private static boolean isBoldName(String fontName) {
        String normalized = fontName.toLowerCase(Locale.ROOT);
        return BOLD_NAME_MARKERS.stream().anyMatch(normalized::contains);
    }

    private static PdfFont createFont(String spec) {
        try {
            return PdfFontFactory.createFont(spec, PdfEncodings.IDENTITY_H);
        } catch (Exception e) {
            return null;
        }
    }

    private static PdfFont createStandardFont() {
        try {
            return PdfFontFactory.createFont(StandardFonts.HELVETICA);
        } catch (Exception e) {
            // 交由 iText 使用默认字体
            return null;
        }
    }

    /**
     * 中文与拉丁字符都必须覆盖，否则英文数字会回退到笔画过细的字体
     */
    private static boolean coversRequiredGlyphs(PdfFont font) {
        return (CJK_PROBE_CHARS + LATIN_PROBE_CHARS).chars()
                .allMatch(codePoint -> containsGlyph(font, codePoint));
    }

    private static boolean containsGlyph(PdfFont font, int codePoint) {
        try {
            return font.containsGlyph(codePoint);
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<Integer, String[]> buildDecorationFallbacks() {
        Map<Integer, String[]> fallbacks = new LinkedHashMap<>();
        // 🏷 类别
        fallbacks.put(0x1F3F7, new String[]{"◆", "•"});
        // 🕒 营业时间
        fallbacks.put(0x1F552, new String[]{"•", "·"});
        // ⏱ 建议停留
        fallbacks.put(0x23F1, new String[]{"•", "·"});
        // 🚶 下一程通勤
        fallbacks.put(0x1F6B6, new String[]{"▸", "•"});
        // 💡 私藏笔记
        fallbacks.put(0x1F4A1, new String[]{"◆", "•"});
        // ✦ 小节标题
        fallbacks.put(0x2726, new String[]{"◆", "•"});
        // 🌅 落日专题
        fallbacks.put(0x1F305, new String[]{"◆", "•"});
        // 变体选择符，无字形时直接丢弃
        fallbacks.put(0xFE0F, new String[0]);
        return Map.copyOf(fallbacks);
    }

    /**
     * 字体规格（文件路径与 ttc 索引），可安全缓存
     */
    private record FontSpecPair(String regular, String bold) {
    }

    private record FontFace(String name, String spec) {
    }
}
