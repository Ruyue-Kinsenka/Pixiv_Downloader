package com.ruyue.pixivdownloader

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberImagePainter
import com.ruyue.pixivdownloader.ui.theme.PixivDownloaderTheme
import kotlinx.coroutines.*
import okio.IOException
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PixivDownloaderTheme {
                MainScreen()
            }
        }
    }

    // get path
    fun getSelectedFolderUri(): Uri? {
        val uriString = getSharedPreferences("app_prefs", MODE_PRIVATE).getString("selected_folder_uri", null)
        return uriString?.let { Uri.parse(it) }
    }
}

@RequiresApi(Build.VERSION_CODES.N)
@Preview
@Composable
fun MainScreen() {
    var pid by remember { mutableStateOf("") }
    var imageUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val selectedFolderUri = (context as? MainActivity)?.getSelectedFolderUri()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Pixiv Downloader",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = pid,
            onValueChange = { pid = it },
            label = { Text("Enter PID") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                isLoading = true
                errorMessage = null

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val urls = fetchImages(pid)
                        withContext(Dispatchers.Main) {
                            imageUrls = urls
                            isLoading = false
                            if (urls.isEmpty()) {
                                errorMessage = "No images found for the given PID."
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            errorMessage = "Failed to fetch images: ${e.message}"
                            isLoading = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Search")
        }
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        LazyColumn {
            items(imageUrls) { url ->
                ImageItem(url, onSave = {
                    if (selectedFolderUri != null) {

                        if (isImageCached(context, url)) {
                            saveImageToCache(context, url, selectedFolderUri)
                        } else {
                            downloadImage(context, url, selectedFolderUri)
                        }
                    } else {
                        Toast.makeText(context, "No folder selected", Toast.LENGTH_SHORT).show()
                    }
                })
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private fun isImageCached(context: Context, imageUrl: String): Boolean {
    val fileName = imageUrl.substringAfterLast("/")
    val cacheDir = File(context.cacheDir, "image_cache")
    val file = File(cacheDir, "$fileName.1")
    return file.exists()
}

private fun saveImageToCache(context: Context, imageUrl: String, folderUri: Uri) {
    val fileName = imageUrl.substringAfterLast("/")
    val cacheDir = File(context.cacheDir, "image_cache")
    val cachedFile = File(cacheDir, "$fileName.1")

    if (cachedFile.exists()) {
        try {
            val targetFile = File(folderUri.path, "$fileName.1")
            cachedFile.copyTo(targetFile, overwrite = true)
            Toast.makeText(context, "Image saved to folder: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Image not found in cache", Toast.LENGTH_SHORT).show()
    }
}


@Composable
fun ImageItem(imageUrl: String, onSave: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            Image(
                painter = rememberImagePainter(data = imageUrl),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentScale = ContentScale.Crop
            )
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(text = "Save")
            }
        }
    }
}

private suspend fun fetchImages(pid: String): List<String> {
    return withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        var index = 1

        if (pid.isEmpty()) {
            return@withContext emptyList()
        }

        while (true) {
            val jpgUrl = if (index == 1) {
                "https://pixiv.re/$pid.jpg"
            } else {
                "https://pixiv.re/$pid-$index.jpg"
            }

            val pngUrl = if (index == 1) {
                "https://pixiv.re/$pid.png"
            } else {
                "https://pixiv.re/$pid-$index.png"
            }

            val validUrl = when {
                checkImageExists(jpgUrl) -> jpgUrl
                checkImageExists(pngUrl) -> pngUrl
                else -> break
            }

            urls.add(validUrl)
            index++
        }
        urls
    }
}

private fun checkImageExists(url: String): Boolean {
    return try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connect()
        val contentType = connection.getHeaderField("Content-Type")
        connection.responseCode == HttpURLConnection.HTTP_OK && contentType?.startsWith("image") == true
    } catch (e: Exception) {
        false
    }
}

@RequiresApi(Build.VERSION_CODES.N)
private fun isValidTreeUri(uri: Uri): Boolean {
    return DocumentsContract.isTreeUri(uri)
            && uri.authority == "com.android.externalstorage.documents"
}

@RequiresApi(Build.VERSION_CODES.N)
private fun downloadImage(context: Context, imageUrl: String, folderUri: Uri) {
    if (!isValidTreeUri(folderUri)) {
        Toast.makeText(context, "error path", Toast.LENGTH_SHORT).show()
        return
    }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            // getpatch
            val documentId = DocumentsContract.getTreeDocumentId(folderUri)
            val targetDirUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, documentId)

            // write files message
            val fileName = imageUrl.substringAfterLast("/")
            val mimeType = when {
                fileName.endsWith(".jpg", true) -> "image/jpeg"
                fileName.endsWith(".png", true) -> "image/png"
                else -> "application/octet-stream"
            }
            val safeName = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")

            val fileUri = DocumentsContract.createDocument(
                context.contentResolver,
                targetDirUri,
                mimeType,
                safeName
            ) ?: throw IOException("cannot selected path")

            context.contentResolver.openOutputStream(fileUri)?.use { output ->
                URL(imageUrl).openStream().use { input ->
                    input.copyTo(output)
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "save successful: ${fileUri.lastPathSegment}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "save failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
