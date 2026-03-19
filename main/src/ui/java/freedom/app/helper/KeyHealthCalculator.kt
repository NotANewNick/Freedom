package freedom.app.helper

import android.content.Context
import freedom.app.data.entity.ContactData
import freedom.app.data.entity.activeSendKey
import freedom.app.data.entity.activeRecvKey
import freedom.app.data.entity.activeSendMsgCount

/**
 * Estimates how healthy a contact's keys are based on message count vs rotation threshold.
 *
 * Thresholds (fraction of rotation threshold used):
 *   < 60 %   → GREEN   (plenty of messages remaining before rotation)
 *   60-90 %  → YELLOW  (approaching rotation)
 *   > 90 %   → RED     (rotation imminent or overdue)
 *
 * Health = min(sendKeyHealth, recvKeyHealth)
 * If either key is missing → RED.
 */
enum class KeyHealth { GREEN, YELLOW, RED }

object KeyHealthCalculator {

    fun compute(contact: ContactData, context: Context): KeyHealth {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val threshold = prefs.getInt("key_rotation_threshold", FreedomCrypto.DEFAULT_ROTATION_THRESHOLD)

        val sendHealth = computeDirectionHealth(contact.activeSendKey, contact.activeSendMsgCount, threshold)
        val recvHealth = computeDirectionHealth(contact.activeRecvKey, 0, threshold)  // recv count not tracked locally

        // Return the worse of the two
        return when {
            sendHealth == KeyHealth.RED || recvHealth == KeyHealth.RED       -> KeyHealth.RED
            sendHealth == KeyHealth.YELLOW || recvHealth == KeyHealth.YELLOW -> KeyHealth.YELLOW
            else -> KeyHealth.GREEN
        }
    }

    private fun computeDirectionHealth(key: String, msgCount: Int, threshold: Int): KeyHealth {
        if (key.isEmpty()) return KeyHealth.RED
        if (threshold <= 0) return KeyHealth.GREEN

        val fraction = msgCount.toDouble() / threshold
        return when {
            fraction >= 0.90 -> KeyHealth.RED
            fraction >= 0.60 -> KeyHealth.YELLOW
            else             -> KeyHealth.GREEN
        }
    }
}
