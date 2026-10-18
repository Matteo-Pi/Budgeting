package io.github.matteopi.budgeting.core.recipes

import java.util.Locale

/** One ingredient line of a [RecipeDraft]: the text as written, its group heading, and whether it says "optional". */
data class DraftLine(
    val group: String? = null,
    val text: String,
    val optional: Boolean = false,
)

/**
 * A recipe read from text, before ingredients are matched. Steps, tips, equipment and storage notes
 * share [steps], told apart by their kind. [warnings] lists what could not be understood.
 */
data class RecipeDraft(
    val title: String = "",
    val serves: Int? = null,
    val prepMins: Int? = null,
    val cookMins: Int? = null,
    val style: String? = null,
    val tags: List<String> = emptyList(),
    /** The "About:" line. */
    val tagline: String? = null,
    val servedWith: String? = null,
    val timeNote: String? = null,
    val lines: List<DraftLine> = emptyList(),
    val steps: List<RecipeStep> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val method: List<RecipeStep> get() = steps.filter { it.kind == StepKind.STEP }
    val tips: List<RecipeStep> get() = steps.filter { it.kind == StepKind.TIP }
    val equipment: List<RecipeStep> get() = steps.filter { it.kind == StepKind.EQUIPMENT }
    val storage: List<RecipeStep> get() = steps.filter { it.kind == StepKind.STORAGE }
}

/** The paste format: reading it (`parse`) and writing it (`format`). See docs/PLAN.md §11.4. */
object RecipeText {

    private enum class Section { HEADER, INGREDIENTS, METHOD, TIPS, EQUIPMENT, STORAGE, IGNORED }

    private enum class Field { SERVES, PREP, COOK, TOTAL, STYLE, TAGS, ABOUT, SERVE_WITH, SERVING, TIME }

    private fun numericField(key: String): Field {
        val k = key.lowercase(Locale.ROOT)
        return when {
            k.startsWith("prep") -> Field.PREP
            k.startsWith("cook") -> Field.COOK
            k.startsWith("total") || k.startsWith("ready") -> Field.TOTAL
            else -> Field.SERVES
        }
    }

    private fun textField(key: String): Field {
        val k = key.lowercase(Locale.ROOT)
        return when {
            k == "style" -> Field.STYLE
            k.startsWith("tag") -> Field.TAGS
            k == "about" || k == "description" -> Field.ABOUT
            k.startsWith("serving") -> Field.SERVING
            k.startsWith("serve") -> Field.SERVE_WITH
            else -> Field.TIME
        }
    }

    private sealed interface Heading {
        data class Title(val text: String) : Heading
        data class Known(val section: Section) : Heading
        data class Other(val text: String, val level: Int) : Heading
    }

    private val SECTION_WORDS: Map<String, Section> = buildMap {
        put("ingredients", Section.INGREDIENTS)
        for (w in listOf("method", "steps", "instructions", "directions", "preparation")) put(w, Section.METHOD)
        for (w in listOf("tips", "tip", "tips and tricks", "notes", "cooks notes")) put(w, Section.TIPS)
        for (w in listOf("equipment", "kit", "you will need", "tools")) put(w, Section.EQUIPMENT)
        for (w in listOf("storage", "storing", "freezing and storing", "storage and freezing", "freezing", "to store")) {
            put(w, Section.STORAGE)
        }
    }

    /**
     * How section headings start when a chat writes them (docs/PLAN.md §11.11): "Ingredients (serves 4)", "How to
     * make it", "Tips & variations", "Storing and reheating". Matched on whole words of [sectionKey]. Nutrition and
     * cost sections are skipped.
     */
    private val SECTION_STARTS: List<Pair<Regex, Section>> = listOf(
        Regex("""^(ingredients?|what you need|what youll need|shopping list)\b""") to Section.INGREDIENTS,
        Regex("""^(equipment|kit|tools?|utensils|you will need|youll need)\b""") to Section.EQUIPMENT,
        Regex(
            """^(method|instructions?|steps|directions?|preparation|how to (make|cook)|cooking (method|instructions?)|lets cook)\b""",
        ) to Section.METHOD,
        Regex("""^(tips?|notes?|cooks notes?|chefs (notes?|tips?)|variations?|swaps?|substitutions?|serving suggestions?)\b""") to
            Section.TIPS,
        Regex("""^(storage|storing|to store|freezing|to freeze|leftovers?|make ahead|reheating|to reheat|keeping)\b""") to
            Section.STORAGE,
        Regex("""^(nutrition|nutritional|costs?|price|prices|macros?|allergens?|per (serving|portion))\b""") to Section.IGNORED,
    )

