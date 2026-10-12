package io.github.matteopi.budgeting.ui.receipts

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.ui.navigation.Route
import java.io.File
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * Where a receipt scan starts: take a photo with the camera app, or pick one from the gallery.
 * The camera is used through the `TakePicture` intent, so the app needs no camera permission.
 * Either way the picture ends up as a file in the receipts cache folder and [onCaptured] gets the
 * review route for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanScreen(
    onBack: () -> Unit,
    onCaptured: (Route.ReceiptReview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saved across the camera app being open (the process can be recreated meanwhile).
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = pendingPath
        pendingPath = null
        if (path != null) {
            if (saved) {
                onCaptured(Route.ReceiptReview(path, ReceiptInputKind.IMAGE.name))
            } else {
                File(path).delete()
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val file = ReceiptFiles.copyToCache(context, uri, "jpg")
                if (file != null) {
                    onCaptured(Route.ReceiptReview(file.absolutePath, ReceiptInputKind.IMAGE.name))
                } else {
                    message = "Couldn't open that picture"
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Scan receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Photograph the whole receipt, flat and well lit. The text is read on your phone; " +
                    "the picture is never uploaded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    message = null
                    try {
                        val file = ReceiptFiles.newFile(context, "jpg")
                        file.createNewFile()
                        pendingPath = file.absolutePath
                        takePicture.launch(ReceiptFiles.uriFor(context, file))
                    } catch (e: ActivityNotFoundException) {
                        pendingPath = null
                        message = "No camera app found on this phone"
                    } catch (e: IOException) {
                        pendingPath = null
                        message = "Couldn't prepare the camera"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(ReceiptIcons.Camera, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Take a photo")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    message = null
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose from gallery")
            }
            val shown = message
            if (shown != null) {
                Spacer(Modifier.height(16.dp))
                Text(shown, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
