package com.slothspeak.data

/**
 * One configured OpenAI-compatible provider and its saved API key.
 *
 * The key never leaves memory: it is only used to prefill the provider dialog so
 * re-using the same endpoint does not force the user to paste the key again.
 */
data class SavedProviderKey(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String
)

/**
 * What the LLM provider dialog wants a saved key for.
 *
 * @param baseUrl the base URL currently shown in the dialog ("" for built-in
 *   providers, which have no editable endpoint)
 * @param compatProviderId the compatible provider being edited, if any
 * @param builtinProviderKey the built-in provider being edited, if any
 *   ([ApiKeyManager.LLM_PROVIDER_OPENAI], … — null when adding a new provider)
 */
data class SavedKeyTarget(
    val baseUrl: String = "",
    val compatProviderId: String? = null,
    val builtinProviderKey: String? = null
)

/**
 * Pure, side-effect free lookup of an already saved API key for a provider
 * endpoint. Kept free of SharedPreferences so the matching rules are unit
 * testable; [com.slothspeak.viewmodel.SettingsViewModel] supplies the keys.
 *
 * Precedence:
 * 1. the key of the compatible provider being edited (only while its stored
 *    endpoint is still the one displayed — changing the URL must not carry the
 *    old key over to a different service),
 * 2. the key of the built-in provider being edited,
 * 3. another compatible provider on the same endpoint,
 * 4. a built-in preset on the same endpoint (OpenAI / Together).
 *
 * A non-matching endpoint therefore always yields null, i.e. an empty field.
 */
object SavedApiKeyLookup {

    /** Built-in providers whose endpoint is OpenAI-compatible. */
    data class BuiltinEndpoint(val baseUrl: String, val providerKey: String)

    val BUILTIN_ENDPOINTS: List<BuiltinEndpoint> = listOf(
        BuiltinEndpoint(ApiKeyManager.OPENAI_BASE_URL, ApiKeyManager.LLM_PROVIDER_OPENAI),
        BuiltinEndpoint(ApiKeyManager.TOGETHER_BASE_URL, ApiKeyManager.STT_PROVIDER_TOGETHER)
    )

    /**
     * Canonical form of a base URL used for matching: trimmed, lower-case
     * scheme/host, default port removed, duplicate slashes collapsed and
     * trailing slashes stripped. Missing schemes default to https so
     * `api.example.com/v1` and `https://api.example.com/v1/` match.
     */
    fun normalizeBaseUrl(raw: String): String {
        var rest = raw.trim()
        if (rest.isEmpty()) return ""

        val scheme: String
        val schemeIndex = rest.indexOf("://")
        if (schemeIndex >= 0) {
            scheme = rest.substring(0, schemeIndex).lowercase()
            rest = rest.substring(schemeIndex + 3)
        } else {
            scheme = "https"
        }

        // Query strings and fragments never take part in endpoint identity.
        rest = rest.substringBefore('#').substringBefore('?')

        val slashIndex = rest.indexOf('/')
        val authority = (if (slashIndex >= 0) rest.substring(0, slashIndex) else rest)
            .substringAfterLast('@')
            .lowercase()
        val path = if (slashIndex >= 0) rest.substring(slashIndex) else ""

        val host = stripDefaultPort(scheme, authority)
        val normalizedPath = path.replace(DUPLICATE_SLASHES, "/").trimEnd('/')
        return "$scheme://$host$normalizedPath"
    }

    /**
     * `…/v1` and `…` are the same service for OpenAI-compatible clients, so an
     * alias match is allowed as a fallback — but only for the same host.
     */
    private fun aliasOf(canonical: String): String =
        if (canonical.endsWith("/v1")) canonical.removeSuffix("/v1") else canonical

    fun findSavedKey(
        target: SavedKeyTarget,
        compatKeys: List<SavedProviderKey>,
        builtinKeys: Map<String, String>
    ): String? {
        val ownProvider = target.compatProviderId?.let { id ->
            compatKeys.firstOrNull { it.providerId == id }
        }

        // No endpoint to match against: only the edited provider's own key applies.
        val endpoint = endpointOf(target.baseUrl)
        if (endpoint == null) {
            return ownProvider?.savedKey()
                ?: target.builtinProviderKey?.let { key -> builtinKeys[key]?.takeIf { it.isNotBlank() } }
        }

        // 1. The provider being edited keeps its key as long as its endpoint is unchanged.
        ownProvider?.let { provider ->
            val ownEndpoint = endpointOf(provider.baseUrl)
            if (ownEndpoint != null && matches(endpoint, ownEndpoint)) {
                provider.savedKey()?.let { return it }
            }
        }

        // 2. The built-in provider being edited.
        target.builtinProviderKey
            ?.let { key -> builtinKeys[key]?.takeIf { it.isNotBlank() } }
            ?.let { return it }

        // 3. Another compatible provider on the same endpoint (a second Z.ai entry, …).
        compatKeys.asSequence()
            .filter { it.providerId != target.compatProviderId }
            .filter { it.apiKey.isNotBlank() }
            .firstOrNull { candidate ->
                val candidateEndpoint = endpointOf(candidate.baseUrl) ?: return@firstOrNull false
                matches(endpoint, candidateEndpoint)
            }
            ?.let { return it.apiKey.trim() }

        // 4. A built-in preset on the same endpoint.
        BUILTIN_ENDPOINTS.forEach { preset ->
            val presetEndpoint = endpointOf(preset.baseUrl) ?: return@forEach
            if (matches(endpoint, presetEndpoint)) {
                builtinKeys[preset.providerKey]?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        return null
    }

    private data class Endpoint(val canonical: String, val host: String, val alias: String)

    private fun endpointOf(raw: String): Endpoint? {
        val canonical = normalizeBaseUrl(raw)
        if (canonical.isEmpty()) return null
        val host = canonical.substringAfter("://", "").substringBefore('/')
        return Endpoint(canonical = canonical, host = host, alias = aliasOf(canonical))
    }

    private fun matches(target: Endpoint, candidate: Endpoint): Boolean =
        target.canonical == candidate.canonical ||
            (target.host == candidate.host && target.alias == candidate.alias)

    private fun SavedProviderKey.savedKey(): String? = apiKey.takeIf { it.isNotBlank() }

    private fun stripDefaultPort(scheme: String, authority: String): String {
        val defaultPort = when (scheme) {
            "http" -> ":80"
            "https" -> ":443"
            else -> return authority
        }
        return authority.removeSuffix(defaultPort)
    }

    private val DUPLICATE_SLASHES = Regex("/{2,}")
}
