package fun7772ei.taptoshare

import android.content.Intent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process one-time token store to distinguish trusted internal Activity launches
 * from untrusted external Intents targeting exported Activities.
 */
object InternalLaunchToken {
    const val EXTRA_INTERNAL_TOKEN = "fun7772ei.taptoshare.extra.INTERNAL_TOKEN"

    private val validTokens = ConcurrentHashMap.newKeySet<String>()

    fun issue(): String {
        val token = UUID.randomUUID().toString()
        validTokens.add(token)
        return token
    }

    fun attach(intent: Intent): Intent = intent.putExtra(EXTRA_INTERNAL_TOKEN, issue())

    fun consume(intent: Intent?): Boolean {
        val token = intent?.getStringExtra(EXTRA_INTERNAL_TOKEN) ?: return false
        return validTokens.remove(token)
    }
}
