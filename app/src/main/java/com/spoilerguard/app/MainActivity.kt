package com.spoilerguard.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.spoilerguard.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, KeywordStore.getKeywords(this))
        binding.listKeywords.adapter = adapter

        binding.btnAdd.setOnClickListener {
            val text = binding.editKeyword.text.toString()
            if (text.isNotBlank()) {
                KeywordStore.addKeyword(this, text)
                refreshList()
                binding.editKeyword.text?.clear()
            }
        }

        binding.listKeywords.setOnItemClickListener { _, _, position, _ ->
            val word = adapter.getItem(position) ?: return@setOnItemClickListener
            KeywordStore.removeKeyword(this, word)
            refreshList()
            Toast.makeText(this, "Supprimé : $word", Toast.LENGTH_SHORT).show()
        }

        binding.btnEnableService.setOnClickListener {
            Toast.makeText(
                this,
                "Cherche \"SpoilerGuard\" dans la liste et active-le",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun refreshList() {
        adapter.clear()
        adapter.addAll(KeywordStore.getKeywords(this))
        adapter.notifyDataSetChanged()
    }
}
