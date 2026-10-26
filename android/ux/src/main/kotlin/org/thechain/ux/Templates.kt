package org.thechain.ux

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

/** Fixed sentence templates. Every number and name comes from a Facts field. */
val LANGS = setOf("en", "hi", "mr")

val T: Map<String, Map<String, String>> = mapOf(
    "en" to mapOf(
        "need" to "{what} needed, {dist} away.", "near" to "Near {place}.", "verified" to "Verified by {n} people.", "unverified" to "Not independently verified yet.", "skill" to "You're the nearest {skill}.", "route_ok" to "Route via {via} looks dry ({p}% safe).", "route_risk" to "Route via {via} is risky ({p}% safe).", "avoid" to "Avoids flooded {names}.", "bat_ok" to "Your battery ({pct}%) covers the trip.", "bat_low" to "Battery is tight: {pct}% for this trip.", "radius" to "Location accurate to about {r} m.", "abort" to "Turn back if {cond}.", "cond" to "water reaches {cm} cm or battery falls below {pct}%", "scout" to "Please scout the route first and report what you see; this is not yet a full dispatch.", "m" to "m", "km" to "km",
    ),
    "hi" to mapOf(
        "need" to "{what} चाहिए, {dist} दूर।", "near" to "{place} के पास।", "verified" to "{n} लोगों ने पुष्टि की।", "unverified" to "अभी तक स्वतंत्र पुष्टि नहीं हुई।", "skill" to "आप सबसे नज़दीकी {skill} हैं।", "route_ok" to "{via} से रास्ता सूखा दिखता है ({p}% सुरक्षित)।", "route_risk" to "{via} वाला रास्ता जोखिम भरा है ({p}% सुरक्षित)।", "avoid" to "बाढ़ वाले {names} से बचा गया है।", "bat_ok" to "आपकी बैटरी ({pct}%) इस यात्रा के लिए काफ़ी है।", "bat_low" to "बैटरी कम है: इस यात्रा के लिए {pct}%।", "radius" to "स्थान लगभग {r} मीटर तक सटीक है।", "abort" to "अगर {cond} तो लौट आइए।", "cond" to "पानी {cm} सेमी तक पहुँचे या बैटरी {pct}% से नीचे जाए", "scout" to "कृपया पहले रास्ता देखकर बताइए कि क्या दिखता है; यह अभी पूरा डिस्पैच नहीं है।", "m" to "मीटर", "km" to "किमी",
    ),
    "mr" to mapOf(
        "need" to "{what} हवे, {dist} अंतरावर.", "near" to "{place} जवळ.", "verified" to "{n} जणांनी पुष्टी केली.", "unverified" to "अजून स्वतंत्र पुष्टी नाही.", "skill" to "तुम्ही सर्वात जवळचे {skill} आहात.", "route_ok" to "{via} मार्गे रस्ता कोरडा दिसतो ({p}% सुरक्षित).", "route_risk" to "{via} मार्गे रस्ता धोकादायक आहे ({p}% सुरक्षित).", "avoid" to "पूरग्रस्त {names} टाळले आहे.", "bat_ok" to "तुमची बॅटरी ({pct}%) या प्रवासासाठी पुरेशी आहे.", "bat_low" to "बॅटरी कमी आहे: या प्रवासासाठी {pct}%.", "radius" to "स्थान सुमारे {r} मीटरपर्यंत अचूक आहे.", "abort" to "{cond} असल्यास परत फिरा.", "cond" to "पाणी {cm} सेमी पर्यंत पोहोचले किंवा बॅटरी {pct}% च्या खाली गेली", "scout" to "कृपया आधी रस्ता पाहून काय दिसते ते कळवा; हे अजून पूर्ण डिस्पॅच नाही.", "m" to "मीटर", "km" to "किमी",
    ),
)

val LABEL: Map<String, Map<String, String>> = mapOf(
    "en" to mapOf(
        "medic" to "medic", "medicine" to "medicine", "insulin" to "insulin", "oxygen" to "oxygen", "boat" to "boat", "water" to "drinking water", "food" to "food", "rope" to "rope", "stretcher" to "stretcher", "shelter" to "shelter", "blood" to "blood", "vehicle" to "vehicle", "fuel" to "fuel", "power" to "power",
    ),
    "hi" to mapOf(
        "medic" to "डॉक्टर", "medicine" to "दवा", "insulin" to "इंसुलिन", "oxygen" to "ऑक्सीजन", "boat" to "नाव", "water" to "पानी", "food" to "खाना", "rope" to "रस्सी", "stretcher" to "स्ट्रेचर", "shelter" to "आश्रय", "blood" to "रक्त", "vehicle" to "गाड़ी", "fuel" to "ईंधन", "power" to "बिजली",
    ),
    "mr" to mapOf(
        "medic" to "डॉक्टर", "medicine" to "औषध", "insulin" to "इन्सुलिन", "oxygen" to "ऑक्सिजन", "boat" to "नाव", "water" to "पाणी", "food" to "अन्न", "rope" to "दोर", "stretcher" to "स्ट्रेचर", "shelter" to "निवारा", "blood" to "रक्त", "vehicle" to "वाहन", "fuel" to "इंधन", "power" to "वीज",
    ),
)

fun label(lang: String, tag: String): String {
    if (tag.startsWith("unknown:")) return tag.substring(8).replace("_", " ")
    return LABEL.getValue(lang)[tag] ?: tag.replace("_", " ")
}

fun pyRoundToInt(x: Double): Int =
    BigDecimal(x.toString()).setScale(0, RoundingMode.HALF_EVEN).intValueExact()

fun fmtDist(lang: String, meters: Double): String {
    val t = T.getValue(lang)
    if (meters < 1000.0) {
        val rounded = max(10, pyRoundToInt(meters / 10.0) * 10)
        return "$rounded ${t.getValue("m")}"
    }
    val km = BigDecimal((meters / 1000.0).toString()).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
    return "$km ${t.getValue("km")}"
}
