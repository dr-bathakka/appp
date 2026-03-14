package com.clukey.os.ui

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.TimeUnit

class AppUsageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0A0E1A.toInt()
        setContentView(buildUI())
    }

    private fun buildUI(): ScrollView {
        val ctx = this
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0xFF0A0E1A.toInt()) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(40))
        }

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
            addView(TextView(ctx).apply {
                text = "←"; textSize = 20f
                setTextColor(0xFF4FC3F7.toInt())
                setPadding(0, 0, dp(14), 0)
                setOnClickListener { finish() }
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = "SCREEN TIME"
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFE2E8F0.toInt())
                    letterSpacing = 0.2f
                })
                addView(TextView(ctx).apply {
                    text = "TODAY'S APP USAGE"
                    textSize = 9f; setTextColor(0xFF4FC3F7.toInt()); letterSpacing = 0.3f
                })
            })
        })

        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        scroll.addView(root)
        loadUsage(listBox)
        return scroll
    }

    private fun loadUsage(box: LinearLayout) {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val startOfDay = now - (now % 86400000L)
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
                ?.filter { it.totalTimeInForeground > 0 }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?.take(20) ?: emptyList()

            if (stats.isEmpty()) {
                box.addView(TextView(this).apply {
                    text = "No usage data.\nGrant Usage Access in Settings → Apps → Special Access."
                    textSize = 13f; setTextColor(0xFF94A3B8.toInt())
                    setPadding(0, dp(20), 0, 0)
                })
                return
            }

            val maxTime = stats.first().totalTimeInForeground

            stats.forEach { stat ->
                val mins = TimeUnit.MILLISECONDS.toMinutes(stat.totalTimeInForeground)
                val appName = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(stat.packageName, 0)).toString()
                } catch (_: Exception) { stat.packageName.split(".").last() }

                val pct = (stat.totalTimeInForeground.toFloat() / maxTime * 100).toInt()

                box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xFF111827.toInt())
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    val p = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                    p.bottomMargin = dp(4); layoutParams = p

                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(context).apply {
                            text = appName
                            textSize = 13f; setTextColor(0xFFCBD5E1.toInt())
                            val lp = LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            layoutParams = lp
                        })
                        addView(TextView(context).apply {
                            text = "${mins}m"
                            textSize = 12f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(
                                if (mins > 120) 0xFFF87171.toInt()
                                else if (mins > 60) 0xFFFBBF24.toInt()
                                else 0xFF4ADE80.toInt()
                            )
                        })
                    })
                    addView(android.view.View(context).apply {
                        val barP = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
                        barP.topMargin = dp(6); layoutParams = barP
                        setBackgroundColor(0xFF1E293B.toInt())
                    })
                    // progress bar
                    addView(LinearLayout(context).apply {
                        val barP = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 0)
                        barP.topMargin = -dp(4); layoutParams = barP
                        val fill = android.view.View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                (resources.displayMetrics.widthPixels * pct / 100), dp(4))
                            setBackgroundColor(
                                if (mins > 120) 0xFFF87171.toInt()
                                else if (mins > 60) 0xFFFBBF24.toInt()
                                else 0xFF3B82F6.toInt()
                            )
                        }
                        addView(fill)
                    })
                })
            }
        } catch (e: Exception) {
            box.addView(TextView(this).apply {
                text = "Error loading usage: ${e.message}"
                textSize = 12f; setTextColor(0xFFF87171.toInt())
            })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
