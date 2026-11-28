"""Generate fictional statements and run the production XLSX reader on JDK 21."""
from datetime import date
from pathlib import Path
from subprocess import run
from tempfile import TemporaryDirectory

from openpyxl import Workbook
from openpyxl.utils.datetime import CALENDAR_MAC_1904

root = Path(__file__).resolve().parents[2]
reader = root / "core/engine/src/main/java/io/eksoda/engine/finance/BankWorkbook.java"
with TemporaryDirectory() as temp:
    directory = Path(temp)
    samples = []
    for mac in (False, True):
        book = Workbook()
        if mac:
            book.epoch = CALENDAR_MAC_1904
        book.active.append(["Ημερομηνία", "Περιγραφή", "Ποσό", "Κωδικός"])
        book.active.append([date(2026, 11, 25), "Ενοίκιο", -510.25, "0001"])
        samples.append(directory / f"statement-{mac}.xlsx")
        book.save(samples[-1])
    book.create_sheet("second")
    samples.append(directory / "multiple.xlsx")
    book.save(samples[-1])
    run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", temp, reader], check=True)
    run(["java", "-cp", temp, root / "scripts/finance/BankCheck.java", *samples], check=True)
