package io.eksoda.engine.finance;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Local reader for single-sheet, literal-value XLSX bank exports. */
public final class BankWorkbook {
    private BankWorkbook() {}

    public static byte[] readLimited(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = stream.read(buffer)) != -1) {
            if (n > limit - out.size()) throw new IOException("File size limit exceeded");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static Document xml(byte[] bytes) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.contains("<!") || text.indexOf(0) >= 0) throw new IOException("Unsupported XML declaration");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
    }

    private static NodeList tags(Element element, String name) {
        return element.getElementsByTagNameNS("*", name);
    }

    public static List<List<String>> rows(byte[] data, int date, int amount,
            char decimal, String pattern) throws Exception {
        byte[] book = null, sheet = null, strings = null;
        int remaining = 8_000_000;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 512) throw new IOException("Too many entries");
                byte[] bytes = readLimited(zip, remaining);
                remaining -= bytes.length;
                String name = entry.getName();
                if (name.equals("xl/workbook.xml")) book = bytes;
                if (name.equals("xl/sharedStrings.xml")) strings = bytes;
                if (name.matches("xl/worksheets/[^/]+[.]xml")) {
                    if (sheet != null) throw new IOException("Select a single-sheet workbook");
                    sheet = bytes;
                }
            }
        }
        if (book == null || sheet == null) throw new IOException("Workbook missing");
        Element workbook = xml(book).getDocumentElement();
        if (tags(workbook,"sheet").getLength() != 1) throw new IOException("Expected one sheet");
        Element props = (Element) tags(workbook,"workbookPr").item(0);
        boolean epoch1904 = props != null && Arrays.asList("1","true").contains(props.getAttribute("date1904"));
        List<String> shared = new ArrayList<>();
        if (strings != null) {
            NodeList items = tags(xml(strings).getDocumentElement(),"si");
            for (int i=0; i<items.getLength(); i++) shared.add(joinText((Element)items.item(i)));
        }
        List<List<String>> result = new ArrayList<>();
        NodeList rows = tags(xml(sheet).getDocumentElement(),"row");
        if (rows.getLength() > 20000) throw new IOException("Too many rows");
        DateTimeFormatter format = DateTimeFormatter.ofPattern(pattern);
        for (int i=0; i<rows.getLength(); i++) {
            List<String> fields = new ArrayList<>();
            NodeList cells = tags((Element)rows.item(i),"c");
            for (int j=0; j<cells.getLength(); j++) {
                Element c = (Element)cells.item(j);
                String address = c.getAttribute("r");
                if (!address.matches("[A-Z]{1,3}[1-9][0-9]*")) throw new IOException("Invalid address");
                int col = 0;
                for (char ch: address.toCharArray()) {
                    if (ch < 'A' || ch > 'Z') break;
                    col = 26 * col + ch - 'A' + 1;
                }
                if (col > 256 || col <= fields.size()) throw new IOException("Invalid column");
                while (fields.size() < col-1) fields.add("");
                if (tags(c,"f").getLength() > 0) throw new IOException("Formula cell");
                Node v = tags(c,"v").item(0);
                String value = v == null ? "" : v.getTextContent();
                switch (c.getAttribute("t")) {
                    case "s": value = shared.get(Integer.parseInt(value)); break;
                    case "inlineStr": value = joinText(c); break;
                    case "str": break;
                    case "d":
                        if (col-1 == date) value = LocalDate.parse(value).format(format);
                        break;
                    case "": case "n":
                        if (i > 0 && !value.isEmpty() && col-1 == date) {
                            long serial = new BigDecimal(value).longValueExact();
                            if (serial < 0 || (!epoch1904 && serial == 60)) throw new IOException("Invalid date");
                            LocalDate origin = epoch1904 ? LocalDate.of(1904,1,1) :
                                LocalDate.of(1899,12,31);
                            value = origin.plusDays(!epoch1904 && serial > 60 ? serial-1 : serial).format(format);
                        } else if (i > 0 && !value.isEmpty() && col-1 == amount) {
                            BigDecimal money = new BigDecimal(value);
                            if (money.precision() > 19 || Math.abs((long)money.scale()) > 18)
                                throw new IOException("Invalid amount");
                            value = money.stripTrailingZeros().toPlainString().replace('.',decimal);
                        }
                        break;
                    default: throw new IOException("Unsupported cell type");
                }
                fields.add(value);
            }
            result.add(fields);
        }
        return result;
    }

    private static String joinText(Element element) {
        StringBuilder text = new StringBuilder();
        NodeList parts = tags(element,"t");
        for (int i=0; i<parts.getLength(); i++) text.append(parts.item(i).getTextContent());
        return text.toString();
    }
}
