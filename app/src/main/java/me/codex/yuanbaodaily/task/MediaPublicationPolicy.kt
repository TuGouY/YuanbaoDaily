package me.codex.yuanbaodaily.task

internal data class MediaPublicationSnapshot(
    val displayName: String,
    val sizeBytes: Long,
    val pending: Int,
    val id: Long = -1L,
    val mimeType: String = "",
    val relativePath: String = "",
    val dateTaken: Long = 0L,
    val dateAdded: Long = 0L,
)

internal object MediaPublicationVerifier {
    fun isReady(expectedDisplayName: String, snapshot: MediaPublicationSnapshot?): Boolean =
        snapshot != null &&
            snapshot.displayName == expectedDisplayName &&
            snapshot.sizeBytes > 0L &&
            snapshot.pending == 0
}
