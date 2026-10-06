package com.cabfusion.app.ui

import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cabfusion.app.R
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.data.model.Role
import com.cabfusion.app.databinding.ActivityRegisterBinding
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {
    private lateinit var b: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.btnRegister.setOnClickListener { submit() }
    }

    /** Returns the first validation problem, or null when the form is valid. */
    private fun validate(name: String, email: String, phone: String, pw: String, confirm: String) = when {
        name.length < 2 -> "Enter your full name"
        !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Enter a valid email address"
        !phone.matches(Regex("[6-9][0-9]{9}")) -> "Enter a valid 10-digit Indian mobile number"
        pw.length < 6 -> "Password must be at least 6 characters"
        pw != confirm -> "Passwords do not match"
        else -> null
    }

    private fun submit() {
        val name = b.etName.text.toString().trim()
        val email = b.etEmail.text.toString().trim()
        val phone = b.etPhone.text.toString().trim()
        val pw = b.etPassword.text.toString()
        val role = if (b.toggleRole.checkedButtonId == R.id.btnDriver) Role.DRIVER else Role.RIDER
        validate(name, email, phone, pw, b.etConfirm.text.toString())?.let { showError(it); return }
        busy(true)
        lifecycleScope.launch {
            try {
                ServiceLocator.backend.register(name, email, phone, pw, role)
                if (role == Role.DRIVER) go<DriverActivity>(clearTask = true) else go<DashboardActivity>(clearTask = true)
                finish()
            } catch (e: Exception) {
                showError(e.message ?: "Could not create the account"); busy(false)
            }
        }
    }

    private fun showError(m: String) { b.tvError.text = m; b.tvError.visibility = View.VISIBLE }
    private fun busy(on: Boolean) {
        b.btnRegister.isEnabled = !on
        b.progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) b.tvError.visibility = View.GONE
    }
}
