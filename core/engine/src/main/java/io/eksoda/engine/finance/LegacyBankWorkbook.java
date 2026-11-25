package io.eksoda.engine.finance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.NumberToTextConverter;

/** Read the literal cells of a single BIFF worksheet, keeping displayed bank references. */
public final class LegacyBankWorkbook {
    private static final byte[] SIGNATURE = {
        (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
    };

    private LegacyBankWorkbook() {}

    public static boolean matches(byte[] input) {
        if (input.length < SIGNATURE.length) return false;
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (input[i] != SIGNATURE[i]) return false;
        }
        return true;
    }

    public static List<List<String>> rows(byte[] input, int date, int amount, char decimal,
            String pattern) throws IOException {
        if (!matches(input) || input.length > 4_000_000) throw new IOException("Invalid XLS input");
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(pattern);
        DataFormatter display = new DataFormatter(Locale.ROOT);
        try (HSSFWorkbook book = new HSSFWorkbook(new ByteArrayInputStream(input))) {
            if (book.getNumberOfSheets() != 1) throw new IOException("Expected one worksheet");
            Sheet sheet = book.getSheetAt(0);
            if (sheet.getLastRowNum() >= 20_000) throw new IOException("Too many rows");
            List<List<String>> rows = new ArrayList<>();
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                List<String> fields = new ArrayList<>();
                if (row != null) {
                    for (int col = 0; col < row.getLastCellNum(); col++) {
                        Cell cell = row.getCell(col);
                        fields.add(readCell(cell, i > 0 && col == date, i > 0 && col == amount,
                                decimal, dates, display));
                    }
                }
                rows.add(fields);
            }
            return rows;
        }
    }

    private static String readCell(Cell cell, boolean date, boolean amount, char decimal,
            DateTimeFormatter dates, DataFormatter display) throws IOException {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case BLANK: return "";
            case STRING: return cell.getStringCellValue();
            case NUMERIC:
                if (date) {
                    if (cell.getNumericCellValue() < 0) throw new IOException("Invalid date");
                    return cell.getLocalDateTimeCellValue().toLocalDate().format(dates);
                }
                if (amount) {
                    String number = NumberToTextConverter.toText(cell.getNumericCellValue());
                    return new BigDecimal(number).stripTrailingZeros().toPlainString().replace('.', decimal);
                }
                return display.formatCellValue(cell);
            default: throw new IOException("Expected literal statement values");
        }
    }
}
