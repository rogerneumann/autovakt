package com.rogerneumann.vakt.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rogerneumann.vakt.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 6A: Trip History screen.
 * Shows a Room-backed RecyclerView of all recorded [TripEntity] sessions,
 * formatted by [HistoryViewModel] into human-readable [TripUiModel] cards.
 */
@AndroidEntryPoint
class HistoryActivity : AppCompatActivity() {

    private val viewModel: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build the layout programmatically to stay dependency-free for now.
        val root = buildLayout()
        setContentView(root)

        supportActionBar?.apply {
            title = "Trip History"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ── Layout (inline to avoid adding an extra XML file) ────────────────────

    private fun buildLayout(): View {
        val bg = android.graphics.Color.parseColor("#0A0A0A")

        // ── DTC fault-code section ────────────────────────────────────────────
        val dtcListView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dtcSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1A0000"))
            setPadding(32, 20, 32, 12)
            visibility = View.GONE
            addView(TextView(context).apply {
                text = "⚠  Active Fault Codes"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#FF5252"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 12)
            })
            addView(dtcListView)
        }

        // ── Trip list ─────────────────────────────────────────────────────────
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            setBackgroundColor(bg)
        }
        val emptyView = TextView(this).apply {
            text = "No trips recorded yet.\n\nStart an OBD2 session to begin logging."
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            gravity = android.view.Gravity.CENTER
            setPadding(48, 96, 48, 48)
            visibility = View.GONE
        }
        val tripFrame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(recycler, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(emptyView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(dtcSection)
            addView(tripFrame)
        }

        val adapter = TripAdapter()
        recycler.adapter = adapter

        lifecycleScope.launch {
            viewModel.trips.collectLatest { trips ->
                adapter.submitList(trips)
                emptyView.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility  = if (trips.isEmpty()) View.GONE   else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.dtcs.collectLatest { dtcs ->
                dtcSection.visibility = if (dtcs.isEmpty()) View.GONE else View.VISIBLE
                dtcListView.removeAllViews()
                dtcs.forEach { dtc ->
                    dtcListView.addView(LinearLayout(this@HistoryActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 4, 0, 4)
                        addView(TextView(context).apply {
                            text = dtc.code
                            textSize = 14f
                            setTextColor(android.graphics.Color.parseColor("#FF8A80"))
                            typeface = android.graphics.Typeface.MONOSPACE
                            layoutParams = LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(TextView(context).apply {
                            text = dtc.timestampLabel
                            textSize = 12f
                            setTextColor(android.graphics.Color.parseColor("#666666"))
                            gravity = android.view.Gravity.END
                        })
                    })
                }
            }
        }

        return root
    }
}

// ── RecyclerView Adapter ─────────────────────────────────────────────────────

private class TripAdapter : ListAdapter<TripUiModel, TripAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val card = buildCardView(parent)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val root: View) : RecyclerView.ViewHolder(root) {
        private val tvDate:       TextView = root.findViewWithTag("date")
        private val tvDuration:   TextView = root.findViewWithTag("duration")
        private val tvDistance:   TextView = root.findViewWithTag("distance")
        private val tvEnergy:     TextView = root.findViewWithTag("energy")
        private val tvEfficiency: TextView = root.findViewWithTag("efficiency")
        private val tvSocDelta:   TextView = root.findViewWithTag("soc")
        private val tvActive:     TextView = root.findViewWithTag("active")

        fun bind(item: TripUiModel) {
            tvDate.text       = item.dateLabel
            tvDuration.text   = item.durationLabel
            tvDistance.text   = item.distanceLabel
            tvEnergy.text     = item.energyLabel
            tvEfficiency.text = item.efficiencyLabel
            tvSocDelta.text   = item.socDeltaLabel
            tvActive.visibility = if (item.isActive) View.VISIBLE else View.GONE
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TripUiModel>() {
            override fun areItemsTheSame(a: TripUiModel, b: TripUiModel) = a.id == b.id
            override fun areContentsTheSame(a: TripUiModel, b: TripUiModel) = a == b
        }
    }
}

/**
 * Builds a dark card View for a single trip row.
 * Programmatic to avoid needing a new XML layout file.
 */
private fun buildCardView(parent: ViewGroup): View {
    val ctx = parent.context
    val green  = android.graphics.Color.parseColor("#00E676")
    val white  = android.graphics.Color.WHITE
    val grey   = android.graphics.Color.parseColor("#888888")
    val yellow = android.graphics.Color.parseColor("#FFD600")

    val card = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
        val lp = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(16, 8, 16, 8) }
        layoutParams = lp
        setPadding(24, 20, 24, 20)
    }

    // Row 1: date + ACTIVE badge
    val row1 = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    val tvDate = TextView(ctx).apply {
        tag = "date"; textSize = 13f; setTextColor(grey)
        layoutParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tvActive = TextView(ctx).apply {
        tag = "active"; text = "● ACTIVE"; textSize = 11f
        setTextColor(yellow); textStyle(android.graphics.Typeface.BOLD)
        visibility = View.GONE
    }
    row1.addView(tvDate); row1.addView(tvActive)

    // Row 2: distance + efficiency (hero metrics)
    val row2 = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        setPadding(0, 8, 0, 4)
    }
    val tvDistance = TextView(ctx).apply {
        tag = "distance"; textSize = 28f; setTextColor(white)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        layoutParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tvEfficiency = TextView(ctx).apply {
        tag = "efficiency"; textSize = 20f; setTextColor(green)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
        layoutParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    row2.addView(tvDistance); row2.addView(tvEfficiency)

    // Row 3: energy + SOC delta + duration
    val row3 = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
    }
    val tvEnergy = TextView(ctx).apply {
        tag = "energy"; textSize = 13f; setTextColor(grey)
        layoutParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tvSocDelta = TextView(ctx).apply {
        tag = "soc"; textSize = 13f; setTextColor(grey)
        layoutParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tvDuration = TextView(ctx).apply {
        tag = "duration"; textSize = 13f; setTextColor(grey)
        gravity = android.view.Gravity.END
        layoutParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    row3.addView(tvEnergy); row3.addView(tvSocDelta); row3.addView(tvDuration)

    card.addView(row1); card.addView(row2); card.addView(row3)
    return card
}

/** Helper extension so we can set bold without a style resource. */
private fun TextView.textStyle(style: Int) {
    typeface = android.graphics.Typeface.defaultFromStyle(style)
}
