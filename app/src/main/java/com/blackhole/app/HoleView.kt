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
    var running = false
        private set
    init { contentDescription = "Black hole. Tap to download copied video link"; isClickable = true; isFocusable = true }
    fun animateHole(active: Boolean) {
        running = active
        if(active && animator == null && isAttachedToWindow) {
            animator = ValueAnimator.ofFloat(angle, angle+360f).apply {
                duration = 14000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { angle = (it.animatedValue as Float) % 360f; invalidate() }; start()
            }
        } else if(!active) { animator?.cancel(); animator=null }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.rotate(angle, width/2f, height/2f)
        bounds.set(0f,0f,width.toFloat(),height.toFloat())
        canvas.drawBitmap(bitmap,null,bounds,paint)
        canvas.restore()
    }
    override fun onDetachedFromWindow() { animator?.cancel(); animator=null; super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animateHole(running) }
}
