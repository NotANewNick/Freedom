package freedom.app.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import freedom.app.R
import freedom.app.data.room.FreedomDatabase
import freedom.app.security.PasskeySession
import freedom.app.security.PasskeyWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-launch passphrase setup.
 *
 * Displays a shuffled chip grid of funny/vulgar words. The user taps words
 * in order to build their passphrase (minimum [MIN_WORDS] words). Tapping a
 * word in the pool adds it to the selected row; tapping a selected word
 * removes it. The resulting space-joined phrase is fed into PBKDF2 exactly
 * like any other passkey string.
 */
class PasskeySetupActivity : AppCompatActivity() {

    private lateinit var cgWords:    ChipGroup
    private lateinit var cgSelected: ChipGroup
    private lateinit var tvWordCount: TextView
    private lateinit var btnCreate:  Button
    private lateinit var progressBar: ProgressBar

    private val selectedWords = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passkey_setup)
        supportActionBar?.hide()

        cgWords    = findViewById(R.id.cgWords)
        cgSelected = findViewById(R.id.cgSelected)
        tvWordCount = findViewById(R.id.tvWordCount)
        btnCreate  = findViewById(R.id.btnCreate)
        progressBar = findViewById(R.id.progressBar)

        populateWordPool()
        updateWordCount()

        btnCreate.setOnClickListener { attemptCreate() }
    }

    private fun populateWordPool() {
        PasskeyWords.shuffled().forEach { word ->
            val chip = Chip(this).apply {
                text = word
                isCheckable = false
                setOnClickListener { onPoolWordTapped(word, this) }
            }
            cgWords.addView(chip)
        }
    }

    private fun onPoolWordTapped(word: String, poolChip: Chip) {
        selectedWords.add(word)
        poolChip.isEnabled = false
        poolChip.alpha = 0.35f

        // Add a removable chip to the selected row
        val selChip = Chip(this).apply {
            text = word
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                selectedWords.remove(word)
                cgSelected.removeView(this)
                poolChip.isEnabled = true
                poolChip.alpha = 1f
                updateWordCount()
            }
        }
        cgSelected.addView(selChip)
        updateWordCount()
    }

    private fun updateWordCount() {
        val n = selectedWords.size
        tvWordCount.text = when {
            n == 0    -> getString(R.string.passkey_words_none)
            n < MIN_WORDS -> getString(R.string.passkey_words_need_more, MIN_WORDS - n)
            else      -> getString(R.string.passkey_words_ready, n)
        }
        tvWordCount.setTextColor(
            if (n >= MIN_WORDS) 0xFF4CAF50.toInt() else 0xFFFFCC00.toInt()
        )
    }

    private fun attemptCreate() {
        if (selectedWords.size < MIN_WORDS) {
            tvWordCount.text = getString(R.string.passkey_words_need_more, MIN_WORDS - selectedWords.size)
            return
        }
        showWriteItDownDialog(selectedWords.toList())
    }

    private fun showWriteItDownDialog(words: List<String>) {
        val phrase = words.joinToString("  ·  ")
        val message = getString(R.string.passkey_write_down_body, phrase)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(getString(R.string.passkey_write_down_title))
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.passkey_write_down_confirm)) { _, _ ->
                commitPassphrase(words.joinToString(" "))
            }
            .setNegativeButton(getString(R.string.passkey_write_down_back), null)
            .show()
    }

    private fun commitPassphrase(passkey: String) {
        setLoading(true)
        CoroutineScope(Dispatchers.IO).launch {
            PasskeySession.setup(applicationContext, passkey)
            encryptExistingContacts()
            withContext(Dispatchers.Main) {
                startActivity(Intent(this@PasskeySetupActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private suspend fun encryptExistingContacts() {
        try {
            fun enc(v: String) = PasskeySession.encryptField(v) ?: v
            val dao      = FreedomDatabase.getDataseClient(applicationContext).contactDao()
            val contacts = dao.getAllOnce()
            for (contact in contacts) {
                dao.insert(
                    contact.copy(
                        sendKey0 = enc(contact.sendKey0),
                        sendKey1 = enc(contact.sendKey1),
                        sendKey2 = enc(contact.sendKey2),
                        recvKey0 = enc(contact.recvKey0),
                        recvKey1 = enc(contact.recvKey1),
                        recvKey2 = enc(contact.recvKey2)
                    )
                )
            }
        } catch (_: Exception) { }
    }

    private fun setLoading(loading: Boolean) {
        btnCreate.isEnabled    = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        cgWords.isEnabled      = !loading
    }

    companion object {
        const val MIN_WORDS = 4
    }
}
