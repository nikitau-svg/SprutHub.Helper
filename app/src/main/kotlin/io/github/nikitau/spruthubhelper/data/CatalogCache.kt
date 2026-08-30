package io.github.nikitau.spruthubhelper.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CatalogCache(context: Context) {
    private val file = File(context.filesDir, "spruthub_catalog_v1.json")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): SprutCatalog = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<SprutCatalog>(file.readText()) }.getOrDefault(SprutCatalog())
    }

    suspend fun write(catalog: SprutCatalog) = withContext(Dispatchers.IO) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(catalog))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }
}
