// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.TreeNode

/**
 * The text an app hands accessibility services, with where each character is
 * drawn. Most ordinary apps draw real text (`TextView`s, WebView and Chrome
 * pages), and for those this is exact and immediate; a game, a video frame or
 * a picture gives nothing here, and is left to OCR.
 *
 * Main thread or a background one, but one window at a time: node calls go
 * to the app's process and wait for it.
 */
object TreeReader {
    /** What a window's tree gave: its text nodes, and where sensitive fields are, so OCR keeps off them too. */
    class Result(val nodes: List<TreeNode>, val sensitive: List<Box>)

    fun read(root: AccessibilityNodeInfo?): Result {
        root ?: return Result(emptyList(), emptyList())
        val nodes = ArrayList<TreeNode>()
        val sensitive = ArrayList<Box>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            try {
                if (!node.isVisibleToUser) continue
                val bounds = Rect().also(node::getBoundsInScreen).let { Box(it.left, it.top, it.right, it.bottom) }
                if (isSensitive(node)) {
                    // Its text is never asked for, and nothing under it is looked at.
                    sensitive += bounds
                    continue
                }
                val text = node.text?.toString()
                if (!text.isNullOrBlank()) nodes += TreeNode(text, bounds, characterBoxes(node, text))
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue += it }
            } catch (_: Exception) {
                // The app went away or changed under us; what was read so far stands.
            }
        }
        return Result(nodes, sensitive)
    }

    private fun isSensitive(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && node.isAccessibilityDataSensitive
    }

    /** Where each character of [text] is drawn, when the node can say; null when it cannot. */
    private fun characterBoxes(node: AccessibilityNodeInfo, text: String): List<Box?>? {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        if (key !in node.availableExtraData) return null
        val length = minOf(text.length, MAX_CHARS)
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        }
        if (!node.refreshWithExtraData(key, args)) return null
        val rects = node.extras.getParcelableArray(key, RectF::class.java) ?: return null
        val boxes = ArrayList<Box?>(text.length)
        for (i in text.indices) {
            val r = rects.getOrNull(i)
            boxes += if (r == null || r.isEmpty) null else Box(r.left.toInt(), r.top.toInt(), kotlin.math.ceil(r.right).toInt(), kotlin.math.ceil(r.bottom).toInt())
        }
        return boxes
    }

    /** A window with more nodes than this is a long feed or page; what is on screen comes first in it. */
    private const val MAX_NODES = 3000

    /** Characters asked for in one node; a long article's node beyond this is placed by guess. */
    private const val MAX_CHARS = 4000
}
