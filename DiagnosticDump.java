import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.File;

/**
 * Quick diagnostic: prints first 300 lines of the PDF text so we can
 * see the actual format used for college headers and branch names.
 *
 * Run with:
 *   javac -cp ".;target/dependency/*" DiagnosticDump.java
 *   java  -cp ".;target/dependency/*" DiagnosticDump
 */
public class DiagnosticDump {
    public static void main(String[] args) throws Exception {
        File pdf = new File("C:/Users/laksh/kcet-analyzer-uploads/45ba103a-1783430534824_PROF_CODE_E_R_06072026kannada.pdf");
        System.out.println("PDF: " + pdf.getName() + "  exists=" + pdf.exists());

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper ts = new PDFTextStripper();
            ts.setSortByPosition(true);
            ts.setStartPage(1);
            ts.setEndPage(3);          // first 3 pages is enough to see the structure
            String text = ts.getText(doc);

            String[] lines = text.split("\\r?\\n");
            System.out.println("=== FIRST 300 LINES OF EXTRACTED TEXT ===");
            for (int i = 0; i < Math.min(300, lines.length); i++) {
                System.out.printf("[%03d] |%s|%n", i + 1, lines[i]);
            }
        }
    }
}
