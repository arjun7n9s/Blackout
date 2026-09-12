"""Generate hostile document fixtures for Teammate C. Writes PNGs under C-Outputs/fixtures/."""
from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1] / "fixtures"
ROOT.mkdir(parents=True, exist_ok=True)

W, H = 1240, 1754  # ~A4 @ 150dpi


def font(size: int, bold: bool = False, hindi: bool = False, script: bool = False) -> ImageFont.FreeTypeFont:
    candidates = []
    if hindi:
        candidates += [r"C:\Windows\Fonts\Nirmala.ttf", r"C:\Windows\Fonts\NirmalaB.ttf"]
    if script:
        candidates += [r"C:\Windows\Fonts\segoesc.ttf", r"C:\Windows\Fonts\comic.ttf", r"C:\Windows\Fonts\Inkfree.ttf"]
    if bold:
        candidates += [r"C:\Windows\Fonts\arialbd.ttf", r"C:\Windows\Fonts\calibrib.ttf"]
    candidates += [r"C:\Windows\Fonts\arial.ttf", r"C:\Windows\Fonts\calibri.ttf", r"C:\Windows\Fonts\tahoma.ttf"]
    for p in candidates:
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            continue
    return ImageFont.load_default()


def page(bg=(248, 246, 240)) -> Image.Image:
    return Image.new("RGB", (W, H), bg)


def draw_header(d: ImageDraw.ImageDraw, bank: str, subtitle: str) -> None:
    d.rectangle([0, 0, W, 110], fill=(18, 52, 86))
    d.text((40, 28), bank, font=font(36, bold=True), fill="white")
    d.text((40, 72), subtitle, font=font(18), fill=(200, 210, 220))


def two_col_statement(path: Path, variant: int = 0) -> None:
    img = page()
    d = ImageDraw.Draw(img)
    banks = [
        ("HORIZON BANK", "Statement of Account  ·  01 Apr 2026 – 30 Jun 2026"),
        ("NARMADA CO-OP BANK", "Savings Account Statement  ·  Q1 FY26-27"),
        ("MERIDIAN PRIVATE BANK", "Priority Current Account  ·  Mar 2026"),
    ]
    people = [
        ("Priya Ramachandran", "AARPR8821K", "14-08-1988", "50123456789012", "HZBN0001429", "Bandra West, Mumbai 400050", "priya.r@example.com", "+91 98200 44112"),
        ("Arjun Mehta", "BQHPM4410Q", "02-11-1991", "00981234009876", "NRCB0000881", "Vastrapur, Ahmedabad 380015", "arjun.mehta@example.net", "+91 79400 11220"),
        ("Sana Qureshi", "CWQPS1099H", "27-03-1985", "33110022998877", "MRPB0002201", "Jubilee Hills, Hyderabad 500033", "sana.q@example.org", "+91 90000 77881"),
    ]
    bank, sub = banks[variant % 3]
    name, pan, dob, acct, ifsc, branch, email, phone = people[variant % 3]
    draw_header(d, bank, sub)
    y = 140
    d.text((40, y), "Customer / Account details", font=font(22, bold=True), fill=(18, 52, 86))
    y += 40
    rows = [
        ("Account Holder", name, "PAN", pan),
        ("Date of Birth", dob, "Customer ID", f"CID-{8800 + variant}"),
        ("Account Number", acct, "IFSC", ifsc),
        ("Account Type", "Savings", "Branch", branch.split(",")[0]),
        ("Registered Mobile", phone, "Email", email),
        ("Nominee", "K. Ramachandran" if variant == 0 else "N. Mehta", "Aadhaar (masked)", f"XXXX-XXXX-{1200 + variant}"),
        ("Communication Address", branch, "CKYC Number", f"CKYC{70000000 + variant}"),
    ]
    label_f, value_f = font(16, bold=True), font(16)
    for left_l, left_v, right_l, right_v in rows:
        d.text((40, y), left_l, font=label_f, fill=(90, 90, 90))
        d.text((40, y + 22), left_v, font=value_f, fill=(20, 20, 20))
        d.text((640, y), right_l, font=label_f, fill=(90, 90, 90))
        d.text((640, y + 22), right_v, font=value_f, fill=(20, 20, 20))
        y += 70
    y += 10
    d.line([(40, y), (W - 40, y)], fill=(180, 180, 180), width=1)
    y += 16
    d.text((40, y), "Date", font=label_f, fill=(90, 90, 90))
    d.text((180, y), "Particulars", font=label_f, fill=(90, 90, 90))
    d.text((780, y), "Debit", font=label_f, fill=(90, 90, 90))
    d.text((940, y), "Credit", font=label_f, fill=(90, 90, 90))
    d.text((1080, y), "Balance", font=label_f, fill=(90, 90, 90))
    y += 28
    txns = [
        ("02-04-2026", "UPI/PRIYA RAMACHANDRAN/8821", "", "25,000.00", "1,12,440.10"),
        ("05-04-2026", "NEFT IN/SALARY/INFOSYS LTD", "", "1,84,220.00", "2,96,660.10"),
        ("07-04-2026", "IMPS/AARPR8821K/SELF", "50,000.00", "", "2,46,660.10"),
        ("12-04-2026", "POS 5123 **** 4412 AMAZON", "4,129.00", "", "2,42,531.10"),
        ("18-04-2026", "ACH DEBIT/HDFC LOAN XXX4412", "18,750.00", "", "2,23,781.10"),
        ("21-04-2026", "UPI/SANA QURESHI/9000077881", "6,400.00", "", "2,17,381.10"),
        ("28-04-2026", "INTEREST CREDIT", "", "412.55", "2,17,793.65"),
        ("03-05-2026", "CHEQUE 109221 / RENT", "45,000.00", "", "1,72,793.65"),
        ("11-05-2026", "GST PAYMENT / GSTIN 27AARPR8821K1Z5", "9,180.00", "", "1,63,613.65"),
        ("19-05-2026", "UPI/ARJUN MEHTA/7940011220", "", "12,000.00", "1,75,613.65"),
        ("02-06-2026", "ATM WDL 400050 / CARD ****4412", "10,000.00", "", "1,65,613.65"),
        ("16-06-2026", "NEFT OUT/IFSC HZBN0001429", "25,000.00", "", "1,40,613.65"),
    ]
    vf = font(15)
    for date, part, debit, credit, bal in txns:
        d.text((40, y), date, font=vf, fill=(30, 30, 30))
        d.text((180, y), part, font=vf, fill=(30, 30, 30))
        d.text((780, y), debit, font=vf, fill=(30, 30, 30))
        d.text((940, y), credit, font=vf, fill=(30, 30, 30))
        d.text((1080, y), bal, font=vf, fill=(30, 30, 30))
        y += 28
    y += 20
    d.text((40, y), "This is a computer-generated statement. No signature required.", font=font(13), fill=(110, 110, 110))
    img.save(path)


