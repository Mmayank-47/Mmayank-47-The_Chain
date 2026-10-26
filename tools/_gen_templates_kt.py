"""One-shot emitter for org.thechain.ux.Templates. Not part of the runtime."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from m10_ux.templates import LABEL, T


def kstr(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def kmap(d: dict) -> str:
    lines = []
    for key, value in d.items():
        inner = ", ".join(f"{kstr(ik)} to {kstr(iv)}" for ik, iv in value.items())
        lines.append(f"    {kstr(key)} to mapOf(\n        {inner},\n    )")
    return ",\n".join(lines)


text = f"""package org.thechain.ux

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

/** Fixed sentence templates. Every number and name comes from a Facts field. */
val LANGS = setOf("en", "hi", "mr")

val T: Map<String, Map<String, String>> = mapOf(
{kmap(T)},
)

val LABEL: Map<String, Map<String, String>> = mapOf(
{kmap(LABEL)},
)

fun label(lang: String, tag: String): String {{
    if (tag.startsWith("unknown:")) return tag.substring(8).replace("_", " ")
    return LABEL.getValue(lang)[tag] ?: tag.replace("_", " ")
}}

fun pyRoundToInt(x: Double): Int =
    BigDecimal(x.toString()).setScale(0, RoundingMode.HALF_EVEN).intValueExact()

fun fmtDist(lang: String, meters: Double): String {{
    val t = T.getValue(lang)
    if (meters < 1000.0) {{
        val rounded = max(10, pyRoundToInt(meters / 10.0) * 10)
        return "$rounded ${{t.getValue("m")}}"
    }}
    val km = BigDecimal((meters / 1000.0).toString()).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
    return "$km ${{t.getValue("km")}}"
}}
"""

path = ROOT / "android" / "ux" / "src" / "main" / "kotlin" / "org" / "thechain" / "ux" / "Templates.kt"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(text, encoding="utf-8", newline="\n")
print(path)
