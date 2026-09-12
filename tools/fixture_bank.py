"""Generates the two-column bank-statement fixture and its per-string ground truth.

Reproducible so before/after scores are comparable. Run:  python fixture_bank.py
Writes testdoc-bank.png next to this file.
"""
from PIL import Image, ImageDraw, ImageFont
import json, os

W, H = 1240, 1754

# ground truth: HIDE = must be redacted, KEEP = must stay readable,
# AMBIG = defensible either way, excluded from scoring.
GT = {}
def gt(text, cls):
    GT[text] = cls
    return text

def font(sz, bold=False):
    for p in ([r"C:\Windows\Fonts\arialbd.ttf"] if bold else [r"C:\Windows\Fonts\arial.ttf"]):
        try: return ImageFont.truetype(p, sz)
        except Exception: pass
    return ImageFont.load_default()

img = Image.new("RGB", (W, H), "white")
d = ImageDraw.Draw(img)
d.rectangle([0, 0, W, 140], fill=(24, 48, 110))
d.text((50, 45), gt("MERIDIAN BANK", "KEEP"), font=font(46, True), fill="white")
d.text((W - 380, 60), gt("Account Statement", "KEEP"), font=font(26), fill=(200, 215, 240))

rows = [
    ("Account Holder",       "Priya Ramachandran",              "HIDE"),
    ("Customer ID",          "MB-4471902",                      "HIDE"),
    ("Account Number",       "5012 3456 7890 1234",             "HIDE"),
    ("IFSC Code",            "MERI0004471",                     "HIDE"),
    ("PAN",                  "ABCDE1234F",                      "HIDE"),
    ("Aadhaar",              "2345 6789 0123",                  "HIDE"),
    ("Date of Birth",        "14/03/1988",                      "HIDE"),
    ("Registered Mobile",    "+91 98765 43210",                 "HIDE"),
    ("Email",                "priya.ram@example.com",           "HIDE"),
    ("Address",              "42 Nehru Cross Road, Indiranagar", "HIDE"),
    ("",                     "Bengaluru 560038, Karnataka",     "HIDE"),
    ("Statement Period",     "01/08/2026 to 31/08/2026",        "KEEP"),
    ("Opening Balance",      "Rs 1,24,500.00",                  "HIDE"),
    ("Closing Balance",      "Rs 2,08,315.50",                  "HIDE"),
    ("Branch",               "Indiranagar Branch",              "AMBIG"),
    ("Relationship Manager", "Sunil Kapoor",                    "HIDE"),
]

y = 200
for label, value, cls in rows:
    if label:
        d.text((60, y), gt(label, "KEEP"), font=font(28), fill=(90, 90, 100))
    d.text((520, y), gt(value, cls), font=font(30, True), fill=(15, 15, 25))
    y += 62

d.line([(50, y + 16), (W - 50, y + 16)], fill=(180, 180, 190), width=2)
y += 50
d.text((60, y), gt("RECENT TRANSACTIONS", "KEEP"), font=font(30, True), fill=(24, 48, 110))
y += 56

txns = [
    ("03/08", "UPI to RAHUL MEHTA",      "-4,200.00",     "HIDE"),
    ("11/08", "Salary Credit ACME LABS", "+1,45,000.00",  "AMBIG"),
    ("19/08", "Card 5012 **** 1234 POS", "-8,999.00",     "HIDE"),
    ("27/08", "NEFT to LANDLORD S IYER", "-35,000.00",    "HIDE"),
]
for dt, desc, amt, cls in txns:
    d.text((60, y),  gt(dt, "KEEP"),  font=font(26), fill=(60, 60, 70))
    d.text((180, y), gt(desc, cls),   font=font(26), fill=(20, 20, 30))
    d.text((950, y), gt(amt, "HIDE"), font=font(26, True), fill=(20, 20, 30))
    y += 52

d.text((60, H - 90),
       gt("This statement is computer generated and does not require a signature.", "KEEP"),
       font=font(22), fill=(120, 120, 130))

here = os.path.dirname(os.path.abspath(__file__))
img.save(os.path.join(here, "testdoc-bank.png"))
with open(os.path.join(here, "testdoc-bank.groundtruth.json"), "w", encoding="utf-8") as f:
    json.dump(GT, f, indent=2, ensure_ascii=False)
print("wrote testdoc-bank.png and ground truth:",
      sum(1 for v in GT.values() if v == "HIDE"), "HIDE,",
      sum(1 for v in GT.values() if v == "KEEP"), "KEEP,",
      sum(1 for v in GT.values() if v == "AMBIG"), "AMBIG")
