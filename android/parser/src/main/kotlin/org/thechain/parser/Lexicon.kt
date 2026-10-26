package org.thechain.parser

// Phrase tables copied from the Python M2 modules. Map order is CPython insertion order.

internal val CAPS: LinkedHashMap<String, List<String>> = linkedMapOf(
    "medic" to listOf("doctor", "doctors", "dr", "nurse", "nurses", "medic", "paramedic", "daktar", "doctor sahab", "first aid", "\u0921\u0949\u0915\u094d\u091f\u0930", "\u0928\u0930\u094d\u0938", "\u0921\u093e\u0915\u094d\u091f\u0930", "\u0935\u0948\u0926\u094d\u092f"),
    "medicine" to listOf("medicine", "medicines", "meds", "dawai", "dawa", "davai", "dawaiyan", "goli", "aushadh", "tablet", "tablets", "\u0926\u0935\u093e", "\u0926\u0935\u093e\u0908", "\u0926\u0935\u093e\u0907\u092f\u093e\u0902", "\u0917\u094b\u0932\u0940", "\u0914\u0937\u0927", "\u0914\u0937\u0927\u0947"),
    "insulin" to listOf("insulin", "\u0907\u0902\u0938\u0941\u0932\u093f\u0928"),
    "oxygen" to listOf("oxygen", "o2", "oxygen cylinder", "\u0911\u0915\u094d\u0938\u0940\u091c\u0928", "\u0911\u0915\u094d\u0938\u093f\u091c\u0928"),
    "dialysis" to listOf("dialysis", "\u0921\u093e\u092f\u0932\u093f\u0938\u093f\u0938"),
    "blood" to listOf("blood", "raktdaan", "rakt", "\u0930\u0915\u094d\u0924", "\u0930\u0915\u094d\u0924\u0926\u093e\u0928"),
    "boat" to listOf("boat", "boats", "naav", "nav", "dinghy", "raft", "\u0928\u093e\u0935", "\u0928\u094c\u0915\u093e", "\u0939\u094b\u0921\u0940"),
    "lifejacket" to listOf("life jacket", "lifejacket", "life vest", "\u0932\u093e\u0907\u092b \u091c\u0948\u0915\u0947\u091f"),
    "rope" to listOf("rope", "rassi", "\u0930\u0938\u094d\u0938\u0940", "\u0926\u094b\u0930", "\u0926\u094b\u0930\u0940"),
    "stretcher" to listOf("stretcher", "\u0938\u094d\u091f\u094d\u0930\u0947\u091a\u0930"),
    "water" to listOf("water", "paani", "pani", "paanee", "\u092a\u093e\u0928\u0940", "\u092a\u093e\u0923\u0940"),
    "food" to listOf("food", "khana", "khaana", "ration", "raashan", "jevan", "bhojan", "\u0916\u093e\u0928\u093e", "\u092d\u094b\u091c\u0928", "\u091c\u0947\u0935\u0923", "\u0930\u093e\u0936\u0928", "\u0905\u0928\u094d\u0928"),
    "shelter" to listOf("shelter", "tent", "tents", "aashray", "nivara", "\u0906\u0936\u094d\u0930\u092f", "\u0928\u093f\u0935\u093e\u0930\u093e", "\u0924\u0902\u092c\u0942"),
    "vehicle" to listOf("vehicle", "car", "truck", "tempo", "jeep", "gaadi", "gadi", "ambulance", "\u0917\u093e\u0921\u093c\u0940", "\u0917\u093e\u0921\u0940", "\u0935\u093e\u0939\u0928", "\u090f\u092e\u094d\u092c\u0941\u0932\u0947\u0902\u0938"),
    "fuel" to listOf("fuel", "petrol", "diesel", "\u092a\u0947\u091f\u094d\u0930\u094b\u0932", "\u0921\u0940\u091c\u0932"),
    "power" to listOf("generator", "powerbank", "power bank", "charger", "bijli", "\u092c\u093f\u091c\u0932\u0940", "\u091c\u0928\u0930\u0947\u091f\u0930", "\u091a\u093e\u0930\u094d\u091c\u0930"),
)

