package io.github.openreportengine.render;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.design.*;
import net.sf.jasperreports.engine.type.OrientationEnum;

import javax.xml.parsers.*;
import javax.xml.xpath.*;
import org.w3c.dom.*;

import java.io.ByteArrayInputStream;
import java.util.*;

public class FontAwareReportLoader {

    private final List<JRDesignBand> detailBands = new ArrayList<>();
    private final java.util.Map<String, String> fieldClasses = new java.util.HashMap<>();

    private static class FontInfo {
        String fontName;
        String pdfFontName;
        String pdfEncoding;
    }

    public JasperDesign loadDesign(JasperReportsContext ctx, byte[] data) throws Exception {
        // Strip namespace from XML for simpler parsing
        String xmlStr = new String(data, "UTF-8");
        xmlStr = xmlStr.replaceAll("xmlns=\"[^\"]*\"", "");
        // Also remove namespace prefixes (e.g., <jr:xxx> -> <xxx>)
        xmlStr = xmlStr.replaceAll("<(\\w+):(\\w+)", "<$2");
        xmlStr = xmlStr.replaceAll("</(\\w+):(\\w+)", "</$2");
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(xmlStr.getBytes("UTF-8")));
        XPath xpath = XPathFactory.newInstance().newXPath();

        Element root = doc.getDocumentElement();

        String name = root.getAttribute("name");
        int pageW = parseIntOrDefault(root.getAttribute("pageWidth"), 595);
        int pageH = parseIntOrDefault(root.getAttribute("pageHeight"), 842);
        String orient = root.getAttribute("orientation");

        JasperDesign design = new JasperDesign();
        design.setName(name != null ? name : "Report");
        design.setPageWidth(pageW);
        design.setPageHeight(pageH);

        if ("Landscape".equalsIgnoreCase(orient) || "landscape".equalsIgnoreCase(orient)) {
            design.setOrientation(OrientationEnum.LANDSCAPE);
        } else {
            design.setOrientation(OrientationEnum.PORTRAIT);
        }

        design.setColumnWidth(parseIntOrDefault(root.getAttribute("columnWidth"), pageW - 40));
        design.setColumnSpacing(parseIntOrDefault(root.getAttribute("columnSpacing"), 0));
        design.setColumnCount(1);
        design.setLeftMargin(parseIntOrDefault(root.getAttribute("leftMargin"), 20));
        design.setRightMargin(parseIntOrDefault(root.getAttribute("rightMargin"), 20));
        design.setTopMargin(parseIntOrDefault(root.getAttribute("topMargin"), 20));
        design.setBottomMargin(parseIntOrDefault(root.getAttribute("bottomMargin"), 20));

        String whenNoData = root.getAttribute("whenNoDataType");
        if ("AllSectionsNoDetail".equals(whenNoData)) {
            design.setWhenNoDataType(net.sf.jasperreports.engine.type.WhenNoDataTypeEnum.ALL_SECTIONS_NO_DETAIL);
        } else if ("NoDataSection".equals(whenNoData)) {
            design.setWhenNoDataType(net.sf.jasperreports.engine.type.WhenNoDataTypeEnum.NO_DATA_SECTION);
        } else if ("BlankPage".equals(whenNoData)) {
            design.setWhenNoDataType(net.sf.jasperreports.engine.type.WhenNoDataTypeEnum.BLANK_PAGE);
        } else {
            design.setWhenNoDataType(net.sf.jasperreports.engine.type.WhenNoDataTypeEnum.ALL_SECTIONS_NO_DETAIL);
        }

        // Process parameters
        NodeList paramNodes = (NodeList) xpath.evaluate("/jasperReport/parameter", doc, XPathConstants.NODESET);
        for (int i = 0; i < paramNodes.getLength(); i++) {
            Element pe = (Element) paramNodes.item(i);
            JRDesignParameter param = new JRDesignParameter();
            String pname = pe.getAttribute("name");
            param.setName(pname);
            String pclass = pe.getAttribute("class");
            if (pclass != null && !pclass.isEmpty()) {
                try {
                    param.setValueClass(Class.forName(pclass));
                } catch (ClassNotFoundException e) {
                    param.setValueClassName(pclass);
                }
            }
            design.addParameter(param);
        }

