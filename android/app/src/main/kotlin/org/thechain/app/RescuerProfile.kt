package org.thechain.app

import android.content.Context

/** Rescuer card kept in app-private prefs. Never put on a mesh frame. */
data class RescuerProfile(
    val name: String,
    val occupation: String,
    val bloodGroup: String,
    val age: String,
    val sex: String,
    val phone: String,
    val emergencyName: String,
    val emergencyPhone: String,
    val allergies: String,
    val conditions: String,
    /** "device" after a successful system biometric, or "unavailable" when the phone has no sensor. */
    val biometric: String,
)

object RescuerProfileStore {
    private const val PREFS = "resq_profile"

    val occupations = listOf("doctor", "nurse", "paramedic", "firefighter", "police", "volunteer", "other")
    val bloodGroups = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
    val sexes = listOf("female", "male", "other")

    fun load(context: Context): RescuerProfile? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString("name", null)?.trim().orEmpty()
        if (name.isEmpty()) return null
        return RescuerProfile(
            name = name,
            occupation = prefs.getString("occupation", "").orEmpty(),
            bloodGroup = prefs.getString("blood", "").orEmpty(),
            age = prefs.getString("age", "").orEmpty(),
            sex = prefs.getString("sex", "").orEmpty(),
            phone = prefs.getString("phone", "").orEmpty(),
            emergencyName = prefs.getString("emergencyName", "").orEmpty(),
            emergencyPhone = prefs.getString("emergencyPhone", "").orEmpty(),
            allergies = prefs.getString("allergies", "").orEmpty(),
            conditions = prefs.getString("conditions", "").orEmpty(),
            biometric = prefs.getString("biometric", "").orEmpty(),
        )
    }

    fun save(context: Context, profile: RescuerProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", profile.name.trim())
            .putString("occupation", profile.occupation)
            .putString("blood", profile.bloodGroup)
            .putString("age", profile.age.trim())
            .putString("sex", profile.sex)
            .putString("phone", profile.phone.trim())
            .putString("emergencyName", profile.emergencyName.trim())
            .putString("emergencyPhone", profile.emergencyPhone.trim())
            .putString("allergies", profile.allergies.trim().ifEmpty { "None" })
            .putString("conditions", profile.conditions.trim().ifEmpty { "None" })
            .putString("biometric", profile.biometric)
            .apply()
    }
}
