"""Deterministic keyword parser: the floor M2 never drops below (no model, bad model output, injection).

Pure token/phrase matching over English, romanised Hindi/Marathi (Hinglish) and Devanagari.
Confidence is capped at 0.85 so a model-backed parse can always outrank it.
"""
from __future__ import annotations

from typing import Dict, List, Optional, Set, Tuple

from .ontology import Ontology, norm, tokenize
from .schema import FEATURES, ParsedClaim

MAX_TOKENS = 200
_DEFAULT = Ontology()

# cue phrase -> where the thing being asked for / offered sits: "a" = after the cue, "b" = before it
NEED_CUES = {"need": "a", "needs": "a", "needed": "a", "require": "a", "requires": "a", "required": "a",
             "sos": "a", "chahiye": "b", "chahie": "b", "zarurat": "b", "jarurat": "b", "zaroorat": "b",
             "चाहिए": "b", "चाहिये": "b", "जरूरत": "b", "ज़रूरत": "b", "pahije": "b", "pahijet": "b",
             "hve": "b", "हवे": "b", "हवी": "b", "पाहिजे": "b", "पाहिजेत": "b", "bachao": "b", "बचाओ": "b"}
OFFER_CUES = {"have": "a", "has": "a", "offer": "a", "offering": "a", "available": "b", "provide": "a",
              "providing": "a", "giving": "a", "donate": "a", "mere paas": "a", "mere pass": "a",
              "hamare paas": "a", "humare paas": "a", "hamare pass": "a", "मेरे पास": "a", "हमारे पास": "a",
              "majhyakade": "a", "aamchyakade": "a", "माझ्याकडे": "a", "आमच्याकडे": "a",
              "de sakta": "b", "de sakti": "b", "de sakte": "b", "de skta": "b", "दे सकता": "b",
              "दे सकते": "b", "देऊ शकतो": "b", "uplabdh": "b", "उपलब्ध": "b"}
NEGATIONS = {"no", "not", "dont", "nahi", "nahin", "nako", "नहीं", "नाही", "नको"}

FEATURE_PHRASES: Dict[str, List[str]] = {
    "not_breathing": ["not breathing", "cant breathe", "cannot breathe", "unable to breathe", "saans nahi",
                      "sans nahi", "saans nahin", "सांस नहीं", "साँस नहीं", "श्वास नाही"],
    "severe_bleeding": ["bleeding", "bleed", "bleeds", "khoon beh", "khun beh", "blood loss", "खून बह",
                        "रक्तस्राव", "रक्त वाहत"],
    "unconscious": ["unconscious", "unresponsive", "passed out", "behosh", "बेहोश"],
    "trapped": ["trapped", "stuck", "stranded", "phas", "phans", "phansa", "phanse", "फंस", "फंसे", "फंसा",
                "adakle", "adkalo", "अडकले", "अडकलो", "अडकलेत"],
    "not_walking": ["cant walk", "cannot walk", "unable to walk", "paralysed", "paralyzed", "bedridden",
                    "wheelchair", "broken leg", "fracture", "chal nahi", "chal nahin", "pair toot",
                    "चल नहीं", "पैर टूट"],
    "elderly": ["elderly", "old man", "old woman", "old lady", "senior citizen", "grandmother", "grandfather",
                "dadi", "nani", "buzurg", "बुजुर्ग", "वृद्ध", "आजी", "ajoba", "आजोबा"],
    "child": ["child", "children", "kid", "kids", "baby", "infant", "toddler", "bachcha", "bachche",
              "bachhe", "bacha", "बच्चा", "बच्चे", "बच्ची", "mulga", "mulgi", "मुलगा", "मुलगी"],
    "pregnant": ["pregnant", "pregnancy", "garbhvati", "garbhwati", "गर्भवती", "labour pain", "labor pain",
                 "prasav", "प्रसव"],
    "medication_dependent": ["insulin", "dialysis", "diabetic", "diabetes", "asthma", "asthmatic", "inhaler",
                             "epileptic", "heart patient", "oxygen", "madhumeh", "मधुमेह", "इंसुलिन",
                             "डायलिसिस", "ऑक्सीजन"],
}
KEEP_BOTH = {"insulin", "oxygen", "dialysis"}      # both a feature and something that can be asked for

