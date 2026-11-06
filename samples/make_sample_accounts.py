#!/usr/bin/env python3
"""
Writes a test account file for the ingestion service: samples/accounts_<yyyyMMdd>.xlsx

    python3 samples/make_sample_accounts.py                # 10,000 rows for 2026-11-10
    python3 samples/make_sample_accounts.py 20261111 2500  # another day, another size
    python3 samples/make_sample_accounts.py 20300101 1000000 /tmp/perf   # into another folder (performance tests)

Uses only the Python standard library. The rows are the same for the same date and size, and different for a
different date, so each date gives a file the ingestion service has not seen before (it skips content it already loaded).

Mix of rows (about):
  95%   eligible: SAVINGS or CURRENT, KYC VERIFIED, balance >= 0        -> UPGRADED
  1.5%  type LOAN                                                       -> REJECTED by the upgrade rules
  1.5%  KYC PENDING                                                     -> REJECTED by the upgrade rules
  1%    negative balance                                                -> REJECTED by the upgrade rules
  1%    broken rows: bad email, missing name, account number repeated   -> INGESTION_ERROR at load
The script prints the exact counts to expect.
"""
import random
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

HEADER = ["Account No", "Customer Id", "Customer Name", "Email", "Current Type", "Balance", "KYC Status"]
FIRST = ["Asha", "Ben", "Chen", "Dana", "Eli", "Farah", "Gopal", "Hana", "Ivan", "Jaya", "Kiran", "Lena", "Mohan", "Nora", "Omar", "Priya"]
LAST = ["Rao", "Cole", "Wei", "Fox", "Nash", "Khan", "Iyer", "Sato", "Petrov", "Menon", "Shah", "Berg", "Das", "Lund", "Aziz", "Nair"]


def make_rows(date, count):
    rnd = random.Random(f"{date}-{count}")
    rows, expect = [], {"eligible": 0, "loan": 0, "kyc": 0, "negative": 0, "bad_email": 0, "no_name": 0, "duplicate": 0}
    loadable = []   # accounts of rows that pass the load checks; a repeat must copy one of these to be caught
    for i in range(1, count + 1):
        account = f"AC{date[2:]}{i:06d}"          # unique per date, so days never collide
        name = f"{rnd.choice(FIRST)} {rnd.choice(LAST)}"
        email = f"{name.lower().replace(' ', '.')}.{i}@example.com"
        acc_type = rnd.choice(["SAVINGS", "CURRENT"])
        balance = round(rnd.uniform(0, 250_000), 2)
        kyc = "VERIFIED"
        roll = rnd.random()
        if roll < 0.015:
            acc_type, kind = "LOAN", "loan"
        elif roll < 0.030:
            kyc, kind = "PENDING", "kyc"
        elif roll < 0.040:
            balance, kind = -round(rnd.uniform(1, 5_000), 2), "negative"
        elif roll < 0.044:
            email, kind = email.replace("@", "-at-"), "bad_email"
        elif roll < 0.047:
            name, kind = "", "no_name"
        elif roll < 0.050 and loadable:
            account, kind = rnd.choice(loadable), "duplicate"   # same account as an earlier row
        else:
            kind = "eligible"
        expect[kind] += 1
        if kind in ("eligible", "loan", "kyc", "negative"):
            loadable.append(account)
        rows.append([account, f"CUST{i:06d}", name, email, acc_type, balance, kyc])
    return rows, expect


def cell(col, row_no, value):
    ref = f"{'ABCDEFG'[col]}{row_no}"
    if isinstance(value, (int, float)):
        return f'<c r="{ref}"><v>{value}</v></c>'
    if value == "":
        return ""
    return f'<c r="{ref}" t="inlineStr"><is><t>{escape(value)}</t></is></c>'


def write_xlsx(path, rows):
    """Writes the sheet row by row into the zip, so a million rows does not need the whole file in memory."""
    ct = "application/vnd.openxmlformats-officedocument.spreadsheetml"
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>'
                   f'<Override PartName="/xl/workbook.xml" ContentType="{ct}.sheet.main+xml"/>'
                   f'<Override PartName="/xl/worksheets/sheet1.xml" ContentType="{ct}.worksheet+xml"/></Types>')
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr("xl/workbook.xml",
                   '<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                   'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                   '<sheets><sheet name="accounts" sheetId="1" r:id="rId1"/></sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels",
                   '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
        with z.open("xl/worksheets/sheet1.xml", "w", force_zip64=True) as sheet:
            sheet.write(b'<?xml version="1.0" encoding="UTF-8"?>'
                        b'<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>')
            for r, row in enumerate([HEADER] + rows, start=1):
                sheet.write((f'<row r="{r}">' + "".join(cell(c, r, v) for c, v in enumerate(row)) + "</row>").encode())
            sheet.write(b"</sheetData></worksheet>")


def main():
    date = sys.argv[1] if len(sys.argv) > 1 else "20261110"
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 10_000
    if len(date) != 8 or not date.isdigit():
        sys.exit("date must be yyyyMMdd, for example 20261110")
    if count > 1_048_575:
        sys.exit("an Excel sheet holds at most 1,048,576 rows including the header")
    rows, e = make_rows(date, count)
    folder = Path(sys.argv[3]) if len(sys.argv) > 3 else Path(__file__).resolve().parent
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"accounts_{date}.xlsx"
    write_xlsx(path, rows)
    bad = e["bad_email"] + e["no_name"] + e["duplicate"]
    loaded = count - bad
    rejected = e["loan"] + e["kyc"] + e["negative"]
    print(f"Wrote {path} ({count} rows)")
    print(f"Expect at load:     {loaded} saved, {bad} in INGESTION_ERROR "
          f"(bad email {e['bad_email']}, no name {e['no_name']}, repeated account {e['duplicate']})")
    print(f"Expect at planning: {loaded - rejected} PENDING, {rejected} REJECTED "
          f"(LOAN {e['loan']}, KYC PENDING {e['kyc']}, negative balance {e['negative']})")
    print(f"Expect after run:   {loaded - rejected} UPGRADED and as many account.upgraded events")


if __name__ == "__main__":
    main()
