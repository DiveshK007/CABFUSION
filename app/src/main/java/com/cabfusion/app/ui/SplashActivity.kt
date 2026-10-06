package com.cabfusion.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.data.model.Role
import com.cabfusion.app.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivitySplashBinding.inflate(layoutInflater).root)
        lifecycleScope.launch {
            delay(900)
            val user = runCatching { ServiceLocator.backend.restoreSession() }.getOrNull()
            when {
                user == null -> go<LoginActivity>(clearTask = true)
                user.role == Role.DRIVER -> go<DriverActivity>(clearTask = true)
                else -> go<DashboardActivity>(clearTask = true)
            }
            finish()
        }
    }
}
