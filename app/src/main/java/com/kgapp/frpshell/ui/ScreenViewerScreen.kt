package com.kgapp.frpshell.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScreenViewerScreen(
    imagePath: String,
    timestamp: Long,
    contentPadding: PaddingValues
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
            .background(Color.Black)
    ) {
        if (imagePath.isNotEmpty()) {
            Image(
                painter = rememberAsyncImagePainter(model = File(imagePath)),
                contentDescription = "Screen Capture",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // Timestamp overlay
        if (timestamp > 0) {
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            Text(
                text = dateStr,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
