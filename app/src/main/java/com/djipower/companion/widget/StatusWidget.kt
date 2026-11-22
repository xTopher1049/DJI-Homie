package com.djipower.companion.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.djipower.companion.R
import com.djipower.companion.data.AppSettings
import com.djipower.companion.ui.MainActivity

/** Home-screen widget showing battery %, watts in/out and time-to-empty from the
 *  last snapshot Conn persisted. Live while the app/service is alive; otherwise it
 *  refreshes on its own period or when tapped (which opens the app). */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(context)) }
    }

    companion object {
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun build(context: Context): RemoteViews {
            val s = AppSettings(context)
            val v = RemoteViews(context.packageName, R.layout.dji_widget)
            val batt = s.wBattery
            v.setTextViewText(R.id.w_batt, if (batt >= 0) "$batt%" else "--%")
            v.setTextViewText(R.id.w_flow, "${s.wOut}W out · ${s.wIn}W in")
            val rt = s.wRuntime
            v.setTextViewText(
                R.id.w_rt,
                if (rt > 0) "~${rt / 60}h ${rt % 60}m left" else "",
            )
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.w_root, pi)
            return v
        }
    }
}
