package dev.tallybook.app;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import dev.tallybook.core.WechatScreenParser;
import dev.tallybook.app.capture.OcrLines;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Emulator-only diagnostic selected by the guarded instrumentation runner. No ledger writes. */
final class OcrFixtureDiagnostics {
    static String run(Instrumentation runner) throws Exception {
        Bitmap image;
        try (ParcelFileDescriptor file = runner.getUiAutomation().executeShellCommand("cat /data/local/tmp/tallybook-ocr.png");
             FileInputStream input = new FileInputStream(file.getFileDescriptor())) {
            image = BitmapFactory.decodeStream(input);
        }
        if (image == null) throw new IllegalArgumentException("No local OCR fixture image");
        TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        try {
            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)), 45, TimeUnit.SECONDS);
            List<WechatScreenParser.TextLine> lines = new ArrayList<>();
            StringBuilder report = new StringBuilder("OCR geometry ").append(image.getWidth()).append('x').append(image.getHeight()).append('\n');
            for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null) continue;
                lines.add(new WechatScreenParser.TextLine(line.getText(), box.left, box.top, box.right, box.bottom));
                String value = line.getText();
                String masked = value.matches("[\\d\\s年月日:：+\\-−＋－.¥￥,<>vV]+")
                        ? value.replaceAll("[0-9]", "#")
                        : value.matches(".*(账单|交易|统计|支出|收入).*")
                        ? value.replaceAll("[0-9]", "#") : "NAME chars=" + value.length();
                report.append(box).append(' ').append(masked).append(" elements=").append(line.getElements().size())
                        .append(" punctuation=");
                value.codePoints().filter(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c))
                        .forEach(c -> report.append(Integer.toHexString(c)).append(','));
                report.append('\n');
            }
            lines = OcrLines.fromText(result, image.getWidth());
            if (lines == null) throw new IllegalStateException("OCR input limit");
            WechatScreenParser.Result parsed = WechatScreenParser.parse(lines, image.getWidth(), image.getHeight());
            return report.append("RESULT ").append(parsed.code).append(" rows=").append(parsed.transactions.size())
                    .append(" skipped=").append(parsed.skippedRows).append('\n').toString();
        } finally { recognizer.close(); image.recycle(); }
    }
}
