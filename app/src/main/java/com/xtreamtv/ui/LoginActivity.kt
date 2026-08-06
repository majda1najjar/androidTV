package com.xtreamtv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.xtreamtv.App
import com.xtreamtv.R
import com.xtreamtv.data.XtreamApiClient
import kotlinx.coroutines.launch

class LoginActivity : FragmentActivity() {

    private lateinit var urlInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var connectButton: Button
    private lateinit var errorText: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        urlInput = findViewById(R.id.input_url)
        usernameInput = findViewById(R.id.input_username)
        passwordInput = findViewById(R.id.input_password)
        connectButton = findViewById(R.id.button_connect)
        errorText = findViewById(R.id.login_error)
        progress = findViewById(R.id.login_progress)

        connectButton.setOnClickListener { attemptLogin() }
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else {
                false
            }
        }

        urlInput.requestFocus()
    }

    private fun attemptLogin() {
        // The IME Done action can fire while a request is in flight even though
        // the Connect button is disabled — don't launch concurrent logins.
        if (!connectButton.isEnabled) return

        val url = urlInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (url.isBlank() || username.isBlank() || password.isBlank()) {
            showError(getString(R.string.login_error_missing_fields))
            return
        }

        setBusy(true)
        val app = App.from(this)
        lifecycleScope.launch {
            try {
                val response = app.api.authenticate(url, username, password)
                val userInfo = response.userInfo
                if (userInfo?.auth == 1) {
                    app.session.save(XtreamApiClient.normalizeBaseUrl(url), username, password)
                    goToDashboard()
                } else {
                    val status = userInfo?.status
                    showError(
                        if (status != null && !status.equals("Active", ignoreCase = true)) {
                            getString(R.string.login_error_account_status, status)
                        } else {
                            getString(R.string.login_error_invalid_credentials)
                        }
                    )
                    setBusy(false)
                }
            } catch (e: Exception) {
                showError(getString(R.string.login_error_connection, e.message ?: "unknown error"))
                setBusy(false)
            }
        }
    }

    private fun goToDashboard() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun setBusy(busy: Boolean) {
        connectButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) errorText.visibility = View.INVISIBLE
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }
}