def kyc_form(path: Path) -> None:
    img = page((255, 255, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, 90], fill=(120, 20, 20))
    d.text((40, 28), "CKYC / KYC UPDATION FORM", font=font(30, bold=True), fill="white")
    y = 120
    fields = [
        ("Full Name (as per PAN)", "Kavya Narayanan"),
        ("Father's / Spouse Name", "R. Narayanan"),
        ("Date of Birth", "09-01-1994"),
        ("PAN", "BDFPN2201L"),
        ("Aadhaar Number", "4455 6677 8899"),
        ("Passport Number", "Z4129981"),
        ("Voter ID", "ABC1234567"),
        ("Driving Licence", "MH14 20110012345"),
        ("Gender", "Female"),
        ("Nationality", "Indian"),
        ("Occupation", "Software Engineer"),
        ("Annual Income", "₹ 18,40,000"),
        ("Residential Address", "12, Palm Court, Koregaon Park, Pune 411001"),
        ("Permanent Address", "41, MG Road, Thrissur, Kerala 680001"),
        ("Mobile Number", "+91 98111 22334"),
        ("Email Address", "kavya.n@example.com"),
        ("Account Number", "381200998877"),
        ("IFSC Code", "SBIN0000456"),
        ("Branch Name", "Koregaon Park"),
        ("Customer ID", "CID-441290"),
    ]
    lf, vf = font(15, bold=True), font(16)
    for i, (lab, val) in enumerate(fields):
        col = i % 2
        row = i // 2
        x = 40 + col * 600
        yy = y + row * 72
        d.rectangle([x, yy, x + 560, yy + 62], outline=(200, 200, 200), width=1)
        d.text((x + 10, yy + 6), lab, font=lf, fill=(100, 100, 100))
        d.text((x + 10, yy + 30), val, font=vf, fill=(15, 15, 15))
    img.save(path)


