package io.github.matteopi.budgeting.widget.food

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.core.nutrition.Batch
import io.github.matteopi.budgeting.core.nutrition.Batches
import io.github.matteopi.budgeting.core.nutrition.Intake
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.ui.food.diary.DiaryMapping
import io.github.matteopi.budgeting.ui.food.log.QuickLogActivity
import io.github.matteopi.budgeting.widget.MessageContent
import io.github.matteopi.budgeting.widget.WidgetTheme
import kotlinx.coroutines.flow.first
import java.util.Locale

private val SMALL_SIZE = DpSize(110.dp, 48.dp)
private val WIDE_SIZE = DpSize(250.dp, 110.dp)
private val TALL_SIZE = DpSize(250.dp, 200.dp)

/** At least this wide and tall, the widget adds protein and the first batch; taller, up to three batches. */
private val WIDE_WIDTH: Dp = 180.dp
private val WIDE_HEIGHT: Dp = 90.dp
private val TALL_HEIGHT: Dp = 180.dp

/** One batch the widget offers. */
data class WidgetBatch(val id: Long, val name: String, val detail: String)

/** What the Food widget shows (docs/PLAN.md §14.6), worked out from today's rows and the kitchen. */
data class FoodWidgetState(
    val kcal: Double,
    val kcalTarget: Double,
    val protein: Double,
    val proteinTarget: Double,
    val batches: List<WidgetBatch>,
) {
    /** "1,240 of 2,000 kcal". */
    val kcalLine: String get() = "${grouped(kcal)} of ${grouped(kcalTarget)} kcal"
    val proteinLine: String get() = "Protein ${Math.round(protein)} of ${Math.round(proteinTarget)} g"

    companion object {
        private fun grouped(value: Double): String = String.format(Locale.UK, "%,d", Math.round(value))

        fun of(today: List<LogItem>, batches: List<Batch>, kcalTarget: Double, proteinTarget: Double): FoodWidgetState {
            val total = Intake.sum(today.map { it.intake })
            return FoodWidgetState(
                kcal = total.kcal,
                kcalTarget = kcalTarget,
                protein = total.protein,
                proteinTarget = proteinTarget,
                batches = Batches.ready(batches).take(3).map { WidgetBatch(it.id, it.name, DiaryMapping.leftText(it)) },
            )
        }
    }
}

/**
 * The Food widget (docs/PLAN.md §14.6): small shows today's kcal against the target and *+*; wide adds protein and
 * the first batch of the kitchen with *Ate one*; tall up to three batches. *Ate one* logs a portion with the sides last
 * eaten with it and says so in a Toast; a batch's name opens the quick log pop-up on it, *+* on *Log food*.
 */
class FoodWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, WIDE_SIZE, TALL_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as BudgetApp).container
        val appSettings = container.settingsStore.appSettings.first()
        val state = runCatching {
            val prefs = container.settingsStore.foodPrefs.first()
            FoodWidgetState.of(
                today = container.diaryRepository.observeDay(container.clock.today()).first(),
                batches = container.diaryRepository.batches(),
                kcalTarget = prefs.targets.kcal,
                proteinTarget = prefs.targets.protein,
            )
        }.getOrNull()
        provideContent {
            WidgetTheme(theme = appSettings.theme, darkMode = appSettings.darkMode) {
                Box(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background)) {
                    if (state == null) MessageContent(text = "Open the diary") else FoodWidgetContent(state, context)
                }
            }
        }
    }

    companion object {
        /** Redraws every placed Food widget; nothing to do (and no error) when none is placed. */
        suspend fun refresh(context: Context) {
            runCatching { FoodWidget().updateAll(context) }
        }

        fun openDiary(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_DIARY
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }
}

@Composable
internal fun FoodWidgetContent(state: FoodWidgetState, context: Context) {
    val size = LocalSize.current
    val wide = size.width >= WIDE_WIDTH && size.height >= WIDE_HEIGHT
    val tall = wide && size.height >= TALL_HEIGHT
    val text = GlanceTheme.colors.onBackground
    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(FoodWidget.openDiary(context)))) {
                Text(text = state.kcalLine, style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                if (wide) Text(text = state.proteinLine, style = TextStyle(color = text, fontSize = 12.sp), maxLines = 1)
            }
            Box(
                modifier = GlanceModifier
                    .width(36.dp)
                    .height(36.dp)
                    .background(GlanceTheme.colors.primary)
                    .clickable(actionStartActivity(QuickLogActivity.intent(context))),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+", style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }
        }
        if (wide) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            val shown = if (tall) state.batches else state.batches.take(1)
            shown.forEach { batch ->
                Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(QuickLogActivity.intent(context, batch.id)))) {
                        Text(text = batch.name, style = TextStyle(color = text, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                        Text(text = batch.detail, style = TextStyle(color = text, fontSize = 10.sp), maxLines = 1)
                    }
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Box(
                        modifier = GlanceModifier
                            .background(GlanceTheme.colors.secondaryContainer)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable(actionRunCallback<AteOneAction>(actionParametersOf(BatchIdKey to batch.id))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "Ate one", style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 12.sp))
                    }
                }
            }
            if (state.batches.isEmpty()) Text(text = "Nothing in the kitchen", style = TextStyle(color = text, fontSize = 12.sp))
        }
    }
}

/** The batch an *Ate one* is for. */
val BatchIdKey = ActionParameters.Key<Long>("food_batch_id")

/** *Ate one* on the widget: a portion of the batch with the sides last eaten with it, then a Toast saying so. */
class AteOneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val batchId = parameters[BatchIdKey] ?: return
        val container = (context.applicationContext as BudgetApp).container
        val done = container.foodDiary.ateOne(batchId)
        val message = done?.message ?: "None left to eat"
        Handler(Looper.getMainLooper()).post { Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show() }
        FoodWidget.refresh(context)
    }
}

class FoodWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FoodWidget()
}