internal val NEED_CUES: LinkedHashMap<String, String> = linkedMapOf(
    "need" to "a",
    "needs" to "a",
    "needed" to "a",
    "require" to "a",
    "requires" to "a",
    "required" to "a",
    "sos" to "a",
    "chahiye" to "b",
    "chahie" to "b",
    "zarurat" to "b",
    "jarurat" to "b",
    "zaroorat" to "b",
    "\u091a\u093e\u0939\u093f\u090f" to "b",
    "\u091a\u093e\u0939\u093f\u092f\u0947" to "b",
    "\u091c\u0930\u0942\u0930\u0924" to "b",
    "\u091c\u093c\u0930\u0942\u0930\u0924" to "b",
    "pahije" to "b",
    "pahijet" to "b",
    "hve" to "b",
    "\u0939\u0935\u0947" to "b",
    "\u0939\u0935\u0940" to "b",
    "\u092a\u093e\u0939\u093f\u091c\u0947" to "b",
    "\u092a\u093e\u0939\u093f\u091c\u0947\u0924" to "b",
    "bachao" to "b",
    "\u092c\u091a\u093e\u0913" to "b",
)

internal val OFFER_CUES: LinkedHashMap<String, String> = linkedMapOf(
    "have" to "a",
    "has" to "a",
    "offer" to "a",
    "offering" to "a",
    "available" to "b",
    "provide" to "a",
    "providing" to "a",
    "giving" to "a",
    "donate" to "a",
    "mere paas" to "a",
    "mere pass" to "a",
    "hamare paas" to "a",
    "humare paas" to "a",
    "hamare pass" to "a",
    "\u092e\u0947\u0930\u0947 \u092a\u093e\u0938" to "a",
    "\u0939\u092e\u093e\u0930\u0947 \u092a\u093e\u0938" to "a",
    "majhyakade" to "a",
    "aamchyakade" to "a",
    "\u092e\u093e\u091d\u094d\u092f\u093e\u0915\u0921\u0947" to "a",
    "\u0906\u092e\u091a\u094d\u092f\u093e\u0915\u0921\u0947" to "a",
    "de sakta" to "b",
    "de sakti" to "b",
    "de sakte" to "b",
    "de skta" to "b",
    "\u0926\u0947 \u0938\u0915\u0924\u093e" to "b",
    "\u0926\u0947 \u0938\u0915\u0924\u0947" to "b",
    "\u0926\u0947\u090a \u0936\u0915\u0924\u094b" to "b",
    "uplabdh" to "b",
    "\u0909\u092a\u0932\u092c\u094d\u0927" to "b",
)

internal val NEGATIONS: Set<String> = setOf(
    "dont", "nahi", "nahin", "nako", "no", "not",
    "\u0928\u0915\u094b", "\u0928\u0939\u0940\u0902", "\u0928\u093e\u0939\u0940",
)