def salary_slip(path: Path) -> None:
    img = page((255, 255, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, 100], fill=(0, 70, 50))
    d.text((40, 24), "NORTHWIND TECHNOLOGIES PVT LTD", font=font(26, bold=True), fill="white")
    d.text((40, 62), "Payslip  ·  August 2026  ·  Confidential", font=font(16), fill=(200, 230, 210))
    y = 130
    pairs = [
        ("Employee Name", "Rohit Iyer", "Employee Code", "NWT-88421"),
        ("Designation", "Staff Engineer", "PAN", "AHXPI3390B"),
        ("Date of Birth", "21-06-1990", "UAN", "100234567890"),
        ("Bank Account", "44556677889900", "IFSC", "ICIC0000123"),
        ("PF Account", "MH/BAN/12345/88421", "ESI Number", "31-00-123456-000-0001"),
        ("Location", "Bengaluru", "Email", "rohit.iyer@northwind.example"),
    ]
    lf, vf = font(15, bold=True), font(16)
    for a, b, c, e in pairs:
        d.text((40, y), a, font=lf, fill=(90, 90, 90))
        d.text((40, y + 22), b, font=vf, fill=(10, 10, 10))
        d.text((640, y), c, font=lf, fill=(90, 90, 90))
        d.text((640, y + 22), e, font=vf, fill=(10, 10, 10))
        y += 68
    y += 8
    d.text((40, y), "Earnings", font=font(20, bold=True), fill=(0, 70, 50))
    d.text((640, y), "Deductions", font=font(20, bold=True), fill=(0, 70, 50))
    y += 36
    left = [("Basic", "1,20,000"), ("HRA", "48,000"), ("Special Allowance", "32,400"), ("LTA", "10,000")]
    right = [("PF", "14,400"), ("Professional Tax", "200"), ("TDS", "22,180"), ("Health Insurance", "1,250")]
    for i in range(4):
        d.text((40, y), left[i][0], font=lf, fill=(60, 60, 60))
        d.text((280, y), left[i][1], font=vf, fill=(10, 10, 10))
        d.text((640, y), right[i][0], font=lf, fill=(60, 60, 60))
        d.text((940, y), right[i][1], font=vf, fill=(10, 10, 10))
        y += 32
    y += 16
    d.text((40, y), "Net Pay", font=font(22, bold=True), fill=(0, 70, 50))
    d.text((280, y), "₹ 1,72,370", font=font(22, bold=True), fill=(0, 70, 50))
    img.save(path)


def receipt(path: Path) -> Image.Image:
    img = page((255, 252, 245))
    d = ImageDraw.Draw(img)
    d.text((40, 40), "CITY PHARMACY", font=font(32, bold=True), fill=(30, 30, 30))
    d.text((40, 84), "GSTIN: 27AABCU9603R1ZX   Tax Invoice", font=font(16), fill=(80, 80, 80))
    d.text((40, 120), "Bill To: Ananya Sharma   Phone: +91 98765 43210", font=font(16), fill=(20, 20, 20))
    d.text((40, 150), "Card: **** **** **** 8812   Auth: 442190", font=font(16), fill=(20, 20, 20))
    y = 200
    for name, amt in [("Dolo 650 15s", "45.00"), ("Crocin Advance", "38.00"), ("Consultation", "500.00")]:
        d.text((40, y), name, font=font(18), fill=(20, 20, 20))
        d.text((980, y), amt, font=font(18), fill=(20, 20, 20))
        y += 36
    d.text((40, y + 20), "TOTAL  ₹ 583.00", font=font(22, bold=True), fill=(20, 20, 20))
    img.save(path)
    return img