        // Process fields
        NodeList fieldNodes = (NodeList) xpath.evaluate("/jasperReport/field", doc, XPathConstants.NODESET);
        for (int i = 0; i < fieldNodes.getLength(); i++) {
            Element fe = (Element) fieldNodes.item(i);
            JRDesignField field = new JRDesignField();
            String fname = fe.getAttribute("name");
            field.setName(fname);
            String fclass = fe.getAttribute("class");
            if (fclass != null && !fclass.isEmpty()) {
                try {
                    field.setValueClass(Class.forName(fclass));
                } catch (ClassNotFoundException e) {
                    field.setValueClassName(fclass);
                }
            }
            fieldClasses.put(fname, fclass);
            design.addField(field);
        }

        // Process variables
        NodeList varNodes = (NodeList) xpath.evaluate("/jasperReport/variable", doc, XPathConstants.NODESET);
        for (int i = 0; i < varNodes.getLength(); i++) {
            Element ve = (Element) varNodes.item(i);
            JRDesignVariable var = new JRDesignVariable();
            String vname = ve.getAttribute("name");
            var.setName(vname);
            String vclass = ve.getAttribute("class");
            if (vclass != null && !vclass.isEmpty()) {
                try {
                    var.setValueClass(Class.forName(vclass));
                } catch (ClassNotFoundException e) {
                    var.setValueClassName(vclass);
                }
            }
            String calc = ve.getAttribute("calculation");
            if ("Sum".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.SUM);
            else if ("Count".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.COUNT);
            else if ("Average".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.AVERAGE);
            else if ("First".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.FIRST);
            else if ("DistinctCount".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.DISTINCT_COUNT);
            else if ("Lowest".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.LOWEST);
            else if ("Highest".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.HIGHEST);
            else if ("StandardDeviation".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.STANDARD_DEVIATION);
            else if ("Variance".equals(calc)) var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.VARIANCE);
            else var.setCalculation(net.sf.jasperreports.engine.type.CalculationEnum.NOTHING);