internal val FEATURE_PHRASES: LinkedHashMap<String, List<String>> = linkedMapOf(
    "not_breathing" to listOf("not breathing", "cant breathe", "cannot breathe", "unable to breathe", "saans nahi", "sans nahi", "saans nahin", "\u0938\u093e\u0902\u0938 \u0928\u0939\u0940\u0902", "\u0938\u093e\u0901\u0938 \u0928\u0939\u0940\u0902", "\u0936\u094d\u0935\u093e\u0938 \u0928\u093e\u0939\u0940"),
    "severe_bleeding" to listOf("bleeding", "bleed", "bleeds", "khoon beh", "khun beh", "blood loss", "\u0916\u0942\u0928 \u092c\u0939", "\u0930\u0915\u094d\u0924\u0938\u094d\u0930\u093e\u0935", "\u0930\u0915\u094d\u0924 \u0935\u093e\u0939\u0924"),
    "unconscious" to listOf("unconscious", "unresponsive", "passed out", "behosh", "\u092c\u0947\u0939\u094b\u0936"),
    "trapped" to listOf("trapped", "stuck", "stranded", "phas", "phans", "phansa", "phanse", "\u092b\u0902\u0938", "\u092b\u0902\u0938\u0947", "\u092b\u0902\u0938\u093e", "adakle", "adkalo", "\u0905\u0921\u0915\u0932\u0947", "\u0905\u0921\u0915\u0932\u094b", "\u0905\u0921\u0915\u0932\u0947\u0924"),
    "not_walking" to listOf("cant walk", "cannot walk", "unable to walk", "paralysed", "paralyzed", "bedridden", "wheelchair", "broken leg", "fracture", "chal nahi", "chal nahin", "pair toot", "\u091a\u0932 \u0928\u0939\u0940\u0902", "\u092a\u0948\u0930 \u091f\u0942\u091f"),
    "elderly" to listOf("elderly", "old man", "old woman", "old lady", "senior citizen", "grandmother", "grandfather", "dadi", "nani", "buzurg", "\u092c\u0941\u091c\u0941\u0930\u094d\u0917", "\u0935\u0943\u0926\u094d\u0927", "\u0906\u091c\u0940", "ajoba", "\u0906\u091c\u094b\u092c\u093e"),
    "child" to listOf("child", "children", "kid", "kids", "baby", "infant", "toddler", "bachcha", "bachche", "bachhe", "bacha", "\u092c\u091a\u094d\u091a\u093e", "\u092c\u091a\u094d\u091a\u0947", "\u092c\u091a\u094d\u091a\u0940", "mulga", "mulgi", "\u092e\u0941\u0932\u0917\u093e", "\u092e\u0941\u0932\u0917\u0940"),
    "pregnant" to listOf("pregnant", "pregnancy", "garbhvati", "garbhwati", "\u0917\u0930\u094d\u092d\u0935\u0924\u0940", "labour pain", "labor pain", "prasav", "\u092a\u094d\u0930\u0938\u0935"),
    "medication_dependent" to listOf("insulin", "dialysis", "diabetic", "diabetes", "asthma", "asthmatic", "inhaler", "epileptic", "heart patient", "oxygen", "madhumeh", "\u092e\u0927\u0941\u092e\u0947\u0939", "\u0907\u0902\u0938\u0941\u0932\u093f\u0928", "\u0921\u093e\u092f\u0932\u093f\u0938\u093f\u0938", "\u0911\u0915\u094d\u0938\u0940\u091c\u0928"),
)

internal val KEEP_BOTH: Set<String> = setOf(
    "dialysis", "insulin", "oxygen",
)

internal val NUMBERS: LinkedHashMap<String, Int> = linkedMapOf(
    "one" to 1,
    "two" to 2,
    "three" to 3,
    "four" to 4,
    "five" to 5,
    "six" to 6,
    "seven" to 7,
    "eight" to 8,
    "nine" to 9,
    "ten" to 10,
    "ek" to 1,
    "do" to 2,
    "teen" to 3,
    "char" to 4,
    "paanch" to 5,
    "panch" to 5,
    "chhe" to 6,
    "saat" to 7,
    "aath" to 8,
    "nau" to 9,
    "das" to 10,
    "don" to 2,
    "tin" to 3,
    "paach" to 5,
    "\u090f\u0915" to 1,
    "\u0926\u094b" to 2,
    "\u0924\u0940\u0928" to 3,
    "\u091a\u093e\u0930" to 4,
    "\u092a\u093e\u0902\u091a" to 5,
    "\u092a\u093e\u091a" to 5,
    "\u091b\u0939" to 6,
    "\u0938\u093e\u0924" to 7,
    "\u0906\u0920" to 8,
    "\u0928\u094c" to 9,
    "\u0926\u0938" to 10,
    "\u0926\u094b\u0928" to 2,
)

internal val PEOPLE: Set<String> = setOf(
    "bachche", "bachhe", "child", "children", "family", "kids",
    "log", "mareez", "members", "patient", "patients", "people",
    "person", "persons", "vyakti", "\u092c\u091a\u094d\u091a\u0947", "\u092e\u0930\u0940\u091c", "\u0932\u094b\u0917",
    "\u0935\u094d\u092f\u0915\u094d\u0924\u0940",
)

