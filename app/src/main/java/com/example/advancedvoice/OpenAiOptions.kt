package com.example.advancedvoice

data class OpenAiOptions(
    val effort: String = "low",                 // minimal|low|medium|high
    val verbosity: String = "low",              // low|medium|high
    val filters: Filters? = null,               // Optional domain filters for web_search
    val userLocation: UserLocation? = null      // Optional approximate user location for web_search
) {
    data class Filters(
        val allowedDomains: List<String> = emptyList()
    )

    data class UserLocation(
        val country: String? = null,            // ISO-2 (e.g., "US")
        val city: String? = null,               // Free-text (e.g., "London")
        val region: String? = null,             // Free-text (e.g., "California")
        val timezone: String? = null            // IANA (e.g., "America/Chicago")
    )
}