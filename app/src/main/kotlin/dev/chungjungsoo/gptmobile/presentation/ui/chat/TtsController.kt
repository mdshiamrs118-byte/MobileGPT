package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.chungjungsoo.gptmobile.R

/**
 * Thin wrapper around Android's [TextToSpeech] using the device's default engine, voice and language.
 *
 * The engine is created lazily on the first request, only one reply is spoken at a time, and
 * [speakingKey] (Compose state) tells the UI which reply is currently being read.
 */
class TtsController(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pending: Pair<String, String>? = null

    // Incremented on every stop/new request so callbacks from old utterances are ignored.
    private var generation = 0

    /** Key of the reply currently being spoken (or about to be), or null when idle. */
    var speakingKey by mutableStateOf<String?>(null)
        private set

    /** Starts reading [markdown] aloud under [key]; tapping the same [key] again stops playback. */
    fun toggle(key: String, markdown: String) {
        if (speakingKey == key) {
            stop()
            return
        }
        speak(key, markdown)
    }

    fun stop() {
        generation++
        pending = null
        tts?.stop()
        speakingKey = null
    }

    fun shutdown() {
        generation++
        pending = null
        speakingKey = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    private fun speak(key: String, markdown: String) {
        val text = markdownToSpeechText(markdown)
        if (text.isBlank()) return

        generation++
        speakingKey = key

        if (tts == null) {
            pending = key to text
            tts = TextToSpeech(appContext) { status -> mainHandler.post { onEngineInit(status) } }
            return
        }
        if (!isReady) {
            pending = key to text
            return
        }
        start(text)
    }

    private fun onEngineInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            engine?.shutdown()
            tts = null
            isReady = false
            pending = null
            speakingKey = null
            Toast.makeText(appContext, R.string.tts_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finish(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId)

                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            }
        )
        isReady = true

        pending?.let { (_, text) ->
            pending = null
            if (speakingKey != null) start(text)
        }
    }

    private fun start(text: String) {
        val engine = tts ?: return
        val maxLength = minOf(TextToSpeech.getMaxSpeechInputLength() - 100, MAX_CHUNK_LENGTH).coerceAtLeast(500)
        val chunks = splitForSpeech(text, maxLength)
        if (chunks.isEmpty()) {
            speakingKey = null
            return
        }

        val currentGeneration = generation
        chunks.forEachIndexed { index, chunk ->
            val isLast = index == chunks.lastIndex
            val result = engine.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "$currentGeneration|${if (isLast) LAST else MORE}|$index"
            )
            if (result != TextToSpeech.SUCCESS) {
                speakingKey = null
                return
            }
        }
    }

    // Called from the TTS binder thread.
    private fun finish(utteranceId: String?) {
        val parts = utteranceId?.split('|') ?: return
        val utteranceGeneration = parts.getOrNull(0)?.toIntOrNull() ?: return
        val isLast = parts.getOrNull(1) == LAST
        mainHandler.post {
            // Only the last chunk of the current request marks the end of playback.
            if (utteranceGeneration == generation && isLast) {
                speakingKey = null
            }
        }
    }

    private companion object {
        const val MAX_CHUNK_LENGTH = 3500
        const val LAST = "last"
        const val MORE = "more"
    }
}

/** Splits [text] into chunks no longer than [maxLength], preferring paragraph/sentence boundaries. */
internal fun splitForSpeech(text: String, maxLength: Int): List<String> {
    val pieces = text
        .split(Regex("(?<=[.!?。！？])\\s+|\\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            chunks += current.toString()
            current.clear()
        }
    }

    for (piece in pieces) {
        if (piece.length > maxLength) {
            flush()
            piece.chunked(maxLength).forEach { chunks += it }
            continue
        }
        if (current.isNotEmpty() && current.length + 1 + piece.length > maxLength) {
            flush()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(piece)
    }
    flush()
    return chunks
}

/** Strips markdown syntax so the TTS engine doesn't read out symbols like `**`, `#` or URLs. */
internal fun markdownToSpeechText(markdown: String): String {
    var text = markdown

    text = text.replace(Regex("```[\\s\\S]*?(```|$)"), "\n") // fenced code blocks
    text = text.replace(Regex("\\$\\$[\\s\\S]*?\\$\\$"), " ") // display math
    text = text.replace(Regex("\\\\\\[[\\s\\S]*?\\\\\\]"), " ") // display math (\[ ... \])
    text = text.replace(Regex("<[^>]+>"), " ") // html tags
    text = text.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1") // images -> alt text
    text = text.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1") // links -> label
    text = text.replace(Regex("https?://\\S+"), "") // bare urls
    text = text.replace(Regex("(?m)^\\s*(\\|?\\s*:?-{3,}:?\\s*)+\\|?\\s*$"), "") // table separators / hr
    text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "") // headings
    text = text.replace(Regex("(?m)^\\s*>+\\s?"), "") // blockquotes
    text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "") // bullets
    text = text.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2") // bold
    text = text.replace(Regex("\\*(.+?)\\*"), "$1") // italic
    text = text.replace("`", "")
    text = text.replace("*", "")
    text = text.replace("$", "")
    text = text.replace("|", ", ")

    return text
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
}
