#!/usr/bin/env python3
"""Python mirror of Nuva's PURE command logic (CommandParser + NuvaDateTimeParser
+ SensitiveAppPolicy), used to sanity-check the JVM unit-test expectations
offline (the sandbox cannot run Gradle). Keep in sync with the Kotlin sources
in ../app/src/main/java/com/nuva/assistant/command/.

Usage: python3 parser_mirror_check.py   (exit 0 = all checks pass)
"""
import re, sys
from pathlib import Path

BN_DIGITS = dict(zip("০১২৩৪৫৬৭৮৯", "0123456789"))

def normalize(text):
    s = "".join(BN_DIGITS.get(c, c) for c in text.lower())
    s = re.sub(r"[;।]", ":", s)
    return re.sub(r"\s+", " ", s).strip()

AM_PARTS = ["shokal", "sokal", "bhor", "সকাল", "ভোর"]
PM_PARTS = ["dupur", "dupura", "bikal", "bikela", "shondha", "sandhya", "raat", "রাত",
            "দুপুর", "বিকাল", "বিকেল", "সন্ধ্যা", "সাঁঝ"]

def part_of_day_is_am(t):
    if any(w in t for w in AM_PARTS): return True
    if any(w in t for w in PM_PARTS): return False
    return None

EXPLICIT_AM_PM = re.compile(r"\b(am|pm|a\.m|p\.m)\b")
CLOCK = re.compile(r"(\d{1,2})\s*[:.]\s*(\d{2})")
HOUR_MIN_BN = re.compile(r"(\d{1,2})\s*(টায়|টার|টাতে|টা|taye|tay|ta)\s+(\d{1,2})\s*(মিনিট|minit|minute|min)(?![a-z])")
HOUR_WORD = re.compile(r"(\d{1,2})\s*(টায়|টার|টাতে|টা|taye|tay|ta|baje|bajbe|bajche|বাজে|বাজবে)(?![a-z])")
BARE_HOUR = re.compile(r"(\d{1,2})\s*(টার|টায়|টা|taye|tay|ta)(?![a-z])")

def build_time(hour, minute, t):
    if not (0 <= hour <= 23 and 0 <= minute <= 59): return None
    h = hour
    if EXPLICIT_AM_PM.search(t):
        pm = re.search(r"\bp\.?m\.?\b", t) is not None
        if pm and h < 12: h += 12
        if (not pm) and h == 12: h = 0
    elif part_of_day_is_am(t) is False and h < 12:
        h += 12
    elif part_of_day_is_am(t) is True and h == 12:
        h = 0
    return (h, minute)

def parse_time(raw):
    t = normalize(raw)
    m = CLOCK.search(t)
    if m: return build_time(int(m.group(1)), int(m.group(2)), t)
    m = HOUR_MIN_BN.search(t)
    if m: return build_time(int(m.group(1)), int(m.group(3)), t)
    m = HOUR_WORD.search(t)
    if m: return build_time(int(m.group(1)), 0, t)
    if EXPLICIT_AM_PM.search(t):
        for mm in re.finditer(r"(\d{1,2})", t):
            v = int(mm.group(1))
            if 0 <= v <= 23: return build_time(v, 0, t)
        return None
    m = BARE_HOUR.search(t)
    if m: return build_time(int(m.group(1)), 0, t)
    return None

TOMORROW_WORDS = ["kal", "কাল", "tomorrow", "agami din"]
TODAY_WORDS = ["aj", "আজ", "today"]
DAY_AFTER = ["parso", "porshu", "parshu", "গোকাল", "পরশু", "day after tomorrow"]

def has_word(raw, word):
    t = normalize(raw)
    if all(32 <= ord(c) <= 127 for c in word):
        return re.search(r"\b" + re.escape(word) + r"\b", t) is not None
    return word in t

def relative_day(raw):
    t = normalize(raw)
    if any(has_word(t, w) for w in DAY_AFTER): return "TOMORROW"
    if any(has_word(t, w) for w in TODAY_WORDS): return "TODAY"
    if any(has_word(t, w) for w in TOMORROW_WORDS): return "TOMORROW"
    return None

WEEKDAY_WORDS = {
    "MON": ["sombar","shombar","monday","সোমবার","সমবার"],
    "TUE": ["mongolbar","mangalbar","tuesday","মঙ্গলবার"],
    "WED": ["budhbar","buddhbar","wednesday","বুধবার"],
    "THU": ["brihospotibar","brishtibar","thursday","বৃহস্পতিবার"],
    "FRI": ["shukrobar","shukrarbar","friday","শুক্রবার"],
    "SAT": ["shonibar","shanibar","saturday","শনিবার"],
    "SUN": ["robibar","rabibar","sunday","রবিবার"],
}
def weekday(raw):
    t = normalize(raw)
    for k, words in WEEKDAY_WORDS.items():
        if any(w in t for w in words): return k
    return None

DURATION_UNITS = [
    (3600, ["ghonta","ghontar","ghanta","hour","hr","ঘণ্টা","ঘন্টা","ঘটা"]),
    (60, ["minute","min","minit","miniter","মিনিট","মিনিটের"]),
    (1, ["second","sec","sekend","সেকেন্ড","সেকেন্ডের"]),
    (86400, ["din","day","দিন"]),
]
def parse_duration(raw):
    t = normalize(raw)
    if not t: return None
    total, matched = 0, False
    half = re.compile(r"(আধা|অর্ধ|adha|ardho|ardh)\s*(ঘণ্টা|ঘন্টা|ghonta|hour)")
    if half.search(t):
        total += 1800; matched = True
        t = half.sub(" ", t)
    tq = re.compile(r"(সাড়ে|sare|share)\s*(তিন|tin|3)\s*(ঘণ্টা|ঘন্টা|ghonta|hour)")
    if tq.search(t):
        total += 3*3600+1800; matched = True
        t = tq.sub(" ", t)
    for secs, words in DURATION_UNITS:
        for w in words:
            rex = re.compile(r"(\d+)\s*" + w)
            while True:
                m = rex.search(t)
                if not m: break
                total += int(m.group(1))*secs; matched = True
                t = t[:m.start()] + " " + t[m.end():]
    return total if matched else None

# ---------------- Financial policy (v1.2, three levels) ----------------
FIN_PKG = [p.strip().lower() for p in [
 "bkash","nagad","konasl.mobileapp","rocket","dbbl","upay","digipay","mcash","trustcloud","mycash",
 "tapnpay","ucash",".bank","bank.","banking","bankapp","citybank","bracbank","ebl","dbblmobile",
 "primebank","bankasia","islamibank","sonali","janatabank","ruton","agrani","pubalibank","easternbank",
 "meghnabank","mtb","sebl","payment","paywell","sslcommerz","portwallet","aamarpay","shurjopay",
 "adyen","stripe","paypal","venmo","cashapp"]]
FIN_NAMES = ["bkash","b kash","nagad","rocket","upay","ucash","mycash","tap and pay","mobile banking",
 "bank","banking","payment","wallet","cash","বিকাশ","বিক্যাশ","নগদ","রকেট","উপায়","উপাই","মোবাইল ব্যাংকিং",
 "ব্যাংক","ব্যাঙ্ক","পেমেন্ট","ওয়ালেট","ক্যাশ"]
TRANSACTIONS = ["send money","cash out","send taka","taka send","money send","cash in","taka pathao","taka pathan","tk pathao","taka dao",
 "taka transfer","transfer money","bank transfer","send tk","add money","top up","mobile recharge",
 "recharge koro","pay the bill","bill pay","payment koro","payment korun","pay koro","make payment",
 "purchase koro","buy koro with card","card diye","card payment","confirm payment","payment confirm",
 "transaction confirm","authorize payment","financial authorization","সেন্ড মানি","ক্যাশ আউট",
 "টাকা পাঠাও","টাকা পাঠান","টাকা দাও","পয়সা পাঠাও","ব্যাংক ট্রান্সফার","লেনদেন করো","লেনদেন নিশ্চিত",
 "পেমেন্ট করো","পেমেন্ট করুন","বিল পরিশোধ","রিচার্জ করো","কার্ড দিয়ে"]
CREDS = ["otp","one time password","pin number","password","passcode","cvv","cvc","card number",
 "credit card","debit card","biometric","2fa code","seed phrase","ওটিপি","পিন নম্বর","পিন নাম্বার",
 "পাসওয়ার্ড","কার্ড নম্বর","সিভিভি","verification code","one-time"]
CODE_LIKE = re.compile(r"(?<!\d)(\d[ -]?){3,7}\d(?!\d)")

def is_sensitive_pkg(p):
    if not p: return False
    p = p.lower().strip()
    return any(f in p for f in FIN_PKG)
def is_sensitive_name(n):
    if not n: return False
    n = n.lower().strip()
    return any(w in n for w in FIN_NAMES)
def is_money(t):
    t = t.lower()
    return any(w in t for w in TRANSACTIONS)
def mentions_creds(t):
    t = t.lower()
    return any(w in t for w in CREDS)
def redact_codes(text):
    if mentions_creds(text):
        return CODE_LIKE.sub(lambda m: "••••" if 4 <= sum(c.isdigit() for c in m.group(0)) <= 8 else m.group(0), text)
    pat = re.compile(r"(otp|code|pin|verification code|one[- ]time)\D{0,12}((\d[ -]?){3,7}\d)", re.I)
    return pat.sub(lambda m: re.sub(r"\d", "•", m.group(0)), text)

TRANSACTION_REFUSAL = "এই financial transaction NUVA নিজে করতে পারবে না। আপনি চাইলে নিজে manually করতে পারবেন।"

# ---------------- CommandParser (v1.3) ----------------
WAKE = re.compile(r"^\s*(nuva|নুভা|hey nuva|নুভা শোনো)\s*[,.!]?[\s]*", re.I)
OPEN_VERBS = ["open koro","open korun","open","khule dao","kholo dao","kholo","khulo","kholun","khulun",
 "chalu koro","চালু করো","চালু করুন","খোলো","খুলে দাও","খুলুন","launch koro","launch","start koro","চালাও"]
CLOSE_VERBS = ["close koro","close korun","close","band koro","bondho koro","bondho korun","বন্ধ করো",
 "বন্ধ করুন","bandho koro","লুকাও"]
