package org.andresoviedo.app.model3D.view

import android.app.Activity
import org.andresoviedo.android_3d_model_engine.view.ModelSurfaceView
import org.andresoviedo.android_3d_model_engine.controller.TouchController
import org.andresoviedo.android_3d_model_engine.services.SceneLoader
import org.andresoviedo.app.model3D.view.ModelViewerGUI
import org.andresoviedo.android_3d_model_engine.collision.CollisionController
import org.andresoviedo.android_3d_model_engine.camera.CameraController
import android.os.Bundle
import org.andresoviedo.android_3d_model_engine.services.LoaderTask
import org.andresoviedo.app.model3D.demo.DemoLoaderTask
import android.widget.Toast
import android.annotation.TargetApi
import android.os.Build
import org.andresoviedo.dddmodel2.R
import android.view.View.OnSystemUiVisibilityChangeListener
import android.content.Intent
import org.andresoviedo.util.android.ContentUtils
import org.andresoviedo.app.model3D.view.ModelActivity
import android.content.ActivityNotFoundException
import android.os.Handler
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import org.andresoviedo.android_3d_model_engine.view.ModelRenderer.ViewEvent
import org.andresoviedo.android_3d_model_engine.view.ModelRenderer
import org.andresoviedo.util.event.EventListener
import java.io.IOException
import java.lang.Exception
import java.net.URI
import java.util.*

/**
 * This activity represents the container for our 3D viewer.
 *
 * @author andresoviedo
 */
class ModelActivity : Activity(), EventListener {
    /**
     * Type of model if file name has no extension (provided though content provider)
     */
    private var paramType = 0

    /**
     * The file to load. Passed as input parameter
     */
    private var paramUri: URI? = null

    /**
     * Enter into Android Immersive mode so the renderer is full screen or not
     */
    private var immersiveMode = false

    /**
     * Background GL clear color. Default is light gray
     */
    private val backgroundColor = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
    private var gLView: ModelSurfaceView? = null
    private var touchController: TouchController? = null
    private var scene: SceneLoader? = null
    private lateinit var gui: ModelViewerGUI
    private var collisionController: CollisionController? = null
    private var handler: Handler? = null
    private var cameraController: CameraController? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i("ModelActivity", "Loading activity...")
        super.onCreate(savedInstanceState)

        // Try to get input parameters
        val b = intent.extras
        if (b != null) {
            try {
                if (b.getString("uri") != null) {
                    paramUri = URI(b.getString("uri"))
                    Log.i("ModelActivity", "Params: uri '$paramUri'")
                }
                paramType = if (b.getString("type") != null) b.getString("type")!!.toInt() else -1
                immersiveMode = "true".equals(b.getString("immersiveMode"), ignoreCase = true)
                if (b.getString("backgroundColor") != null) {
                    val backgroundColors = b.getString("backgroundColor")!!.split(" ".toRegex()).toTypedArray()
                    backgroundColor[0] = backgroundColors[0].toFloat()
                    backgroundColor[1] = backgroundColors[1].toFloat()
                    backgroundColor[2] = backgroundColors[2].toFloat()
                    backgroundColor[3] = backgroundColors[3].toFloat()
                }
            } catch (ex: Exception) {
                Log.e("ModelActivity", "Error parsing activity parameters: " + ex.message, ex)
            }
        }
        handler = Handler(mainLooper)

