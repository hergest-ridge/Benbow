package app.benbow.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.benbow.app.AppServices
import app.benbow.app.RunningTimer
import app.benbow.app.WidgetTracker
import app.benbow.app.runningTimers
import app.benbow.app.stopTimerNow
import app.benbow.app.tickToday
import app.benbow.app.trackersToday
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Trackers on the home screen (ADR-050, B8): today's marks of the first trackers, a check flipped or a count added in
 * one tap without opening the app, the rest opening the trackers; a running timer with «Стоп». Labs builds only: the
 * receiver is turned on at start where trackers show ([enable]).
 */
class TrackerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val services = (context.applicationContext as BenbowApplication).services
        val trackers = services.trackersToday()
        val timers = services.runningTimers().first()
        provideContent { GlanceTheme { Content(trackers, timers) } }
    }

    companion object {
        /** Called when the app leaves the screen and after a tap here: today's marks may have changed. */
        fun refresh(context: Context, services: AppServices) {
            services.scope.launch { TrackerWidget().updateAll(context.applicationContext) }
        }

        /** The widget can be picked only where trackers show (dev and bench builds), never in the owner's app yet. */
        fun enable(context: Context, on: Boolean) {
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, TrackerWidgetReceiver::class.java),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

class TrackerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TrackerWidget()
}

/** A tap on a check or a count: written today, then the widget drawn again. */
class TickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val column = parameters[COLUMN] ?: return
        val services = (context.applicationContext as BenbowApplication).services
        services.tickToday(column)
        TrackerWidget().updateAll(context)
    }
}

/** «Стоп» on a running timer. */
class StopTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val column = parameters[COLUMN] ?: return
        val services = (context.applicationContext as BenbowApplication).services
        services.stopTimerNow(column)
        TimerNotifications.cancel(context, column)
        TrackerWidget().updateAll(context)
    }
}

private val COLUMN = ActionParameters.Key<String>("column")

@Composable
private fun Content(trackers: List<WidgetTracker>, timers: List<RunningTimer>) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    val open = actionStartActivity(BenbowWidget.appIntent(context, ACTION_OPEN_TRACKERS))
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp).background(colors.widgetBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (trackers.isEmpty() && timers.isEmpty()) {
            Text(
                context.getString(R.string.tracker_widget_empty),
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp),
                modifier = GlanceModifier.fillMaxWidth().clickable(open),
            )
            return@Column
        }
        timers.forEach { timer ->
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timer.title,
                    style = TextStyle(color = colors.onSurface, fontSize = 14.sp),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight().clickable(open),
                )
                Text(
                    context.getString(R.string.timer_stop),
                    style = TextStyle(color = colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.cornerRadius(
                        12.dp,
                    ).background(colors.primaryContainer).padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable(actionRunCallback<StopTimerAction>(actionParametersOf(COLUMN to timer.columnId))),
                )
            }
        }
        trackers.forEach { tracker ->
            Text(
                tracker.title,
                style = TextStyle(color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.fillMaxWidth().clickable(open),
            )
            Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                tracker.marks.forEach { mark ->
                    Box(GlanceModifier.defaultWeight().height(40.dp).padding(horizontal = 2.dp)) {
                        Box(
                            GlanceModifier.fillMaxSize().cornerRadius(10.dp)
                                .background(if (mark.marked) colors.primaryContainer else colors.surfaceVariant)
                                .clickable(
                                    if (mark.tickable) actionRunCallback<TickAction>(actionParametersOf(COLUMN to mark.columnId)) else open,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(mark.text, style = TextStyle(color = colors.onSurface, fontSize = 13.sp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

internal const val ACTION_OPEN_TRACKERS = "app.benbow.action.OPEN_TRACKERS"