NUMBERS = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
           "nine": 9, "ten": 10, "ek": 1, "do": 2, "teen": 3, "char": 4, "paanch": 5, "panch": 5,
           "chhe": 6, "saat": 7, "aath": 8, "nau": 9, "das": 10, "don": 2, "tin": 3, "paach": 5,
           "एक": 1, "दो": 2, "तीन": 3, "चार": 4, "पांच": 5, "पाच": 5, "छह": 6, "सात": 7, "आठ": 8,
           "नौ": 9, "दस": 10, "दोन": 2}
PEOPLE = {"people", "persons", "person", "log", "लोग", "vyakti", "व्यक्ती", "family", "members", "patients",
          "patient", "mareez", "मरीज", "child", "children", "kids", "bachche", "bachhe", "बच्चे"}

PREP_EN = {"at", "near", "opposite", "behind", "outside", "inside", "beside", "around", "in", "on"}
POST = {"mein", "par", "pe", "jawal", "javal", "madhe", "aaspas", "nazdeek", "najdik", "में", "पर", "पे",
        "जवळ", "मध्ये", "आसपास"}
ARTICLES = {"the", "a", "an", "my", "our", "this"}

STOP = PREP_EN | ARTICLES | {
    "help", "urgent", "urgently", "please", "asap", "immediately", "jaldi", "turant", "now", "here", "there",
    "someone", "somebody", "anyone", "everyone", "also", "very", "much", "many", "some", "any", "your", "we",
    "i", "you", "they", "he", "she", "it", "is", "are", "was", "am", "be", "been", "to", "for", "of", "and",
    "or", "with", "from", "by", "but", "so", "flood", "hours", "hour", "minutes", "mins", "min", "days", "day",
    "mujhe", "mera", "meri", "mere", "hamare", "humare", "hum", "ko", "ki", "ka", "ke", "hai", "hain", "ho",
    "aur", "bahut", "paas", "pass", "kuch", "koi", "mala", "majhya", "aamhi", "aahe", "aahet", "ahe", "kay",
    "can", "will", "would", "could", "should", "us", "me", "my", "need", "have"} | PEOPLE

HI_MARK = {"chahiye", "hai", "hain", "mere", "mujhe", "hamare", "humare", "paas", "mein", "ko", "bachao",
           "madad", "dawai", "paani", "khana", "bahut", "jaldi", "pe", "par", "phas", "phans", "chahie",
           "चाहिए", "है", "हैं", "मेरे", "मुझे", "हमारे", "पास", "में", "को", "बचाओ", "मदद", "पानी", "दवा"}
MR_MARK = {"pahije", "aahe", "aahet", "majhya", "mala", "aamhi", "jawal", "madhe", "nako", "kay", "ahe",
           "majhyakade", "aamchyakade", "adakle", "हवे", "पाहिजे", "आहे", "आहेत", "माझ्या", "मला", "आम्ही",
           "जवळ", "मध्ये", "नको", "पाणी"}
EN_MARK = {"need", "have", "please", "trapped", "help", "at", "near", "the", "and", "with", "is", "are",
           "we", "stuck", "urgent", "my", "our", "can", "no", "not"}


def _dev(s: str) -> bool:
    return any("ऀ" <= ch <= "ॿ" for ch in s)


def detect_language(toks: List[str]) -> str:
    if not toks:
        return "unknown"
    hi = sum(t in HI_MARK for t in toks)
    mr = sum(t in MR_MARK for t in toks)
    en = sum(t in EN_MARK for t in toks)
    has_dev = any(_dev(t) for t in toks)
    has_lat = any(ch.isascii() and ch.isalpha() for t in toks for ch in t)
    if has_dev and has_lat:
        return "mixed"
    indic = hi + mr
    if indic and en:
        return "mixed"
    if indic or has_dev:
        return "mr" if mr > hi else "hi"
    return "en"


def _phrase_index(table) -> Tuple[Dict[Tuple[str, ...], object], int]:
    idx, mx = {}, 1
    for ph, v in table.items():
        t = tuple(tokenize(ph))
        if t:
            idx[t] = v
            mx = max(mx, len(t))
    return idx, mx


_NEED, _NEED_MAX = _phrase_index(NEED_CUES)
_OFFER, _OFFER_MAX = _phrase_index(OFFER_CUES)
_FEAT = {}
_FEAT_MAX = 1
for _f, _forms in FEATURE_PHRASES.items():
    _i, _m = _phrase_index({p: _f for p in _forms})
    _FEAT.update(_i)
    _FEAT_MAX = max(_FEAT_MAX, _m)
_NEG = {tokenize(n)[0] for n in NEGATIONS}
_NUM = {tokenize(k)[0]: v for k, v in NUMBERS.items()}
_PEOPLE = {tokenize(p)[0] for p in PEOPLE}