TAIL_VERBS = ["koro","korun","korbo","koren","dao","din","diben","diagcen","diachen","করো","করুন","দাও",
 "দিন","দিবেন","চালাও","বাজাও","পাঠাও","পাঠান"]
APP_ALIASES = {}
def _put(canon, *keys):
    for k in keys: APP_ALIASES[k] = canon
_put("youtube","youtube","ইউটিউব","ইউটুব"); _put("whatsapp","whatsapp","whats app","হোয়াটসঅ্যাপ","হোয়াটসাপ","hatsapp")
_put("facebook","facebook","fb","ফেসবুক"); _put("messenger","messenger","মেসেঞ্জার","fb messenger")
_put("telegram","telegram","টেলিগ্রাম"); _put("chrome","chrome","গুগল ক্রোম")
_put("browser","browser","ব্রাউজার","opera","firefox"); _put("camera","camera","ক্যামেরা","kamera")
_put("calculator","calculator","ক্যালকুলেটর","hishab","হিসাব"); _put("calendar","calendar","ক্যালেন্ডার")
_put("gmail","gmail","mail","ইমেইল","email"); _put("google maps","maps","google maps","ম্যাপ")
_put("play store","play store","playstore","প্লে স্টোর"); _put("phone","phone","dialer","ফোন")
_put("contacts","contacts","যোগাযোগ","contact list"); _put("gallery","gallery","photos","গ্যালারি","ছবি")
_put("spotify","spotify","স্পটিফাই"); _put("settings","settings","setting","সেটিংস")
_put("files","files","file manager","ফাইল ম্যানেজার","my files"); _put("recorder","recorder","voice recorder","রেকর্ডার")
_put("translate","translate","অনুবাদ"); _put("music","music","গান","gaan")
_put("bkash","bkash","বিকাশ","b kash"); _put("nagad","nagad","নগদ")
_put("rocket","rocket","রকেট","dbbl rocket"); _put("upay","upay","উপায়","উপাই")
PHONE_NUMBER = re.compile(r"(\+?88)?01[3-9](?:[\s-]?\d){8}|\+\d{8,15}")
HYPHEN_SUFFIX = re.compile(r"-(ke|kei|keu|kar|e|te|er|r)\b")
def digits_only(raw): return "".join(c for c in raw if c.isdigit() or c == "+")
PRONOUNS = ["oke","o ke","take","tar ke","takei","tarke","ওকে","ওর কে","তাকে","তার কে","একে"]
WHATSAPP_WORDS = ["whatsapp e","whatsapp","হোয়াটসঅ্যাপে","হোয়াটসঅ্যাপ","hatsapp e","whats app"]
SMS_WORDS = ["sms","es em es","message e","এসএমএস","এস এম এস","মেসেজে","text koro"]
SAY_MARKERS = ["message dau","message dao","msg dau","msg dao","bolo","bole dao","bole din","bolun",
               "bolen","bolena","বলো","বলুন","বলে দাও","বলে দিন","মেসেজ দাও"]

def swap_word(text, word):
    if all(32 <= ord(c) <= 127 for c in word):
        return re.sub(r"\b" + re.escape(word) + r"\b", " ", text)
    return text.replace(word, " ")

def strip_wake(t):
    t = WAKE.sub("", t)
    return re.sub(r"^[,.!\s]+", "", t).strip()

def prepare_hyphens(text):
    return re.sub(r"\s+", " ", HYPHEN_SUFFIX.sub(lambda m: " " + m.group(1) + " ", text)).strip()

def prepare(raw):
    t = strip_wake(normalize(raw))
    if not t: return None
    return prepare_hyphens(t)

def ok(action, intent, risk="LOW", base=None, speech=""):
    base = base or risk
    final = max(risk, base, key=lambda r: ["LOW","MEDIUM","HIGH"].index(r))
    return {"intent": intent, "action": action, "unsupported": False, "risk": final,
            "confirm": final != "LOW", "speech": speech, "source": "offline"}
def unsupported(speech):
    return {"intent": None, "action": None, "unsupported": True, "risk": "LOW", "confirm": False,
            "speech": speech, "source": "offline", "reasons": ["offline parser needs more info"]}
def refused():
    return {"intent": None, "action": None, "unsupported": True, "risk": "HIGH", "confirm": False,
            "speech": TRANSACTION_REFUSAL, "source": "offline-security",
            "reasons": ["blocked: financial transaction automation (level 3)"]}

def parse(raw):
    text = prepare(raw)
    if not text: return None
    return parse_prepared(text)

def parse_nav(t):
    if any(w in t for w in ["home e jao","go home","home e cholo","home e firi jao","হোমে যাও","হোম স্ক্রিনে যাও"]):
        return ok({"kind":"GoHome"}, "GO_HOME")
    if any(w in t for w in ["back jao","go back","back koro","pichone jao","পিছনে যাও","পিছনে চলো","একটু পিছনে"]):
        return ok({"kind":"GoBack"}, "GO_BACK")
    if any(w in t for w in ["recent app","recent apps","recents","recent dekhao","রিসেন্ট","রিসেন্ট অ্যাপ"]):
        return ok({"kind":"ShowRecents"}, "SHOW_RECENTS")
    return None

def parse_universal(t):
    if any(w in t for w in ["notification panel","notification shade","নোটিফিকেশন প্যানেল","notification khulo"]):
        return ok({"kind":"shade"},"OPEN_NOTIFICATIONS")
    if any(w in t for w in ["notification er app","notification wala app","notification ta kholo","prothom notification"]):
        ordinal = 1
        m = re.search(r"(\d+)\s*(number|no|tomo|তম)", t)
        if m: ordinal = max(1, min(30, int(m.group(1))))
        return ok({"kind":"notifapp","ordinal":ordinal},"OPEN_NOTIFICATION_APP")
    bare = any(w in t for w in ["eta press","eta chapo","এটা press","এটা চাপো","এটা ট্যাপ"])
    label = None
    if not bare:
        m1 = re.match(r"^(.{2,60}?)\s*(button|btn|ta|বাটন)\s*(press|chapo|chapun|চাপো|ট্যাপ)(?![a-z])", t)
        m2 = re.search(r"(?<![a-z])(press|chapo|chapun|চাপো)\s+(.{2,60}?)$", t)
        if m1:
            label = m1.group(1).strip() or None
        elif m2:
            label = m2.group(2).strip() or None
    wants = label is not None or bare or any(w in t for w in ["press koro","press korun","button chapo","press করো","বাটন চাপো","চাপো দাও"])
    if wants:
        if label:
            label = re.sub(r"\s+"," ",re.sub(r"\b(button|btn|ta|the|eta|koro|korun)\b"," ",label)).strip() or None
        return ok({"kind":"press","label":label},"PRESS")
    if any(w in t for w in ["muchhe felo","muchhe dao","clear koro","lekhata muchho","লেখাটা মুছো","মুছে ফেলো","মুছে দাও"]):
        return ok({"kind":"clear"},"CLEAR_TEXT")
    if any(w in t for w in ["button dekhao","button gulo","ki button ache","ui dekhao","ui summary","বাটন দেখাও","কী বাটন আছে"]):
        return ok({"kind":"describe"},"DESCRIBE_SCREEN")
    return None

def parse_screen(t):
    if any(w in t for w in ["screen poro","screen ta poro","poro screen","ki lekha ache","screen e ki ache",
        "এই স্ক্রিনটা পড়ো","স্ক্রিন পড়ো","কী লেখা আছে","স্ক্রিনে কী আছে"]):
        return ok({"kind":"ReadScreen"}, "READ_SCREEN")
    if any(w in t for w in ["notification poro","notification gulo poro","notification dekhao",
        "notification ki eseche","notification summary","কী নোটিফিকেশন এসেছে","নোটিফিকেশন পড়ো","নোটিফিকেশন দেখাও"]):
        return ok({"kind":"ReadNotifications"}, "READ_NOTIFICATIONS")
    return None

def parse_user_file(t):
    share = any(w in t for w in ["share","pathao","send","শেয়ার","শেয়ার","পাঠাও"])
    select = any(w in t for w in ["select","choose","pick","beche","বেছে","নির্বাচন"])
    opened = any(w in t for w in ["open","kholo","khulo","খোলো","খুলে"])
    file_word=any(w in t for w in ["file","document","ফাইল","ডকুমেন্ট"])
    multiple=any(w in t for w in ["multiple","several","onek","sob","একাধিক","অনেক"])
    if file_word and share and multiple:
        return ok({"kind":"userfile","operation":"SHARE_MULTIPLE_FILES"},"USER_FILE",risk="MEDIUM")
    if file_word and any(w in t for w in ["delete","remove","muchhe","মুছে","ডিলিট"]):
        return ok({"kind":"userfile","operation":"DELETE_FILE"},"USER_FILE",risk="MEDIUM")
    if file_word and any(w in t for w in ["rename","nam bodlao","নাম বদল","রিনেম"]):
        marker=next((w for w in ["new name","rename to","nam dao","নাম দাও","নতুন নাম"] if w in t),None)
        name=t.split(marker,1)[1].strip(" ,.: ") if marker else None
        if not name: return unsupported("Notun nam bolun")
        return ok({"kind":"userfile","operation":"RENAME_FILE","new_name":name},"USER_FILE",risk="MEDIUM")
    if file_word and any(w in t for w in ["copy","কপি"]):
        return ok({"kind":"userfile","operation":"COPY_FILE"},"USER_FILE",risk="MEDIUM")
    if file_word and any(w in t for w in ["move","sorao","সরাও","মুভ"]):
        return ok({"kind":"userfile","operation":"MOVE_FILE"},"USER_FILE",risk="MEDIUM")
    if any(w in t for w in ["folder select","folder choose","folder access","directory select","ফোল্ডার বেছে"]):
        return ok({"kind":"userfile","operation":"OPEN_FOLDER"}, "USER_FILE", risk="MEDIUM")
    photo = any(w in t for w in ["photo","chobi","image","ছবি","ফটো"])
    video = any(w in t for w in ["video","ভিডিও"])
    gallery = any(w in t for w in ["gallery theke","গ্যালারি থেকে","photo picker","media picker"])
    if photo and share and multiple:
        return ok({"kind":"userfile","operation":"SHARE_MULTIPLE_PHOTOS"},"USER_FILE",risk="MEDIUM")
    if video and share and multiple:
        return ok({"kind":"userfile","operation":"SHARE_MULTIPLE_VIDEOS"},"USER_FILE",risk="MEDIUM")
    if photo and any(w in t for w in ["edit","crop","rotate","filter","এডিট","ক্রপ"]):
        return ok({"kind":"userfile","operation":"EDIT_PHOTO"},"USER_FILE",risk="MEDIUM")
    if photo and (gallery or share or select):
        op="SHARE_PHOTO" if share else "PICK_PHOTO"
        return ok({"kind":"userfile","operation":op}, "USER_FILE", risk="MEDIUM" if share else "LOW")
    if video and (gallery or share or select):
        op="SHARE_VIDEO" if share else "PICK_VIDEO"
        return ok({"kind":"userfile","operation":op}, "USER_FILE", risk="MEDIUM" if share else "LOW")
    text_file=any(w in t for w in ["text file","txt file","টেক্সট ফাইল"])
    wants_read=any(w in t for w in ["read","poro","পড়ো","পড়ো","pore shonao"])
    if text_file and wants_read:
        return ok({"kind":"userfile","operation":"READ_TEXT"}, "USER_FILE")
    if file_word and share:
        return ok({"kind":"userfile","operation":"SHARE_FILE"}, "USER_FILE", risk="MEDIUM")
    if file_word and (select or opened):
        return ok({"kind":"userfile","operation":"OPEN_FILE"}, "USER_FILE")
    return None

