package com.cabfusion.app.ui

import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.data.model.Role
import com.cabfusion.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var b: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvDemo.visibility = if (ServiceLocator.backend.isDemo) View.VISIBLE else View.GONE
        b.tvRegister.setOnClickListener { go<RegisterActivity>() }
        b.btnLogin.setOnClickListener { submit() }
    }

    private fun submit() {
        val email = b.etEmail.text.toString().trim()
        val pw = b.etPassword.text.toString()
        val err = when {
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Enter a valid email address"
            pw.length < 6 -> "Password must be at least 6 characters"
            else -> null
        }
        if (err != null) { showError(err); return }
        busy(true)
        lifecycleScope.launch {
            try {
                val u = ServiceLocator.backend.login(email, pw)
                if (u.role == Role.DRIVER) go<DriverActivity>(clearTask = true) else go<DashboardActivity>(clearTask = true)
                finish()
            } catch (e: Exception) {
                showError(e.message ?: "Sign-in failed"); busy(false)
            }
        }
    }

    private fun showError(m: String) { b.tvError.text = m; b.tvError.visibility = View.VISIBLE }
    private fun busy(on: Boolean) {
        b.btnLogin.isEnabled = !on
        b.progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) b.tvError.visibility = View.GONE
    }
}
