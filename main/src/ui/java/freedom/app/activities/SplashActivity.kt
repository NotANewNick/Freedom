/*
 * Copyright (c) 2012-2025 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package freedom.app.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import freedom.app.R
import freedom.app.databinding.ActivitySplashBinding
import freedom.app.security.PasskeySession

class SplashActivity : BaseActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun initViewBinding() {
        binding = ActivitySplashBinding.inflate(layoutInflater)
        val view = binding.root
        supportActionBar?.hide() // Hide Action Bar
        setContentView(view)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
////        if (!SharedPreferenceUtil.isLoggedIn)
//            navigateToLoginScreen()
////        else
        navigateToMainScreen()

        val sharedPreferences = getSharedPreferences("MapsPrefs", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.clear() // Clear all preferences
        editor.apply() // Apply changes

    }

    override fun observeViewModel() {
    }

    private fun navigateToMainScreen() {
        Handler().postDelayed({
            val next = when {
                PasskeySession.isUnlocked             -> MainActivity::class.java
                PasskeySession.isPasskeySet(this)     -> PasskeyPromptActivity::class.java
                else                                  -> PasskeySetupActivity::class.java
            }
            startActivity(Intent(this, next))
            finish()
        }, 1500)
    }

}