internal val PREP_EN: Set<String> = setOf(
    "around", "at", "behind", "beside", "in", "inside",
    "near", "on", "opposite", "outside",
)

internal val POST: Set<String> = setOf(
    "aaspas", "javal", "jawal", "madhe", "mein", "najdik",
    "nazdeek", "par", "pe", "\u0906\u0938\u092a\u093e\u0938", "\u091c\u0935\u0933", "\u092a\u0930",
    "\u092a\u0947", "\u092e\u0927\u094d\u092f\u0947", "\u092e\u0947\u0902",
)

internal val ARTICLES: Set<String> = setOf(
    "a", "an", "my", "our", "the", "this",
)

internal val STOP: Set<String> = setOf(
    "a", "aahe", "aahet", "aamhi", "ahe", "also",
    "am", "an", "and", "any", "anyone", "are",
    "around", "asap", "at", "aur", "bachche", "bachhe",
    "bahut", "be", "been", "behind", "beside", "but",
    "by", "can", "child", "children", "could", "day",
    "days", "everyone", "family", "flood", "for", "from",
    "hai", "hain", "hamare", "have", "he", "help",
    "here", "ho", "hour", "hours", "hum", "humare",
    "i", "immediately", "in", "inside", "is", "it",
    "jaldi", "ka", "kay", "ke", "ki", "kids",
    "ko", "koi", "kuch", "log", "majhya", "mala",
    "many", "mareez", "me", "members", "mera", "mere",
    "meri", "min", "mins", "minutes", "much", "mujhe",
    "my", "near", "need", "now", "of", "on",
    "opposite", "or", "our", "outside", "paas", "pass",
    "patient", "patients", "people", "person", "persons", "please",
    "she", "should", "so", "some", "somebody", "someone",
    "the", "there", "they", "this", "to", "turant",
    "urgent", "urgently", "us", "very", "vyakti", "was",
    "we", "will", "with", "would", "you", "your",
    "\u092c\u091a\u094d\u091a\u0947", "\u092e\u0930\u0940\u091c", "\u0932\u094b\u0917", "\u0935\u094d\u092f\u0915\u094d\u0924\u0940",
)

internal val HI_MARK: Set<String> = setOf(
    "bachao", "bahut", "chahie", "chahiye", "dawai", "hai",
    "hain", "hamare", "humare", "jaldi", "khana", "ko",
    "madad", "mein", "mere", "mujhe", "paani", "paas",
    "par", "pe", "phans", "phas", "\u0915\u094b", "\u091a\u093e\u0939\u093f\u090f",
    "\u0926\u0935\u093e", "\u092a\u093e\u0928\u0940", "\u092a\u093e\u0938", "\u092c\u091a\u093e\u0913", "\u092e\u0926\u0926", "\u092e\u0941\u091d\u0947",
    "\u092e\u0947\u0902", "\u092e\u0947\u0930\u0947", "\u0939\u092e\u093e\u0930\u0947", "\u0939\u0948", "\u0939\u0948\u0902",
)

internal val MR_MARK: Set<String> = setOf(
    "aahe", "aahet", "aamchyakade", "aamhi", "adakle", "ahe",
    "jawal", "kay", "madhe", "majhya", "majhyakade", "mala",
    "nako", "pahije", "\u0906\u092e\u094d\u0939\u0940", "\u0906\u0939\u0947", "\u0906\u0939\u0947\u0924", "\u091c\u0935\u0933",
    "\u0928\u0915\u094b", "\u092a\u093e\u0923\u0940", "\u092a\u093e\u0939\u093f\u091c\u0947", "\u092e\u0927\u094d\u092f\u0947", "\u092e\u0932\u093e", "\u092e\u093e\u091d\u094d\u092f\u093e",
    "\u0939\u0935\u0947",
)

internal val EN_MARK: Set<String> = setOf(
    "and", "are", "at", "can", "have", "help",
    "is", "my", "near", "need", "no", "not",
    "our", "please", "stuck", "the", "trapped", "urgent",
    "we", "with",
)
