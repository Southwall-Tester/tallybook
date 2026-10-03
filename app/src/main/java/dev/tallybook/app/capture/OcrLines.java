package dev.tallybook.app.capture;

import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import dev.tallybook.core.WechatScreenParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Converts OCR geometry without guessing coordinates or correcting recognized characters. */
public final class OcrLines {
    private static final int MAX_LINES = 320;
    private static final int MAX_CHARACTERS = 20000;
    private static final int MAX_ELEMENTS = 256;
    private static final Pattern AMOUNT = Pattern.compile("[-+−](?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?");
    private OcrLines() {}

    /** Mutable lines, or null when the bounded input/output limits are exceeded. */
    public static List<WechatScreenParser.TextLine> fromText(Text text, int imageWidth) {
        if (text == null || imageWidth <= 0 || imageWidth > 20000) return null;
        List<WechatScreenParser.TextLine> result = new ArrayList<>();
        int characters = 0;
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
            Rect bounds = line.getBoundingBox();
            if (bounds == null) continue;
            String value = line.getText();
            characters += value.length();
            if (value.length() > 2048 || characters > MAX_CHARACTERS) return null;
            List<WechatScreenParser.TextLine> separated = splitRightAmount(line, imageWidth);
            if (separated == null) result.add(asLine(value, bounds));
            else result.addAll(separated);
            if (result.size() > MAX_LINES) return null;
        }
        return result;
    }

    private static List<WechatScreenParser.TextLine> splitRightAmount(Text.Line line, int width) {
        List<Text.Element> original = line.getElements();
        if (original.size() < 2 || original.size() > MAX_ELEMENTS) return null;
        List<WechatScreenParser.TextLine> elements = new ArrayList<>();
        for (Text.Element element : original) {
            Rect box = element.getBoundingBox();
            String value = element.getText();
            if (box == null || box.left < 0 || box.right > width || box.top < 0
                    || box.width() <= 0 || box.height() <= 0 || value.isEmpty() || value.length() > 2048) return null;
            elements.add(asLine(value, box));
        }
        elements.sort(Comparator.comparingInt(element -> element.left));
        String suffix = "";
        Rect amountBounds = null;
        for (int start = elements.size() - 1; start >= Math.max(0, elements.size() - 8); start--) {
            WechatScreenParser.TextLine element = elements.get(start);
            if (element.left < width * .62) break;
            if (start + 1 < elements.size() && !adjacent(element, elements.get(start + 1), width)) break;
            suffix = compact(element.text) + suffix;
            if (suffix.length() > 32) break;
            if (amountBounds == null) amountBounds = bounds(element);
            else amountBounds.union(bounds(element));
            if (!AMOUNT.matcher(suffix).matches()) continue;
            String title = prefixBeforeAmount(line.getText(), suffix);
            if (title == null) return null;
            if (start == 0) {
                if (!title.isEmpty()) return null;
                List<WechatScreenParser.TextLine> onlyAmount = new ArrayList<>();
                onlyAmount.add(asLine(suffix, amountBounds));
                return onlyAmount;
            }
            Rect titleBounds = null;
            StringBuilder titleElements = new StringBuilder();
            for (int index = 0; index < start; index++) {
                WechatScreenParser.TextLine part = elements.get(index);
                if (!sameRow(part, element, width) || part.right >= amountBounds.left - width * .025) return null;
                if (titleBounds == null) titleBounds = bounds(part);
                else titleBounds.union(bounds(part));
                titleElements.append(compact(part.text));
            }
            // The name keeps its original text; only exact element bounds define each column.
            if (titleBounds == null || titleBounds.left >= width * .60 || title.isEmpty()
                    || !compact(title).equals(titleElements.toString())) return null;
            List<WechatScreenParser.TextLine> separated = new ArrayList<>();
            separated.add(asLine(title, titleBounds));
            separated.add(asLine(suffix, amountBounds));
            return separated;
        }
        return null;
    }

    private static boolean adjacent(WechatScreenParser.TextLine left, WechatScreenParser.TextLine right, int width) {
        int gap = right.left - left.right;
        int height = Math.max(left.bottom - left.top, right.bottom - right.top);
        return sameRow(left, right, width) && gap >= -width * .005
                && gap <= Math.max(width * .025, height * .75);
    }

    private static boolean sameRow(WechatScreenParser.TextLine left, WechatScreenParser.TextLine right, int width) {
        double distance = Math.abs((left.top + left.bottom) / 2.0 - (right.top + right.bottom) / 2.0);
        return distance <= Math.max(left.bottom - left.top, right.bottom - right.top) * .65 + width * .005;
    }

    /** Match the actual suffix, ignoring whitespace only; preserve the remaining merchant text. */
    private static String prefixBeforeAmount(String line, String amount) {
        int cursor = line.length() - 1;
        for (int index = amount.length() - 1; index >= 0; index--) {
            while (cursor >= 0 && isSpace(line.charAt(cursor))) cursor--;
            if (cursor < 0 || line.charAt(cursor) != amount.charAt(index)) return null;
            cursor--;
        }
        while (cursor >= 0 && isSpace(line.charAt(cursor))) cursor--;
        return line.substring(0, cursor + 1);
    }

    private static String compact(String text) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < text.length(); index++) if (!isSpace(text.charAt(index))) result.append(text.charAt(index));
        return result.toString();
    }

    private static boolean isSpace(char value) { return Character.isWhitespace(value) || value == '\u00a0'; }
    private static Rect bounds(WechatScreenParser.TextLine line) { return new Rect(line.left, line.top, line.right, line.bottom); }
    private static WechatScreenParser.TextLine asLine(String value, Rect box) {
        return new WechatScreenParser.TextLine(value, box.left, box.top, box.right, box.bottom);
    }
}
