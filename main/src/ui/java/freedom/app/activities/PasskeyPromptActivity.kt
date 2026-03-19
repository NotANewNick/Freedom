package freedom.app.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import freedom.app.R
import freedom.app.security.PasskeySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown on every subsequent launch (after the passkey has been set up).
 * The user re-enters their passkey to unlock [PasskeySession] for this session.
 */
class PasskeyPromptActivity : AppCompatActivity() {

    private lateinit var tilPasskey:  TextInputLayout
    private lateinit var etPasskey:   TextInputEditText
    private lateinit var tvError:     TextView
    private lateinit var btnUnlock:   Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passkey_prompt)
        supportActionBar?.hide()

        tilPasskey  = findViewById(R.id.tilPasskey)
        etPasskey   = findViewById(R.id.etPasskey)
        tvError     = findViewById(R.id.tvError)
        btnUnlock   = findViewById(R.id.btnUnlock)
        progressBar = findViewById(R.id.progressBar)

        btnUnlock.setOnClickListener { attemptUnlock() }

        etPasskey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attemptUnlock(); true } else false
        }
    }

    /** Back press is disabled — the user must unlock before proceeding. */
    override fun onBackPressed() { /* intentionally blocked */ }

    private fun attemptUnlock() {
        val passkey = etPasskey.text.toString()
        tvError.visibility = View.GONE
        tilPasskey.error   = null

        if (passkey.isEmpty()) {
            tilPasskey.error = getString(R.string.passkey_error_empty)
            return
        }

        setLoading(true)

        CoroutineScope(Dispatchers.IO).launch {
            val ok = PasskeySession.unlock(applicationContext, passkey)
            withContext(Dispatchers.Main) {
                setLoading(false)
                if (ok) {
                    startActivity(Intent(this@PasskeyPromptActivity, MainActivity::class.java))
                    finish()
                } else {
                    tvError.text       = getString(R.string.passkey_error_wrong)
                    tvError.visibility = View.VISIBLE
                    etPasskey.text?.clear()
                    etPasskey.requestFocus()
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        btnUnlock.isEnabled    = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        etPasskey.isEnabled    = !loading
    }
}
