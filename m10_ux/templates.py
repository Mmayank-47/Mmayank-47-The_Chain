"""Fixed sentence templates (English, Hindi, Marathi). Every number and name in a sentence comes from
a `Facts` field, never from a model. Numbers stay Latin digits so they can be checked mechanically."""
from __future__ import annotations

LANGS = ("en", "hi", "mr")

T = {
    "en": {
        "need": "{what} needed, {dist} away.", "near": "Near {place}.",
        "verified": "Verified by {n} people.", "unverified": "Not independently verified yet.",
        "skill": "You're the nearest {skill}.",
        "route_ok": "Route via {via} looks dry ({p}% safe).",
        "route_risk": "Route via {via} is risky ({p}% safe).",
        "avoid": "Avoids flooded {names}.",
        "bat_ok": "Your battery ({pct}%) covers the trip.", "bat_low": "Battery is tight: {pct}% for this trip.",
        "radius": "Location accurate to about {r} m.",
        "abort": "Turn back if {cond}.", "cond": "water reaches {cm} cm or battery falls below {pct}%",
        "scout": "Please scout the route first and report what you see; this is not yet a full dispatch.",
        "m": "m", "km": "km",
    },
    "hi": {
        "need": "{what} चाहिए, {dist} दूर।", "near": "{place} के पास।",
        "verified": "{n} लोगों ने पुष्टि की।", "unverified": "अभी तक स्वतंत्र पुष्टि नहीं हुई।",
        "skill": "आप सबसे नज़दीकी {skill} हैं।",
        "route_ok": "{via} से रास्ता सूखा दिखता है ({p}% सुरक्षित)।",
        "route_risk": "{via} वाला रास्ता जोखिम भरा है ({p}% सुरक्षित)।",
        "avoid": "बाढ़ वाले {names} से बचा गया है।",
        "bat_ok": "आपकी बैटरी ({pct}%) इस यात्रा के लिए काफ़ी है।", "bat_low": "बैटरी कम है: इस यात्रा के लिए {pct}%।",
        "radius": "स्थान लगभग {r} मीटर तक सटीक है।",
        "abort": "अगर {cond} तो लौट आइए।", "cond": "पानी {cm} सेमी तक पहुँचे या बैटरी {pct}% से नीचे जाए",
        "scout": "कृपया पहले रास्ता देखकर बताइए कि क्या दिखता है; यह अभी पूरा डिस्पैच नहीं है।",
        "m": "मीटर", "km": "किमी",
    },
    "mr": {
        "need": "{what} हवे, {dist} अंतरावर.", "near": "{place} जवळ.",
        "verified": "{n} जणांनी पुष्टी केली.", "unverified": "अजून स्वतंत्र पुष्टी नाही.",
        "skill": "तुम्ही सर्वात जवळचे {skill} आहात.",
        "route_ok": "{via} मार्गे रस्ता कोरडा दिसतो ({p}% सुरक्षित).",
        "route_risk": "{via} मार्गे रस्ता धोकादायक आहे ({p}% सुरक्षित).",
        "avoid": "पूरग्रस्त {names} टाळले आहे.",
        "bat_ok": "तुमची बॅटरी ({pct}%) या प्रवासासाठी पुरेशी आहे.", "bat_low": "बॅटरी कमी आहे: या प्रवासासाठी {pct}%.",
        "radius": "स्थान सुमारे {r} मीटरपर्यंत अचूक आहे.",
        "abort": "{cond} असल्यास परत फिरा.", "cond": "पाणी {cm} सेमी पर्यंत पोहोचले किंवा बॅटरी {pct}% च्या खाली गेली",
        "scout": "कृपया आधी रस्ता पाहून काय दिसते ते कळवा; हे अजून पूर्ण डिस्पॅच नाही.",
        "m": "मीटर", "km": "किमी",
    },
}

LABEL = {
    "en": {"medic": "medic", "medicine": "medicine", "insulin": "insulin", "oxygen": "oxygen", "boat": "boat",
           "water": "drinking water", "food": "food", "rope": "rope", "stretcher": "stretcher",
           "shelter": "shelter", "blood": "blood", "vehicle": "vehicle", "fuel": "fuel", "power": "power"},
    "hi": {"medic": "डॉक्टर", "medicine": "दवा", "insulin": "इंसुलिन", "oxygen": "ऑक्सीजन", "boat": "नाव",
           "water": "पानी", "food": "खाना", "rope": "रस्सी", "stretcher": "स्ट्रेचर", "shelter": "आश्रय",
           "blood": "रक्त", "vehicle": "गाड़ी", "fuel": "ईंधन", "power": "बिजली"},
    "mr": {"medic": "डॉक्टर", "medicine": "औषध", "insulin": "इन्सुलिन", "oxygen": "ऑक्सिजन", "boat": "नाव",
           "water": "पाणी", "food": "अन्न", "rope": "दोर", "stretcher": "स्ट्रेचर", "shelter": "निवारा",
           "blood": "रक्त", "vehicle": "वाहन", "fuel": "इंधन", "power": "वीज"},
}


def label(lang: str, tag: str) -> str:
    if tag.startswith("unknown:"):
        return tag[8:].replace("_", " ")
    return LABEL[lang].get(tag, tag.replace("_", " "))


def fmt_dist(lang: str, m: float) -> str:
    t = T[lang]
    if m < 1000:
        return f"{max(10, int(round(m / 10.0)) * 10)} {t['m']}"
    return f"{m / 1000.0:.1f} {t['km']}"
