import io.eksoda.engine.finance.BankWorkbook;
import java.nio.file.*;
import java.util.*;
public class BankCheck {
 public static void main(String[] args) throws Exception {
 for(String path : List.of(args[0],args[1])){
 var rows=BankWorkbook.rows(Files.readAllBytes(Path.of(path)),0,2,',',"dd/MM/uuuu");
 if (!rows.get(1).equals(List.of("25/11/2026","Ενοίκιο","-510,25","0001"))) throw new AssertionError(rows);
 }
 try { BankWorkbook.rows(Files.readAllBytes(Path.of(args[2])),0,2,',',"dd/MM/uuuu"); throw new AssertionError(); }
 catch(java.io.IOException expected) {}
 System.out.println("Passed both date systems, signed EUR amount, Greek text, leading-zero ID and multi-sheet rejection");
 }}
