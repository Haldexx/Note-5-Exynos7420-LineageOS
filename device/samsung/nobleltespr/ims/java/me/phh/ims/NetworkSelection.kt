package me.phh.ims

sealed class NetworkSelectionRequest {
    object Automatic : NetworkSelectionRequest()

    data class Manual(val plmn: String) : NetworkSelectionRequest()
}

fun parseNetworkSelection(requested: String?): NetworkSelectionRequest? {
    val value = requested?.trim() ?: return null
    if (value.equals("auto", ignoreCase = true) || value.equals("automatic", ignoreCase = true)) {
        return NetworkSelectionRequest.Automatic
    }
    if (!value.matches(Regex("[0-9]{5,6}"))) return null
    return NetworkSelectionRequest.Manual(value)
}
