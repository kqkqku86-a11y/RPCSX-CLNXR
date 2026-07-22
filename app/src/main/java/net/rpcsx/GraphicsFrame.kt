package net.rpcsx

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

class GraphicsFrame : SurfaceView, SurfaceHolder.Callback {
    constructor(context: Context) : super(context) {
        holder.addCallback(this)
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        holder.addCallback(this)
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        holder.addCallback(this)
    }

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        holder.addCallback(this)
    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        // Guard against surfaceCreated being called before RPCSX is initialized.
        // This can happen if the View hierarchy is created before the graphics
        // system is ready, causing EGL_NOT_INITIALIZED errors on the RenderThread.
        if (RPCSX.initialized && RPCSX.activeLibrary.value != null) {
            try {
                RPCSX.instance.surfaceEvent(p0.surface, 0)
            } catch (e: Exception) {
                Log.e("GraphicsFrame", "Failed to handle surface creation", e)
            }
        } else {
            Log.w("GraphicsFrame", "Ignoring surfaceCreated: RPCSX not yet initialized")
        }
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        // SurfaceView.Callback interface requirement; currently no-op
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        // SurfaceView.Callback interface requirement; currently no-op
    }
}
