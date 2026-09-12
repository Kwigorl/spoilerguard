package com.spoilerguard.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.text.Normalizer

/**
 * Parcourt le contenu affiché à l'écran (toutes apps) et masque uniquement
 * les zones dont le texte contient un mot-clé filtré.
 *
 * Points clés de conception :
 *  - On ignore nos propres événements, sinon l'ajout d'un overlay déclenche
 *    un nouvel événement d'accessibilité et le service part en boucle.
 *  - Les révélations sont mémorisées par texte (pas par position), donc
 *    scroller ne fait pas réapparaître le cache sur un contenu déjà lu, et
 *    on ne réaffiche jamais un overlay sur des coordonnées devenues obsolètes.
 *  - Tout est nettoyé à l'arrêt du service, sinon les overlays restent
 *    collés à l'écran jusqu'au redémarrage du téléphone.
 */
class SpoilerAccessibilityService : AccessibilityService() {

    private val overlays = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingScan: Runnable? = null

    /** Textes révélés par l'utilisateur -> horodatage de la révélation. */
    private val revealed = mutableMapOf<String, Long>()

    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    companion object {
        private const val SCAN_DEBOUNCE_MS = 250L
        private const val REVEAL_DURATION_MS = 60_000L
        private const val MAX_OVERLAYS = 40
        private const val MAX_TREE_DEPTH = 60
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Garde anti-boucle : nos propres fenêtres génèrent des événements.
        if (event?.packageName == packageName) return

        // Debounce : on attend une pause dans les événements avant de scanner,
        // pour ne pas saturer le thread UI pendant un scroll rapide.
        pendingScan?.let { handler.removeCallbacks(it) }
        val scan = Runnable { scanScreen() }
        pendingScan = scan
        handler.postDelayed(scan, SCAN_DEBOUNCE_MS)
    }

    override fun onInterrupt() {
        clearOverlays()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanUp()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    private fun cleanUp() {
        handler.removeCallbacksAndMessages(null)
        pendingScan = null
        clearOverlays()
        revealed.clear()
    }

    private fun scanScreen() {
        clearOverlays()
        pruneRevealed()

        val keywords = KeywordStore.getKeywords(this)
        if (keywords.isEmpty()) return

        val root = rootInActiveWindow ?: return
        val matches = mutableListOf<Match>()
        findMatches(root, keywords, matches, depth = 0)
        matches.forEach { addOverlay(it) }
    }

    /** Une zone à masquer : sa position et le texte qui l'a déclenchée. */
    private data class Match(val rect: Rect, val text: String)

    private fun findMatches(
        node: AccessibilityNodeInfo?,
        keywords: List<String>,
        results: MutableList<Match>,
        depth: Int
    ) {
        // Garde-fous : évite les arbres trop profonds ou un nombre d'overlays
        // qui ferait ramer le téléphone.
        if (node == null || depth > MAX_TREE_DEPTH || results.size >= MAX_OVERLAYS) return

        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) {
            val key = normalize(text)
            // Si l'utilisateur a déjà révélé ce texte, on ne le remasque pas.
            if (!revealed.containsKey(key) && containsSpoiler(text, keywords)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    results.add(Match(rect, text))
                    // Ce noeud est déjà couvert : inutile de descendre plus bas,
                    // ça empilerait plusieurs overlays sur la même zone.
                    return
                }
            }
        }

        for (i in 0 until node.childCount) {
            findMatches(node.getChild(i), keywords, results, depth + 1)
        }
    }

    private fun containsSpoiler(text: String, keywords: List<String>): Boolean {
        val normalizedText = normalize(text)
        return keywords.any { kw ->
            val normalizedKw = normalize(kw)
            if (normalizedKw.isBlank()) return@any false
            // Frontières de mots basées sur lettres/chiffres : évite que
            // "OM" matche "comme", tout en acceptant les mots-clés à espaces.
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(normalizedKw)}(?![\\p{L}\\p{N}])"
            )
            pattern.containsMatchIn(normalizedText)
        }
    }

    private fun normalize(input: String): String {
        val lowered = input.lowercase()
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{Mn}"), "") // retire les accents
    }

    private fun pruneRevealed() {
        val now = System.currentTimeMillis()
        val expired = revealed.filterValues { now - it > REVEAL_DURATION_MS }.keys
        expired.forEach { revealed.remove(it) }
    }

    private fun addOverlay(match: Match) {
        val tv = TextView(this).apply {
            text = "🙈 Spoiler masqué — touche pour révéler"
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            alpha = 0.95f
        }

        val params = WindowManager.LayoutParams(
            match.rect.width().coerceAtLeast(200),
            match.rect.height().coerceAtLeast(60),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = match.rect.left
            y = match.rect.top
        }

        tv.setOnClickListener {
            // On mémorise le TEXTE révélé, puis on retire simplement l'overlay.
            // Aucun réaffichage différé : remettre un cache sur des coordonnées
            // figées poserait un rectangle au hasard si l'utilisateur a scrollé
            // ou changé d'application entre-temps.
            revealed[normalize(match.text)] = System.currentTimeMillis()
            removeOverlay(tv)
        }

        try {
            windowManager.addView(tv, params)
            overlays.add(tv)
        } catch (e: Exception) {
            // La fenêtre cible a pu disparaître entre le calcul et l'affichage.
        }
    }

    private fun removeOverlay(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // Déjà retirée.
        }
        overlays.remove(view)
    }

    private fun clearOverlays() {
        // Copie de la liste : removeOverlay modifie `overlays` pendant l'itération.
        overlays.toList().forEach { removeOverlay(it) }
        overlays.clear()
    }
}
