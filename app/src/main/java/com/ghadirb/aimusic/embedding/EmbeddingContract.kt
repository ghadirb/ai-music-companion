package com.ghadirb.aimusic.embedding

import com.ghadirb.aimusic.data.local.entity.TrackEntity

/**
 * Privacy boundary for the optional embedding client. The Android app can
 * construct only a metadata document; it never owns a provider API key and
 * it excludes local lyrics and audio files.
 */
enum class EmbeddingModel(val id: String) {
    TEXT_SMALL("text-embedding-3-small"),
    TEXT_LARGE("text-embedding-3-large"),
    GEMINI("gemini-embedding-001")
}

data class EmbeddingDocument(val text: String)

fun TrackEntity.toPrivacySafeEmbeddingDocument(): EmbeddingDocument {
    val fields = listOf(title, artist, album, genre.orEmpty(), moodTag.orEmpty(), bpm?.toString().orEmpty())
    return EmbeddingDocument(fields.filter { it.isNotBlank() && !it.startsWith("Unknown") }.joinToString(" | "))
}

interface AuthenticatedEmbeddingGateway {
    suspend fun embed(document: EmbeddingDocument, model: EmbeddingModel = EmbeddingModel.TEXT_SMALL): FloatArray
}
