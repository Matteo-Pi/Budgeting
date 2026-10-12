package io.github.matteopi.budgeting.ui.receipts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons the core icon set (`material-icons-core`) does not have. Drawn from the Material
 * "photo camera" outline path so the extended icon library is not needed.
 */
object ReceiptIcons {
    val Camera: ImageVector by lazy {
        ImageVector.Builder(
            name = "ReceiptCamera",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(
                "M12 12m-3.2 0a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0" +
                    "M9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9z" +
                    "m3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
    }
}