            Element varExpr = getChildElement(ve, "variableExpression");
            if (varExpr != null) {
                String exprText = varExpr.getTextContent();
                if (exprText != null) {
                    exprText = exprText.trim();
                    if (exprText.startsWith("<![CDATA[")) {
                        exprText = exprText.substring(9, exprText.length() - 3);
                    }
                }
                if (exprText != null && !exprText.isEmpty()) {
                    var.setExpression(new JRDesignExpression(exprText));
                }
            }
            Element initExpr = getChildElement(ve, "initialValueExpression");
            if (initExpr != null) {
                String exprText = initExpr.getTextContent();
                if (exprText != null) {
                    exprText = exprText.trim();
                    if (exprText.startsWith("<![CDATA[")) {
                        exprText = exprText.substring(9, exprText.length() - 3);
                    }
                }
                if (exprText != null && !exprText.isEmpty()) {
                    var.setInitialValueExpression(new JRDesignExpression(exprText));
                }
            }
            design.addVariable(var);
        }

        // Process queryString
        NodeList queryNodes = (NodeList) xpath.evaluate("/jasperReport/queryString", doc, XPathConstants.NODESET);
        if (queryNodes.getLength() > 0) {
            Element qe = (Element) queryNodes.item(0);
            String queryText = qe.getTextContent();
            if (queryText != null) {
                queryText = queryText.trim();
                if (queryText.startsWith("<![CDATA[")) {
                    queryText = queryText.substring(9, queryText.length() - 3);
                }
            }
            if (queryText != null && !queryText.isEmpty()) {
                JRDesignQuery query = new JRDesignQuery();
                query.setText(queryText);
                String lang = qe.getAttribute("language");
                if (lang != null && !lang.isEmpty()) {
                    query.setLanguage(lang);
                }
                design.setQuery(query);
            }
        }

        // Process bands
        String[] bandNames = {"title", "pageHeader", "columnHeader", "detail", "columnFooter", "pageFooter", "summary", "background", "lastPageFooter"};
        for (String bandName : bandNames) {
            NodeList bandNodes = (NodeList) xpath.evaluate("/jasperReport/" + bandName, doc, XPathConstants.NODESET);
            if (bandNodes.getLength() == 0) {
                System.err.println("Band " + bandName + ": not found");
                continue;
            }

            Element sectionEl = (Element) bandNodes.item(0);
            // Find the inner element container.
            // Two formats:
            //   1) <title><band height="...">...elements...</band></title>
            //   2) <title height="...">...elements directly...</title>  (Jasper 7+)
            Element bandEl = getChildElement(sectionEl, "band");
            if (bandEl == null) {
                // Try new format — section has height directly
                bandEl = sectionEl;
            }

            int bandHeight = parseIntOrDefault(bandEl.getAttribute("height"), 30);
            JRDesignBand band = new JRDesignBand();
            band.setHeight(bandHeight);

            // Process v7-style elements (<element kind="staticText"> ...)
            processV7Elements(xpath, bandEl, band);

            // Process staticText elements
            processStaticTexts(xpath, bandEl, band);

            // Process textField elements
            processTextFields(xpath, bandEl, band);

            System.err.println("  " + bandName + " elements: " + band.getElements().length + " (staticTexts=" + countStaticTexts(xpath, bandEl) + ")");
            if ("columnHeader".equals(bandName) || "detail".equals(bandName)) {
                for (JRElement el : band.getElements()) {
                    if (el instanceof JRDesignTextElement) {
                        JRDesignTextElement te = (JRDesignTextElement) el;
                        System.err.println("    fontName=" + te.getFontName() + " pdfFontName=" + te.getPdfFontName() + " pdfEnc=" + te.getPdfEncoding());
                    }
                }
            }

            // Set band on design (JasperDesign has these methods directly)
            if ("detail".equals(bandName)) {
                detailBands.add(band);
            } else {
                setDesignBand(design, bandName, band);
            }
        }

        // Set detail section via getDetailSection().addBand()
        if (!detailBands.isEmpty()) {
            try {
                java.lang.reflect.Method getDetail = design.getClass().getMethod("getDetailSection");
                Object section = getDetail.invoke(design);
                if (section != null) {
                    java.lang.reflect.Method addBand = section.getClass().getMethod("addBand", JRBand.class);
                    for (JRDesignBand b : detailBands) {
                        addBand.invoke(section, b);
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to set detail section: " + e.getMessage());
            }
        }

        // Apply Arial as fallback for any element without explicit font
        applyArialFallback(design);

        // Debug: count elements
        try {
            int elemCount = 0;
            java.lang.reflect.Method getTitle = design.getClass().getMethod("getTitle");
            Object titleBand = getTitle.invoke(design);
            if (titleBand != null) {
                java.lang.reflect.Method getElements = titleBand.getClass().getMethod("getElements");
                JRElement[] elems = (JRElement[]) getElements.invoke(titleBand);
                elemCount += elems.length;
            }
            System.err.println("FontAwareReportLoader: parsed " + design.getName() + " with " + elemCount + " title elements");
        } catch (Exception e) {
            System.err.println("debug count error: " + e.getMessage());
        }
        return design;
    }

    private void applyFontFromElement(Element elementEl, JRDesignTextElement te) {
        Element teEl = getChildElement(elementEl, "textElement");
        if (teEl == null) return;

        Element fontEl = getChildElement(teEl, "font");
        if (fontEl == null) return;

        String fn = fontEl.getAttribute("fontName");
        if (fn != null && !fn.isEmpty()) te.setFontName(fn);

        String pdf = fontEl.getAttribute("pdfFontName");
        if (pdf != null && !pdf.isEmpty()) te.setPdfFontName(pdf);

        String enc = fontEl.getAttribute("pdfEncoding");
        if (enc != null && !enc.isEmpty()) te.setPdfEncoding(enc);
    }

    private void applyArialFallback(JasperDesign design) {
        List<JRDesignBand> bands = new ArrayList<>();
        try { bands.add((JRDesignBand) getDesignBand(design, "title")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "pageHeader")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "columnHeader")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "columnFooter")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "pageFooter")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "summary")); } catch (Exception e) {}
        try { bands.add((JRDesignBand) getDesignBand(design, "background")); } catch (Exception e) {}

        for (JRDesignBand band : bands) {
            if (band == null) continue;
            applyArialToBand(band);
        }
        for (JRDesignBand band : detailBands) {
            applyArialToBand(band);
        }
    }

    private void applyArialToBand(JRDesignBand band) {
        for (JRElement elem : band.getElements()) {
            if (!(elem instanceof JRDesignTextElement)) continue;
            JRDesignTextElement te = (JRDesignTextElement) elem;
            if (te.getFontName() == null || te.getFontName().isEmpty() ||
                "Helvetica".equals(te.getFontName()) || "SansSerif".equals(te.getFontName()) ||
                "Arial".equals(te.getFontName())) {
                te.setFontName(FontDefaults.FAMILY);
                te.setPdfFontName(FontDefaults.PDF_NAME);
                te.setPdfEncoding(FontDefaults.PDF_ENCODING);
            }
        }
    }

    private Element getChildElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String nodeName = child.getNodeName();
                // Strip namespace prefix if present
                int colon = nodeName.indexOf(':');
                String localName = colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
                if (name.equals(localName)) {
                    return (Element) child;
                }
            }
        }
        return null;
    }

    private void processV7Elements(XPath xpath, Element parentEl, JRDesignBand band) throws Exception {
        NodeList children = parentEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) child;
            String kind = el.getAttribute("kind");
            if (kind == null || kind.isEmpty()) continue;

            String nodeName = child.getNodeName();
            int colon = nodeName.indexOf(':');
            String localName = colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
            if (!"element".equals(localName)) continue;

            int x = parseIntOrDefault(el.getAttribute("x"), 0);
            int y = parseIntOrDefault(el.getAttribute("y"), 0);
            int w = parseIntOrDefault(el.getAttribute("width"), 100);
            int h = parseIntOrDefault(el.getAttribute("height"), 20);

            if ("staticText".equals(kind)) {
                // Convert v7 staticText to textField with constant expression
                JRDesignTextField tf = new JRDesignTextField();
                tf.setX(x); tf.setY(y); tf.setWidth(w); tf.setHeight(h);

                String txt = getChildTextContent(el, "text");
                if (txt != null && !txt.isEmpty()) {
                    txt = txt.replace("\"", "\\\"");
                    tf.setExpression(new JRDesignExpression("\"" + txt + "\""));
                }

                // Font attributes on the element itself
                String fn = el.getAttribute("fontName");
                if (fn != null && !fn.isEmpty()) tf.setFontName(fn);
                String pdf = el.getAttribute("pdfFontName");
                if (pdf != null && !pdf.isEmpty()) tf.setPdfFontName(pdf);
                String enc = el.getAttribute("pdfEncoding");
                if (enc != null && !enc.isEmpty()) tf.setPdfEncoding(enc);

                // Foreground color
                String fc = el.getAttribute("forecolor");
                if (fc != null && !fc.isEmpty()) {
                    try {
                        tf.setForecolor(java.awt.Color.decode(fc));
                    } catch (Exception e) {}
                }

                // Apply font defaults
                applyFontDefaults(tf);

                band.addElement(tf);

            } else if ("textField".equals(kind)) {
                JRDesignTextField tf = new JRDesignTextField();
                tf.setX(x); tf.setY(y); tf.setWidth(w); tf.setHeight(h);

                String exprText = getChildTextContent(el, "expression");
                if (exprText != null && !exprText.isEmpty()) {
                    tf.setExpression(new JRDesignExpression(exprText));
                }

                String fn = el.getAttribute("fontName");
                if (fn != null && !fn.isEmpty()) tf.setFontName(fn);
                String pdf = el.getAttribute("pdfFontName");
                if (pdf != null && !pdf.isEmpty()) tf.setPdfFontName(pdf);
                String enc = el.getAttribute("pdfEncoding");
                if (enc != null && !enc.isEmpty()) tf.setPdfEncoding(enc);

                String fc = el.getAttribute("forecolor");
                if (fc != null && !fc.isEmpty()) {
                    try {
                        tf.setForecolor(java.awt.Color.decode(fc));
                    } catch (Exception e) {}
                }

                applyFontDefaults(tf);

                band.addElement(tf);

            } else if ("line".equals(kind)) {
                JRDesignLine line = new JRDesignLine();
                line.setX(x); line.setY(y); line.setWidth(w); line.setHeight(h);
                String dir = el.getAttribute("direction");
                if ("TopDown".equals(dir)) line.setDirection(net.sf.jasperreports.engine.type.LineDirectionEnum.TOP_DOWN);
                else if ("BottomUp".equals(dir)) line.setDirection(net.sf.jasperreports.engine.type.LineDirectionEnum.BOTTOM_UP);
                else line.setDirection(net.sf.jasperreports.engine.type.LineDirectionEnum.TOP_DOWN);
                band.addElement(line);

            } else if ("frame".equals(kind)) {
                // Process nested elements inside frame
                processV7Elements(xpath, el, band);
            }
        }
    }

    private String getChildTextContent(Element parent, String name) {
        Element child = getChildElement(parent, name);
        if (child == null) return null;
        String txt = child.getTextContent();
        if (txt == null) return null;
        txt = txt.trim();
        if (txt.startsWith("<![CDATA[")) {
            txt = txt.substring(9, txt.length() - 3);
        }
        return txt;
    }

    private void applyFontDefaults(JRDesignTextElement te) {
        if (te.getFontName() == null || te.getFontName().isEmpty() ||
            "Helvetica".equals(te.getFontName()) || "SansSerif".equals(te.getFontName())) {
            te.setFontName(FontDefaults.FAMILY);
            te.setPdfFontName(FontDefaults.PDF_NAME);
            te.setPdfEncoding(FontDefaults.PDF_ENCODING);
        }
    }

    private int countStaticTexts(XPath xpath, Element bandEl) throws Exception {
        int count = 0;
        NodeList children = bandEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String nodeName = child.getNodeName();
            int colon = nodeName.indexOf(':');
            String localName = colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
            if ("staticText".equals(localName)) count++;
        }
        return count;
    }

    private void processStaticTexts(XPath xpath, Element bandEl, JRDesignBand band) throws Exception {
        NodeList children = bandEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String nodeName = child.getNodeName();
            int colon = nodeName.indexOf(':');
            String localName = colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
            if (!"staticText".equals(localName)) continue;

            Element stEl = (Element) child;

            // Convert staticText to textField with constant expression.
            // This ensures text is rendered through Jasper's Identity-H/UTF-16 path,
            // supporting Cyrillic and custom fonts correctly, instead of WinAnsi/Helvetica.
            JRDesignTextField tf = new JRDesignTextField();

            Element re = getChildElement(stEl, "reportElement");
            if (re != null) {
                tf.setX(parseIntOrDefault(re.getAttribute("x"), 0));
                tf.setY(parseIntOrDefault(re.getAttribute("y"), 0));
                tf.setWidth(parseIntOrDefault(re.getAttribute("width"), 100));
                tf.setHeight(parseIntOrDefault(re.getAttribute("height"), 20));
            }

            applyFontFromElement(stEl, tf);

            // Always set monospaced Cyrillic-capable font on converted staticText.
            tf.setFontName(FontDefaults.FAMILY);
            tf.setPdfFontName(FontDefaults.PDF_NAME);
            tf.setPdfEncoding(FontDefaults.PDF_ENCODING);

            Element textEl = getChildElement(stEl, "text");
            if (textEl != null) {
                String txt = textEl.getTextContent();
                if (txt != null) {
                    txt = txt.trim();
                    if (txt.startsWith("<![CDATA[")) {
                        txt = txt.substring(9, txt.length() - 3);
                    }
                    txt = txt.replace("\"", "\\\"");
                    JRDesignExpression expr = new JRDesignExpression("\"" + txt + "\"");
                    tf.setExpression(expr);
                }
            }

            band.addElement(tf);
        }
    }

    private void processTextFields(XPath xpath, Element bandEl, JRDesignBand band) throws Exception {
        NodeList children = bandEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String nodeName = child.getNodeName();
            int colon = nodeName.indexOf(':');
            String localName = colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
            if (!"textField".equals(localName)) continue;

            Element tfEl = (Element) child;
            JRDesignTextField tf = new JRDesignTextField();

            Element re = getChildElement(tfEl, "reportElement");
            if (re != null) {
                tf.setX(parseIntOrDefault(re.getAttribute("x"), 0));
                tf.setY(parseIntOrDefault(re.getAttribute("y"), 0));
                tf.setWidth(parseIntOrDefault(re.getAttribute("width"), 100));
                tf.setHeight(parseIntOrDefault(re.getAttribute("height"), 20));
            }

            applyFontFromElement(tfEl, tf);

            // Always set monospaced Cyrillic-capable font on textField.
            tf.setFontName(FontDefaults.FAMILY);
            tf.setPdfFontName(FontDefaults.PDF_NAME);
            tf.setPdfEncoding(FontDefaults.PDF_ENCODING);

            Element exprEl = getChildElement(tfEl, "textFieldExpression");
            if (exprEl != null) {
                String exprText = exprEl.getTextContent();
                if (exprText != null) {
                    exprText = exprText.trim();
                    if (exprText.startsWith("<![CDATA[")) {
                        exprText = exprText.substring(9, exprText.length() - 3);
                    }
                }
                if (exprText != null && !exprText.isEmpty()) {
                    // Wrap numeric fields in String() to force Identity-H encoding.
                    // Jasper 7.x encodes Integer/Long fields as WinAnsi even with
                    // pdfFontName=Arial/Identity-H. String() forces UTF-16 path.
                    String wrapped = wrapNumericExpression(exprText);
                    JRDesignExpression expr = new JRDesignExpression(wrapped);
                    tf.setExpression(expr);
                }
            }

            band.addElement(tf);
        }
    }

    private void setDesignBand(JasperDesign design, String name, JRDesignBand band) throws Exception {
        String methodName = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        java.lang.reflect.Method m = design.getClass().getMethod(methodName, JRBand.class);
        m.invoke(design, band);
    }

    private Object getDesignBand(JasperDesign design, String name) throws Exception {
        String methodName = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        java.lang.reflect.Method m = design.getClass().getMethod(methodName);
        return m.invoke(design);
    }

    private String wrapNumericExpression(String expr) {
        // Only wrap simple $F{field} or $V{var} expressions (no concatenation)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "^\\$[FV]\\{(\\w+)\\}$").matcher(expr.trim());
        if (m.find()) {
            String name = m.group(1);
            String fclass = fieldClasses.get(name);
            if (fclass != null && (fclass.contains("Integer") || fclass.contains("Long") ||
                fclass.contains("Short") || fclass.contains("Byte") ||
                fclass.contains("BigDecimal") || fclass.contains("BigInteger") ||
                fclass.contains("Double") || fclass.contains("Float") ||
                fclass.contains("Number"))) {
                return "String.valueOf(" + expr.trim() + ")";
            }
        }
        return expr;
    }

    private int parseIntOrDefault(String value, int def) {
        if (value == null || value.isEmpty()) return def;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