def _scan(toks, index, mx):
    out, i = [], 0
    while i < len(toks):
        for n in range(min(mx, len(toks) - i), 0, -1):
            v = index.get(tuple(toks[i:i + n]))
            if v is not None:
                out.append((i, i + n, v))
                i += n - 1
                break
        i += 1
    return out


def _number(tok: str) -> Optional[int]:
    if tok.isdigit() and len(tok) <= 4:
        return int(tok) if int(tok) > 0 else None
    return _NUM.get(tok)


def _quantity(toks, anchors) -> Optional[int]:
    for a in anchors:
        for j in (a - 1, a - 2):
            if j >= 0 and _number(toks[j]) is not None:
                return _number(toks[j])
    return None


def _unknown_tag(toks, cues, taken: Set[int]) -> Optional[str]:
    for s, e, d in cues:
        rng = range(e, min(len(toks), e + 5)) if d == "a" else range(s - 1, max(-1, s - 5), -1)
        got = []
        for j in rng:
            t = toks[j]
            ok = j not in taken and t not in STOP and _number(t) is None and len(t) >= 3 and not t.isdigit()
            if ok:
                got.append(t)
                if len(got) == 2:
                    break
            elif got:
                break
        if got:
            return "unknown:" + "_".join(got if d == "a" else got[::-1])
    return None


def _location(toks, taken: Set[int]) -> str:
    def ok(j):
        return j not in taken and toks[j] not in STOP

    cands = []
    for i, t in enumerate(toks):
        if t in PREP_EN:
            j = i + 1
            while j < len(toks) and toks[j] in ARTICLES:
                j += 1
            got = []
            while j < len(toks) and len(got) < 4 and ok(j):
                got.append(toks[j])
                j += 1
            cands.append(got)
        end = i if (t in POST or (t == "ke" and i + 1 < len(toks) and toks[i + 1] in
                                  {"paas", "pass", "nazdeek", "samne"})) else None
        if end is not None:
            got, j = [], end - 1
            while j >= 0 and len(got) < 3 and ok(j):
                got.append(toks[j])
                j -= 1
            cands.append(got[::-1])
    cands = [c for c in cands if c and not all(x.isdigit() for x in c)]
    return " ".join(max(cands, key=len)) if cands else ""


def parse(text: str, ontology: Optional[Ontology] = None) -> ParsedClaim:
    onto = ontology or _DEFAULT
    toks = tokenize(text)[:MAX_TOKENS]
    lang = detect_language(toks)
    if not toks:
        return ParsedClaim.build("unknown", (), None, (), "", lang, 0.0, "keyword")

    feat_hits = _scan(toks, _FEAT, _FEAT_MAX)
    feat_spans = {k for s, e, _ in feat_hits for k in range(s, e)}
    caps = [(tag, s, e) for tag, s, e in onto.find(toks)
            if tag in KEEP_BOTH or not any(k in feat_spans for k in range(s, e))]
    need = [(s, e, _NEED[tuple(toks[s:e])]) for s, e, _ in _scan(toks, _NEED, _NEED_MAX)]
    offer = [(s, e, _OFFER[tuple(toks[s:e])]) for s, e, _ in _scan(toks, _OFFER, _OFFER_MAX)]
    feats = sorted({f for _, _, f in feat_hits})

    if need:
        kind = "need"
    elif offer:
        kind = "need" if any(t in _NEG for t in toks) else "offer"
    elif feats:
        kind = "need"
    else:
        kind = "unknown"

    tags: List[str] = []
    for tag, _, _ in caps:
        if tag not in tags:
            tags.append(tag)
    taken = {k for _, s, e in caps for k in range(s, e)} | feat_spans | \
            {k for s, e, _ in need + offer for k in range(s, e)}
    if not tags and kind != "unknown":
        u = _unknown_tag(toks, sorted(need + offer), taken)
        if u:
            tags.append(u)

    anchors = [s for _, s, _ in caps] + [i for i, t in enumerate(toks) if t in _PEOPLE]
    qty = _quantity(toks, sorted(anchors))
    loc = _location(toks, taken)

    conf = 0.35
    conf += 0.20 if kind != "unknown" else 0.0
    conf += 0.20 if any(not t.startswith("unknown:") for t in tags) else 0.0
    conf += 0.10 if loc else 0.0
    conf += 0.10 if (qty is not None or feats) else 0.0
    return ParsedClaim.build(kind, tags, qty, feats, loc, lang, min(conf, 0.85), "keyword")
