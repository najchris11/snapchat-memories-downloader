package com.najdev.snapvault

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter

val LocalAwtWindow = compositionLocalOf<Window?> { null }

@Composable
actual fun DraggableArea(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val window = LocalAwtWindow.current
    if (window == null) {
        Box(modifier = modifier, content = content)
        return
    }
    val moveHandler = remember(window) { DragMoveHandler(window) }
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                moveHandler.start()
            }
        },
        content = content
    )
}

private class DragMoveHandler(private val window: Window) {
    private var windowStart: Point? = null
    private var mouseStart: Point? = null

    private val dragListener = object : MouseMotionAdapter() {
        override fun mouseDragged(e: MouseEvent) {
            val ws = windowStart ?: return
            val ms = mouseStart ?: return
            val cur = MouseInfo.getPointerInfo()?.location ?: return
            window.setLocation(ws.x + cur.x - ms.x, ws.y + cur.y - ms.y)
        }
    }
    private val releaseListener = object : MouseAdapter() {
        override fun mouseReleased(e: MouseEvent) {
            window.removeMouseMotionListener(dragListener)
            window.removeMouseListener(this)
        }
    }

    fun start() {
        mouseStart = MouseInfo.getPointerInfo()?.location ?: return
        windowStart = window.location
        window.addMouseMotionListener(dragListener)
        window.addMouseListener(releaseListener)
    }
}
