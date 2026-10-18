package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeTextTest {

    // The example of docs/PLAN.md §11.4, with the sections the plan elides filled in.
    private val example = """
        # Beef, Bean & Lentil Chilli
        Serves: 4 · Prep: 20 min · Cook: 55 min
        Style: Hob · one pot · batch of 4 · freezes well
        Tags: beef, rice, batch
        About: Browned mince, bloomed spices and red lentils that melt into the sauce.
        Serve with: 100 g rice per portion (included in the numbers).
        Time: Allow about 1½ hours.

        ## Ingredients
        ### Main
        - 500 g lean beef mince (5% fat)
        - 1 tbsp olive oil
        ### Vegetables
        - 1 bird's eye chilli, finely chopped (optional)

        ## Method
        1. Brown the mince (~10 min): Oil in the pot, then the mince…

           Ready when: the bubbling turns to a sizzle and the pieces are brown and crumbly.
        2. Veg in (8–10 min): Onion and pepper straight in.

        ## Tips
        - Medium heat unless a step says otherwise.
        ## Equipment
        - A pot of at least 4 litres, with a lid.
        ## Storage
        - Cool fast.
    """.trimIndent()

    // ---- the starter recipes ----------------------------------------------------------------

    @Test
    fun `each starter recipe formats to text and parses back to the same recipe`() {
        for (recipe in StarterBook.recipes) {
            val text = RecipeText.format(recipe)
            val draft = RecipeText.parse(text)
            val name = recipe.title
            assertEquals(recipe.title, draft.title, name)
            assertEquals(recipe.serves, draft.serves, name)
            assertEquals(recipe.prepMins, draft.prepMins, name)
            assertEquals(recipe.cookMins, draft.cookMins, name)
            assertEquals(recipe.style, draft.style, name)
            assertEquals(recipe.tags, draft.tags, name)
            assertEquals(recipe.tagline, draft.tagline, name)
            assertEquals(recipe.servedWith, draft.servedWith, name)
            assertEquals(recipe.timeNote, draft.timeNote, name)
            assertEquals(recipe.lines.map { DraftLine(it.group, it.text, it.optional) }, draft.lines, "$name lines")
            assertEquals(recipe.steps, draft.steps, "$name steps, tips, equipment and storage")
            assertTrue(draft.warnings.isEmpty(), "$name: ${draft.warnings}")
        }
    }

    @Test
    fun `formatting what was parsed gives the same text`() {
        for (recipe in StarterBook.recipes) {
            val text = RecipeText.format(recipe)
            assertEquals(text, RecipeText.format(RecipeText.parse(text)), recipe.title)
        }
    }

    @Test
    fun `step titles, times and multi-paragraph bodies survive`() {
        val chilli = StarterBook.recipe("chilli")
        val draft = RecipeText.parse(RecipeText.format(chilli))
        val brown = draft.method[1]
        assertEquals("Brown the mince", brown.title)
        assertEquals("~10 min", brown.time)
        assertEquals(listOf(ParagraphKind.PLAIN, ParagraphKind.CUE), RecipeSteps.paragraphs(brown.body).map { it.kind })
        val traybake = RecipeText.parse(RecipeText.format(StarterBook.recipe("traybake")))
        assertEquals("Marinate the chicken (optional)", traybake.method[0].title)
        assertNull(traybake.method[0].time)
        assertEquals(4, draft.tips.size)
        assertEquals(3, draft.equipment.size)
        assertEquals(7, draft.storage.size)
    }

    @Test
    fun `the exported text leads with the title and the header lines`() {
        val lines = RecipeText.format(StarterBook.recipe("chilli")).lines()
        assertEquals("# Beef, Bean & Lentil Chilli", lines[0])
        assertEquals("Serves: 4 · Prep: 20 min · Cook: 55 min", lines[1])
        assertEquals("Style: Hob · one pot · batch of 4 · freezes well", lines[2])
        assertEquals("Tags: beef, rice, batch, freezes well", lines[3])
        assertTrue(lines[4].startsWith("About: Browned mince"))
        assertEquals("Serve with: 100 g rice per portion (included in the numbers).", lines[5])
        assertEquals("Time: Allow about 1½ hours.", lines[6])
        assertTrue("## Ingredients" in lines)
        assertTrue("### Main" in lines)
        assertTrue("- 500 g lean beef mince (5% fat)" in lines)
        assertTrue("## Method" in lines)
        assertTrue("## Tips" in lines && "## Equipment" in lines && "## Storage" in lines)
    }

    @Test
    fun `an optional line that does not say so gets a marker that parses back to the flag`() {
        val stirFry = StarterBook.recipe("stir-fry")
        val text = RecipeText.format(stirFry)
        assertTrue("- 1 tbsp oyster sauce (optional)" in text.lines())
        val draft = RecipeText.parse(text)
        val oyster = draft.lines.first { it.text.contains("oyster") }
        assertEquals("1 tbsp oyster sauce", oyster.text)
        assertTrue(oyster.optional)
        assertFalse(draft.lines.first { it.text.contains("cornflour") }.optional)
    }

    // ---- the plan's example -----------------------------------------------------------------

    @Test
    fun `the example in the plan parses into its fields`() {
        val d = RecipeText.parse(example)
        assertEquals("Beef, Bean & Lentil Chilli", d.title)
        assertEquals(4, d.serves)
        assertEquals(20, d.prepMins)
        assertEquals(55, d.cookMins)
        assertEquals("Hob · one pot · batch of 4 · freezes well", d.style)
        assertEquals(listOf("beef", "rice", "batch"), d.tags)
        assertEquals("Browned mince, bloomed spices and red lentils that melt into the sauce.", d.tagline)
        assertEquals("Served with 100 g rice per portion (included in the numbers).", d.servedWith)
        assertEquals("Allow about 1½ hours.", d.timeNote)
        assertEquals(
            listOf(
                DraftLine("Main", "500 g lean beef mince (5% fat)"),
                DraftLine("Main", "1 tbsp olive oil"),
                DraftLine("Vegetables", "1 bird's eye chilli, finely chopped", optional = true),
            ),
            d.lines,
        )
        assertEquals(2, d.method.size)
        assertEquals(RecipeStep(StepKind.STEP, "Brown the mince", "~10 min",
            "Oil in the pot, then the mince…\n\nReady when: the bubbling turns to a sizzle and the pieces are brown and crumbly."), d.method[0])
        assertEquals(RecipeStep(StepKind.STEP, "Veg in", "8–10 min", "Onion and pepper straight in."), d.method[1])
        assertEquals(listOf("Medium heat unless a step says otherwise."), d.tips.map { it.body })
        assertEquals(listOf("A pot of at least 4 litres, with a lid."), d.equipment.map { it.body })
        assertEquals(listOf("Cool fast."), d.storage.map { it.body })
        assertTrue(d.warnings.isEmpty(), d.warnings.toString())
    }

    // ---- header lines -----------------------------------------------------------------------

    private fun header(vararg lines: String) = RecipeText.parse("# T\n" + lines.joinToString("\n") + "\n\n## Ingredients\n- 1 egg\n")

    @Test
    fun `serves, makes and the colon all work`() {
        assertEquals(4, header("Serves 4").serves)
        assertEquals(4, header("Serves: 4").serves)
        assertEquals(4, header("Makes 4").serves)
        assertEquals(6, header("Makes: 6 muffins").serves)
        assertEquals(4, header("Servings: 4-6").serves)
    }

    @Test
    fun `prep and cook accept minutes, hours and mixed times`() {
        val d = header("Prep: 1 hr 15 min · Cook: 1½ hours")
        assertEquals(75, d.prepMins)
        assertEquals(90, d.cookMins)
        assertEquals(20, header("Prep 20 min").prepMins)
        assertEquals(90, header("Cook: 90 minutes").cookMins)
        assertEquals(30, header("Cook: 30m").cookMins)
        assertEquals(90, header("Cook: 1h30").cookMins)
        assertEquals(45, header("Prep: 45").prepMins)
        assertEquals(20, header("Cook: 20-25 min").cookMins, "a range uses its first value")
        assertEquals(120, header("Cook: 2 hours").cookMins)
    }

    @Test
    fun `minutes are written and read back`() {
        for (m in listOf(0, 1, 20, 59, 60, 61, 75, 90, 120, 185)) {
            assertEquals(m, RecipeText.parseMinutes(RecipeText.formatMinutes(m)), "$m")
        }
        assertEquals("1 hr 15 min", RecipeText.formatMinutes(75))
        assertEquals("2 hr", RecipeText.formatMinutes(120))
        assertNull(RecipeText.parseMinutes("soon"))
    }

    @Test
    fun `serve with and the free-form serving suggestion both round trip`() {
        assertEquals("Served with rice", header("Serve with: rice").servedWith)
        assertEquals("Served with rice", header("Served with: rice").servedWith)
        assertEquals("Served with rice", header("Serve with: Served with rice").servedWith)
        assertEquals("Potatoes are the carb.", header("Serving suggestion: Potatoes are the carb.").servedWith)
        val recipe = Recipe(title = "T", servedWith = "Potatoes are the carb, so no rice needed.", lines = listOf(RecipeLine(text = "1 egg")))
        assertEquals(recipe.servedWith, RecipeText.parse(RecipeText.format(recipe)).servedWith)
    }

    @Test
    fun `a style with middle dots is not split into fields`() {
        val d = header("Style: Hob · fast, high heat")
        assertEquals("Hob · fast, high heat", d.style)
        assertNull(d.serves)
    }

    @Test
    fun `tags are split on commas and tidied`() {
        assertEquals(listOf("beef", "rice", "freezes well"), header("Tags: beef,  rice , #freezes well").tags)
    }

    // ---- steps ------------------------------------------------------------------------------

    private fun method(vararg lines: String) =
        RecipeText.parse("# T\n\n## Ingredients\n- 1 egg\n\n## Method\n" + lines.joinToString("\n") + "\n").method

    @Test
    fun `a step is N dot title in brackets time colon body`() {
        val steps = method("1. Sear the chicken (about 6 min): Heat the oil until hot.")
        assertEquals(RecipeStep(StepKind.STEP, "Sear the chicken", "about 6 min", "Heat the oil until hot."), steps.single())
    }

    @Test
    fun `the title is the text before the first colon when that is at most sixty characters`() {
        val short = "a".repeat(60)
        assertEquals(short, method("1. $short: do it").single().title)
        val long = "a".repeat(61)
        val step = method("1. $long: do it").single()
        assertNull(step.title)
        assertEquals("$long: do it", step.body)
        assertNull(method("1. Straight away, over the rice.").single().title)
        assertEquals("Straight away, over the rice.", method("1. Straight away, over the rice.").single().body)
    }

    @Test
    fun `a bracket that is not a time stays in the title`() {
        val step = method("1. Marinate the chicken (optional): 20 min ahead.").single()
        assertEquals("Marinate the chicken (optional)", step.title)
        assertNull(step.time)
        assertEquals("20 min ahead.", step.body)
    }

    @Test
    fun `only the first colon splits the title off`() {
        val step = method("1. Prep: Slicing tip: freeze the chicken first.").single()
        assertEquals("Prep", step.title)
        assertEquals("Slicing tip: freeze the chicken first.", step.body)
    }

    @Test
    fun `indented or unnumbered lines after a step are paragraphs of that step`() {
        val steps = method(
            "1. Brown the mince (~10 min): Oil in the pot.",
            "",
            "   Ready when: it is brown.",
            "   If it sticks: add water.",
            "2. Veg in: Onion.",
            "Ready when: soft.",
        )
        assertEquals(2, steps.size)
        assertEquals("Oil in the pot.\n\nReady when: it is brown.\n\nIf it sticks: add water.", steps[0].body)
        assertEquals("Onion.\n\nReady when: soft.", steps[1].body)
    }

    @Test
    fun `wrapped lines without a blank line stay in one paragraph`() {
        val step = method("1. Simmer: Bring to the boil", "   and turn down low.").single()
        assertEquals("Bring to the boil\nand turn down low.", step.body)
        assertEquals(1, RecipeSteps.paragraphs(step.body).size)
    }

    @Test
    fun `numbers can be followed by a bracket or the word step`() {
        val steps = method("1) First: do this.", "Step 2: Second: do that.", "3. Third")
        assertEquals(listOf("First", "Second", null), steps.map { it.title })
        assertEquals("Third", steps[2].body)
    }

    @Test
    fun `a method with no numbers has one step per line`() {
        val steps = method("Chop the onion.", "Fry it gently.", "Serve.")
        assertEquals(listOf("Chop the onion.", "Fry it gently.", "Serve."), steps.map { it.body })
    }

    @Test
    fun `a step with no title that would be misread gets a leading colon`() {
        val recipe = Recipe(
            title = "T",
            lines = listOf(RecipeLine(text = "1 egg")),
            steps = listOf(RecipeStep(body = "Tip: warm the pan first."), RecipeStep(time = "5 min", body = "Wait.")),
        )
        val text = RecipeText.format(recipe)
        assertTrue("1. : Tip: warm the pan first." in text.lines())
        assertEquals(recipe.steps, RecipeText.parse(text).steps)
    }

    @Test
    fun `tips, equipment and storage items take bullets, numbers or plain lines`() {
        val d = RecipeText.parse(
            "# T\n\n## Ingredients\n- 1 egg\n\n## Tips\n- one\n* two\n1. three\nfour\n  five continued\n\n## Kit\n- a pan\n\n## Freezing and storing\n- cool it\n",
        )
        assertEquals(listOf("one", "two", "three", "four\nfive continued"), d.tips.map { it.body })
        assertEquals(listOf("a pan"), d.equipment.map { it.body })
        assertEquals(listOf("cool it"), d.storage.map { it.body })
    }

    // ---- ingredients ------------------------------------------------------------------------

    @Test
    fun `bullets of any kind are stripped and groups can be headings or lines ending in a colon`() {
        val d = RecipeText.parse(
            "# T\n\n## Ingredients\n- 1 egg\n* 2 onions\n• 3 carrots\n250 g flour\nFor the sauce:\n- 1 tbsp oil\n### Topping\n- 1 pinch salt\n",
        )
        assertEquals(
            listOf(
                DraftLine(null, "1 egg"),
                DraftLine(null, "2 onions"),
                DraftLine(null, "3 carrots"),
                DraftLine(null, "250 g flour"),
                DraftLine("For the sauce", "1 tbsp oil"),
                DraftLine("Topping", "1 pinch salt"),
            ),
            d.lines,
        )
    }

    @Test
    fun `the word optional anywhere marks a line optional, a trailing bracket is taken off`() {
        val d = RecipeText.parse("# T\n\n## Ingredients\n- 1 tsp paprika (optional)\n- Optional: 1 lemon\n- 2 eggs\n")
        assertEquals(DraftLine(null, "1 tsp paprika", true), d.lines[0])
        assertEquals(DraftLine(null, "Optional: 1 lemon", true), d.lines[1])
        assertEquals(DraftLine(null, "2 eggs", false), d.lines[2])
    }

    @Test
    fun `lines with no amount are kept as text`() {
        val d = RecipeText.parse("# T\n\n## Ingredients\n- Salt and pepper to taste\n")
        assertEquals("Salt and pepper to taste", d.lines.single().text)
    }

    // ---- without headings -------------------------------------------------------------------

    @Test
    fun `a headingless paste takes the first line as the title, amounts as ingredients and numbers as steps`() {
        val d = RecipeText.parse(
            """
            Quick Tomato Pasta
            Serves 2
            200 g pasta
            400 g tin chopped tomatoes
            1 tsp sugar
            Salt
            1. Boil the pasta (10 min): Salted water.
            2. Warm the tomatoes with the sugar.
            """.trimIndent(),
        )
        assertEquals("Quick Tomato Pasta", d.title)
        assertEquals(2, d.serves)
        assertEquals(listOf("200 g pasta", "400 g tin chopped tomatoes", "1 tsp sugar"), d.lines.map { it.text })
        assertEquals(2, d.method.size)
        assertEquals("Boil the pasta", d.method[0].title)
        assertEquals("10 min", d.method[0].time)
        assertEquals("Warm the tomatoes with the sugar.", d.method[1].body)
        assertTrue(d.warnings.any { it.contains("Salt") }, "the line that is neither is reported: ${d.warnings}")
    }

    @Test
    fun `a headingless paste with a pinch and fractions`() {
        val d = RecipeText.parse("Pancakes\n½ cup flour\nPinch of salt\n1. Mix.\n")
        assertEquals(listOf("½ cup flour", "Pinch of salt"), d.lines.map { it.text })
        assertEquals(listOf("Mix."), d.method.map { it.body })
    }

    // ---- odds and ends ----------------------------------------------------------------------

    @Test
    fun `windows line endings and bold headings read the same`() {
        val windows = example.replace("\n", "\r\n")
        assertEquals(RecipeText.parse(example), RecipeText.parse(windows))
        val bold = RecipeText.parse("Title here\n\n**Ingredients**\n- 1 egg\n\n**Method**\n1. Boil it.\n")
        assertEquals("Title here", bold.title)
        assertEquals(listOf("1 egg"), bold.lines.map { it.text })
        assertEquals(listOf("Boil it."), bold.method.map { it.body })
    }

    @Test
    fun `an unknown section is ignored with a warning`() {
        val d = RecipeText.parse("# T\n\n## Ingredients\n- 1 egg\n\n## Wine pairing\n- Riesling\n")
        assertEquals(listOf("1 egg"), d.lines.map { it.text })
        assertTrue(d.warnings.any { it.contains("Wine pairing") })
    }

    @Test
    fun `a missing title or ingredient list is reported`() {
        val d = RecipeText.parse("## Method\n1. Boil.\n")
        assertEquals("", d.title)
        assertTrue(d.warnings.contains("No title found."))
        assertTrue(d.warnings.contains("No ingredients found."))
        assertEquals(RecipeDraft(warnings = listOf("No title found.", "No ingredients found.")), RecipeText.parse(""))
    }

    @Test
    fun `shared text is a recipe when it has an ingredients heading and either numbers or a method heading`() {
        assertTrue(RecipeText.looksLikeRecipe(example))
        assertTrue(RecipeText.looksLikeRecipe("Soup\nIngredients\n- 1 leek\n1. Chop.\n"))
        assertTrue(RecipeText.looksLikeRecipe("Soup\n## ingredients:\n1 leek\n# Instructions\nChop it\n"))
        assertTrue(RecipeText.looksLikeRecipe("Soup\n**Ingredients**\n1 leek\nSteps\nChop it\n"))
        assertFalse(RecipeText.looksLikeRecipe("Soup\nIngredients\n- 1 leek\n"))
        assertFalse(RecipeText.looksLikeRecipe("Soup\n1. Chop.\n2. Boil.\n"))
        assertFalse(RecipeText.looksLikeRecipe("TESCO\nMILK 1.20\nTOTAL 1.20\n"))
    }

    @Test
    fun `a draft can be built from a recipe and formatted`() {
        val recipe = StarterBook.recipe("braise")
        val draft = RecipeText.draftOf(recipe)
        assertEquals(RecipeText.format(recipe), RecipeText.format(draft))
        assertEquals(recipe.lines.size, draft.lines.size)
        assertEquals(recipe.steps, draft.steps)
    }

    // ---- recipes copied from a chat (docs/PLAN.md §11.11) ----------------------------------

    private val chatNoodles = """
        Here's a cheap, high-protein dinner that keeps well:

        # Spicy Peanut Chicken Noodles

        A quick weeknight stir-fry with a punchy peanut sauce.

        **Serves:** 2 | **Prep:** 10 minutes | **Cook:** 15 minutes

        ## Ingredients

        **For the sauce:**
        - 2 tbsp smooth peanut butter
        - 2 tbsp light soy sauce
        - 1 tsp honey

        **For the noodles:**
        - 150 g egg noodles
        - 2 chicken breast fillets (about 320 g), sliced
        - 1 tbsp vegetable oil
        - 1 red pepper, sliced
        - *Optional:* 1 red chilli, sliced

        ## Method

        1. **Make the sauce (2 min):** Whisk the peanut butter, soy sauce and honey with 3 tbsp hot water.
        2. **Cook the noodles.** Boil for 4 minutes, then drain.
        3. **Fry the chicken** (5–6 min): Heat the oil in a wok and fry until golden.

           **Ready when:** no pink remains in the thickest piece.
        4. Add the pepper and noodles, pour over the sauce and toss for 1 minute.

        ## Tips
        - Swap the chicken for **firm tofu** to make it meat-free.
        - Use [crunchy peanut butter](https://example.com/pb) for texture.

        ## Storage
        Keeps in the fridge for 2 days.

        ---

        Enjoy! Let me know if you'd like a vegetarian version.
    """.trimIndent()

    @Test
    fun `a recipe as a chat writes it reads into its parts`() {
        val d = RecipeText.parse(chatNoodles)
        assertEquals("Spicy Peanut Chicken Noodles", d.title)
        assertEquals("A quick weeknight stir-fry with a punchy peanut sauce.", d.tagline)
        assertEquals(2, d.serves)
        assertEquals(10, d.prepMins)
        assertEquals(15, d.cookMins)
        assertEquals(
            listOf(
                DraftLine("For the sauce", "2 tbsp smooth peanut butter"),
                DraftLine("For the sauce", "2 tbsp light soy sauce"),
                DraftLine("For the sauce", "1 tsp honey"),
                DraftLine("For the noodles", "150 g egg noodles"),
                DraftLine("For the noodles", "2 chicken breast fillets (about 320 g), sliced"),
                DraftLine("For the noodles", "1 tbsp vegetable oil"),
                DraftLine("For the noodles", "1 red pepper, sliced"),
                DraftLine("For the noodles", "Optional: 1 red chilli, sliced", optional = true),
            ),
            d.lines,
        )
        assertEquals(
            listOf(
                RecipeStep(StepKind.STEP, "Make the sauce", "2 min", "Whisk the peanut butter, soy sauce and honey with 3 tbsp hot water."),
                RecipeStep(StepKind.STEP, "Cook the noodles", null, "Boil for 4 minutes, then drain."),
                RecipeStep(
                    StepKind.STEP, "Fry the chicken", "5–6 min",
                    "Heat the oil in a wok and fry until golden.\n\nReady when: no pink remains in the thickest piece.",
                ),
                RecipeStep(StepKind.STEP, null, null, "Add the pepper and noodles, pour over the sauce and toss for 1 minute."),
            ),
            d.method,
        )
        assertEquals(
            listOf("Swap the chicken for firm tofu to make it meat-free.", "Use crunchy peanut butter for texture."),
            d.tips.map { it.body },
        )
        assertEquals(listOf("Keeps in the fridge for 2 days."), d.storage.map { it.body })
        assertTrue(d.warnings.none { "Enjoy" in it || "Spicy" in it }, d.warnings.toString())
        assertTrue(RecipeText.looksLikeRecipe(chatNoodles))
    }

    @Test
    fun `bold headings, an ingredients table and step headings`() {
        val text = """
            **Creamy Tomato & Lentil Soup**

            Serves 4 · Prep time: 10 mins · Cook time: 30 mins

            **Ingredients**

            | Ingredient | Amount |
            |---|---|
            | Red split lentils | 150 g |
            | Chopped tomatoes | 1 × 400 g tin |
            | Onion, diced | 1 |
            | Salt and pepper | to taste |

            **Instructions**

            **Step 1: Soften the onion**
            Fry the onion in a little oil for 5 minutes.

            **Step 2: Simmer**
            Add everything else and simmer for 25 minutes, stirring every 5 min.

            **Nutrition (per portion, approx.)**
            - 210 kcal
            - 12 g protein
        """.trimIndent()
        val d = RecipeText.parse(text)
        assertEquals("Creamy Tomato & Lentil Soup", d.title)
        assertEquals(4, d.serves)
        assertEquals(10, d.prepMins)
        assertEquals(30, d.cookMins)
        assertEquals(
            listOf("150 g Red split lentils", "1 × 400 g tin Chopped tomatoes", "1 Onion, diced", "Salt and pepper, to taste"),
            d.lines.map { it.text },
        )
        assertEquals(listOf("Soften the onion", "Simmer"), d.method.map { it.title })
        assertEquals(
            listOf("Fry the onion in a little oil for 5 minutes.", "Add everything else and simmer for 25 minutes, stirring every 5 min."),
            d.method.map { it.body },
        )
        assertTrue(RecipeText.looksLikeRecipe(text))
    }

    @Test
    fun `headings with emoji and extra words, chat openers and nested bullets`() {
        val d = RecipeText.parse(
            """
            Sure! Here's a simple traybake.

            ## 🍗 Lemon Chicken Traybake

            ### Ingredients (serves 2)
            * 4 chicken thighs
            * 500 g baby potatoes, halved
            * 1 lemon

            ### How to make it
            1. Heat the oven to 200°C (180°C fan).
            2. Toss everything on a tray:
               - potatoes cut side down
               - chicken skin side up
            3. Roast for 40–45 minutes.

            ### Tips & variations
            - Add a handful of olives for the last 10 minutes.

            Would you like a side to go with it?
            """.trimIndent(),
        )
        assertEquals("Lemon Chicken Traybake", d.title)
        assertEquals(2, d.serves)
        assertEquals(listOf("4 chicken thighs", "500 g baby potatoes, halved", "1 lemon"), d.lines.map { it.text })
        assertEquals(3, d.method.size)
        assertEquals("Toss everything on a tray", d.method[1].title)
        assertEquals("potatoes cut side down\n\nchicken skin side up", d.method[1].body)
        assertEquals("Roast for 40–45 minutes.", d.method[2].body)
        assertEquals(listOf("Add a handful of olives for the last 10 minutes."), d.tips.map { it.body })
    }

    @Test
    fun `unknown header facts are skipped and a copied recipe pastes back unchanged`() {
        val d = RecipeText.parse("# Soup\n**Difficulty:** Easy | **Serves:** 3 | **Ready in:** 40 min\n\n## Ingredients\n- 1 leek\n\n## Method\n1. Chop.\n")
        assertEquals(3, d.serves)
        assertNull(d.prepMins)
        for (key in StarterBook.models.recipeKeys.keys) {
            val recipe = StarterBook.recipe(key)
            val copied = RecipeText.format(recipe)
            assertEquals(copied, RecipeText.format(RecipeText.parse(copied)), key)
        }
    }
}
