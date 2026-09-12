package com.spoilerguard.app

import android.content.Context

object KeywordStore {
    private const val PREFS = "spoilerguard_prefs"
    private const val KEY_WORDS = "keywords"

    fun getKeywords(context: Context): MutableList<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_WORDS, "") ?: ""
        return if (raw.isBlank()) mutableListOf() else raw.split("||").toMutableList()
    }

    fun saveKeywords(context: Context, keywords: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WORDS, keywords.joinToString("||")).apply()
    }

    fun addKeyword(context: Context, keyword: String) {
        // "||" sert de séparateur en stockage : on le retire de la saisie,
        // sinon un mot-clé qui en contient couperait la liste en deux.
        val trimmed = keyword.replace("||", "").trim()
        if (trimmed.isEmpty()) return
        val list = getKeywords(context)
        if (list.none { it.equals(trimmed, ignoreCase = true) }) {
            list.add(trimmed)
            saveKeywords(context, list)
        }
    }

    fun removeKeyword(context: Context, keyword: String) {
        val list = getKeywords(context)
        list.removeAll { it.equals(keyword, ignoreCase = true) }
        saveKeywords(context, list)
    }
}
