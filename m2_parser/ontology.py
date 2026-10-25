"""Open capability ontology: English / Hinglish / Hindi / Marathi surface forms -> capability tag.

Exact phrase match first, then a string-similarity fallback for typos and spelling variants
(`similarity` is a hook: swap in multilingual-embedding cosine on a real phone). Anything that
still does not map is returned as nothing, and the caller keeps it in the unknown bucket.
"""
from __future__ import annotations

import difflib
import re
import unicodedata
from typing import Callable, Dict, List, Optional, Sequence, Tuple

_TOKEN = re.compile(r"[\wऀ-ॣ०-ॿ]+")


def norm(text: str) -> str:
    t = unicodedata.normalize("NFKC", text).lower().replace("़", "")   # fold nukta variants
    return re.sub(r"['’`]", "", t)


def tokenize(text: str) -> List[str]:
    return _TOKEN.findall(norm(text))


CAPS: Dict[str, List[str]] = {
    "medic": ["doctor", "doctors", "dr", "nurse", "nurses", "medic", "paramedic", "daktar", "doctor sahab",
              "first aid", "डॉक्टर", "नर्स", "डाक्टर", "वैद्य"],
    "medicine": ["medicine", "medicines", "meds", "dawai", "dawa", "davai", "dawaiyan", "goli", "aushadh",
                 "tablet", "tablets", "दवा", "दवाई", "दवाइयां", "गोली", "औषध", "औषधे"],
    "insulin": ["insulin", "इंसुलिन"],
    "oxygen": ["oxygen", "o2", "oxygen cylinder", "ऑक्सीजन", "ऑक्सिजन"],
    "dialysis": ["dialysis", "डायलिसिस"],
    "blood": ["blood", "raktdaan", "rakt", "रक्त", "रक्तदान"],
    "boat": ["boat", "boats", "naav", "nav", "dinghy", "raft", "नाव", "नौका", "होडी"],
    "lifejacket": ["life jacket", "lifejacket", "life vest", "लाइफ जैकेट"],
    "rope": ["rope", "rassi", "रस्सी", "दोर", "दोरी"],
    "stretcher": ["stretcher", "स्ट्रेचर"],
    "water": ["water", "paani", "pani", "paanee", "पानी", "पाणी"],
    "food": ["food", "khana", "khaana", "ration", "raashan", "jevan", "bhojan", "खाना", "भोजन", "जेवण", "राशन", "अन्न"],
    "shelter": ["shelter", "tent", "tents", "aashray", "nivara", "आश्रय", "निवारा", "तंबू"],
    "vehicle": ["vehicle", "car", "truck", "tempo", "jeep", "gaadi", "gadi", "ambulance", "गाड़ी", "गाडी", "वाहन", "एम्बुलेंस"],
    "fuel": ["fuel", "petrol", "diesel", "पेट्रोल", "डीजल"],
    "power": ["generator", "powerbank", "power bank", "charger", "bijli", "बिजली", "जनरेटर", "चार्जर"],
}


class Ontology:
    def __init__(self, extra: Optional[Dict[str, List[str]]] = None, fuzzy: float = 0.82,
                 similarity: Optional[Callable[[str, str], float]] = None):
        self.fuzzy = fuzzy
        self.similarity = similarity or (lambda a, b: difflib.SequenceMatcher(None, a, b).ratio())
        self._phrases: Dict[Tuple[str, ...], str] = {}
        self._singles: List[Tuple[str, str]] = []
        self._max = 1
        for tag, forms in {**CAPS, **(extra or {})}.items():
            for f in forms:
                toks = tuple(tokenize(f))
                if not toks:
                    continue
                self._phrases[toks] = tag
                self._max = max(self._max, len(toks))
                if len(toks) == 1:
                    self._singles.append((toks[0], tag))
        self._tags = frozenset(self._phrases.values())

    @property
    def tags(self):
        return self._tags

    def match_at(self, toks: Sequence[str], i: int) -> Optional[Tuple[str, int]]:
        """Longest exact phrase starting at token i -> (tag, length)."""
        for n in range(min(self._max, len(toks) - i), 0, -1):
            tag = self._phrases.get(tuple(toks[i:i + n]))
            if tag:
                return tag, n
        return None

    def fuzzy_tag(self, tok: str) -> Optional[str]:
        if len(tok) < 5 or tok.isdigit():
            return None
        best, tag = 0.0, None
        for form, t in self._singles:
            if form[0] != tok[0] or abs(len(form) - len(tok)) > 2:
                continue
            s = self.similarity(tok, form)
            if s > best:
                best, tag = s, t
        return tag if best >= self.fuzzy else None

    def find(self, toks: Sequence[str]) -> List[Tuple[str, int, int]]:
        """Non-overlapping mentions as (tag, start, end_exclusive)."""
        out, i = [], 0
        while i < len(toks):
            m = self.match_at(toks, i)
            if m:
                out.append((m[0], i, i + m[1]))
                i += m[1]
                continue
            t = self.fuzzy_tag(toks[i])
            if t:
                out.append((t, i, i + 1))
            i += 1
        return out

    def map_phrase(self, phrase: str) -> Optional[str]:
        """Map a free-text capability (e.g. from a model) to a tag, or None."""
        toks = tokenize(phrase)
        for tag, _, _ in self.find(toks):
            return tag
        return None
