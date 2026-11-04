package fr.offlinetune.ui.download

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun DownloadScreen(padding: PaddingValues, sharedText: String?, onSharedConsumed: () -> Unit) {
    Text("À venir", Modifier.padding(padding))
}
