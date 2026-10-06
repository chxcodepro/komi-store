package zed.rainxch.details.presentation.translation

import kotlinx.coroutines.flow.first
import zed.rainxch.core.domain.repository.TweaksRepository

internal suspend fun resolveAutoTranslateTarget(
    tweaksRepository: TweaksRepository,
    fallbackLanguageCode: String,
): String? {
    val enabled =
        runCatching { tweaksRepository.getAutoTranslateEnabled().first() }.getOrDefault(false)
    if (!enabled) return null

    val explicit =
        runCatching { tweaksRepository.getAutoTranslateTargetLang().first() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    val appLanguage =
        runCatching { tweaksRepository.getAppLanguage().first() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    return explicit ?: appLanguage ?: fallbackLanguageCode.takeIf { it.isNotBlank() }
}

internal fun isSameLanguage(sourceLanguageCode: String?, targetLanguageCode: String): Boolean {
    val source = sourceLanguageCode?.primarySubtag() ?: return false
    val target = targetLanguageCode.primarySubtag()
    return source.isNotEmpty() && source == target
}

private fun String.primarySubtag(): String =
    substringBefore('-').substringBefore('_').lowercase()