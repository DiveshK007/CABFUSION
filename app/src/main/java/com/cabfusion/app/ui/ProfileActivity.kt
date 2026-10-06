package com.cabfusion.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.data.model.Role
import com.cabfusion.app.data.model.Status
import com.cabfusion.app.databinding.ActivityProfileBinding
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {
    private lateinit var b: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        val backend = ServiceLocator.backend
        val u = backend.currentUser() ?: run { go<LoginActivity>(clearTask = true); finish(); return }
        b.tvAvatar.text = Fmt.initial(u.name)
        b.tvName.text = u.name
        b.tvEmail.text = u.email
        b.tvPhone.text = u.phone.ifBlank { "—" }
        b.tvRole.text = if (u.role == Role.DRIVER) "DRIVER" else "RIDER"
        b.tvBackend.text = if (backend.isDemo) "On-device demo backend" else "Firebase (Auth + Cloud Firestore)"
        b.btnSwitchRole.text = if (u.role == Role.DRIVER) "Switch to rider mode" else "Switch to driver mode"
        b.btnSwitchRole.setOnClickListener {
            lifecycleScope.launch {
                val newRole = if (u.role == Role.DRIVER) Role.RIDER else Role.DRIVER
                runCatching { backend.updateRole(newRole) }
                    .onSuccess { if (newRole == Role.DRIVER) go<DriverActivity>(clearTask = true) else go<DashboardActivity>(clearTask = true); finish() }
                    .onFailure { toast(it.message ?: "Could not switch role") }
            }
        }
        b.btnLogout.setOnClickListener { backend.logout(); go<LoginActivity>(clearTask = true); finish() }
        lifecycleScope.launch {
            val rides = runCatching { backend.myRequests(u.uid) }.getOrDefault(emptyList()).filter { it.status == Status.COMPLETED }
            b.tvRides.text = rides.size.toString()
            b.tvSaved.text = Fmt.rs(rides.sumOf { it.saving })
        }
    }
}
