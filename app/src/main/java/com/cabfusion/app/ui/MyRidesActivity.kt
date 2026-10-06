package com.cabfusion.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.data.model.Status
import com.cabfusion.app.databinding.ActivityMyRidesBinding
import kotlinx.coroutines.launch

class MyRidesActivity : AppCompatActivity() {
    private lateinit var b: ActivityMyRidesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMyRidesBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val user = ServiceLocator.backend.currentUser() ?: return@launch
            val rides = runCatching { ServiceLocator.backend.myRequests(user.uid) }.getOrDefault(emptyList())
            val done = rides.filter { it.status == Status.COMPLETED }
            val shared = done.filter { it.saving > 0 }
            b.tvTotalSaved.text = Fmt.rs(done.sumOf { it.saving })
            b.tvSummary.text = "saved across ${shared.size} shared ride${if (shared.size == 1) "" else "s"} · ${done.size} completed"
            b.list.removeAllViews()
            rides.forEach { r ->
                b.list.addView(rideRow(layoutInflater, b.list, r) {
                    go<RideStatusActivity> { putExtra(RideStatusActivity.EXTRA_REQUEST_ID, it.id) }
                })
            }
            b.tvEmpty.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
