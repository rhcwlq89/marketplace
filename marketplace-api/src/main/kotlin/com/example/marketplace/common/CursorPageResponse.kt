package com.example.marketplace.common

import java.time.LocalDateTime
import java.util.Base64

data class CursorPageResponse<T>(
    val content: List<T>,
    val nextCursor: String?,
    val hasNext: Boolean,
    val size: Int
) {
    companion object {
        fun <T> of(
            content: List<T>,
            limit: Int,
            cursorExtractor: (T) -> Pair<LocalDateTime, Long>
        ): CursorPageResponse<T> {
            val hasNext = content.size > limit
            val resultContent = if (hasNext) content.dropLast(1) else content

            val nextCursor = if (hasNext && resultContent.isNotEmpty()) {
                val last = resultContent.last()
                val (timestamp, id) = cursorExtractor(last)
                encodeCursor(timestamp, id)
            } else {
                null
            }

            return CursorPageResponse(
                content = resultContent,
                nextCursor = nextCursor,
                hasNext = hasNext,
                size = resultContent.size
            )
        }

        fun encodeCursor(timestamp: LocalDateTime, id: Long): String {
            val cursorString = "${timestamp}:$id"
            return Base64.getEncoder().encodeToString(cursorString.toByteArray())
        }

        fun decodeCursor(cursor: String): Pair<LocalDateTime, Long>? {
            return try {
                val decoded = String(Base64.getDecoder().decode(cursor))
                val parts = decoded.split(":")
                if (parts.size == 2) {
                    val timestamp = LocalDateTime.parse(parts[0])
                    val id = parts[1].toLong()
                    timestamp to id
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
