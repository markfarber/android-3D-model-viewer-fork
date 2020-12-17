package org.andresoviedo.android_3d_model_engine.view

import android.app.Activity
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import org.andresoviedo.android_3d_model_engine.controller.TouchController
import org.andresoviedo.android_3d_model_engine.services.SceneLoader
import org.andresoviedo.util.android.AndroidUtils
import org.andresoviedo.util.event.EventListener
import java.util.*

/**
 * This is the actual opengl view. From here we can detect touch gestures for example
 *
 * @author andresoviedo
 */
class ModelSurfaceView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : GLSurfaceView(ctx, attrs), EventListener {
    var backgroundColor: FloatArray? = null
    var scene: SceneLoader? = null
    var modelRenderer: ModelRenderer? = null
    private var touchController: TouchController? = null
    private val listeners: MutableList<EventListener> = ArrayList()

    fun init(_backgroundColor: FloatArray?, _scene: SceneLoader?) {
        backgroundColor = _backgroundColor
        scene = _scene
        try {
            Log.i("ModelSurfaceView", "Loading [OpenGL 2] ModelSurfaceView...")

            // Create an OpenGL ES 2.0 context.
            setEGLContextClientVersion(2)

            // This is the actual renderer of the 3D space
            modelRenderer = ModelRenderer(context, this, backgroundColor, scene)
            modelRenderer?.addListener(this)
            setRenderer(modelRenderer)
        } catch (e: Exception) {
            Log.e("ModelActivity", e.message, e)
            Toast.makeText(context, """
     Error loading shaders:
     ${e.message}
     """.trimIndent(), Toast.LENGTH_LONG).show()
            throw RuntimeException(e)
        }
    }

    fun setTouchController(touchController: TouchController?) {
        this.touchController = touchController
    }

    fun addListener(listener: EventListener) {
        listeners.add(listener)
    }

    val projectionMatrix: FloatArray
        get() = modelRenderer!!.projectionMatrix
    val viewMatrix: FloatArray
        get() = modelRenderer!!.viewMatrix

    override fun onTouchEvent(event: MotionEvent): Boolean = try {
        touchController!!.onTouchEvent(event)
    } catch (ex: Exception) {
        Log.e("ModelSurfaceView", "Exception: " + ex.message, ex)
        false
    }

    private fun fireEvent(event: EventObject) {
        AndroidUtils.fireEvent(listeners, event)
    }

    override fun onEvent(event: EventObject): Boolean {
        fireEvent(event)
        return true
    }

    fun toggleLights() {
        Log.i("ModelSurfaceView", "Toggling lights...")
        modelRenderer!!.toggleLights()
    }

    fun toggleSkyBox() {
        Log.i("ModelSurfaceView", "Toggling sky box...")
        modelRenderer!!.toggleSkyBox()
    }

    fun toggleWireframe() {
        Log.i("ModelSurfaceView", "Toggling wireframe...")
        modelRenderer!!.toggleWireframe()
    }

    fun toggleTextures() {
        Log.i("ModelSurfaceView", "Toggling textures...")
        modelRenderer!!.toggleTextures()
    }

    fun toggleColors() {
        Log.i("ModelSurfaceView", "Toggling colors...")
        modelRenderer!!.toggleColors()
    }

    fun toggleAnimation() {
        Log.i("ModelSurfaceView", "Toggling animation...")
        modelRenderer!!.toggleAnimation()
    }

    val isLightsEnabled: Boolean
        get() = modelRenderer!!.isLightsEnabled
}