package io.github.matteopi.budgeting.core.model

/** Categories created on first launch. The user can rename, add and archive (not the system one). */
object DefaultCategories {
    val all: List<Category> = listOf(
        Category(name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF2E7D32, sortOrder = 0),
        Category(name = "Eating out", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFFEF6C00, sortOrder = 1),
        Category(name = "Nights out", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF6A1B9A, sortOrder = 2),
        Category(name = "Transport", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF1565C0, sortOrder = 3),
        Category(name = "Shopping", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFFAD1457, sortOrder = 4),
        Category(name = "Course costs", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF00838F, sortOrder = 5),
        Category(name = "Health", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFFC62828, sortOrder = 6),
        Category(name = "Other", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF546E7A, sortOrder = 7),
        Category(name = "Rent & bills", flow = Flow.OUT, forecastMode = ForecastMode.SCHEDULED_ONLY, colorArgb = 0xFF4E342E, sortOrder = 8),
        Category(name = "Subscriptions", flow = Flow.OUT, forecastMode = ForecastMode.SCHEDULED_ONLY, colorArgb = 0xFF283593, sortOrder = 9),
        Category(name = "Student loan", flow = Flow.IN, forecastMode = ForecastMode.SCHEDULED_ONLY, colorArgb = 0xFF1B5E20, isMajorIncomeDefault = true, sortOrder = 10),
        Category(name = "Wages", flow = Flow.IN, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF33691E, sortOrder = 11),
        Category(name = "Family", flow = Flow.IN, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF827717, sortOrder = 12),
        Category(name = "Bursary", flow = Flow.IN, forecastMode = ForecastMode.SCHEDULED_ONLY, colorArgb = 0xFF004D40, isMajorIncomeDefault = true, sortOrder = 13),
        Category(name = "Refunds", flow = Flow.IN, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF0277BD, sortOrder = 14),
        Category(name = "Other income", flow = Flow.IN, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF558B2F, sortOrder = 15),
        Category(
            name = "Untracked", flow = Flow.BOTH, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF9E9E9E,
            isSystem = true, systemKey = SystemKeys.UNTRACKED, sortOrder = 99,
        ),
    )
}
