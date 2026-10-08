package com.socksrelay.vertex

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.socksrelay.vertex.databinding.ActivityDocumentViewerBinding

/**
 * Displays one of the bundled markdown documents (privacy policy, terms
 * of service, about, contact) from `assets/`. Deliberately bundled rather
 * than fetched from a URL — none of those documents are hosted anywhere
 * yet (see the placeholders inside them), so this keeps them functional
 * immediately without depending on external hosting.
 */
class DocumentViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ASSET_FILE_NAME = "extra_asset_file_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityDocumentViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val fileName = intent.getStringExtra(EXTRA_ASSET_FILE_NAME)

        val markdown = try {
            fileName?.let { assets.open(it).bufferedReader().use { reader -> reader.readText() } }
        } catch (e: Exception) {
            null
        }

        binding.documentText.text = if (markdown != null) {
            MarkdownLite.render(markdown)
        } else {
            getString(R.string.document_load_failed)
        }
    }
}