def apply_glare(src: Image.Image, path: Path) -> None:
    img = src.convert("RGB")
    overlay = Image.new("RGB", img.size, (255, 255, 255))
    mask = Image.new("L", img.size, 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([400, 80, 1100, 700], fill=200)
    mask = mask.filter(ImageFilter.GaussianBlur(80))
    out = Image.composite(overlay, img, mask)
    out = ImageEnhance.Contrast(out).enhance(1.15)
    out.save(path)


def apply_blur(src: Image.Image, path: Path) -> None:
    src.filter(ImageFilter.GaussianBlur(3.2)).save(path)


def apply_skew(src: Image.Image, path: Path, deg: float = 36) -> None:
    rot = src.rotate(deg, expand=True, fillcolor=(30, 30, 30))
    rot.thumbnail((W, H), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (W, H), (30, 30, 30))
    canvas.paste(rot, ((W - rot.width) // 2, (H - rot.height) // 2))
    canvas.save(path)


def hindi_english(path: Path) -> None:
    img = page()
    d = ImageDraw.Draw(img)
    draw_header(d, "भारत बैंक  /  BHARAT BANK", "खाता विवरण  ·  Account Statement")
    hf, ef = font(22, hindi=True), font(18)
    d.text((40, 140), "खाता धारक / Account Holder", font=hf, fill=(80, 80, 80))
    d.text((40, 174), "राहुल शर्मा  /  Rahul Sharma", font=hf, fill=(15, 15, 15))
    d.text((40, 230), "स्थायी खाता संख्या / PAN", font=hf, fill=(80, 80, 80))
    d.text((40, 264), "BHXPS4412D", font=ef, fill=(15, 15, 15))
    d.text((640, 230), "जन्म तिथि / Date of Birth", font=hf, fill=(80, 80, 80))
    d.text((640, 264), "15-08-1987", font=ef, fill=(15, 15, 15))
    d.text((40, 330), "खाता संख्या / Account Number", font=hf, fill=(80, 80, 80))
    d.text((40, 364), "1100220033004400", font=ef, fill=(15, 15, 15))
    d.text((640, 330), "IFSC", font=hf, fill=(80, 80, 80))
    d.text((640, 364), "BARB0CONNAU", font=ef, fill=(15, 15, 15))
    d.text((40, 430), "पता / Address", font=hf, fill=(80, 80, 80))
    d.text((40, 464), "12, कनॉट प्लेस, नई दिल्ली 110001", font=hf, fill=(15, 15, 15))
    d.text((40, 520), "मोबाइल / Mobile  +91 98100 11223", font=hf, fill=(15, 15, 15))
    d.text((40, 580), "Only the Latin fields above should OCR reliably.", font=ef, fill=(90, 90, 90))
    img.save(path)


def small_print(path: Path) -> None:
    img = page((255, 255, 255))
    d = ImageDraw.Draw(img)
    d.text((40, 30), "MASTER SERVICES AGREEMENT — CONFIDENTIAL", font=font(14, bold=True), fill=(0, 0, 0))
    body = (
        "This Agreement is made between Northwind Technologies Pvt Ltd (PAN AHXPI3390B) and "
        "Priya Ramachandran (Aadhaar 2233 4455 6677, PAN AARPR8821K, account 50123456789012, "
        "IFSC HZBN0001429). Consideration of INR 18,40,000 per annum. Notices to 12 Palm Court, "
        "Koregaon Park, Pune 411001 and email priya.r@example.com / +91 98200 44112. "
        "GSTIN 27AARPR8821K1Z5. Passport Z4129981. Date of Birth 14-08-1988. "
    ) * 18
    d.multiline_text((40, 60), wrap(body, 118), font=font(9), fill=(20, 20, 20), spacing=2)
    img.save(path)


def wrap(text: str, width: int) -> str:
    words = text.split()
    lines, cur = [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if len(trial) > width:
            lines.append(cur)
            cur = w
        else:
            cur = trial
    if cur:
        lines.append(cur)
    return "\n".join(lines)


def dark(src: Image.Image, path: Path) -> None:
    img = ImageEnhance.Brightness(src).enhance(0.28)
    img = ImageEnhance.Contrast(img).enhance(0.85)
    img.save(path)


def crumpled(src: Image.Image, path: Path) -> None:
    img = src.convert("RGB")
    d = ImageDraw.Draw(img)
    rng = random.Random(7)
    for _ in range(18):
        x = rng.randint(0, W)
        y = rng.randint(0, H)
        d.line([(x, y), (x + rng.randint(-400, 400), y + rng.randint(-80, 80))], fill=(180, 180, 175), width=2)
    img = img.transform(img.size, Image.Transform.QUAD, (40, 20, W - 10, 50, W - 30, H - 20, 20, H - 40), Image.Resampling.BILINEAR, fillcolor=(60, 60, 55))
    img.save(path)


def screen_photo(src: Image.Image, path: Path) -> None:
    img = src.convert("RGB")
    grid = Image.new("RGBA", img.size, (0, 0, 0, 0))
    g = ImageDraw.Draw(grid)
    for x in range(0, W, 3):
        g.line([(x, 0), (x, H)], fill=(0, 0, 0, 28))
    for y in range(0, H, 3):
        g.line([(0, y), (W, y)], fill=(0, 0, 0, 18))
    out = Image.alpha_composite(img.convert("RGBA"), grid).convert("RGB")
    out = ImageEnhance.Color(out).enhance(1.25)
    glare = Image.new("L", img.size, 0)
    ImageDraw.Draw(glare).polygon([(200, 0), (900, 0), (700, 400), (100, 280)], fill=90)
    glare = glare.filter(ImageFilter.GaussianBlur(40))
    white = Image.new("RGB", img.size, (255, 255, 255))
    out = Image.composite(white, out, glare)
    out.save(path)


def cutoff(src: Image.Image, path: Path) -> None:
    src.crop((0, 0, int(W * 0.62), int(H * 0.55))).resize((W, H), Image.Resampling.NEAREST).save(path)


def handwriting(path: Path) -> None:
    img = page((252, 248, 230))
    d = ImageDraw.Draw(img)
    d.text((40, 40), "Patient intake — handwritten", font=font(22, bold=True), fill=(40, 40, 40))
    hf = font(28, script=True)
    lines = [
        "Name: Meera Joshi",
        "DOB: 3 March 1979",
        "Phone: 9876543210",
        "Aadhaar: 6677 8899 0011",
        "Address: 8 Lake View, Indore",
        "Complaint: fever 2 days",
        "Rx: Dolo 650 1-1-1",
    ]
    y = 120
    for line in lines:
        d.text((60, y), line, font=hf, fill=(20, 40, 90))
        y += 70
    img.save(path)


def blank(path: Path) -> None:
    Image.new("RGB", (W, H), (245, 245, 245)).save(path)


def dense(path: Path) -> None:
    img = page((255, 255, 255))
    d = ImageDraw.Draw(img)
    d.text((20, 12), "HIGH-DENSITY LEDGER  ·  140 line items", font=font(16, bold=True), fill=(0, 0, 0))
    f = font(11)
    y = 40
    n = 0
    for i in range(70):
        left = f"L{i:03d}  PAN AARPR{i:04d}K  ACC 5012{i:08d}  IFSC HZBN000{i:04d}"
        right = f"R{i:03d}  MOB 98{10000000+i}  EMAIL u{i}@ex.com  AAD 4455{i:08d}"
        d.text((16, y), left, font=f, fill=(20, 20, 20))
        d.text((630, y), right, font=f, fill=(20, 20, 20))
        y += 24
        n += 2
        if y > H - 30:
            break
    img.save(path)


def rotated(src: Image.Image, path: Path) -> None:
    src.rotate(90, expand=True).resize((W, H), Image.Resampling.LANCZOS).save(path)


def upside_down(src: Image.Image, path: Path) -> None:
    src.rotate(180, expand=False).save(path)


def main() -> None:
    bank0 = ROOT / "_base_bank0.png"
    bank1 = ROOT / "_base_bank1.png"
    bank2 = ROOT / "_base_bank2.png"
    two_col_statement(bank0, 0)
    two_col_statement(bank1, 1)
    two_col_statement(bank2, 2)
    rec = ROOT / "_base_receipt.png"
    receipt(rec)
    b0, b1, b2, r0 = (Image.open(p) for p in (bank0, bank1, bank2, rec))

    two_col_statement(ROOT / "C-001-two-col-horizon.png", 0)
    two_col_statement(ROOT / "C-002-two-col-narmada.png", 1)
    kyc_form(ROOT / "C-003-two-col-kyc.png")
    salary_slip(ROOT / "C-017-two-col-payslip.png")
    apply_glare(Image.open(rec), ROOT / "C-004-glare-receipt.png")
    apply_blur(b0, ROOT / "C-005-motion-blur.png")
    apply_skew(b0, ROOT / "C-006-skew-statement.png", 36)
    hindi_english(ROOT / "C-007-hindi-english.png")
    small_print(ROOT / "C-008-small-print.png")
    dark(b1, ROOT / "C-009-dark-room.png")
    crumpled(b2, ROOT / "C-010-crumpled.png")
    screen_photo(b0, ROOT / "C-011-screen-photo.png")
    cutoff(b0, ROOT / "C-012-cutoff-page.png")
    handwriting(ROOT / "C-013-handwriting.png")
    blank(ROOT / "C-014-blank.png")
    dense(ROOT / "C-015-dense-ledger.png")
    rotated(b0, ROOT / "C-016-rotated-90.png")
    print("wrote", len(list(ROOT.glob("C-*.png"))), "fixtures to", ROOT)


if __name__ == "__main__":
    main()
