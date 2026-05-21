package io.github.openreportengine.render;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.design.*;
import net.sf.jasperreports.engine.xml.ReportLoader;
import com.jaspersoft.jasperreports.legacy.xml.LegacyXmlLoader;
import java.util.Optional;

public class FontInjectingReportLoader implements ReportLoader {

    private final LegacyXmlLoader delegate = new LegacyXmlLoader();

    @Override
    public Optional<JasperDesign> loadReport(JasperReportsContext ctx, byte[] data) {
        try {
            Optional<JasperDesign> opt = delegate.loadReport(ctx, data);
            if (opt.isPresent()) {
                JasperDesign design = opt.get();
                injectFonts(design);
                System.err.println("FontInjectingReportLoader: fonts injected to " + design.getName());
                return Optional.of(design);
            }
        } catch (JRException e) {
            System.err.println("FontInjectingReportLoader: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public Optional<JRTemplate> loadTemplate(JasperReportsContext ctx, byte[] data) {
        return Optional.empty();
    }

    private void injectFonts(JasperDesign design) {
        applyToBand(design.getTitle());
        applyToBand(design.getPageHeader());
        applyToBand(design.getColumnHeader());
        if (design.getDetailSection() != null) {
            for (int i = 0; i < design.getDetailSection().getBands().length; i++) {
                applyToBand(design.getDetailSection().getBands()[i]);
            }
        }
        applyToBand(design.getColumnFooter());
        applyToBand(design.getPageFooter());
        applyToBand(design.getSummary());
        applyToBand(design.getBackground());
        applyToBand(design.getLastPageFooter());
    }

    private void applyToBand(JRBand band) {
        if (band == null) return;
        for (JRElement elem : band.getElements()) {
            if (elem instanceof JRDesignTextElement) {
                JRDesignTextElement te = (JRDesignTextElement) elem;
                if (te.getFontName() == null || "Helvetica".equals(te.getFontName()) || "SansSerif".equals(te.getFontName())) {
                    te.setFontName("DejaVu Sans");
                }
                if (te.getPdfFontName() == null || "Helvetica".equals(te.getPdfFontName())) {
                    te.setPdfFontName("DejaVu Sans");
                }
                if (te.getPdfEncoding() == null || "WinAnsiEncoding".equals(te.getPdfEncoding())) {
                    te.setPdfEncoding("Identity-H");
                }
            }
        }
    }
}
