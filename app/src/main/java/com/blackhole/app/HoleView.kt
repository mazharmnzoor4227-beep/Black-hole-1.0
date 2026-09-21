package com.blackhole.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator

class HoleView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Preserve the supplied photograph; remove only near-black compression noise so
    // its square edges disappear into the exact #000000 activity background.
    private val bitmap = BitmapFactory.decodeResource(resources, R.drawable.black_hole).copy(Bitmap.Config.ARGB_8888, true).also { b ->
        val pixels = IntArray(b.width * b.height)
        b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
        for(i in pixels.indices) {
            val p=pixels[i]
            if(Color.red(p) < 13 && Color.green(p) < 13 && Color.blue(p) < 13) pixels[i] = Color.BLACK
        }
        b.setPixels(pixels,0,b.width,0,0,b.width,b.height)
    }
    private val bounds = RectF()
    private var angle = 0f
    private var animator: ValueAnimator? = null
    private var feedback: ValueAnimator? = null
    private var pulse = 0f
    var running = false
        private set
    init { contentDescription = "Black hole. Tap to download copied video link"; isClickable = true; isFocusable = true }
    fun animateHole(active: Boolean) {
        running = active
        if (!ValueAnimator.areAnimatorsEnabled()) {
            animator?.cancel(); animator = null; angle = 0f; invalidate(); return
        }
        if(active && animator == null && isAttachedToWindow) {
            animator = ValueAnimator.ofFloat(angle, angle+360f).apply {
                duration = 14000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { val value = it.animatedValue as Float; angle = if(value.isFinite()) value % 360f else 0f; invalidate() }; start()
            }
        } else if(!active) { animator?.cancel(); animator=null }
    }
    fun acknowledgeLink() {
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) return
        feedback?.cancel()
        feedback = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { pulse = 0f; invalidate() }
            })
            start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        val wave = kotlin.math.sin(pulse * Math.PI).toFloat()
        val shake = kotlin.math.sin(pulse * Math.PI * 8).toFloat() * wave
        canvas.translate(shake * resources.displayMetrics.density * 2f, 0f)
        canvas.scale(1f + wave * .025f, 1f + wave * .025f, width/2f, height/2f)
        canvas.rotate(angle + shake * 1.5f, width/2f, height/2f)
        bounds.set(0f,0f,width.toFloat(),height.toFloat())
        canvas.drawBitmap(bitmap,null,bounds,paint)
        canvas.restore()
    }
    override fun onDetachedFromWindow() { animator?.cancel(); animator=null; feedback?.cancel(); feedback=null; pulse=0f; super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animateHole(running) }
}