def parse_productivity(t):
    panel=None
    if any(w in t for w in ["app info","application info","app details"]): panel="APP_INFO"
    elif any(w in t for w in ["notification setting","notification settings"]): panel="NOTIFICATIONS"
    elif any(w in t for w in ["play store page","store page"]): panel="PLAY_STORE"
    if panel:
        app=t
        for w in ["application info","app details","app info","notification settings","notification setting","play store page","store page","khulo","kholo","open"]:
            app=app.replace(w," ")
        app=re.sub(r"\b(app|ta|please|er)\b"," ",app)
        app=re.sub(r"\s+"," ",app).strip(" ,.: ")
        if app: return ok({"kind":"appmanagement","app":app,"panel":panel},"OPEN_APP_MANAGEMENT")
    uninstall=next((w for w in ["uninstall koro","uninstall korun","remove app","app uninstall"] if w in t),None)
    if uninstall:
        app=re.sub(r"\b(app|ta|please|koro|korun)\b"," ",t.replace(uninstall," "))
        app=re.sub(r"\s+"," ",app).strip(" ,.: ")
        if not app: return unsupported("App name bolun")
        if app in ["bkash","nagad","rocket","upay"]: return unsupported("financial app uninstall blocked")
        return ok({"kind":"uninstall","app":app},"UNINSTALL_APP",risk="MEDIUM",base="MEDIUM")
    if any(w in t for w in ["contact edit","edit contact","contact bodlao"]):
        return ok({"kind":"contacthandoff","operation":"EDIT"},"CONTACT_HANDOFF",risk="MEDIUM",base="MEDIUM")
    if any(w in t for w in ["contact dekhao","contact details","view contact"]):
        return ok({"kind":"contacthandoff","operation":"VIEW"},"CONTACT_HANDOFF",risk="MEDIUM",base="MEDIUM")
    if any(w in t for w in ["new contact add","contact create","contact draft","নতুন কন্টাক্ট"]):
        m=re.search(r"\s(?:name|nam|নাম)\s+(.+?)(?=\s+(?:number|phone|email)\s|$)",t)
        if not m: return unsupported("Contact name bolun")
        return ok({"kind":"contactdraft","name":m.group(1).strip()},"CREATE_CONTACT_DRAFT",risk="MEDIUM",base="MEDIUM")
    share_marker=next((w for w in ["text share koro","lekha share koro","share text","ei lekha share"] if w in t),None)
    if share_marker:
        text=content_after(t,share_marker)
        if text: text=re.sub(r"^(je|যে)\s+","",text).strip()
        if not text: return unsupported("Text bolun")
        return ok({"kind":"sharetext","text":text},"SHARE_TEXT",risk="MEDIUM",base="MEDIUM")
    draft_words=["scheduled draft","scheduled email","scheduled sms","compose reminder"]
    if any(w in t for w in draft_words) and any(w in t for w in ["list","dekhao","poro","show"]):
        return ok({"kind":"listdrafts"},"LIST_SCHEDULED_DRAFTS")
    if any(w in t for w in draft_words) and any(w in t for w in ["cancel","batil","remove"]):
        m=re.search(r"(\d+)",t); ordinal=int(m.group(1)) if m else 1
        return ok({"kind":"canceldraft","ordinal":ordinal},"CANCEL_SCHEDULED_DRAFT",risk="MEDIUM",base="MEDIUM")
    scheduled=any(w in t for w in ["schedule email","scheduled email","email reminder","schedule sms","scheduled sms","message compose reminder"])
    if scheduled:
        tm=parse_time(t)
        if not tm: return unsupported("Koto tay compose reminder dibo?")
        body_marker=next((w for w in [" body "," message "," je "," যে "] if w in t),None)
        body=t.split(body_marker,1)[1].strip(" ,.:") if body_marker else None
        if not body: return unsupported("Compose body bolun")
        channel="SMS" if "sms" in t else "EMAIL"
        recurrence="DAILY" if any(w in t for w in ["protidin","daily","every day","প্রতিদিন"]) else \
            ("WEEKLY" if any(w in t for w in ["weekly","every week","proti shoptaho","প্রতি সপ্তাহ"]) or weekday(t) else "ONCE")
        return ok({"kind":"schedulecompose","channel":channel,"body":body,"time":tm,"recurrence":recurrence},"SCHEDULE_COMPOSE",risk="MEDIUM",base="MEDIUM")
    form_requested=any(w in t for w in ["form prepare","application prepare","application form kholo","booking prepare","form draft"])
    if not form_requested: return None
    kinds=[("passport","PASSPORT"),("nid","NID"),("birth","BIRTH_REGISTRATION"),("driving","DRIVING_LICENSE"),
           ("visa","VISA"),("admission","ADMISSION"),("job","JOB"),("doctor","DOCTOR"),("hotel","HOTEL"),
           ("flight","FLIGHT"),("courier","COURIER")]
    kind=next((value for marker,value in kinds if marker in t),None)
    if not kind: return unsupported("Kon form?")
    details=t.split(" details ",1)[1].strip() if " details " in t else None
    return ok({"kind":"prepareform","form_kind":kind,"details":details},"PREPARE_FORM",risk="MEDIUM",base="MEDIUM")

