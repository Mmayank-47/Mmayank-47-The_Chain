package org.thechain.app.oem

import android.os.Build

/**
 * OEM-specific "keep The Chain alive" steps. Aggressive kill lists are keyed off
 * [Build.MANUFACTURER] / [Build.BRAND] (case-insensitive contains).
 */
object OemGuide {
    enum class Family {
        Xiaomi,
        Oppo,
        Vivo,
        OnePlus,
        Huawei,
        Samsung,
        Generic,
    }

    data class Steps(
        val family: Family,
        val titleKey: String,
        /** Ordered UI steps (string resource names resolved by caller). */
        val stepKeys: List<String>,
    )

    fun detect(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Family {
        val blob = "$manufacturer $brand".lowercase()
        return when {
            blob.contains("xiaomi") || blob.contains("redmi") || blob.contains("poco") -> Family.Xiaomi
            blob.contains("oppo") || blob.contains("realme") -> Family.Oppo
            blob.contains("vivo") || blob.contains("iqoo") -> Family.Vivo
            blob.contains("oneplus") -> Family.OnePlus
            blob.contains("huawei") || blob.contains("honor") -> Family.Huawei
            blob.contains("samsung") -> Family.Samsung
            else -> Family.Generic
        }
    }

    fun stepsFor(family: Family = detect()): Steps = when (family) {
        Family.Xiaomi -> Steps(
            family,
            "oem_title_xiaomi",
            listOf(
                "oem_step_xiaomi_1",
                "oem_step_xiaomi_2",
                "oem_step_xiaomi_3",
                "oem_step_xiaomi_4",
            ),
        )
        Family.Oppo -> Steps(
            family,
            "oem_title_oppo",
            listOf("oem_step_oppo_1", "oem_step_oppo_2", "oem_step_oppo_3"),
        )
        Family.Vivo -> Steps(
            family,
            "oem_title_vivo",
            listOf("oem_step_vivo_1", "oem_step_vivo_2", "oem_step_vivo_3"),
        )
        Family.OnePlus -> Steps(
            family,
            "oem_title_oneplus",
            listOf("oem_step_oneplus_1", "oem_step_oneplus_2", "oem_step_oneplus_3"),
        )
        Family.Huawei -> Steps(
            family,
            "oem_title_huawei",
            listOf("oem_step_huawei_1", "oem_step_huawei_2", "oem_step_huawei_3"),
        )
        Family.Samsung -> Steps(
            family,
            "oem_title_samsung",
            listOf("oem_step_samsung_1", "oem_step_samsung_2", "oem_step_samsung_3"),
        )
        Family.Generic -> Steps(
            family,
            "oem_title_generic",
            listOf("oem_step_generic_1", "oem_step_generic_2", "oem_step_generic_3"),
        )
    }

    fun isAggressiveOem(family: Family = detect()): Boolean = family != Family.Generic
}
