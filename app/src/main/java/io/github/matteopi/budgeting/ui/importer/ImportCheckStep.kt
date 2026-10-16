@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.matteopi.budgeting.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.importer.AmountMismatch
import io.github.matteopi.budgeting.core.importer.BalanceGap
import io.github.matteopi.budgeting.core.importer.BeforeOpening
import io.github.matteopi.budgeting.core.importer.CheckAnswer
import io.github.matteopi.budgeting.core.importer.ImportQuestion
import io.github.matteopi.budgeting.core.importer.NotOnStatement
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.importer.PossibleDuplicate
import io.github.matteopi.budgeting.core.importer.StatementProblem
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.components.formatDisplayDate

/** Test tags of the check step. */
object CheckTags {
    /** The button that carries on from the check step (disabled until every question has an answer). */
    const val NEXT = "import-check-next"
}

/**
 * The check step of the import wizard (docs/PLAN.md §6.5): "N things to check", then one card per question
 * with both sides where there are two (the bank's row and the app's transaction) and the answers as buttons.
 * Nothing is saved until the import; the balance card updates as the other answers change.
 */
@Composable
internal fun CheckStep(
    state: ImportUiState,
    onAnswer: (String, CheckAnswer) -> Unit,
    onAnswerAllDuplicates: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val check = state.check ?: return
    val questions = check.questions
    val duplicateCount = questions.count { it is PossibleDuplicate }
    val othersAnswered = questions.filter { it !is BalanceGap }.all { check.answers.containsKey(it.id) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(CheckText.heading(questions.size), style = MaterialTheme.typography.titleMedium)
            Text(
                "Nothing is saved until you press Import.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (duplicateCount > 1) {
                item(key = "all-duplicates") {
                    OutlinedButton(onClick = onAnswerAllDuplicates, modifier = Modifier.fillMaxWidth()) {
                        Text(CheckText.ALL_DUPLICATES)
                    }
                }
            }
            items(questions, key = { it.id }) { question ->
                QuestionCard(
                    question = question,
                    answer = check.answers[question.id],
                    state = state,
                    othersAnswered = othersAnswered,
                    onAnswer = { answer -> onAnswer(question.id, answer) },
                )
            }
        }
        Column(modifier = Modifier.padding(16.dp)) {
            if (!check.complete) {
                Text(
                    CheckText.ANSWER_ALL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text("Back") }
                // With no row left to import there is nothing to categorise, so this is where the answers are saved.
                Button(onClick = onNext, enabled = check.complete, modifier = Modifier.testTag(CheckTags.NEXT)) {
                    Text(if (check.plan.importRows.isEmpty()) "Import" else "Next")
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: ImportQuestion,
    answer: CheckAnswer?,
    state: ImportUiState,
    othersAnswered: Boolean,
    onAnswer: (CheckAnswer) -> Unit,
) {
    SectionCard(title = CheckText.title(question)) {
        Spacer(Modifier.height(8.dp))
        when (question) {
            is PossibleDuplicate -> {
                BankSide(state.rows[question.rowIndex])
                Spacer(Modifier.height(8.dp))
                AppSide(question.existing, state)
            }
            is AmountMismatch -> {
                BankSide(state.rows[question.rowIndex])
                Spacer(Modifier.height(8.dp))
                AppSide(question.existing, state)
            }
            is NotOnStatement -> {
                AppSide(question.existing, state)
                if (question.mayNotHaveReachedBank) {
                    Spacer(Modifier.height(4.dp))
                    Note(CheckText.MAY_NOT_HAVE_REACHED_BANK)
                }
                question.transferPartner?.let { partner ->
                    Spacer(Modifier.height(4.dp))
                    Note(CheckText.transferWarning(state.accounts.firstOrNull { it.id == partner.accountId }?.name))
                }
            }
            is StatementProblem -> {
                Text(question.text, style = MaterialTheme.typography.bodyMedium)
                question.rowIndex?.let { index ->
                    state.rows.getOrNull(index)?.let { row ->
                        Spacer(Modifier.height(8.dp))
                        BankSide(row)
                    }
                }
            }
            is BeforeOpening -> Text(CheckText.beforeOpening(question), style = MaterialTheme.typography.bodyMedium)
            is BalanceGap -> {
                Text(CheckText.balanceGap(question), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Note(CheckText.balanceGapHelp(question))
                if (!othersAnswered) {
                    Note(CheckText.SO_FAR)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Choices(question, answer, onAnswer)
    }
}

/** The answers to [question] as selectable buttons; moving an entry opens a list of accounts. */
@Composable
private fun Choices(question: ImportQuestion, answer: CheckAnswer?, onChoose: (CheckAnswer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        question.choices.filter { it !is CheckAnswer.MoveTo }.forEach { choice ->
            AnswerChip(label = CheckText.label(question, choice), selected = answer == choice, onClick = { onChoose(choice) })
        }
        if (question is NotOnStatement && question.moveTargets.isNotEmpty()) {
            MoveToChip(targets = question.moveTargets, answer = answer, onChoose = onChoose)
        }
    }
}

@Composable
private fun AnswerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Move to another account", which opens the list of accounts; once one is picked the button names it. */
@Composable
private fun MoveToChip(targets: List<Account>, answer: CheckAnswer?, onChoose: (CheckAnswer) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val chosen = (answer as? CheckAnswer.MoveTo)?.let { moveTo -> targets.firstOrNull { it.id == moveTo.accountId } }
    Box(modifier = Modifier.fillMaxWidth()) {
        AnswerChip(
            label = if (chosen != null) CheckText.movedTo(chosen.name) else CheckText.MOVE_TO_ANOTHER,
            selected = chosen != null,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            targets.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onChoose(CheckAnswer.MoveTo(account.id))
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The statement's row: date, description, amount. */
@Composable
private fun BankSide(row: ParsedRow) {
    Side(heading = "On your statement", title = row.description, detail = formatDisplayDate(row.date), amount = row.amount)
}

/** The app's transaction: date, label, category, amount. */
@Composable
private fun AppSide(tx: Transaction, state: ImportUiState) {
    val category = if (tx.kind == TxKind.TRANSFER) {
        "Move between accounts"
    } else {
        state.categories.firstOrNull { it.id == tx.categoryId }?.name
    }
    Side(
        heading = "In the app",
        title = tx.label.ifBlank { category ?: "No label" },
        detail = formatDisplayDate(tx.date) + (category?.let { " · $it" } ?: ""),
        amount = tx.amount,
    )
}

@Composable
private fun Side(heading: String, title: String, detail: String, amount: Money) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(heading, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoneyText(amount = amount)
        }
    }
}