    private val HASHES = Regex("""^(#{1,6})\s*(.*)$""")
    private val NUMBERED = Regex("""^(\d{1,3})[.)]\s+(.*)$""")
    private val STEP_WORD = Regex("""(?i)^step\s+(\d{1,3})\s*[.):]\s*(.*)$""")
    private val STEP_START = Regex("""^step \d""")
    private val BULLET = Regex("""^[-*•–]\s+(.*)$""")
    private val OPTIONAL_WORD = Regex("""(?i)\boptional\b""")
    private val OPTIONAL_SUFFIX = Regex("""(?i)\s*\(\s*optional\s*\)\s*$""")
    private val TIME_WORDS = Regex("""(?i)\b(overnight|secs?|seconds?|mins?|minutes?|hrs?|hours?|days?)\b""")
    private val TIME_PART = Regex(
        """(?i)(\d+(?:\.\d+)?\s?[½¼¾⅓⅔]?|[½¼¾⅓⅔])(?:\s*[-–]\s*\d+(?:\.\d+)?)?\s*(hours?|hrs?|h|minutes?|mins?|m)(?![a-z])""",
    )
    private val FRACTION_GLYPHS = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3)
    private val BOLD = Regex("""\*\*|__""")

    /** A line that is all bold, perhaps with a bracket or a colon after: `**For the sauce:**`, `**Ingredients** (serves 4)`. */
    private val WHOLE_BOLD = Regex("""^(?:\*\*|__)([^*_]+)(?:\*\*|__)\s*(?:\([^)]*\))?\s*:?\s*$""")

    /** A bold lead and the rest: `**Brown the mince.** Oil in the pot…`. */
    private val BOLD_LEAD = Regex("""^\*\*([^*]+)\*\*(.*)$""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val CHECKBOX = Regex("""^\[[ xX]]\s+""")
    private val ITALIC_STAR = Regex("""(?<![\w*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![\w*])""")
    private val ITALIC_UNDERSCORE = Regex("""(?<![\w_])_(?=\S)([^_\n]+?)(?<=\S)_(?![\w_])""")
    private val RULE = Regex("""^(?:-{3,}|\*{3,}|_{3,}|—{2,})$""")
    private val TABLE_RULE = Regex("""^:?-{2,}:?$""")
    private val TABLE_HEADERS = setOf(
        "ingredient", "ingredients", "amount", "quantity", "qty", "item", "items", "notes", "note", "weight", "measure",
        "how much", "what", "prep", "use",
    )
    private val LABEL_VALUE = Regex("""^[^:]{1,30}:\s*\S""")
    private val NON_WORD = Regex("""[^a-z0-9 ]+""")
    private val SPACES = Regex("""\s+""")

    /** The opening of a chat answer, before the recipe itself: "Here's…", "Sure!", "Absolutely —" (on [Quantities.fold]). */
    private val OPENER = Regex(
        """^((sure|absolutely|of course|certainly|okay|ok|great|perfect|yes)\s*[!,.:—–-]|""" +
            """(heres|here is|here are|below is|happy to|ive put|ive written|i have put|i have written)\b)""",
    )

    /** The end of a chat answer: "Enjoy!", "Let me know if…". */
    private val CLOSER = Regex(
        """^(enjoy|let me know|would you like|want me to|i can also|if youd like|happy cooking|bon appetit|hope you|""" +
            """feel free|do you want|shall i)\b""",
    )
    private val GROUP_HEADING = Regex("""(?i)^(for|to)\s""")
    private val SERVES_IN = Regex("""(?i)\b(?:serves|for|makes)\s+(\d{1,2})\b|\b(\d{1,2})\s+(?:servings|portions|people)\b""")
    private val PINCH_START = Regex("""(?i)^(a\s+)?pinch\b""")
    private val HOURS_MINUTES = Regex("""(?i)^\s*(\d+)\s*h(?:r|rs|ours?)?\s*(\d+)\s*m?\s*$""")
    private val TITLE_AND_TIME = Regex("""^(.*?)\s*\(([^()]*)\)$""")
    private val NUMERIC_KEY = Regex(
        """(?i)^(serves|servings?|portions?|makes|yields?|feeds|prep(?:aration)?(?:\s+time)?|cook(?:ing)?(?:\s+time)?|total(?:\s+time)?|ready\s+in)\s*:?\s*(\d.*|[½¼¾⅓⅔].*)$""",
    )
    private val TEXT_KEY = Regex(
        """(?i)^(style|tags?|about|description|serves?\s+with|served\s+with|serving(?:\s+suggestion|\s+note)?|time)\s*:\s*(.*)$""",
    )

    /** The longest title a step may have before its ": ". */
    private const val MAX_STEP_TITLE = 60

    /** The most words a section heading may have ("Storing and reheating the leftovers" has five). */
    private const val MAX_HEADING_WORDS = 6

    // ---- parse -----------------------------------------------------------------------------

    /**
     * Reads the paste format, and recipes as a chat writes them in Markdown (docs/PLAN.md §11.11). Headings are
     * optional: without any, lines starting with an amount are ingredients, numbered lines are steps and the first
     * line is the title.
     */
    fun parse(text: String): RecipeDraft {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val parser = Parser(raw.any { headingOf(it.trim()) is Heading.Known })
        for (line in raw) parser.feed(line)
        return parser.finish()
    }

    /**
     * True when [text] looks like a recipe rather than a receipt: it has an "Ingredients" heading line
     * (case-insensitive, `#` or bold optional) and either a numbered line or a Method / Steps / Instructions heading.
     */
    fun looksLikeRecipe(text: String): Boolean {
        val lines = text.lines().map { it.trim() }
        val headings = lines.map { headingOf(it) }
        val hasIngredients = headings.any { it is Heading.Known && it.section == Section.INGREDIENTS }
        if (!hasIngredients) return false
        val hasMethod = headings.any { it is Heading.Known && it.section == Section.METHOD }
        return hasMethod || lines.any { isNumbered(it) || isNumbered(cleanInline(it)) }
    }

    /** [text] without Markdown: bold, italics, code marks, links (their text stays) and a leading check box. */
    internal fun cleanInline(text: String): String {
        var t = LINK.replace(text, "$1")
        t = BOLD.replace(t, "")
        t = ITALIC_STAR.replace(t, "$1")
        t = ITALIC_UNDERSCORE.replace(t, "$1")
        t = t.replace("`", "")
        return CHECKBOX.replace(t, "")
    }

    /** Lower case letters, digits and single spaces: "🥘 Tips & Tricks:" -> "tips tricks". */
    private fun sectionKey(text: String): String = SPACES.replace(NON_WORD.replace(Quantities.fold(text), " "), " ").trim()

    /** The section a heading's text names, exactly or by how it starts; null for any other heading. */
    private fun sectionOf(text: String): Section? {
        SECTION_WORDS[Quantities.fold(text.trim())]?.let { return it }
        val key = sectionKey(text)
        // A long line that happens to start with "Ingredients" or "Tips" is a sentence, not a heading.
        if (key.isEmpty() || STEP_START.containsMatchIn(key) || key.count { it == ' ' } >= MAX_HEADING_WORDS) return null
        SECTION_WORDS[key]?.let { return it }
        return SECTION_STARTS.firstOrNull { (start, _) -> start.containsMatchIn(key) }?.second
    }

    private fun headingOf(line: String): Heading? {
        val hashes = HASHES.matchEntire(line)
        if (hashes != null) {
            val level = hashes.groupValues[1].length
            val text = cleanInline(hashes.groupValues[2]).trim()
            val body = text.trimEnd(':').trim()
            val known = sectionOf(body)
            return when {
                known != null -> Heading.Known(known)
                level == 1 -> Heading.Title(text)
                else -> Heading.Other(body, level)
            }
        }
        WHOLE_BOLD.matchEntire(line)?.let { bold ->
            return sectionOf(cleanInline(bold.groupValues[1]).trim().trimEnd(':'))?.let { Heading.Known(it) }
        }
        val plain = cleanInline(line).trim()
        SECTION_WORDS[Quantities.fold(plain.trimEnd(':').trim())]?.let { return Heading.Known(it) }
        // A short line ending in a colon ("Instructions:", "To store:") is a heading when it names a section.
        if (plain.endsWith(":") && plain.length <= 40 && !startsWithAmount(plain)) {
            sectionOf(plain.dropLast(1))?.let { return Heading.Known(it) }
        }
        return null
    }

    private fun startsWithAmount(line: String): Boolean {
        val first = line.firstOrNull() ?: return false
        if (first in '0'..'9' || first in FRACTION_GLYPHS) return true
        return PINCH_START.containsMatchIn(line)
    }

    private fun stripBullet(line: String): String = BULLET.matchEntire(line)?.groupValues?.get(1)?.trim() ?: line

    private fun isNumbered(line: String): Boolean = NUMBERED.matches(line) || STEP_WORD.matches(line)

    private fun numberedBody(line: String): String =
        NUMBERED.matchEntire(line)?.groupValues?.get(2) ?: STEP_WORD.matchEntire(line)?.groupValues?.get(2) ?: line

    /** The text after a step's number, from the line as written or with its Markdown taken off; null if it isn't numbered. */
    private fun numberedRest(line: String): String? {
        if (isNumbered(line)) return numberedBody(line)
        val clean = cleanInline(line)
        return if (isNumbered(clean)) numberedBody(clean) else null
    }

    /** [text] without emoji and other symbols in front: "🍗 Lemon Chicken" -> "Lemon Chicken". */
    private fun trimDecoration(text: String): String {
        val decoration = setOf(
            Character.OTHER_SYMBOL.toInt(), Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
            Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
        )
        return text.trimStart { it.isWhitespace() || Character.getType(it) in decoration }.ifEmpty { text }
    }

    private fun isOpener(line: String): Boolean = OPENER.containsMatchIn(Quantities.fold(line).trim())

    private fun isCloser(line: String): Boolean = CLOSER.containsMatchIn(sectionKey(line))

    private class StepAcc(val head: String, firstBody: String) {
        val paragraphs = mutableListOf<String>().also { if (firstBody.isNotEmpty()) it += firstBody }
    }

    private class ItemAcc(val kind: StepKind, var text: String)

    private class Parser(val headed: Boolean) {
        private var title: String? = null

        /** The title came from a heading or a bold line, so a later heading doesn't replace it. */
        private var titleStrong = false
        private var serves: Int? = null
        private var prep: Int? = null
        private var cook: Int? = null
        private var style: String? = null
        private var tags: List<String> = emptyList()
        private var about: String? = null
        private var servedWith: String? = null
        private var timeNote: String? = null
        private val lines = mutableListOf<DraftLine>()
        private val steps = mutableListOf<RecipeStep>()
        private val warnings = mutableListOf<String>()

        private var section = Section.HEADER
        private var group: String? = null
        private var step: StepAcc? = null
        private var item: ItemAcc? = null
        private var methodNumbered: Boolean? = null
        private var pendingBlank = false

        fun feed(rawLine: String) {
            val indented = rawLine.isNotEmpty() && (rawLine[0] == ' ' || rawLine[0] == '\t')
            val line = rawLine.trim()
            if (line.isEmpty()) {
                pendingBlank = true
                return
            }
            val blankBefore = pendingBlank
            pendingBlank = false

            // A rule (---) ends the section it is in: what follows in a chat is usually its sign-off.
            if (RULE.matches(line)) {
                closeOpen()
                if (headed && section != Section.HEADER) section = Section.IGNORED
                return
            }

            when (val heading = headingOf(line)) {
                is Heading.Title -> {
                    if (section == Section.HEADER && (title == null || !titleStrong)) {
                        setStrongTitle(heading.text)
                    } else {
                        closeOpen()
                        section = Section.IGNORED
                        warnings += "Ignored the section \"${heading.text}\"."
                    }
                    return
                }
                is Heading.Known -> {
                    closeOpen()
                    // "Ingredients (serves 4)" gives the servings when nothing else has.
                    if (heading.section == Section.INGREDIENTS && serves == null) {
                        SERVES_IN.find(line)?.let { m -> serves = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
                    }
                    section = heading.section
                    group = null
                    methodNumbered = null
                    return
                }
                is Heading.Other -> {
                    when {
                        section == Section.HEADER && (title == null || !titleStrong) -> setStrongTitle(heading.text)
                        section == Section.INGREDIENTS && (heading.level >= 3 || GROUP_HEADING.containsMatchIn(heading.text)) ->
                            group = heading.text.ifEmpty { null }
                        section == Section.METHOD && heading.level >= 3 -> {
                            // "### Step 1: Brown the mince" starts a step; any other sub-heading inside the method is not one.
                            if (isNumbered(heading.text)) {
                                closeStep()
                                methodNumbered = true
                                step = StepAcc(numberedBody(heading.text).trim().trimEnd(':', '.').trim(), "")
                            }
                        }
                        else -> {
                            closeOpen()
                            section = Section.IGNORED
                            warnings += "Ignored the section \"${heading.text}\"."
                        }
                    }
                    return
                }
                null -> Unit
            }

            if (section != Section.HEADER && section != Section.IGNORED && !isNumbered(line) && BULLET.matchEntire(line) == null &&
                isCloser(line)
            ) {
                warnings += "Ignored the line \"${cleanInline(line)}\"."
                return
            }

            when (section) {
                Section.HEADER -> header(line)
                Section.INGREDIENTS -> ingredient(line)
                Section.METHOD -> method(line, blankBefore)
                Section.TIPS -> listItem(StepKind.TIP, line, indented)
                Section.EQUIPMENT -> listItem(StepKind.EQUIPMENT, line, indented)
                Section.STORAGE -> listItem(StepKind.STORAGE, line, indented)
                Section.IGNORED -> Unit
            }
        }

        private fun setStrongTitle(text: String) {
            title?.let { warnings += "Ignored the line \"$it\"." }
            // An emoji in front ("🍗 Lemon Chicken") is decoration, not part of the name.
            title = trimDecoration(text)
            titleStrong = true
        }

        private fun closeOpen() {
            closeStep()
            closeItem()
        }

        private fun closeStep() {
            val acc = step ?: return
            step = null
            val (stepTitle, stepTime) = splitHead(acc.head)
            steps += RecipeStep(StepKind.STEP, stepTitle, stepTime, acc.paragraphs.joinToString("\n\n"))
        }

        private fun closeItem() {
            val acc = item ?: return
            item = null
            if (acc.text.isNotBlank()) steps += RecipeStep(acc.kind, null, null, acc.text.trim())
        }

        private fun header(rawLine: String) {
            val line = cleanInline(rawLine).trim()
            if (applyHeaderLine(line)) return
            if (isOpener(line) || isCloser(line)) {
                warnings += "Ignored the line \"$line\"."
                return
            }
            val bold = WHOLE_BOLD.matches(rawLine)
            if (title == null) {
                if (headed && line.endsWith(":")) {
                    // A chat's lead-in ("Here's what you'll need:") is not the title.
                    warnings += "Ignored the line \"$line\"."
                    return
                }
                title = trimDecoration(line)
                titleStrong = bold
                return
            }
            if (bold && !titleStrong) {
                setStrongTitle(line.trimEnd(':').trim())
                return
            }
            if (!headed) {
                // Headingless: the first amount line starts the ingredients, the first numbered line the method.
                when {
                    isNumbered(rawLine) -> {
                        section = Section.METHOD
                        method(rawLine, false)
                    }
                    startsWithAmount(stripBullet(rawLine)) -> {
                        section = Section.INGREDIENTS
                        ingredient(rawLine)
                    }
                    else -> warnings += "Ignored the line \"$rawLine\"."
                }
                return
            }
            // The sentence after the title describes the dish; a "Label: value" line it doesn't know is skipped.
            if (about == null && !LABEL_VALUE.containsMatchIn(line) && !line.endsWith(":")) {
                about = line
                return
            }
            warnings += "Ignored the line \"$rawLine\"."
        }

        private fun applyHeaderLine(line: String): Boolean {
            val fields = mutableListOf<Pair<Field, String>>()
            for (segment in line.split('·', '|', '•')) {
                // Emoji and other marks in front of a key ("⏱️ Prep: 10 min") are not part of it.
                val s = segment.trim().trimStart { !it.isLetterOrDigit() && it !in FRACTION_GLYPHS }.trim()
                if (s.isEmpty()) continue
                val numeric = NUMERIC_KEY.matchEntire(s)
                val textual = TEXT_KEY.matchEntire(s)
                when {
                    numeric != null -> fields += numericField(numeric.groupValues[1]) to numeric.groupValues[2].trim()
                    textual != null -> fields += textField(textual.groupValues[1]) to textual.groupValues[2].trim()
                    fields.isNotEmpty() -> {
                        val (field, value) = fields.last()
                        fields[fields.lastIndex] = field to "$value · $s"
                    }
                    // A "Label: value" the reader doesn't know ("Difficulty: easy") before the fields is left out.
                    LABEL_VALUE.containsMatchIn(s) -> continue
                    else -> return false
                }
            }
            if (fields.isEmpty()) return false
            for ((field, value) in fields) {
                when (field) {
                    Field.SERVES -> Regex("""\d+""").find(value)?.value?.toIntOrNull()?.let { serves = it }
                    Field.PREP -> parseMinutes(value)?.let { prep = it }
                    Field.COOK -> parseMinutes(value)?.let { cook = it }
                    Field.TOTAL -> Unit
                    Field.STYLE -> style = value.ifEmpty { null }
                    Field.TAGS -> tags = value.split(',').map { it.trim().trimStart('#').trim() }.filter { it.isNotEmpty() }
                    Field.ABOUT -> about = value.ifEmpty { null }
                    Field.SERVE_WITH -> servedWith = if (value.startsWith("served with", true) || value.startsWith("serve with", true)) {
                        value
                    } else {
                        "Served with $value"
                    }
                    Field.SERVING -> servedWith = value
                    Field.TIME -> timeNote = value.ifEmpty { null }
                }
            }
            return true
        }

        private fun ingredient(rawLine: String) {
            if (rawLine.startsWith("|")) {
                tableRow(rawLine)
                return
            }
            val bullet = BULLET.matchEntire(rawLine)
            val bulletless = bullet?.groupValues?.get(1)?.trim() ?: rawLine
            val text = cleanInline(bulletless).trim()
            val bold = WHOLE_BOLD.matches(bulletless)
            val isGroup = !startsWithAmount(text) && text.length <= 50 && ((bullet == null && text.endsWith(":")) || bold)
            if (isGroup) {
                group = text.trimEnd(':').trim().ifEmpty { null }
                return
            }
            if (!headed && isNumbered(rawLine)) {
                section = Section.METHOD
                method(rawLine, false)
                return
            }
            if (!headed && !startsWithAmount(text) && bullet == null) {
                warnings += "Ignored the line \"$rawLine\"."
                return
            }
            addLine(text)
        }

        /** A Markdown table row ("| Beef mince | 500 g |"): the cell with the amount first, the other cells after it. */
        private fun tableRow(rawLine: String) {
            val cells = rawLine.trim().trim('|').split('|').map { cleanInline(it).trim() }.filter { it.isNotEmpty() }
            if (cells.isEmpty() || cells.all { TABLE_RULE.matches(it) }) return
            if (cells.all { sectionKey(it) in TABLE_HEADERS }) return
            val amount = cells.indexOfFirst { startsWithAmount(it) }
            val text = if (amount < 0) {
                cells.joinToString(", ")
            } else {
                val others = cells.filterIndexed { index, _ -> index != amount }
                (listOf("${cells[amount]} ${others.firstOrNull().orEmpty()}".trim()) + others.drop(1)).joinToString(", ")
            }
            addLine(text)
        }

        private fun addLine(text: String) {
            val optional = OPTIONAL_WORD.containsMatchIn(text)
            val clean = OPTIONAL_SUFFIX.replace(text, "").trim()
            if (clean.isNotEmpty()) lines += DraftLine(group, clean, optional)
        }

        private fun method(rawLine: String, blankBefore: Boolean) {
            // "**Step 1: Brown the mince**" or "**1. Brown the mince**" on its own line: the step's title.
            WHOLE_BOLD.matchEntire(rawLine)?.let { bold ->
                val inner = cleanInline(bold.groupValues[1]).trim()
                if (isNumbered(inner)) {
                    closeStep()
                    methodNumbered = true
                    step = StepAcc(numberedBody(inner).trim().trimEnd(':', '.').trim(), "")
                    return
                }
            }
            val numbered = numberedRest(rawLine)
            if (numbered != null) {
                closeStep()
                methodNumbered = true
                startStep(numbered)
                return
            }
            val bulletless = stripBullet(rawLine)
            if (methodNumbered == null) methodNumbered = false
            if (methodNumbered == false) {
                closeStep()
                startStep(bulletless)
                return
            }
            val acc = step
            if (acc == null) {
                startStep(bulletless)
                return
            }
            val line = cleanInline(bulletless).trim()
            val last = acc.paragraphs.lastIndex
            if (last < 0 || blankBefore || bulletless != rawLine || RecipeSteps.classify(line).kind != ParagraphKind.PLAIN) {
                acc.paragraphs += line
            } else {
                acc.paragraphs[last] = acc.paragraphs[last] + "\n" + line
            }
        }

        /** Starts a step from the text after its number: a bold lead ("**Brown the mince.** Oil in…") is its title. */
        private fun startStep(rawRest: String) {
            val lead = BOLD_LEAD.matchEntire(rawRest.trim())
            if (lead != null) {
                val leadText = cleanInline(lead.groupValues[1]).trim()
                var after = lead.groupValues[2].trim()
                val separator = after.firstOrNull()
                val separated = separator == ':' || separator == '.' || separator == '-' || separator == '–' || separator == '—'
                val head = leadText.trimEnd(':', '.').trim()
                if ((after.isEmpty() || separated || leadText.endsWith(":") || leadText.endsWith(".")) && head.length in 1..MAX_STEP_TITLE) {
                    if (separated) after = after.drop(1).trim()
                    step = StepAcc(head, cleanInline(after).trim())
                    return
                }
            }
            val rest = cleanInline(rawRest)
            val colon = titleColon(rest)
            step = if (colon in 0..MAX_STEP_TITLE) {
                StepAcc(rest.substring(0, colon).trim(), rest.substring(colon + 1).trim())
            } else {
                StepAcc("", rest.trim())
            }
        }

        private fun listItem(kind: StepKind, rawLine: String, indented: Boolean) {
            val bullet = BULLET.matchEntire(rawLine)
            val numbered = NUMBERED.matchEntire(rawLine)
            when {
                bullet != null -> startItem(kind, cleanInline(bullet.groupValues[1]).trim())
                numbered != null -> startItem(kind, cleanInline(numbered.groupValues[2]).trim())
                indented && item != null -> item!!.text = item!!.text + "\n" + cleanInline(rawLine)
                else -> startItem(kind, cleanInline(rawLine).trim())
            }
        }

        private fun startItem(kind: StepKind, text: String) {
            closeItem()
            item = ItemAcc(kind, text)
        }

        fun finish(): RecipeDraft {
            closeOpen()
            val problems = warnings.toMutableList()
            if (title.isNullOrBlank()) problems += "No title found."
            if (lines.isEmpty()) problems += "No ingredients found."
            return RecipeDraft(
                title = title.orEmpty().trim(),
                serves = serves,
                prepMins = prep,
                cookMins = cook,
                style = style,
                tags = tags,
                tagline = about,
                servedWith = servedWith,
                timeNote = timeNote,
                lines = lines.toList(),
                steps = steps.toList(),
                warnings = problems,
            )
        }
    }

    /** The index of the colon that ends a step's title: the first ": ", or a colon at the end of the text. -1 if none. */
    private fun titleColon(text: String): Int {
        val inside = text.indexOf(": ")
        if (inside >= 0) return inside
        return if (text.endsWith(":")) text.length - 1 else -1
    }

    private fun looksLikeTime(text: String): Boolean = text.any { it in '0'..'9' } || TIME_WORDS.containsMatchIn(text)

    /** "Title (about 5 min)" -> title and time; a bracket that isn't a time stays in the title. */
    private fun splitHead(head: String): Pair<String?, String?> {
        val h = head.trim()
        if (h.isEmpty()) return null to null
        val m = TITLE_AND_TIME.matchEntire(h)
        if (m != null && looksLikeTime(m.groupValues[2])) {
            return m.groupValues[1].trim().ifEmpty { null } to m.groupValues[2].trim()
        }
        return h to null
    }

    /**
     * Minutes from a time such as "20 min", "1 hr 15 min", "1½ hours", "90 minutes" or "1h30"; a bare
     * number is minutes. Null when there is no number.
     */
    fun parseMinutes(text: String): Int? {
        HOURS_MINUTES.matchEntire(text)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        var total = 0.0
        var found = false
        for (m in TIME_PART.findAll(text)) {
            val value = numberOf(m.groupValues[1]) ?: continue
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            total += if (unit.startsWith("h")) value * 60 else value
            found = true
        }
        if (found) return Math.floor(total + 0.5).toInt()
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    private fun numberOf(token: String): Double? {
        val t = token.trim()
        val glyph = t.lastOrNull()?.let { FRACTION_GLYPHS[it] }
        val digits = if (glyph != null) t.dropLast(1).trim() else t
        val base = if (digits.isEmpty()) 0.0 else digits.toDoubleOrNull() ?: return null
        return base + (glyph ?: 0.0)
    }

    // ---- format ----------------------------------------------------------------------------

    /** A time for the paste format: "20 min", "1 hr", "1 hr 15 min". */
    fun formatMinutes(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }

    /** The draft a recipe would be shared as (see [format]). */
    fun draftOf(recipe: Recipe): RecipeDraft = RecipeDraft(
        title = recipe.title,
        serves = recipe.serves,
        prepMins = recipe.prepMins,
        cookMins = recipe.cookMins,
        style = recipe.style,
        tags = recipe.tags,
        tagline = recipe.tagline,
        servedWith = recipe.servedWith,
        timeNote = recipe.timeNote,
        lines = recipe.lines.map { DraftLine(it.group, it.text, it.optional) },
        steps = recipe.steps,
    )

    /** *Share as text*: the paste format for [recipe]. [parse] reads it back. */
    fun format(recipe: Recipe): String = format(draftOf(recipe))

    /**
     * Writes the paste format. An optional line that doesn't already say so gets " (optional)" added,
     * which [parse] takes off again and records as the line's flag.
     */
    fun format(draft: RecipeDraft): String {
        val out = mutableListOf<String>()
        out += "# ${draft.title}"

        val times = listOfNotNull(
            draft.serves?.let { "Serves: $it" },
            draft.prepMins?.let { "Prep: ${formatMinutes(it)}" },
            draft.cookMins?.let { "Cook: ${formatMinutes(it)}" },
        )
        if (times.isNotEmpty()) out += times.joinToString(" · ")
        draft.style?.takeIf { it.isNotBlank() }?.let { out += "Style: $it" }
        if (draft.tags.isNotEmpty()) out += "Tags: ${draft.tags.joinToString(", ")}"
        draft.tagline?.takeIf { it.isNotBlank() }?.let { out += "About: $it" }
        draft.servedWith?.takeIf { it.isNotBlank() }?.let {
            out += if (it.startsWith("Served with ")) "Serve with: ${it.removePrefix("Served with ")}" else "Serving suggestion: $it"
        }
        draft.timeNote?.takeIf { it.isNotBlank() }?.let { out += "Time: $it" }

        if (draft.lines.isNotEmpty()) {
            out += ""
            out += "## Ingredients"
            var current: String? = null
            for (line in draft.lines) {
                if (line.group != current) {
                    current = line.group
                    out += "### ${current ?: "Other"}"
                }
                val base = OPTIONAL_SUFFIX.replace(line.text, "").trim()
                out += "- " + if (line.optional) "$base (optional)" else base
            }
        }

        val method = draft.method
        if (method.isNotEmpty()) {
            out += ""
            out += "## Method"
            method.forEachIndexed { index, step -> out += formatStep(index + 1, step) }
        }
        for ((heading, kind) in listOf("Tips" to StepKind.TIP, "Equipment" to StepKind.EQUIPMENT, "Storage" to StepKind.STORAGE)) {
            val items = draft.steps.filter { it.kind == kind }
            if (items.isEmpty()) continue
            out += ""
            out += "## $heading"
            for (item in items) {
                val lines = item.body.trim().split('\n')
                out += "- ${lines.first()}"
                for (extra in lines.drop(1)) out += "  $extra"
            }
        }
        return out.joinToString("\n") + "\n"
    }

    private fun formatStep(number: Int, step: RecipeStep): List<String> {
        val head = listOfNotNull(
            step.title?.trim()?.takeIf { it.isNotEmpty() },
            step.time?.trim()?.takeIf { it.isNotEmpty() }?.let { "($it)" },
        ).joinToString(" ")
        val paragraphs = RecipeSteps.splitParagraphs(step.body)
        val firstLines = (paragraphs.firstOrNull() ?: "").split('\n')
        val first = firstLines.first()
        val out = mutableListOf<String>()
        out += when {
            head.isNotEmpty() -> "$number. $head:" + if (first.isNotEmpty()) " $first" else ""
            titleColon(first) in 0..MAX_STEP_TITLE -> "$number. : $first"
            else -> "$number. $first"
        }
        for (extra in firstLines.drop(1)) out += "   $extra"
        for (paragraph in paragraphs.drop(1)) {
            out += ""
            for (extra in paragraph.split('\n')) out += "   $extra"
        }
        return out
    }
}
