package io.github.openreportengine.render;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import net.sf.jasperreports.pdf.classic.ClassicPdfFontMapper;
import net.sf.jasperreports.pdf.classic.ClassicPdfProducer;

public class CustomPdfFontMapper extends ClassicPdfFontMapper {
    public CustomPdfFontMapper(ClassicPdfProducer producer) {
        super(producer);
    }
    
    @Override
    public BaseFont awtToPdf(java.awt.Font font) {
        try {
            return BaseFont.createFont("DejaVu Sans", "Identity-H", BaseFont.EMBEDDED);
        } catch (Exception e) {
            return super.awtToPdf(font);
        }
    }
}