        // Create our 3D scenario
        Log.i("ModelActivity", "Loading Scene...")
        scene = SceneLoader(this, paramUri, paramType, gLView)
        Log.i(":::ModelActivity", "@onCreate -> loading paramUri = $paramUri, paramType = paramType")
        Log.i(":::ModelActivity", "@onCreate -> scene objects = ${scene?.objects}")
        if (paramUri == null) {
            val task: LoaderTask = DemoLoaderTask(this, null, scene)
            task.execute()
        }

/*        Log.i("ModelActivity","Loading Scene...");
        if (paramUri == null) {
            scene = new ExampleSceneLoader(this);
        } else {
            scene = new SceneLoader(this, paramUri, paramType, gLView);
        }*/try {
            Log.i("ModelActivity", "Loading GLSurfaceView...")
            setContentView(R.layout.activity_model)
            gLView = findViewById(R.id.model_view)
//            gLView?.init(backgroundColor, scene)
            gLView?.init(null, scene)
            gLView!!.addListener(this)
            scene!!.setView(gLView)
        } catch (e: Exception) {
            Log.e("ModelActivity", e.message, e)
            Toast.makeText(this, """
     Error loading OpenGL view:
     ${e.message}
     """.trimIndent(), Toast.LENGTH_LONG).show()
        }
        try {
            Log.i("ModelActivity", "Loading TouchController...")
            touchController = TouchController(this)
            touchController!!.addListener(this)
        } catch (e: Exception) {
            Log.e("ModelActivity", e.message, e)
            Toast.makeText(this, """
     Error loading TouchController:
     ${e.message}
     """.trimIndent(), Toast.LENGTH_LONG).show()
        }
        try {
            Log.i("ModelActivity", "Loading CollisionController...")
            collisionController = CollisionController(gLView, scene)
            collisionController!!.addListener(scene)
            touchController!!.addListener(collisionController)
            touchController!!.addListener(scene)
        } catch (e: Exception) {
            Log.e("ModelActivity", e.message, e)
            Toast.makeText(this, """
     Error loading CollisionController
     ${e.message}
     """.trimIndent(), Toast.LENGTH_LONG).show()
        }
        try {
            Log.i("ModelActivity", "Loading CameraController...")
            cameraController = CameraController(scene!!.camera)
            gLView!!.modelRenderer?.addListener(cameraController!!)
            touchController!!.addListener(cameraController)
        } catch (e: Exception) {
            Log.e("ModelActivity", e.message, e)
            Toast.makeText(this, "Error loading CameraController" + e.message, Toast.LENGTH_LONG).show()
        }
        try {
            // TODO: finish UI implementation
            Log.i("ModelActivity", "Loading GUI...")
            gui = ModelViewerGUI(gLView, scene)
            touchController!!.addListener(gui)
            gLView!!.addListener(gui)
            scene!!.addGUIObject(gui)
        } catch (e: Exception) {
            Log.e("ModelActivity", e.message, e)
            Toast.makeText(this, "Error loading GUI" + e.message, Toast.LENGTH_LONG).show()
        }

        // Show the Up button in the action bar.
        setupActionBar()
        setupOnSystemVisibilityChangeListener()

