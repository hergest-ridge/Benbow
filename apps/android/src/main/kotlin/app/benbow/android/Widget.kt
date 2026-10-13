package app.benbow.android

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.benbow.app.AppServices
import app.benbow.app.TodaySummary
import app.benbow.app.todaySummary
import kotlinx.coroutines.launch

/**
 * Home-screen widget in the system's colors: today's date and entries, the question of the day (tap to answer),
 * and one-tap "Заметка" (the capture sheet) and "Запись" (a new entry). Small sizes keep just the two buttons.
 */
class BenbowWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(BUTTONS, FULL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = (context.applicationContext as BenbowApplication).services.todaySummary()
        provideContent { GlanceTheme { Content(today) } }
    }

    companion object {
        private val BUTTONS = DpSize(110.dp, 48.dp)
        private val FULL = DpSize(250.dp, 110.dp)

        /** Called when the app leaves the screen: the count and mood may have changed. The hour tick catches the new day. */
        fun refresh(context: Context, services: AppServices) {
            services.scope.launch { BenbowWidget().updateAll(context.applicationContext) }
        }

        /** Opens the app's existing screen stack (or starts it) and hands it [action]. */
        fun appIntent(context: Context, action: String): Intent = Intent(action).setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

class BenbowWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BenbowWidget()
}

@Composable
private fun Content(today: TodaySummary) {
    val context = LocalContext.current
    val size = LocalSize.current
    val colors = GlanceTheme.colors
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp).background(colors.widgetBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = if (size.height >= 110.dp) Alignment.Top else Alignment.CenterVertically,
        horizontalAlignment = if (size.width >= 250.dp) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (size.width < 250.dp || size.height < 110.dp) {
            Buttons(context)
            return@Column
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Text(today.date, style = TextStyle(color = colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(today.status, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
            }
            Buttons(context)
        }
        Spacer(GlanceModifier.height(8.dp))
        Text(
            today.prompt,
            style = TextStyle(color = colors.onSurface, fontSize = 16.sp),
            maxLines = 3,
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(BenbowWidget.appIntent(context, ACTION_ANSWER_PROMPT))),
        )
    }
}

@Composable
private fun Buttons(context: Context) {
    val colors = GlanceTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(
            imageProvider = ImageProvider(R.drawable.ic_tile_note),
            contentDescription = context.getString(R.string.shortcut_note),
            onClick = actionStartActivity(CaptureActivity.intent(context)),
            backgroundColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        )
        Spacer(GlanceModifier.width(8.dp))
        CircleIconButton(
            imageProvider = ImageProvider(R.drawable.ic_book),
            contentDescription = context.getString(R.string.shortcut_entry),
            onClick = actionStartActivity(BenbowWidget.appIntent(context, Shortcuts.ACTION_NEW_ENTRY)),
            backgroundColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        )
    }
}

internal const val ACTION_ANSWER_PROMPT = "app.benbow.action.ANSWER_PROMPT"