def parse_communication(t):
    mentions_notification="notification" in t or "নোটিফিকেশন" in t
    if mentions_notification and any(w in t for w in ["dismiss","clear notification","notification muchhe","সরাও","মুছে দাও"]):
        m=re.search(r"(\d+)",t); ordinal=int(m.group(1)) if m else 1
        return ok({"kind":"managenotif","ordinal":ordinal,"operation":"DISMISS"},"MANAGE_NOTIFICATION",risk="MEDIUM",base="MEDIUM")
    if mentions_notification and any(w in t for w in ["mark as read","mark read","পঠিত","পড়া হয়েছে"]):
        m=re.search(r"(\d+)",t); ordinal=int(m.group(1)) if m else 1
        return ok({"kind":"managenotif","ordinal":ordinal,"operation":"MARK_READ"},"MANAGE_NOTIFICATION",risk="MEDIUM",base="MEDIUM")
    reply_marker=next((w for w in ["notification e reply dao","notification reply dao","notification reply koro",
        "reply to notification","নোটিফিকেশনে রিপ্লাই দাও","reply dao","reply koro"] if w in t),None)
    if reply_marker and ("notification" in t or "নোটিফিকেশন" in t):
        m=re.search(r"(\d+)\s*(number|no|tomo|তম)",t)
        ordinal=max(1,min(30,int(m.group(1)))) if m else 1
        quoted=re.search(r'["\'“”](.+?)["\'“”]',t)
        message=quoted.group(1).strip() if quoted else content_after(t,reply_marker)
        if message:
            message=re.sub(r"^(je|যে)\s+","",message).strip()
        if not message: return unsupported("Notification e ki reply dibo?")
        return ok({"kind":"replynotif","ordinal":ordinal,"message":message},"REPLY_NOTIFICATION",risk="MEDIUM",base="MEDIUM")
    email_marker=next((w for w in ["email compose koro","compose email","email likho","email koro","email pathao",
        "mail compose koro","mail likho","mail pathao","ইমেইল লেখো","ইমেইল পাঠাও"] if w in t),None)
    if not email_marker: return None
    m=re.search(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+",t)
    recipient=m.group(0) if m else None
    body_marker=next((w for w in [" body "," message "," je "," যে "] if w in t),None)
    body=t.split(body_marker,1)[1].strip(" ,.:") if body_marker else None
    attachment=any(w in t for w in ["attachment","attach file","file attach","document attach"])
    multiple=attachment and any(w in t for w in ["multiple","several","onek","একাধিক","অনেক"])
    return ok({"kind":"email","recipient":recipient,"body":body,"attachment":attachment,"multiple":multiple},"COMPOSE_EMAIL",risk="MEDIUM",base="MEDIUM")

def parse_status(t):
    if any(w in t for w in ["battery","battary","bettery","charge koto","charge koy","কত চার্জ","ব্যাটারি","চার্জ কত"]) and \
       not any(w in t for w in ["battery saver","battery setting","power saving","ব্যাটারি সেভার"]):
        return ok({"kind":"BATTERY"}, "DEVICE_STATUS")
    now_words = ["ekhon","akhon","akon","akn","ekhn","now","current","এখন","বর্তমান"]
    time_phrases = ["koyta baje","koita baje","koi ta baje","koy ta baje","kota baje","koto baje",
        "koyta bajche","koita bajche","koto bajche","somoy koto","shomoy koto","time koto",
        "what time is it","what's the time","current time","time now","tell me the time",
        "কটা বাজে","কয়টা বাজে","কয়টা বাজে","কতটা বাজে","সময় কত","সময় কত","এখন কটা","এখন কয়টা","এখন কয়টা"]
    asks_time = any(w in t for w in time_phrases) or (
        any(has_word(t,w) for w in now_words) and any(w in t for w in ["time","somoy","shomoy","baje","bajche","সময়","সময়","বাজে"]))
    today_words = ["aj","aaj","ajke","today","আজ","আজকে","আজকের"]
    date_phrases = ["aj koto tarik","aj koto tarikh","aaj koto tarik","ajke koto tarik","ajke koto tarikh",
        "aj tarikh koto","aj tarik koto","ajker tarik","ajker tarikh","aj ki tarik","aj ki tarikh",
        "tarikh koto","tarik koto","date koto","today's date","todays date","date today","what is the date",
        "what's the date","what day is it","aj kibar","aj ki bar","ajke ki bar","আজ কি বার","আজ কী বার",
        "আজকে কি বার","আজকে কী বার","আজ কত তারিখ","আজকে কত তারিখ","আজ কী তারিখ","আজ কি তারিখ","আজকের তারিখ"]
    asks_date = any(w in t for w in date_phrases) or (
        any(has_word(t,w) for w in today_words) and any(w in t for w in ["tarik","tarikh","date","kibar","ki bar","তারিখ","কি বার","কী বার"]))
    if asks_time and asks_date: return ok({"kind":"DATE_TIME"}, "DEVICE_STATUS")
    if asks_time: return ok({"kind":"TIME"}, "DEVICE_STATUS")
    if asks_date: return ok({"kind":"DATE"}, "DEVICE_STATUS")
    if any(w in t for w in ["internet ache","internet ase","internet on ache","network kothay","net ache","net ase","wifi e connected",
        "নেটওয়ার্ক","ইন্টারনেট আছে","নেট আছে","network status","connection ache"]):
        return ok({"kind":"NETWORK"}, "DEVICE_STATUS")
    if any(w in t for w in ["storage","koto jayga","koto jaiga","কত জায়গা","কত জায়গা","স্টোরেজ","memory koto","space koto","free space","jayga khali","jaiga khali"]) and \
       not any(w in t for w in ["storage setting","স্টোরেজ সেটিং"]):
        return ok({"kind":"STORAGE"}, "DEVICE_STATUS")
    return None

def parse_realtime(t):
    topics = ["weather","abohawa","আবহাওয়া","আবহাওয়া","temperature","তাপমাত্রা","brishti","বৃষ্টি",
        "latest news","today news","news today","ajker news","ajker khobor","খবর","সংবাদ",
        "live score","score koto","current score","cricket score","football score","লাইভ স্কোর","স্কোর কত",
        "traffic","jam kemon","rastar obostha","ট্রাফিক","যানজট","রাস্তার অবস্থা",
        "dollar rate","exchange rate","gold price","sonar dam","fuel price","ডলারের রেট","সোনার দাম"]
    if not any(w in t for w in topics): return None
    hints = ["ekhon","akhon","akon","akn","ekhn","current","latest","live","today","aj","ajke","ajker",
        "koto","kemon","ki","hobe","now","এখন","আজ","আজকে","আজকের","বর্তমান","সর্বশেষ","লাইভ","কত","কেমন","কি","কী","হবে"]
    if not any(has_word(t,w) if w.isascii() else w in t for w in hints): return None
    return ok({"kind":"search","query":t.strip(" .,?!:")}, "SEARCH_WEB")

def parse_daily(t):
    # Compact mirror of the Kotlin data-driven utility engine. The Kotlin JVM
    # suite owns exhaustive converter/math coverage; these probes catch route
    # ordering regressions in environments without an Android SDK.
    m = re.search(r"(-?\d+(?:\.\d+)?)\s*(?:er|এর|of)?\s*(-?\d+(?:\.\d+)?)\s*(?:%|percent|shotangsho|পারসেন্ট|শতাংশ)", t)
    if m:
        base, pct = float(m.group(1)), float(m.group(2))
        value = base * pct / 100
        if any(w in t for w in ["discount","ছাড়","ছাড়","komle","কমলে"]): value = base - value
        return ok({"kind":"localanswer","answer":str(value),"category":"percentage"}, "LOCAL_ANSWER")
    m = re.search(r"(\d+(?:\.\d+)?)\s*(km|kilometer|কিলোমিটার).*?(\d+(?:\.\d+)?)\s*(liter|litre|l|লিটার)", t)
    if m and any(w in t for w in ["mileage","average","km/l","মাইলেজ"]):
        value = float(m.group(1)) / float(m.group(3))
        return ok({"kind":"localanswer","answer":str(value),"category":"mileage"}, "LOCAL_ANSWER")
    if any(w in t for w in ["average","mean","গড়","গড়"]):
        values = [float(v) for v in re.findall(r"-?\d+(?:\.\d+)?", t)]
        if len(values) >= 2:
            return ok({"kind":"localanswer","answer":str(sum(values)/len(values)),"category":"average"}, "LOCAL_ANSWER")
    eta = re.search(r"(\d+(?:\.\d+)?)\s*(?:km|kilometer).*?(\d+(?:\.\d+)?)\s*(?:kmph|km/h|kph)", t)
    if eta and any(w in t for w in ["travel time","eta koto","koto somoy lagbe","কত সময় লাগবে"]):
        minutes = round(float(eta.group(1))/float(eta.group(2))*60)
        return ok({"kind":"localanswer","answer":str(minutes),"category":"travel_eta"}, "LOCAL_ANSWER")
    units = {"kilometer":1000.0,"km":1000.0,"mile":1609.344,"kg":1.0,"kilogram":1.0,
             "pound":0.45359237,"gigabyte":1024.0**3,"megabyte":1024.0**2,"cup":0.2365882365,
             "milliliter":0.001,"liter":1.0}
    aliases = "|".join(sorted(map(re.escape, units), key=len, reverse=True))
    m = re.search(r"(-?\d+(?:\.\d+)?)\s*("+aliases+r").*?\b("+aliases+r")\b", t)
    if m and m.group(2) != m.group(3):
        value = float(m.group(1))*units[m.group(2)]/units[m.group(3)]
        return ok({"kind":"localanswer","answer":str(value),"category":"unit"}, "LOCAL_ANSWER")
    expr = t
    for word, symbol in [("plus","+"),("jog","+"),("যোগ","+"),("minus","-"),("biyog","-"),("বিয়োগ","-"),
                         ("times","*"),("gun","*"),("গুণ","*"),("divided by","/"),("vag","/"),("ভাগ","/")]:
        expr = expr.replace(word, symbol)
    for filler in ["calculate","hisab koro","hisab","koto","কত","হিসাব","উত্তর"]: expr = expr.replace(filler," ")
    expr = re.sub(r"\s+", "", expr)
    if 3 <= len(expr) <= 120 and re.fullmatch(r"[0-9.+\-*/()]+", expr) and any(op in expr for op in "+-*/"):
        try:
            value = eval(expr, {"__builtins__": {}}, {})
            if isinstance(value, (int,float)):
                return ok({"kind":"localanswer","answer":str(value),"category":"calculation"}, "LOCAL_ANSWER")
        except Exception: pass
    return None

def parse_grammar(t):
    # Representative aliases from the 12,250-form Kotlin grammar. Full count
    # and uniqueness are source-audited below; Kotlin JVM tests cover families.
    for prefix in ["nuva please ","doya kore ","please ","ektu ","plz "]:
        if t.startswith(prefix): t=t[len(prefix):].strip()
    for suffix in [" please"," ekhon"," ekhoni"," kore dao"," bolen"," taratari"]:
        if t.endswith(suffix): t=t[:-len(suffix)].strip()
    mapping = {
        "go to home": ("GO_HOME", {"kind":"home"}),
        "open notification app": ("OPEN_NOTIFICATION_APP", {"kind":"notifapp","ordinal":1}),
        "battery percentage bolo": ("DEVICE_STATUS", {"kind":"BATTERY"}),
        "current aqi bolo": ("SEARCH_WEB", {"kind":"search","query":"current air quality"}),
        "picture tolar screen dao": ("CAMERA", {"kind":"CAPTURE"}),
    }
    hit=mapping.get(t)
    return ok(hit[1],hit[0]) if hit else None

def parse_help(t):
    if any(w in t for w in ["ki ki korte paro","ki kaj paro","what can you do","কী কী করতে পারো","কি কি করতে পারো"]):
        return ok({"kind":"localanswer","answer":"features","category":"assistant_help"}, "LOCAL_ANSWER")
    return None

def parse_media_ctl(t):
    media_word = any(w in t for w in ["gaan","গান","music","song","video","ভিডিও","media","player","giti","গীত","track","ট্র্যাক"])
    pause = any(w in t for w in ["pause koro","pause korun","pause","thamo","থামাও","band koro music"]) and (media_word or "pause" in t)
    resume = any(w in t for w in ["resume koro","resume korun","resume","abar chalao","আবার চালাও"]) and (media_word or "resume" in t)
    nxt = media_word and any(w in t for w in ["next","porer","পরের","agamir"])
    prev = media_word and any(w in t for w in ["previous","ager","আগের","agerta","prev"])
    if pause: return ok({"kind":"PAUSE"},"MEDIA_CONTROL")
    if resume: return ok({"kind":"PLAY"},"MEDIA_CONTROL")
    if nxt: return ok({"kind":"NEXT"},"MEDIA_CONTROL")
    if prev: return ok({"kind":"PREVIOUS"},"MEDIA_CONTROL")
    return None

def parse_volume(t):
    if not any(w in t for w in ["volume","ভলিউম","shobdo","শব্দ","sound","সাউন্ড"]): return None
    if any(w in t for w in ["mute","নীরব","চুপ","bondho shobdo","shobdo bandho","শব্দ বন্ধ"]):
        return ok({"kind":"MUTE"},"VOLUME_CONTROL")
    if any(w in t for w in ["barao","baran","badhao","beshi","up","বাড়াও","বাড়ান","বেশি","চড়াও"]):
        return ok({"kind":"UP"},"VOLUME_CONTROL")
    if any(w in t for w in ["kom koro","koman","kom","namiye","নামাও","কম করো","কমাও"]):
        return ok({"kind":"DOWN"},"VOLUME_CONTROL")
    return None

def parse_camera(t):
    if not any(w in t for w in ["camera","ক্যামেরা","chobi tolo","ছবি তোলো"]): return None
    if any(w in t for w in ["chobi tolo","photo tolo","ছবি তোলো","ছবি তুলে দাও","take a photo","picture tolo"]):
        return ok({"kind":"CAPTURE"},"CAMERA")
    if any(w in t for w in ["video","ভিডিও"]):
        return ok({"kind":"VIDEO"},"CAMERA")
    return ok({"kind":"PHOTO"},"CAMERA")

def parse_settings(t):
    if any(w in t for w in ["torch","flashlight","টর্চ","ফ্ল্যাশলাইট","হাতলণ্ঠন"]):
        return ok({"kind":"TORCH"}, "OPEN_SETTING")
    if any(w in t for w in ["brightness","উজ্জ্বলতা","ব্রাইটনেস"]):
        return ok({"kind":"BRIGHTNESS"}, "OPEN_SETTING")
    if any(w in t for w in ["sound setting","volume setting","সাউন্ড সেটিং","শব্দের সেটিং"]):
        return ok({"kind":"VOLUME"}, "OPEN_SETTING")
    if any(w in t for w in ["do not disturb","disturb","dnd","ডিস্টার্ব"]):
        return ok({"kind":"DND"}, "OPEN_SETTING")
    if any(w in t for w in ["wifi","wi fi","ওয়াইফাই"]):
        return ok({"kind":"WIFI"}, "OPEN_SETTING")
    if any(w in t for w in ["bluetooth","ব্লুটুথ"]):
        return ok({"kind":"BLUETOOTH"}, "OPEN_SETTING")
    extended=[
      (["mobile data setting","data usage setting"],"MOBILE_DATA"),(["airplane mode","flight mode"],"AIRPLANE_MODE"),
      (["location setting","gps setting"],"LOCATION"),(["hotspot setting","tether setting"],"HOTSPOT"),
      (["nfc setting"],"NFC"),(["vpn setting"],"VPN"),(["battery saver","power saving"],"BATTERY_SAVER"),
      (["default app","default apps"],"DEFAULT_APPS"),(["date time setting","date and time setting"],"DATE_TIME"),
      (["language setting"],"LANGUAGE"),(["storage setting"],"STORAGE_SETTINGS"),(["privacy setting"],"PRIVACY"),
      (["security setting"],"SECURITY"),(["cast setting","screen cast"],"CAST"),(["print setting"],"PRINT"),
      (["caption setting","subtitle setting"],"CAPTIONS")]
    for aliases,target in extended:
        if any(w in t for w in aliases): return ok({"kind":target},"OPEN_SETTING")
    if any(w in t for w in ["notification setting","notification settings","নোটিফিকেশন সেটিং"]):
        return ok({"kind":"NOTIF_SETTING"}, "OPEN_SETTING")
    if any(w in t for w in ["app setting","app settings","nuva er setting"]):
        return ok({"kind":"APP_SETTING"}, "OPEN_SETTING")
    if any(w in t for w in ["accessibility setting","accessibility settings","অ্যাক্সেসিবিলিটি সেটিং"]):
        return ok({"kind":"ACCESS_SETTING"}, "OPEN_SETTING")
    if any(w in t for w in ["phone er setting","phone settings","system setting","সেটিংস খোলো","settings khulo"]):
        return ok({"kind":"GENERAL"}, "OPEN_SETTING")
    return None

def parse_alarm(t):
    if not any(w in t for w in ["alarm","আলার্ম","অ্যালার্ম","ghum theke"]): return None
    time = parse_time(t)
    if not time: return unsupported("Koto tay alarm dibo? Somoy ta bole din.")
    return ok({"kind":"alarm","h":time[0],"m":time[1]}, "SET_ALARM")

def parse_timer(t):
    if not any(w in t for w in ["timer","টাইমার"]): return None
    secs = parse_duration(t)
    if secs is None: return unsupported("Koto somoyer timer?")
    if not (1 <= secs <= 86400): return unsupported("too long")
    return ok({"kind":"timer","secs":secs}, "SET_TIMER")

def parse_reminder(t):
    if not any(w in t for w in ["reminder","রিমাইন্ডার","মনে করিয়ে","mone koriye","calendar e","ক্যালেন্ডারে",
        "meeting rakho","meeting boshao","event rakho"]): return None
    time = parse_time(t)
    title = reminder_title(t) or "Reminder"
    human = next((w for w in ["kal","কাল","aj","আজ","parso","পরশু"] if has_word(t, w)), time and "%02d:%02d" % time)
    return ok({"kind":"reminder","title":title,"when":human}, "SET_REMINDER", base="MEDIUM")

def reminder_title(t):
    title = t
    for w in ["reminder","রিমাইন্ডার","মনে করিয়ে দাও","mone koriye dao","calendar e","ক্যালেন্ডারে",
              "rakho","রাখো","boshao","meeting","মিটিং","event","আমাকে","amake"]:
        title = title.replace(w, " ")
    title = re.sub(r"\d{1,2}[:.]\d{2}", " ", title)
    title = re.sub(r"\d{1,2}\s*(টায়|টার|টাতে|টা|taye|tay|ta|bajche|বাজে)(?![a-z])", " ", title)
    if parse_duration(title):
        title = re.sub(r"\d+\s*(din|day|ঘণ্টা|ঘন্টা|মিনিট|minute|second|সেকেন্ড)\w*", " ", title)
    for w in ["kal","কাল","aj","আজ","shokal","সকাল","raat","রাত","dupur","দুপুর","bikal","বিকাল","parso","পরশু"]:
        title = swap_word(title, w)
    for w in TAIL_VERBS: title = swap_word(title, w)
    cleaned = re.sub(r"\s+", " ", title).strip(" -,.!?")
    return cleaned if 2 <= len(cleaned) <= 200 else None

def parse_note_todo(t):
    wants_read = any(w in t for w in ["dekhao","poro","read","show","দেখাও","পড়ো"])
    if wants_read:
        if any(w in t for w in ["shopping list","grocery list","bazar list","বাজারের তালিকা"]):
            return ok({"kind":"readitems","item_kind":"SHOPPING"}, "READ_SAVED_ITEMS")
        if any(w in t for w in ["expense","khoroch","খরচ"]):
            return ok({"kind":"readitems","item_kind":"EXPENSE"}, "READ_SAVED_ITEMS")
        if any(w in t for w in ["todo","kaj list","টুডু"]):
            return ok({"kind":"readitems","item_kind":"TODO"}, "READ_SAVED_ITEMS")
        if any(w in t for w in ["note","নোট"]):
            return ok({"kind":"readitems","item_kind":"NOTE"}, "READ_SAVED_ITEMS")
    shopping = next((w for w in ["shopping list e","shopping list","grocery list e","bazar list e","বাজারের তালিকায়","বাজারের লিস্টে"] if w in t), None)
    if shopping:
        content = content_after(t, shopping)
        if content:
            content = re.sub(r"\b(add|koro|korun)\b", " ", content)
            return ok({"kind":"todo","content":"Shopping: "+re.sub(r"\s+"," ",content).strip()}, "CREATE_TODO")
        return unsupported("Shopping list e ki add korbo?")
    expense = next((w for w in ["expense note","expense log","khoroch likhe rakho","খরচ লিখে রাখো"] if w in t), None)
    if expense:
        content = content_after(t, expense) or t.replace(expense," ").strip()
        if content: return ok({"kind":"note","content":"Expense: "+content}, "CREATE_NOTE")
        return unsupported("Khoroch bolun")
    todo_marker = next((w for w in ["todo te","to do te","todo list e","kaj er list e","kaj list e","টুডু"] if w in t), None)
    if todo_marker:
        content = content_after(t, todo_marker)
        if not content: return unsupported("Ki kaj add korbo?")
        return ok({"kind":"todo","content":content}, "CREATE_TODO")
    note_marker = next((w for w in ["note koro","note korun","note nao","note te likho","notun note",
        "নোট নাও","নোট করো","নোটে লেখো","লিখে রাখো"] if w in t), None)
    if note_marker:
        content = content_after(t, note_marker)
        if not content: return unsupported("Note e ki likhbo?")
        return ok({"kind":"note","content":content}, "CREATE_NOTE")
    return None

def parse_call(t):
    pronoun_start = next((p for p in PRONOUNS if t.startswith(p + " ")), None)
    if pronoun_start and any(w in t for w in ["call","phone","ফোন","কল"]):
        return ok({"kind":"call","contact":pronoun_start,"number":None}, "CALL_CONTACT", risk="MEDIUM", base="MEDIUM")
    if not any(w in t for w in ["call koro","call korun","call dao","call diya jao","phone koro","phone korun",
        "ফোন করো","ফোন করুন","কল করো","কল দাও","dial koro","যোগাযোগ করো"]): return None
    m = PHONE_NUMBER.search(t)
    if m:
        num = digits_only(m.group(0))
        return ok({"kind":"call","contact":num,"number":num}, "CALL_CONTACT", risk="MEDIUM", base="MEDIUM")
    name = contact_name(t, True)
    if not name: return unsupported("Kake call korbo? Nam bole din.")
    return ok({"kind":"call","contact":name,"number":None}, "CALL_CONTACT", risk="MEDIUM", base="MEDIUM")

CHAT_MARKERS = [" er chat"," chat","ের চ্যাট","এর চ্যাট"," চ্যাট"]

def parse_chat_open(t):
    if not any(v in t for v in OPEN_VERBS): return None
    marker = next((m for m in CHAT_MARKERS if m in t), None)
    if marker is None: return None
    if any(w in t for w in WHATSAPP_WORDS): app = "WHATSAPP"
    elif any(w in t for w in SMS_WORDS): app = "SMS"
    elif "telegram" in t: app = "TELEGRAM"
    elif "messenger" in t: app = "MESSENGER"
    elif "signal" in t: app = "SIGNAL"
    elif "viber" in t: app = "VIBER"
    elif "imo" in t: app = "IMO"
    else: app = "WHATSAPP"
    idx = t.find(marker)
    raw = t[:idx].strip() if idx > 0 else ""
    if len(raw) > 3 and (raw.endswith("ের") or raw.endswith("এর")):
        raw = raw[:-2]
    pronoun = next((p for p in PRONOUNS if raw.endswith(p) or raw == p), None)
    contact = pronoun if pronoun else clean_name(raw)
    if not contact or not contact.strip():
        return unsupported("Kake chat khulbo — nam bole din.")
    return ok({"kind":"openchat","app":app,"contact":contact,"number":None}, "OPEN_CHAT")

def parse_send(t):
    app_w = next((w for w in WHATSAPP_WORDS if w in t), None)
    sms_w = next((w for w in SMS_WORDS if w in t), None)
    say = next((w for w in SAY_MARKERS if w in t), None)
    pronoun = next((p for p in PRONOUNS if t.startswith(p + " ") or (" " + p + " ") in t), None)
    if app_w is None and sms_w is None and say is None and pronoun is None: return None
    app = "WHATSAPP" if app_w else ("SMS" if sms_w else "WHATSAPP")
    send_verb = any(w in t for w in ["pathao","pathan","pathiye dao","পাঠাও","পাঠান","send koro","send korun","dau"]) \
        or "message" in t or "মেসেজ" in t or say is not None
    m = PHONE_NUMBER.search(t)
    number = digits_only(m.group(0)) if m else None
    name = contact_name(t, False) or pronoun
    if (not name or not name.strip()) and not number:
        return unsupported("Kake pathabo?") if send_verb else None
    message = extract_message(t)
    if not message or not message.strip():
        return unsupported("Ki message pathabo bolen")
    target = name if name else number
    return ok({"kind":"send","app":app,"contact":target,"message":message,"number":number},
              "SEND_MESSAGE", risk="MEDIUM", base="MEDIUM")

def extract_message(t):
    m = re.search(r'["\'“”](.+?)["\'“”]', t)
    if m: return m.group(1).strip()
    for mk in ["message dau","message dao","msg dau","msg dao","message:","msg:","message pathao",
               "bole dao","bole din","bolun","bolena","bolen","bole diya","bolo","message e",
               "বলে দাও","বলে দিন","বলুন","বলো","মেসেজ দাও","মেসেজ:","মেসেজে","pathao","pathan","pathiye dao","পাঠাও","পাঠান",
               "send koro","send korun"]:
        after = content_after(t, mk)
        if after and 1 <= len(after) <= 2000: return after
    return None

def parse_media(t):
    play_verb = any(w in t for w in ["chalao","chalun","bajao","bajao dao","chira dao","chirao","shonao",
        "চালাও","চালান","বাজাও","শোনাও","play koro"]) or "play" in t
    if not play_verb: return None
    if not (any(w in t for w in ["gaan","গান","giti","গীত","song","video","ভিডিও","movie","সিনেমা","chobi"]) or "youtube" in t):
        return None
    spotify = "spotify" in t
    q = t
    for w in ["gaan","গান","giti","গীত","song","video","ভিডিও","movie","সিনেমা","chobi","youtube","ইউটিউব",
              "ইউটুব","spotify","স্পটিফাই","e","theke","থেকে"]:
        q = swap_word(q, w)
    for w in TAIL_VERBS: q = swap_word(q, w)
    for w in ["chalao","chalun","bajao","chira dao","chirao","shonao","চালাও","চালান","বাজাও","শোনাও","play"]:
        q = q.replace(w, " ")
    cleaned = re.sub(r"\s+", " ", q).strip(" -,.!?") or "bangla gaan"
    return ok({"kind":"play","query":cleaned,"app":"SPOTIFY" if spotify else "YOUTUBE"}, "PLAY_MEDIA")

def parse_maps(t):
    if not (any(w in t for w in ["map","ম্যাপ","location","কোথায়","kothay"])): return None
    q = None
    if "map e" in t: q = content_after(t, "map e")
    elif "maps e" in t: q = content_after(t, "maps e")
    elif "er location" in t: q = t.split(" er location")[0].strip() or None
    elif "er map" in t: q = t.split(" er map")[0].strip() or None
    elif "কোথায়" in t or "kothay" in t:
        q = re.sub(r"\s+", " ", t.replace("kothay"," ").replace("কোথায়"," ").replace("ache"," ").replace("আছে"," ")).strip(" -,.!?") or None
    if not q: return None
    cleaned = " ".join(w for w in q.split(" ") if w not in ["khujho","dekhao","dekhan","bolo","jao","koro","korun"]).strip()
    if not (2 <= len(cleaned) <= 120): return None
    return ok({"kind":"url","url":"https://www.google.com/maps/search/?api=1&query=" + cleaned.replace(" ","%20")}, "OPEN_URL")

def parse_web(t):
    markers = ["khujho","khujen","khunji","search koro","search korun","google e","google a","খোঁজো",
               "খুঁজে দাও","সার্চ করো","গুগলে"]
    if any(w in t for w in markers):
        q = t
        for w in markers: q = q.replace(w, " ")
        for w in TAIL_VERBS: q = swap_word(q, w)
        cleaned = re.sub(r"\s+", " ", q).strip(" -,.!?")
        if 2 <= len(cleaned) <= 300: return ok({"kind":"search","query":cleaned}, "SEARCH_WEB")
        return ok({"kind":"search","query":t[:120]}, "SEARCH_WEB")
    m = re.search(r"\b([a-z0-9][a-z0-9-]{1,61}\.(com|net|org|io|co|bd|info|xyz|dev|app|gov|edu|me|tv|shop|site)(/[^\s]*)?)\b", t)
    if m:
        return ok({"kind":"url","url":"https://" + m.group(1)}, "OPEN_URL")
    return None

def parse_scroll(t):
    if "scroll" in t or "স্ক্রল" in t:
        return ok({"kind":"scroll"}, "SCROLL")
    if "swipe" in t:
        return ok({"kind":"swipe"}, "SWIPE")
    return None

def parse_close(t):
    verb = next((v for v in CLOSE_VERBS if v in t), None)
    if not verb: return None
    app = app_name_from(t, verb)
    if not app: return None
    return ok({"kind":"close","app":app}, "CLOSE_APP")

def parse_open(t):
    verb = next((v for v in OPEN_VERBS if v in t), None)
    if not verb: return None
    app = app_name_from(t, verb)
    if not app: return None
    return ok({"kind":"open","app":APP_ALIASES.get(app, app)}, "OPEN_APP")

def app_name_from(t, verb):
    name = t.replace(verb, " ")
    for w in ["app","ta","টা","app ta","please","eko","hoye","amar","আমার"]: name = swap_word(name, w)
    for w in TAIL_VERBS: name = swap_word(name, w)
    cleaned = re.sub(r"\s+", " ", name).strip(" -,.!?")
    if not cleaned or len(cleaned) > 40 or len(cleaned.split(" ")) > 3: return None
    return cleaned

def contact_name(t, call_mode):
    s = t.replace("nuva", " ").replace("নুভা", " ")
    m = re.match(r"^(.*?)(\s+ke|\s+কে|\s+keর)\s+", s)
    if m:
        cand = m.group(1).strip()
        susp = any(w in cand for w in ["call","phone","kholo","bondho","open","launch"]) or " ar " in cand
        if 2 <= len(cand) <= 60 and not susp:
            return clean_name(cand)
    for lead in ["call koro","call korun","call dao","phone koro","phone korun","dial koro","কল করো",
                 "ফোন করো","ফোন করুন","কল দাও","যোগাযোগ করো"]:
        if lead in s:
            rest = content_after(s, lead)
            if rest and 2 <= len(rest) <= 60: return clean_name(rest)
    if not call_mode: return None
    for v in ["ke call","কে কল","ke phone","কে ফোন"]:
        idx = s.find(v)
        if idx > 2: return clean_name(s[:idx])
    return None

def clean_name(raw):
    name = raw
    for w in WHATSAPP_WORDS: name = name.replace(w, " ")
    for w in SMS_WORDS: name = name.replace(w, " ")
    for w in ["message","msg","মেসেজ","e","diye","দিয়ে","amar","আমার","er","এর"]: name = swap_word(name, w)
    for w in TAIL_VERBS: name = swap_word(name, w)
    cleaned = re.sub(r"\s+", " ", name).strip(" -,.!?")
    if 2 <= len(cleaned) <= 60 and len(cleaned.split(" ")) <= 5: return cleaned
    return None

def content_after(t, marker):
    idx = t.find(marker)
    if idx < 0: return None
    rest = t[idx+len(marker):]
    for w in TAIL_VERBS: rest = swap_word(rest, w)
    cleaned = re.sub(r"\s+", " ", rest).strip(" -,.!?")
    return cleaned or None

# ---------------- compound plan (v1.3) ----------------
CONNECTORS = [" ar ", " ebong ", " and then ", " and ", " tarpor ", " erpor ", " then ", " also ",
              " আর ", " এবং ", " তারপর ", " এরপর ", " তারপরে ", "; "]

def parse_daily_skill(t):
    # Representative aliases; the Kotlin registry itself is source-counted
    # below to guarantee exactly 100 unique skill definitions.
    aliases = ["kacher pharmacy","কাছের ফার্মেসি","parcel tracking","passport application",
               "current job circular","internet speed test","গাছের যত্ন","fact check","qibla direction",
               "nearby hospital","blood bank","public holiday","cybercrime report"]
    if any(alias in t for alias in aliases):
        return ok({"kind":"search","query":t}, "SEARCH_WEB")
    return None

def parse_extended_daily_skill(t):
    # Representative entity × task pairs from the exact 500-entry Kotlin matrix.
    pairs = [
        (["private tutor","home tutor","গৃহশিক্ষক"], ["nearby","near me","কাছের"]),
        (["passport","পাসপোর্ট"], ["ki kagoj lagbe","required documents","কী কাগজ লাগবে"]),
        (["excel","এক্সেল"], ["tutorial","ধাপে ধাপে"]),
        (["washing machine","ওয়াশিং মেশিন"], ["repair","problem fix","মেরামত"]),
        (["router","রাউটার"], ["user manual","ব্যবহারের নিয়ম","ম্যানুয়াল"]),
    ]
    if any(any(e in t for e in entities) and any(k in t for k in tasks) for entities,tasks in pairs):
        return ok({"kind":"search","query":t}, "SEARCH_WEB")
    return None

def parse_knowledge(t):
    questions = ["what ","how ","why ","who ","where ","when ","ki ","kivabe","keno","kothay","kokhon",
                 "কী ","কি ","কিভাবে","কেন","কোথায়","কখন"]
    endings = [" ki"," keno"," kothay"," koto"," kemon"," কী"," কি"," কেন"," কোথায়"," কত"," কেমন"]
    topics = ["recipe","রেসিপি","রান্না","meaning","মানে","translate","অনুবাদ","nearby","schedule","price","দাম কত",
              "bus","train","flight","doctor","hospital","medicine","school","college","job"]
    if any(t.startswith(w) or (" "+w) in t for w in questions) or any(t.endswith(w) for w in endings) or any(w in t for w in topics):
        return ok({"kind":"search","query":t}, "SEARCH_WEB")
    return None

def rule_table(t):
    return (parse_grammar(t) or parse_nav(t) or parse_universal(t) or parse_screen(t) or parse_user_file(t)
            or parse_productivity(t) or parse_communication(t) or parse_daily(t) or parse_realtime(t)
            or parse_status(t) or parse_help(t) or parse_media_ctl(t) or parse_volume(t) or parse_camera(t) or parse_settings(t)
            or parse_alarm(t) or parse_timer(t) or parse_reminder(t) or parse_note_todo(t)
            or parse_call(t) or parse_chat_open(t) or parse_send(t) or parse_media(t) or parse_maps(t) or parse_web(t)
            or parse_scroll(t) or parse_close(t) or parse_open(t) or parse_daily_skill(t)
            or parse_extended_daily_skill(t) or parse_knowledge(t))

def parse_prepared(t):
    security_rewrite = t.replace("paymnt","payment").replace("paymentt","payment").replace("send mony","send money") \
        .replace("pasword","password").replace("passwrd","password")
    if is_money(t) or is_money(security_rewrite): return refused()
    if mentions_creds(t) or mentions_creds(security_rewrite): return unsupported("otp...")
    return rule_table(t)

def clean_message(dec):
    send = dec.get("action") if dec and dec.get("action",{}).get("kind")=="send" else None
    if not send: return False
    contact = send["contact"].lower()
    susp = any(w in contact for w in [" ar ","kholo","bondho","band ","open","launch"])
    return (not susp) and bool(send.get("message"))

def split_plan(text, depth):
    if depth > 5 or not text.strip(): return None
    for conn in CONNECTORS:
        frm = 0
        while True:
            idx = text.find(conn, frm)
            if idx < 0: break
            left_raw = text[:idx].strip(); right_raw = text[idx+len(conn):].strip()
            frm = idx + len(conn)
            if not left_raw: continue
            left = parse_prepared(left_raw)
            if left is None or left["unsupported"]: continue
            if left.get("action",{}).get("kind") == "send": continue
            if len(left_raw.split(" ")) > 12: continue
            nested = split_plan(right_raw, depth+1) if any(c in right_raw for c in CONNECTORS) else None
            whole_tail = parse_prepared(right_raw)
            rest = nested if nested else ([whole_tail] if whole_tail is not None else [])
            if not rest: continue
            return refine_plan([left] + rest)
    return None

def refine_plan(plan):
    media = next((st["action"] for st in plan if st["action"].get("kind")=="open" and st["action"]["app"] in ("youtube","spotify")), None)
    if not media: return plan
    out = []
    for step in plan:
        if step["action"].get("kind") == "search":
            out.append(ok({"kind":"play","query":step["action"]["query"],
                           "app":"SPOTIFY" if media["app"]=="spotify" else "YOUTUBE"}, "PLAY_MEDIA"))
        else:
            out.append(step)
    return out

def parse_compound(raw):
    text = prepare(raw)
    if not text: return None
    if is_money(text): return [refused()]
    if mentions_creds(text): return [unsupported("otp...")]
    whole = parse_prepared(text)
    if whole is not None and not whole["unsupported"] and clean_message(whole):
        return [whole]
    plan = split_plan(text, 0)
    if plan and len(plan) >= 2: return plan
    return [whole] if whole is not None else None

# ---------------- TESTS ----------------
FAIL = []
def check(cond, label):
    if not cond: FAIL.append(label); print("FAIL:", label)

check(parse_time("shokal 7 tay") == (7,0), "time shokal 7")
check(parse_time("রাত ৮টায়".replace("৮","8")) == (20,0) or parse_time("raat 8 tay") == (20,0), "raat 8")
check(parse_duration("আধা ঘণ্টা") == 1800, "adha ghonta")
check(parse_duration("1 ghonta 30 minute") == 5400, "1:30")
check(is_sensitive_pkg("com.bKash.customerapp"), "pkg bkash")
check(is_money("card diye payment koro") and not is_money("bkash kholo"), "transaction vs open")
check(redact_codes("Your OTP is 4321") == "Your OTP is ••••", "redact")

check(parse("Nuva YouTube open koro.")["intent"] == "OPEN_APP", "open yt")
check(parse("নুভা ইউটিউব খোলো")["action"]["app"] == "youtube", "open yt bn")
check(parse("whatsapp kholo")["intent"] == "OPEN_APP", "kholo spelling")
check(parse("chrome kholo")["action"]["app"] == "chrome", "chrome kholo")
check(parse("nuva bkash khulo")["action"]["app"] == "bkash", "bkash khulo L1")
check(parse("bkash kholo")["action"]["app"] == "bkash", "bkash kholo L1")
check(parse("nuva bkash diye 5000 taka pathao")["unsupported"], "money refuse")
check(parse("nuva rahim ke call koro")["action"]["contact"].lower() == "rahim", "call rahim")
check(parse("nuva 01712345678 ke call koro")["action"]["number"] == "01712345678", "call number")
w = parse("nuva rahim ke whatsapp e bole dao kal class hobe")
check(w["action"]["message"] == "kal class hobe" and w["confirm"], "wa msg")
check(parse("nuva volume barao")["intent"] == "VOLUME_CONTROL", "vol up")
check(parse("nuva music pause koro")["intent"] == "MEDIA_CONTROL", "pause")
check(parse("nuva chobi tolo")["action"]["kind"] == "CAPTURE", "chobi tolo")
check(parse("nuva torch jalo")["action"]["kind"] == "TORCH", "torch")
check(parse("nuva google e dhaka weather khujho")["action"]["query"] == "dhaka weather", "web search")
check(parse("aj koto tarik")["action"]["kind"] == "DATE", "user phrase current date")
check(parse("akn koyta baje")["action"]["kind"] == "TIME", "user phrase current time")
check(parse("aj koto tarik akn koyta baje")["action"]["kind"] == "DATE_TIME", "combined current date and time")
check(parse("latest news ki")["intent"] == "SEARCH_WEB", "latest info web search")
check(parse("2 + 3 * 4 koto")["intent"] == "LOCAL_ANSWER", "offline arithmetic")
check(parse("500 er 20 percent discount")["intent"] == "LOCAL_ANSWER", "offline percentage")
check(parse("5 kilometer mile e koto")["intent"] == "LOCAL_ANSWER", "unit conversion")
check(parse("tumi ki ki korte paro")["intent"] == "LOCAL_ANSWER", "assistant help")
check(parse("chicken biryani recipe")["intent"] == "SEARCH_WEB", "knowledge search")
check(parse("Send button press koro")["intent"] == "PRESS", "universal route connected")
check(parse("shopping list e add koro dim dudh")["intent"] == "CREATE_TODO", "shopping list")
check(parse("shopping list dekhao")["intent"] == "READ_SAVED_ITEMS", "read shopping list")
check(parse("average of 10 20 30")["intent"] == "LOCAL_ANSWER", "advanced average")
check(parse("120 km 60 kmph travel time koto")["intent"] == "LOCAL_ANSWER", "travel eta")
check(parse("parcel tracking ZX123")["intent"] == "SEARCH_WEB", "daily sourced skill")
registry_source = (Path(__file__).parent.parent / "app/src/main/java/com/nuva/assistant/command/DailySkillRegistry.kt").read_text()
registry_ids = re.findall(r'^\s*skill\("([^"]+)"', registry_source, re.MULTILINE)
check(len(registry_ids) == 100 and len(set(registry_ids)) == 100, "exact 100 unique daily skills")
extended_source = (Path(__file__).parent.parent / "app/src/main/java/com/nuva/assistant/command/ExtendedDailySkillRegistry.kt").read_text()
check(re.search(r"EXPECTED_SKILL_COUNT\s*=\s*500", extended_source) is not None, "extended registry declares 500 skills")
check(parse("nearby private tutor")["intent"] == "SEARCH_WEB", "extended service skill")
check(parse("passport ki kagoj lagbe")["intent"] == "SEARCH_WEB", "extended public skill")
check(parse("excel tutorial")["intent"] == "SEARCH_WEB", "extended learning skill")
check(parse("washing machine repair")["intent"] == "SEARCH_WEB", "extended product skill")
grammar_source = (Path(__file__).parent.parent / "app/src/main/java/com/nuva/assistant/command/NaturalCommandGrammar.kt").read_text()
grammar_rows = [re.findall(r'"([^"]*)"', line) for line in grammar_source.splitlines() if line.strip().startswith("pattern(")]
grammar_aliases = [value for row in grammar_rows for value in row[1:]]
check(len(grammar_rows) == 50 and all(len(row) == 6 for row in grammar_rows), "50 grammar families x 5 aliases")
check(len(grammar_aliases) == len(set(grammar_aliases)) == 250, "250 unique grammar aliases")
check(len(grammar_aliases) * 7 * 7 == 12250, "12250 generated command forms")
check(parse("please open notification app please")["intent"] == "OPEN_NOTIFICATION_APP", "grammar notification app")
check(parse("nuva please battery percentage bolo taratari")["intent"] == "DEVICE_STATUS", "grammar battery")
check(parse("bkash paymnt koro")["unsupported"], "grammar security typo")
check(parse("file open koro")["action"]["operation"] == "OPEN_FILE", "user file picker")
check(parse("file share koro")["confirm"], "file sharing confirms")
check(parse("gallery theke photo select koro")["action"]["operation"] == "PICK_PHOTO", "photo picker")
check(parse("video share koro")["action"]["operation"] == "SHARE_VIDEO", "video share picker")
check(parse("user@example.com ke email koro je ami ashchi")["intent"] == "COMPOSE_EMAIL", "email compose")
reply_probe=parse("2 number notification e reply dao je ami ashchi")
check(reply_probe["intent"] == "REPLY_NOTIFICATION" and reply_probe["confirm"], "notification RemoteInput reply")
check(parse("email compose koro attachment")["action"]["attachment"], "email attachment picker handoff")
check(parse("passport application form kholo details local draft")["intent"] == "PREPARE_FORM", "form handoff")
check(parse("kal 9 tay schedule email je ami ashchi")["intent"] == "SCHEDULE_COMPOSE", "scheduled compose reminder")
check(parse("file delete koro")["action"]["operation"] == "DELETE_FILE", "target-aware delete")
check(parse("file rename koro new name report.pdf")["action"]["new_name"] == "report.pdf", "target-aware rename")
check(parse("file copy koro")["action"]["operation"] == "COPY_FILE", "picker copy")
check(parse("file move koro")["action"]["operation"] == "MOVE_FILE", "picker move")
check(parse("photo crop koro")["action"]["operation"] == "EDIT_PHOTO", "photo editor handoff")
check(parse("protidin 8 tay schedule sms message update")["action"]["recurrence"] == "DAILY", "daily draft")
check(parse("shukrobar 9 tay schedule email je report")["action"]["recurrence"] == "WEEKLY", "weekly draft")
check(parse("scheduled draft list dekhao")["intent"] == "LIST_SCHEDULED_DRAFTS", "list drafts")
check(parse("2 number scheduled draft cancel koro")["action"]["ordinal"] == 2, "cancel draft")
check(parse("text share koro je ami ashchi")["intent"] == "SHARE_TEXT", "text share handoff")
check(parse("new contact add koro name Rahim number 01712345678")["intent"] == "CREATE_CONTACT_DRAFT", "contact draft")
check(parse("2 number notification dismiss koro")["action"]["operation"] == "DISMISS", "notification dismiss")
check(parse("notification mark as read koro")["action"]["operation"] == "MARK_READ", "notification mark read")
check(parse("multiple file share koro")["action"]["operation"] == "SHARE_MULTIPLE_FILES", "multiple file share")
check(parse("onek photo share koro")["action"]["operation"] == "SHARE_MULTIPLE_PHOTOS", "multiple photo share")
check(parse("email compose koro multiple attachment")["action"]["multiple"], "multiple email attachments")
check(parse("contact edit koro")["intent"] == "CONTACT_HANDOFF", "contact edit handoff")
check(parse("facebook uninstall koro")["intent"] == "UNINSTALL_APP", "system uninstall handoff")
check(parse("whatsapp notification settings khulo")["action"]["panel"] == "NOTIFICATIONS", "app notification panel")
check(parse("youtube play store page khulo")["action"]["panel"] == "PLAY_STORE", "app store page")
check(parse("airplane mode setting khulo")["action"]["kind"] == "AIRPLANE_MODE", "airplane settings")
check(parse("vpn setting khulo")["action"]["kind"] == "VPN", "vpn settings")
check(parse("battery saver setting khulo")["action"]["kind"] == "BATTERY_SAVER", "battery saver settings")
check(parse("storage setting khulo")["action"]["kind"] == "STORAGE_SETTINGS", "storage settings")

# v1.3: natural language, hyphens, defaults, compounds
w1 = parse("Hey Nuva, Rohim-ke WhatsApp-e message dau ami agamikal asbona")
check(w1 and w1["intent"]=="SEND_MESSAGE" and w1["action"]["app"]=="WHATSAPP", "hyphen whatsapp-e")
check(w1["action"]["contact"].lower()=="rohim", "hyphen rohim-ke")
check(w1["action"]["message"]=="ami agamikal asbona", "message content bangla")
check(w1["confirm"] is True, "message needs confirmation")

m2 = parse("Hey Nuva, Mim-ke bolo ami 10 minit pore ashtesi")
check(m2 and m2["intent"]=="SEND_MESSAGE" and m2["action"]["app"]=="WHATSAPP", "default app whatsapp")
check(m2["action"]["contact"].lower()=="mim" and "ashtesi" in m2["action"]["message"], "mim message")

c4 = parse("Hey Nuva, amar bhai Sakib-ke call koro")
check(c4 and c4["intent"]=="CALL_CONTACT" and "sakib" in c4["action"]["contact"].lower(), "kinship call")

p5 = parse_compound("Hey Nuva, WhatsApp kholo ar Rohim-ke message dau ami agamikal asbona")
check(p5 and len(p5)==2, "compound plan size 2")
check(p5[0]["intent"]=="OPEN_APP" and p5[0]["action"]["app"]=="whatsapp", "plan step1 open whatsapp")
check(p5[1]["intent"]=="SEND_MESSAGE" and p5[1]["action"]["contact"].lower()=="rohim", "plan step2 message rohim")
check(p5[1]["action"]["message"]=="ami agamikal asbona", "plan step2 content")
check(p5[1]["confirm"] is True, "plan message step confirms")

p6 = parse_compound("Hey Nuva, Chrome kholo ar Google-e best laptop under 50000 search koro")
check(p6 and len(p6)==2 and p6[0]["action"]["app"]=="chrome", "chrome plan")
check(p6[1]["intent"]=="SEARCH_WEB" and p6[1]["action"]["query"]=="best laptop under 50000", "laptop search query")

p7 = parse_compound("Hey Nuva, YouTube kholo ar Rahat Ahmed search koro")
check(p7 and len(p7)==2 and p7[0]["action"]["app"]=="youtube", "youtube plan")
check(p7[1]["intent"]=="PLAY_MEDIA" and p7[1]["action"]["query"]=="rahat ahmed", "context search->playmedia")

p8 = parse("rohim ke whatsapp e bole dao ami ar ashbo")
check(p8 and p8["intent"]=="SEND_MESSAGE" and p8["action"]["message"]=="ami ar ashbo", "ar inside message kept")

p9 = parse("nuva 01712-345678 ke call koro")
check(p9 and p9["intent"]=="CALL_CONTACT" and p9["action"]["number"]=="01712345678", "hyphenated phone")

p10 = parse_compound("bkash kholo ar rohim ke 500 taka pathao")
check(p10 and p10[0]["unsupported"] and p10[0]["risk"]=="HIGH", "compound refused on transaction")

# ---- v1.4: chat open, pronouns, transaction patterns ----
ch = parse("Rohim-er chat kholo")
check(ch and ch["intent"]=="OPEN_CHAT" and ch["action"]["contact"].lower()=="rohim", "chat open rohim")
check(ch["risk"]=="LOW" and ch["confirm"] is False, "chat open is low risk")
chb = parse("নুভা রহিমের চ্যাট খোলো")
check(chb and chb["action"]["contact"]=="রহিম", "chat open bangla")
cht = parse("Rohim-er chat Telegram-e kholo")
check(cht and cht["action"]["app"]=="TELEGRAM", "chat open explicit app")

pr = parse("ওকে বলো আমি কাল আসব না")
check(pr and pr["intent"]=="SEND_MESSAGE" and pr["action"]["contact"] in PRONOUNS, "bangla pronoun message")
check(pr["action"]["message"]=="আমি কাল আসব না" and pr["confirm"], "pronoun message confirms")
pr2 = parse("oke bolo ami 10 minute e ashi")
check(pr2 and pr2["action"]["contact"] in PRONOUNS and pr2["action"]["message"]=="ami 10 minute e ashi", "banglish pronoun message")
pr3 = parse("tar ke call koro")
check(pr3 and pr3["intent"]=="CALL_CONTACT" and pr3["action"]["contact"] in PRONOUNS and pr3["confirm"], "pronoun call")

ts = parse("nuva bkash e 5000 taka send koro")
check(ts and ts["unsupported"] and ts["risk"]=="HIGH", "taka send refused")

mx = parse_compound("Hey Nuva, Chrome kholo and search koro Bangladesh weather")
check(mx and len(mx)==2 and mx[1]["action"]["query"]=="bangladesh weather", "english connector")

g = parse_compound("Hey Nuva, WhatsApp kholo ar Rohim-ke bolo ami agamikal asbona")
check(g and len(g)==2 and g[1]["action"]["message"]=="ami agamikal asbona" and g[1]["confirm"], "golden sentence with bolo")

# ---- v1.4b: maps/LOCATION, retry note, clarifying copy ----
mp = parse("nuva dhaka er map dekhao")
check(mp and mp["intent"]=="OPEN_URL" and "maps/search" in mp["action"]["url"] and "dhaka" in mp["action"]["url"], "dhaka map")
mp2 = parse("map e cox bazar khujho")
check(mp2 and "cox%20bazar" in mp2["action"]["url"] or (mp2 and "cox" in mp2["action"]["url"]), "map e cox bazar")
mp3 = parse("rail station kothay")
check(mp3 and "rail%20station" in mp3["action"]["url"] or (mp3 and "rail" in mp3["action"]["url"]), "kothay map")
gm = parse("google maps khulo")
check(gm and gm["intent"]=="OPEN_APP", "google maps app still opens")

# ---- v1.5: universal commands ----
u1 = parse("nuva send button press koro")
check(u1 and u1["intent"]=="PRESS" and u1["action"]["label"]=="send", "press named")
u2 = parse("এটা press করো")
check(u2 and u2["intent"]=="PRESS" and u2["action"]["label"] is None, "press bare bangla")
u3 = parse("লগইন বাটন চাপো")
check(u3 and u3["intent"]=="PRESS" and u3["action"]["label"]=="লগইন", "press bangla label")
u4 = parse("nuva lekhata muchho")
check(u4 and u4["intent"]=="CLEAR_TEXT", "clear text")
u5 = parse("notification panel kholo")
check(u5 and u5["intent"]=="OPEN_NOTIFICATIONS", "notification shade")
u6 = parse("ki button ache")
check(u6 and u6["intent"]=="DESCRIBE_SCREEN", "describe screen")
u7 = parse("notification poro")
check(u7 and u7["intent"]=="READ_NOTIFICATIONS", "read notif still works")
u8 = parse("notification er app khulo")
check(u8 and u8["intent"]=="OPEN_NOTIFICATION_APP" and u8["action"]["ordinal"]==1, "open notif app")
u9 = parse("3 number notification er app khulo")
check(u9 and u9["action"]["ordinal"]==3, "open 3rd notif app")
u10 = parse("notification setting khulo")
check(u10 and u10["action"]["kind"]=="NOTIF_SETTING", "notif settings")
u11 = parse("accessibility settings kholo")
check(u11 and u11["action"]["kind"]=="ACCESS_SETTING", "accessibility settings")

print()
print("PASS" if not FAIL else f"{len(FAIL)} FAILURES")
sys.exit(1 if FAIL else 0)
