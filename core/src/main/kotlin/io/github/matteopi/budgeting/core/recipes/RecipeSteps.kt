package io.github.matteopi.budgeting.core.recipes

enum class ParagraphKind {
    PLAIN,

    /** "Ready when: …": how to tell the step is done. */
    CUE,

    /** "If …: …": what to do when it goes wrong. */
    RESCUE,
}

/**
 * One paragraph of a step body. [text] is the paragraph as written; for a cue or rescue, [lead] is
 * the part before the first colon ("Ready when", "If the base goes darker than chestnut brown") and
 * [rest] what follows it. For plain text [lead] is empty and [rest] is the whole paragraph.
 */
data class StepParagraph(
    val kind: ParagraphKind,
    val text: String,
    val lead: String = "",
    val rest: String = text,
)

/** Splits a step body into paragraphs and classifies them (docs/PLAN.md §11.3, "Step bodies"). */
object RecipeSteps {

    private val BLANK_LINE = Regex("""\n[ \t]*\n\s*""")

    /** A rescue's lead is at most this long and holds no sentence end. */
    private const val MAX_RESCUE_LEAD = 120

    /** The paragraphs of [body] as written, split at blank lines, trimmed, empty ones dropped. */
    fun splitParagraphs(body: String): List<String> =
        body.replace("\r\n", "\n").replace('\r', '\n').trim().split(BLANK_LINE).map { it.trim() }.filter { it.isNotEmpty() }

    /** Classifies one paragraph: a cue starts "Ready when:", a rescue "If …:" (up to the first colon), anything else is plain. */
    fun classify(paragraph: String): StepParagraph {
        val text = paragraph.trim()
        val colon = text.indexOf(':')
        if (colon > 0) {
            val lead = text.substring(0, colon).trim()
            val rest = text.substring(colon + 1).trim()
            if (lead.equals("Ready when", ignoreCase = true)) return StepParagraph(ParagraphKind.CUE, text, lead, rest)
            if (lead.startsWith("If ", ignoreCase = true) && lead.length <= MAX_RESCUE_LEAD &&
                lead.none { it == '.' || it == '!' || it == '?' }
            ) {
                return StepParagraph(ParagraphKind.RESCUE, text, lead, rest)
            }
        }
        return StepParagraph(ParagraphKind.PLAIN, text)
    }

    /** The paragraphs of a step [body], classified. */
    fun paragraphs(body: String): List<StepParagraph> = splitParagraphs(body).map { classify(it) }
}
