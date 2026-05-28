package com.cuarzopolar.permission

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator

class LaserGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val lineCount = 5
    private val progresses = FloatArray(lineCount * 2) { 0f }
    private val retracts   = FloatArray(lineCount * 2) { 0f }
    private var pulseFactor = 0f
    private var pulseAnimator: ValueAnimator? = null
    private var linesComplete = 0
    private var retracting = false
    private var retractComplete = 0
    private val activeAnimators = mutableListOf<ValueAnimator>()

    private val dp = context.resources.displayMetrics.density

    private val outerHalo = stroke(25,  255,   0,   0, 22f * dp)
    private val wideGlow  = stroke(65,  255,  20,  20,  9f * dp)
    private val midBeam   = stroke(140, 255,  60,  60,  3f * dp)
    private val innerBeam = stroke(215, 255, 140, 140, 1.3f * dp)
    private val whiteCore = stroke(255, 255, 255, 255, 0.6f * dp)
    private val beamLayers = arrayOf(outerHalo, wideGlow, midBeam, innerBeam, whiteCore)

    private val tipHalo   = fill(55,  255,  80,  80)
    private val tipFlare  = fill(140, 255, 210, 210)
    private val tipPoint  = fill(255, 255, 255, 255)

    private fun stroke(a: Int, r: Int, g: Int, b: Int, w: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(a, r, g, b); strokeWidth = w; style = Paint.Style.STROKE
        }

    private fun fill(a: Int, r: Int, g: Int, b: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(a, r, g, b); style = Paint.Style.FILL
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f) return

        val vStep = w / (lineCount + 1)
        val hStep = h / (lineCount + 1)

        val boost = 0.6f + 0.4f * pulseFactor
        outerHalo.alpha = (25 * boost).toInt().coerceIn(0, 255)
        wideGlow.alpha  = (65 * boost).toInt().coerceIn(0, 255)

        for (i in 0 until lineCount) {
            val p = progresses[i]
            if (p <= 0f) continue
            val x = (i + 1) * vStep
            if (retracting) {
                val r = retracts[i]
                if (r >= 1f) continue
                val tailY = h * r
                beamLayers.forEach { canvas.drawLine(x, tailY, x, h, it) }
                if (r > 0f) drawTip(canvas, x, tailY)
            } else {
                val tipY = h * p
                beamLayers.forEach { canvas.drawLine(x, 0f, x, tipY, it) }
                if (p < 1f) drawTip(canvas, x, tipY)
            }
        }

        for (i in 0 until lineCount) {
            val p = progresses[lineCount + i]
            if (p <= 0f) continue
            val y = (i + 1) * hStep
            if (retracting) {
                val r = retracts[lineCount + i]
                if (r >= 1f) continue
                val tailX = w * r
                beamLayers.forEach { canvas.drawLine(tailX, y, w, y, it) }
                if (r > 0f) drawTip(canvas, tailX, y)
            } else {
                val tipX = w * p
                beamLayers.forEach { canvas.drawLine(0f, y, tipX, y, it) }
                if (p < 1f) drawTip(canvas, tipX, y)
            }
        }
    }

    private fun drawTip(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, 11f * dp, tipHalo)
        canvas.drawCircle(x, y,  5f * dp, tipFlare)
        canvas.drawCircle(x, y,  2f * dp, tipPoint)
    }

    private fun cancelAll() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        activeAnimators.forEach { it.cancel() }
        activeAnimators.clear()
    }

    fun animateIn() {
        cancelAll()
        linesComplete = 0
        retracting = false
        retractComplete = 0
        progresses.fill(0f)
        retracts.fill(0f)
        pulseFactor = 0f
        invalidate()
        for (i in 0 until lineCount) {
            fireBeam(i,             delay = i * 2 * 60L)
            fireBeam(lineCount + i, delay = (i * 2 + 1) * 60L)
        }
    }

    private fun fireBeam(index: Int, delay: Long) {
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            startDelay = delay
            interpolator = AccelerateInterpolator(1.6f)
            addUpdateListener {
                progresses[index] = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    activeAnimators.remove(this@apply)
                    if (cancelled) return
                    if (++linesComplete == lineCount * 2) startPulse()
                }
            })
            start()
        }
        activeAnimators.add(anim)
    }

    private fun startPulse() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { pulseFactor = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun animateOut() {
        cancelAll()
        retracting = true
        retractComplete = 0
        retracts.fill(0f)
        var delay = 0L
        for (step in 0 until lineCount) {
            val hIdx = lineCount + (lineCount - 1 - step)
            val vIdx = lineCount - 1 - step
            retractBeam(hIdx, delay); delay += 60L
            retractBeam(vIdx, delay); delay += 60L
        }
    }

    private fun retractBeam(index: Int, delay: Long) {
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            startDelay = delay
            interpolator = AccelerateInterpolator(1.6f)
            addUpdateListener {
                retracts[index] = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    activeAnimators.remove(this@apply)
                    if (cancelled) return
                    if (++retractComplete == lineCount * 2) finishRetract()
                }
            })
            start()
        }
        activeAnimators.add(anim)
    }

    private fun finishRetract() {
        if (!retracting) return  // animateIn() was called mid-retract — abort
        visibility = INVISIBLE
        retracting = false
        progresses.fill(0f)
        retracts.fill(0f)
        pulseFactor = 0f
        linesComplete = 0
        retractComplete = 0
        invalidate()
    }
}