        // load model
        scene!!.init()
        Log.i("ModelActivity", "Finished loading")
        findViewById<Button>(R.id.btn2).setOnClickListener {
            Log.i(":::ModelActivity", "scen objects: ${scene!!.objects}")
        }
        findViewById<Button>(R.id.btn3).setOnClickListener {
            val rot = scene!!.objects!!.get(0)!!.rotation
            scene?.objects?.get(0)?.rotation = floatArrayOf(rot[0] + 60f, rot[1] + 60f, rot[2] + 60f)
        }
    }

    /**
     * Set up the [android.app.ActionBar], if the API is available.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private fun setupActionBar() {
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
        // getActionBar().setDisplayHomeAsUpEnabled(true);
        // }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.model, menu)
        return true
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun setupOnSystemVisibilityChangeListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return
        }
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility: Int ->
            // Note that system bars will only be "visible" if none of the
            // LOW_PROFILE, HIDE_NAVIGATION, or FULLSCREEN flags are set.
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                // The system bars are visible. Make any desired
                hideSystemUIDelayed()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUIDelayed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.model_toggle_wireframe -> scene!!.toggleWireframe()
            R.id.model_toggle_boundingbox -> scene!!.toggleBoundingBox()
            R.id.model_toggle_skybox -> gLView!!.toggleSkyBox()
            R.id.model_toggle_textures -> scene!!.toggleTextures()
            R.id.model_toggle_animation -> scene!!.toggleAnimation()
            R.id.model_toggle_smooth -> scene!!.toggleSmooth()
            R.id.model_toggle_collision -> scene!!.toggleCollision()
            R.id.model_toggle_lights -> scene!!.toggleLighting()
            R.id.model_toggle_stereoscopic -> scene!!.toggleStereoscopic()
            R.id.model_toggle_blending -> scene!!.toggleBlending()
            R.id.model_toggle_immersive -> toggleImmersive()
            R.id.model_load_texture -> {
                val target = ContentUtils.createGetContentIntent("image/*")
                val intent = Intent.createChooser(target, "Select a file")
                try {
                    startActivityForResult(intent, REQUEST_CODE_LOAD_TEXTURE)
                } catch (e: ActivityNotFoundException) {
                    // The reason for the existence of aFileChooser
                }
            }
        }
        hideSystemUIDelayed()
        return super.onOptionsItemSelected(item)
    }

    private fun toggleImmersive() {
        immersiveMode = !immersiveMode
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return
        }
        if (immersiveMode) {
            hideSystemUI()
        } else {
            showSystemUI()
        }
        Toast.makeText(this, "Fullscreen " + immersiveMode, Toast.LENGTH_SHORT).show()
    }

    private fun hideSystemUIDelayed() {
        if (!immersiveMode) {
            return
        }
        handler!!.removeCallbacksAndMessages(null)
        handler!!.postDelayed({ hideSystemUI() }, FULLSCREEN_DELAY.toLong())
    }

    private fun hideSystemUI() {
        if (!immersiveMode) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            hideSystemUIKitKat()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            hideSystemUIJellyBean()
        }
    }

    // This snippet hides the system bars.
    @TargetApi(Build.VERSION_CODES.KITKAT)
    private fun hideSystemUIKitKat() {
        // Set the IMMERSIVE flag.
        // Set the content to appear under the system bars so that the content
        // doesn't resize when the system bars hide and show.
        val decorView = window.decorView
        decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                or View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                or View.SYSTEM_UI_FLAG_IMMERSIVE)
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun hideSystemUIJellyBean() {
        val decorView = window.decorView
        decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LOW_PROFILE)
    }

    // This snippet shows the system bars. It does this by removing all the flags
    // except for the ones that make the content appear under the system bars.
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun showSystemUI() {
        handler!!.removeCallbacksAndMessages(null)
        val decorView = window.decorView
        decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (resultCode != RESULT_OK) {
            return
        }
        when (requestCode) {
            REQUEST_CODE_LOAD_TEXTURE -> {
                // The URI of the selected file
                val uri = data.data
                if (uri != null) {
                    Log.i("ModelActivity", "Loading texture '$uri'")
                    try {
                        ContentUtils.setThreadActivity(this)
                        scene!!.loadTexture(null, uri)
                    } catch (ex: IOException) {
                        Log.e("ModelActivity", "Error loading texture: " + ex.message, ex)
                        Toast.makeText(this, "Error loading texture '$uri'. " + ex
                                .message, Toast.LENGTH_LONG).show()
                    } finally {
                        ContentUtils.setThreadActivity(null)
                    }
                }
            }
        }
    }

    override fun onEvent(event: EventObject): Boolean {
        if (event is ViewEvent) {
            val viewEvent = event
            if (viewEvent.code == ViewEvent.Code.SURFACE_CHANGED) {
                touchController!!.setSize(viewEvent.width, viewEvent.height)
                gLView!!.setTouchController(touchController)

                // process event in GUI
                gui.setSize(viewEvent.width, viewEvent.height)
                gui.isVisible = true
            }
        }
        return true
    }

    companion object {
        private const val REQUEST_CODE_LOAD_TEXTURE = 1000
        private const val FULLSCREEN_DELAY = 10000
    }
}