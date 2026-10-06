package me.phh.sip

enum class CallerPresentation { ALLOWED, RESTRICTED, UNKNOWN }
data class CallerIdentity(val number: String, val presentation: CallerPresentation)

fun incomingCallerIdentity(from: String?, privacy: List<String>): CallerIdentity {
    val private = privacy.flatMap { it.lowercase().split(';', ',') }
        .any { it.trim() in setOf("id", "user", "header") }
    val uri = from?.let { Regex("(?i)(?:sips?|tel):([^\\s<>]+)").find(it)?.groupValues?.get(1) }
    val user = uri?.substringBefore('@')?.substringBefore(';')?.substringBefore('?')
    if (private || user.equals("anonymous", ignoreCase = true) ||
        uri?.substringAfter('@', "")?.substringBefore(';').equals("anonymous.invalid", ignoreCase = true)) {
        return CallerIdentity("", CallerPresentation.RESTRICTED)
    }
    val number = user?.replace(Regex("[-.()]"), "")
    return if (number != null && number.matches(Regex("\\+?[0-9]+"))) {
        CallerIdentity(number, CallerPresentation.ALLOWED)
    } else CallerIdentity("", CallerPresentation.UNKNOWN)
}
