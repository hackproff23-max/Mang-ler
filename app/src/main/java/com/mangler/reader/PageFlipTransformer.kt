package com.mangler.reader

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Efeito de folha virando para o lado: a página que sai gira em torno da
 * lombada (esquerda no modo LTR, direita no modo mangá/RTL), revelando a próxima.
 */
class PageFlipTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val rtl = page.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val dir = if (rtl) -1f else 1f
        val w = page.width.toFloat()
        page.cameraDistance = w * 12f

        if (position <= -1f || position >= 1f) {
            page.alpha = 0f
            page.rotationY = 0f
            return
        }

        page.alpha = 1f
        // Mantém a página parada no lugar; só a que sai gira.
        page.translationX = -position * w * dir

        if (position <= 0f) {
            page.translationZ = 1f
            page.pivotX = if (rtl) w else 0f
            page.pivotY = page.height / 2f
            page.rotationY = 90f * position * dir
            page.alpha = 1f - abs(position) * 0.35f
        } else {
            page.translationZ = 0f
            page.rotationY = 0f
        }
    }
}
