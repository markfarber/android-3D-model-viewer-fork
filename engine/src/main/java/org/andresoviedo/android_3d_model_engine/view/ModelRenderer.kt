package org.andresoviedo.android_3d_model_engine.view

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import org.andresoviedo.android_3d_model_engine.animation.Animator
import org.andresoviedo.android_3d_model_engine.drawer.RendererFactory
import org.andresoviedo.android_3d_model_engine.model.AnimatedModel
import org.andresoviedo.android_3d_model_engine.model.Object3DData
import org.andresoviedo.android_3d_model_engine.objects.*
import org.andresoviedo.android_3d_model_engine.services.SceneLoader
import org.andresoviedo.util.android.AndroidUtils
import org.andresoviedo.util.android.ContentUtils
import org.andresoviedo.util.android.GLUtil
import org.andresoviedo.util.event.EventListener
import java.io.ByteArrayInputStream
import java.util.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer(parent: Context?, modelSurfaceView: ModelSurfaceView,
                    backgroundColor: FloatArray?, scene: SceneLoader?) : GLSurfaceView.Renderer {
    class ViewEvent(source: Any?, val code: Code, val width: Int, val height: Int) : EventObject(source) {

        enum class Code {
            SURFACE_CREATED, SURFACE_CHANGED
        }
    }

    class FPSEvent(source: Any?, val fps: Int) : EventObject(source)

    private val backgroundColor: FloatArray
    private val scene: SceneLoader?
    private val listeners: MutableList<EventListener> = ArrayList()

    // 3D window (parent component)
    private val main: GLSurfaceView

    // width of the screen
    var width = 0
        private set

    // height of the screen
    var height = 0
        private set
    private var ratio = 0f

    /**
     * Drawer factory to get right renderer/shader based on object attributes
     */
    private val drawer: RendererFactory

    // frames per second
    private var framesPerSecondTime: Long = -1
    private var framesPerSecond = 0
    private var framesPerSecondCounter = 0

    // The wireframe associated shape (it should be made of lines only)
    private val wireframes: MutableMap<Object3DData?, Object3DData?> = HashMap()

    // The loaded textures
    private val textures: MutableMap<Any, Int> = HashMap()

    // The corresponding opengl bounding boxes and drawer
    private val boundingBoxes: MutableMap<Object3DData?, Object3DData?> = HashMap()

    // The corresponding opengl bounding boxes
    private val normals: MutableMap<Object3DData?, Object3DData> = HashMap()

    // skeleton
    private val skeleton: MutableMap<Object3DData, Object3DData?> = HashMap()
    private var debugSkeleton = false

    // 3D matrices to project our 3D world
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    // light
    private val tempVector4 = FloatArray(4)
    private val lightPosInWorldSpace = FloatArray(3)
    private val cameraPosInWorldSpace = FloatArray(3)
    private val lightPosition = floatArrayOf(0f, 0f, 0f, 1f)

    // Decoration
    private val extras: MutableList<Object3DData> = ArrayList()
    private val axis = Axis.build().setId("axis").setSolid(false).setScale(floatArrayOf(50f, 50f, 50f))
    private val gridx = Grid.build(-GRID_WIDTH, 0f, -GRID_WIDTH, GRID_WIDTH, 0f, GRID_WIDTH, GRID_SIZE).setColor(GRID_COLOR).setId("grid-x").setSolid(false)
    private val gridy = Grid.build(-GRID_WIDTH, -GRID_WIDTH, 0f, GRID_WIDTH, GRID_WIDTH, 0f, GRID_SIZE).setColor(GRID_COLOR).setId("grid-y").setSolid(false)
    private val gridz = Grid.build(0f, -GRID_WIDTH, -GRID_WIDTH, 0f, GRID_WIDTH, GRID_WIDTH, GRID_SIZE).setColor(GRID_COLOR).setId("grid-z").setSolid(false)

    // 3D stereoscopic matrix (left & right camera)
    private val viewMatrixLeft = FloatArray(16)
    private val projectionMatrixLeft = FloatArray(16)
    private val viewProjectionMatrixLeft = FloatArray(16)
    private val viewMatrixRight = FloatArray(16)
    private val projectionMatrixRight = FloatArray(16)
    private val viewProjectionMatrixRight = FloatArray(16)

    // settings
    var isLightsEnabled = true
        private set
    private var wireframeEnabled = false
    private var texturesEnabled = true
    private var colorsEnabled = true
    private var animationEnabled = true

    // skybox
    private var isDrawSkyBox = true
    private var isUseskyBoxId = 0
    private val projectionMatrixSkyBox = FloatArray(16)
    private val viewMatrixSkyBox = FloatArray(16)
    private var skyBoxes: Array<SkyBox>? = null
    private var skyBoxes3D: Array<Object3DData?>? = null

    /**
     * Whether the info of the model has been written to console log
     */
    private val infoLogged: MutableMap<String?, Boolean> = HashMap()

    /**
     * Switch to akternate drawing of right and left image
     */
    private var anaglyphSwitch = false

    /**
     * Skeleton Animator
     */
    private val animator = Animator()

    /**
     * Did the application explode?
     */
    private var fatalException = false
    fun addListener(listener: EventListener): ModelRenderer {
        listeners.add(listener)
        return this
    }

    val near: Float
        get() = Companion.near
    val far: Float
        get() = Companion.far

    fun toggleLights() {
        isLightsEnabled = !isLightsEnabled
    }

    fun toggleSkyBox() {
        isUseskyBoxId++
        if (isUseskyBoxId > 1) {
            isUseskyBoxId = -3
        }
        Log.i("ModelRenderer", "Toggled skybox. Idx: $isUseskyBoxId")
    }

    fun toggleWireframe() {
        wireframeEnabled = !wireframeEnabled
    }

    fun toggleTextures() {
        texturesEnabled = !texturesEnabled
    }

    fun toggleColors() {
        colorsEnabled = !colorsEnabled
    }

    fun toggleAnimation() {
        animationEnabled = !animationEnabled
    }

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        // log event
        Log.d(TAG, "onSurfaceCreated. config: $config")

        // Set the background frame color
        GLES20.glClearColor(backgroundColor[0], backgroundColor[1], backgroundColor[2], backgroundColor[3])

        // Use culling to remove back faces.
        // Don't remove back faces so we can see them
        // GLES20.glEnable(GLES20.GL_CULL_FACE);

        // Enable depth testing for hidden-surface elimination.
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Enable not drawing out of view port
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        AndroidUtils.fireEvent(listeners, ViewEvent(this, ViewEvent.Code.SURFACE_CREATED, 0, 0))

        // init variables having android context
        ContentUtils.setThreadActivity(main.context)
        skyBoxes = arrayOf(SkyBox.getSkyBox1(), SkyBox.getSkyBox2())
        skyBoxes3D = arrayOfNulls(skyBoxes!!.size)
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        this.width = width
        this.height = height

        // Adjust the viewport based on geometry changes, such as screen rotation
        GLES20.glViewport(0, 0, width, height)

        // the projection matrix is the 3D virtual space (cube) that we want to project
        ratio = width.toFloat() / height
        Log.d(TAG, "onSurfaceChanged: projection: [" + -ratio + "," + ratio + ",-1,1]-near/far[1,10]")
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, near, far)
        Matrix.frustumM(projectionMatrixRight, 0, -ratio, ratio, -1f, 1f, near, far)
        Matrix.frustumM(projectionMatrixLeft, 0, -ratio, ratio, -1f, 1f, near, far)
        Matrix.orthoM(projectionMatrixSkyBox, 0, -ratio, ratio, -1f, 1f, near, far)
        AndroidUtils.fireEvent(listeners, ViewEvent(this, ViewEvent.Code.SURFACE_CHANGED, width, height))
    }

    override fun onDrawFrame(unused: GL10) {
        if (fatalException) {
            return
        }
        try {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glScissor(0, 0, width, height)

            // Draw background color
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (scene == null) {
                // scene not ready
                return
            }
            var colorMask = BLENDING_MASK_DEFAULT
            if (scene.isBlendingEnabled) {
                // Enable blending for combining colors when there is transparency
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                if (scene.isBlendingForced) {
                    colorMask = BLENDING_MASK_FORCED
                }
            } else {
                GLES20.glDisable(GLES20.GL_BLEND)
            }

            // animate scene
            scene.onDrawFrame()

            // recalculate mvp matrix according to where we are looking at now
            val camera = scene.camera
            cameraPosInWorldSpace[0] = camera.getxPos()
            cameraPosInWorldSpace[1] = camera.getyPos()
            cameraPosInWorldSpace[2] = camera.getzPos()
            if (camera.hasChanged()) {
                // INFO: Set the camera position (View matrix)
                // The camera has 3 vectors (the position, the vector where we are looking at, and the up position (sky)

                // the projection matrix is the 3D virtual space (cube) that we want to project
                val ratio = width.toFloat() / height
                // Log.v(TAG, "Camera changed: projection: [" + -ratio + "," + ratio + ",-1,1]-near/far[1,10], ");
                if (!scene.isStereoscopic) {
                    Matrix.setLookAtM(viewMatrix, 0, camera.getxPos(), camera.getyPos(), camera.getzPos(), camera.getxView(), camera.getyView(),
                            camera.getzView(), camera.getxUp(), camera.getyUp(), camera.getzUp())
                    Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
                } else {
                    val stereoCamera = camera.toStereo(EYE_DISTANCE)
                    val leftCamera = stereoCamera[0]
                    val rightCamera = stereoCamera[1]

                    // camera on the left for the left eye
                    Matrix.setLookAtM(viewMatrixLeft, 0, leftCamera.getxPos(), leftCamera.getyPos(), leftCamera.getzPos(), leftCamera
                            .getxView(),
                            leftCamera.getyView(), leftCamera.getzView(), leftCamera.getxUp(), leftCamera.getyUp(), leftCamera.getzUp())
                    // camera on the right for the right eye
                    Matrix.setLookAtM(viewMatrixRight, 0, rightCamera.getxPos(), rightCamera.getyPos(), rightCamera.getzPos(), rightCamera
                            .getxView(),
                            rightCamera.getyView(), rightCamera.getzView(), rightCamera.getxUp(), rightCamera.getyUp(), rightCamera.getzUp())
                    if (scene.isAnaglyph) {
                        Matrix.frustumM(projectionMatrixRight, 0, -ratio, ratio, -1f, 1f, near, far)
                        Matrix.frustumM(projectionMatrixLeft, 0, -ratio, ratio, -1f, 1f, near, far)
                    } else if (scene.isVRGlasses) {
                        val ratio2 = width.toFloat() / 2 / height
                        Matrix.frustumM(projectionMatrixRight, 0, -ratio2, ratio2, -1f, 1f, near, far)
                        Matrix.frustumM(projectionMatrixLeft, 0, -ratio2, ratio2, -1f, 1f, near, far)
                    }
                    // Calculate the projection and view transformation
                    Matrix.multiplyMM(viewProjectionMatrixLeft, 0, projectionMatrixLeft, 0, viewMatrixLeft, 0)
                    Matrix.multiplyMM(viewProjectionMatrixRight, 0, projectionMatrixRight, 0, viewMatrixRight, 0)
                }
                camera.setChanged(false)
            }
            if (!scene.isStereoscopic) {
                this.onDrawFrame(viewMatrix, projectionMatrix, viewProjectionMatrix, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace)
                return
            }
            if (scene.isAnaglyph) {
                // INFO: switch because blending algorithm doesn't mix colors
                if (anaglyphSwitch) {
                    this.onDrawFrame(viewMatrixLeft, projectionMatrixLeft, viewProjectionMatrixLeft, lightPosInWorldSpace,
                            COLOR_RED, cameraPosInWorldSpace)
                } else {
                    this.onDrawFrame(viewMatrixRight, projectionMatrixRight, viewProjectionMatrixRight, lightPosInWorldSpace,
                            COLOR_BLUE, cameraPosInWorldSpace)
                }
                anaglyphSwitch = !anaglyphSwitch
                return
            }
            if (scene.isVRGlasses) {

                // draw left eye image
                GLES20.glViewport(0, 0, width / 2, height)
                GLES20.glScissor(0, 0, width / 2, height)
                this.onDrawFrame(viewMatrixLeft, projectionMatrixLeft, viewProjectionMatrixLeft, lightPosInWorldSpace,
                        null, cameraPosInWorldSpace)

                // draw right eye image
                GLES20.glViewport(width / 2, 0, width / 2, height)
                GLES20.glScissor(width / 2, 0, width / 2, height)
                this.onDrawFrame(viewMatrixRight, projectionMatrixRight, viewProjectionMatrixRight, lightPosInWorldSpace,
                        null, cameraPosInWorldSpace)
            }
        } catch (ex: Exception) {
            Log.e("ModelRenderer", "Fatal exception: " + ex.message, ex)
            fatalException = true
        } catch (err: Error) {
            Log.e("ModelRenderer", "Fatal error: " + err.message, err)
            fatalException = true
        }
    }

    private fun onDrawFrame(viewMatrix: FloatArray, projectionMatrix: FloatArray, viewProjectionMatrix: FloatArray,
                            lightPosInWorldSpace: FloatArray, colorMask: FloatArray?, cameraPosInWorldSpace: FloatArray) {


        // set up camera
        val camera = scene!!.camera

        // draw environment
        val skyBoxId = isUseskyBoxId
        when {
            skyBoxId == -3 -> {
                // draw all extra objects
                for (i in extras.indices) {
                    drawObject(viewMatrix, projectionMatrix, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace, false, false, false, false, false, extras, i)
                }
            }
            skyBoxId == -2 -> {
                GLES20.glClearColor(backgroundColor[0], backgroundColor[1], backgroundColor[2], backgroundColor[3])
                // Draw background color
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            }
            skyBoxId == -1 -> {
                // invert background color
                GLES20.glClearColor(1 - backgroundColor[0], 1 - backgroundColor[1], 1 - backgroundColor[2], 1 - backgroundColor[3])
                // Draw background color
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            }
            isDrawSkyBox && skyBoxId >= 0 && skyBoxId < skyBoxes3D!!.size -> {
                GLES20.glDepthMask(false)
                try {
                    //skyBoxId = 1;
                    // lazy building of the 3d object
                    if (skyBoxes3D!![skyBoxId] == null) {
                        Log.i("ModelRenderer", "Loading sky box textures to GPU... skybox: $skyBoxId")
                        val textureId = GLUtil.loadCubeMap(skyBoxes!![skyBoxId].cubeMap)
                        Log.d("ModelRenderer", "Loaded textures to GPU... id: $textureId")
                        if (textureId != -1) {
                            skyBoxes3D!![skyBoxId] = SkyBox.build(skyBoxes!![skyBoxId])
                        } else {
                            Log.e("ModelRenderer", "Error loading sky box textures to GPU. ")
                            isDrawSkyBox = false
                        }
                    }
                    Matrix.setLookAtM(viewMatrixSkyBox, 0, 0f, 0f, 0f, camera.getxView() - camera.getxPos(), camera.getyView() - camera.getyPos(),
                            camera.getzView() - camera.getzPos(), camera.getxUp() - camera.getxPos(), camera.getyUp() - camera.getyPos(), camera.getzUp() - camera.getzPos())
                    if (scene.isFixCoordinateSystem) {
                        Matrix.rotateM(viewMatrixSkyBox, 0, 90f, 1f, 0f, 0f)
                    }
                    val basicShader = drawer.skyBoxDrawer
                    basicShader.draw(skyBoxes3D!![skyBoxId], projectionMatrix, viewMatrixSkyBox, skyBoxes3D!![skyBoxId]!!.material.textureId, null, cameraPosInWorldSpace)
                } catch (ex: Throwable) {
                    Log.e("ModelRenderer", "Error rendering sky box. " + ex.message, ex)
                    isDrawSkyBox = false
                }
                GLES20.glDepthMask(true)
            }
        }


        // draw light
        val doAnimation = scene.isDoAnimation && animationEnabled
        val drawLighting = scene.isDrawLighting && isLightsEnabled
        val drawWireframe = scene.isDrawWireframe || wireframeEnabled
        val drawTextures = scene.isDrawTextures && texturesEnabled
        val drawColors = scene.isDrawColors && colorsEnabled
        if (drawLighting) {
            val basicShader = drawer.basicShader

            // Calculate position of the light in world space to support lighting
            if (scene.isRotatingLight) {
                Matrix.multiplyMV(tempVector4, 0, scene.lightBulb.modelMatrix, 0, lightPosition, 0)
                lightPosInWorldSpace[0] = tempVector4[0]
                lightPosInWorldSpace[1] = tempVector4[1]
                lightPosInWorldSpace[2] = tempVector4[2]

                // Draw a point that represents the light bulb
                basicShader.draw(scene.lightBulb, projectionMatrix, viewMatrix, -1, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace)
                //basicShader.draw(Point.build(lightPosInWorldSpace), projectionMatrix, viewMatrix, -1, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace);
            } else {
                lightPosInWorldSpace[0] = cameraPosInWorldSpace[0]
                lightPosInWorldSpace[1] = cameraPosInWorldSpace[1]
                lightPosInWorldSpace[2] = cameraPosInWorldSpace[2]
            }

            // FIXME: memory leak
            if (scene.isDrawNormals) {
                basicShader.draw(Line.build(floatArrayOf(lightPosInWorldSpace[0],
                        lightPosInWorldSpace[1], lightPosInWorldSpace[2], 0f, 0f, 0f)).setId("light_line"), projectionMatrix,
                        viewMatrix, -1,
                        lightPosInWorldSpace,
                        colorMask, cameraPosInWorldSpace)
            }
        }

        // draw all available objects
        val objects = scene.objects
        for (i in objects.indices) {
            drawObject(viewMatrix, projectionMatrix, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace, doAnimation, drawLighting, drawWireframe, drawTextures, drawColors, objects, i)
        }

        // draw all GUI objects
        val guiObjects = scene.guiObjects
        for (i in guiObjects.indices) {
            drawObject(viewMatrix, projectionMatrix, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace, doAnimation, drawLighting, drawWireframe, drawTextures, drawColors, guiObjects, i)
        }
        if (framesPerSecondTime == -1L) {
            framesPerSecondTime = SystemClock.elapsedRealtime()
            framesPerSecondCounter++
        } else if (SystemClock.elapsedRealtime() > framesPerSecondTime + 1000) {
            framesPerSecond = framesPerSecondCounter
            framesPerSecondCounter = 1
            framesPerSecondTime = SystemClock.elapsedRealtime()
            AndroidUtils.fireEvent(listeners, FPSEvent(this, framesPerSecond))
        } else {
            framesPerSecondCounter++
        }
        debugSkeleton = !debugSkeleton
    }

    private fun drawObject(viewMatrix: FloatArray, projectionMatrix: FloatArray, lightPosInWorldSpace: FloatArray, colorMask: FloatArray?, cameraPosInWorldSpace: FloatArray, doAnimation: Boolean, drawLighting: Boolean, drawWireframe: Boolean, drawTextures: Boolean, drawColors: Boolean, objects: List<Object3DData>, i: Int) {
        var objData: Object3DData? = null
        try {
            objData = objects[i]
            if (!objData.isVisible) {
                return
            }
            if (!infoLogged.containsKey(objData.id)) {
                Log.i("ModelRenderer", "Drawing model: " + objData.id + ", " + objData.javaClass.simpleName)
                infoLogged[objData.id] = true
            }
            val drawerObject = drawer.getDrawer(objData, false, drawTextures, drawLighting, doAnimation, drawColors)
            if (drawerObject == null) {
                if (!infoLogged.containsKey(objData.id + "drawer")) {
                    Log.e("ModelRenderer", "No drawer for " + objData.id)
                    infoLogged[objData.id + "drawer"] = true
                }
                return
            }
            val changed = objData.isChanged
            objData.isChanged = false

            // load textures
            var textureId: Int? = null
            if (drawTextures) {

                // TODO: move texture loading to Renderer
                if (objData.elements != null) {
                    for (e in objData.elements.indices) {
                        val element = objData.elements[e]

                        // check required info
                        if (element.material == null || element.material.textureData == null) continue

                        // check if texture is already binded
                        textureId = textures[element.material.textureData]
                        if (textureId != null) continue

                        // bind texture
                        Log.i("ModelRenderer", "Loading material texture for element... '$element")
                        textureId = GLUtil.loadTexture(element.material.textureData)
                        element.material.textureId = textureId

                        // cache texture
                        textures[element.material.textureData] = textureId

                        // log event
                        Log.i("ModelRenderer", "Loaded material texture for element. id: $textureId")

                        // FIXME: we have to set this, otherwise the RendererFactory won't return textured shader
                        objData.textureData = element.material.textureData
                    }
                } else {
                    textureId = textures[objData.textureData]
                    if (textureId == null && objData.textureData != null) {
                        Log.i("ModelRenderer", "Loading texture for obj: '" + objData.id + "'... bytes: " + objData.textureData.size)
                        val textureIs = ByteArrayInputStream(objData.textureData)
                        textureId = GLUtil.loadTexture(textureIs)
                        textureIs.close()
                        textures[objData.textureData] = textureId
                        objData.material.textureId = textureId
                        Log.i("ModelRenderer", "Loaded texture OK. id: $textureId")
                    }
                }
            }
            if (textureId == null) {
                textureId = -1
            }

            // draw points
            if (objData.drawMode == GLES20.GL_POINTS) {
                val basicDrawer = drawer.basicShader
                basicDrawer.draw(objData, projectionMatrix, viewMatrix, GLES20.GL_POINTS, lightPosInWorldSpace, cameraPosInWorldSpace)
            } else {

                // draw wireframe
                if (drawWireframe && objData.drawMode != GLES20.GL_POINTS && objData.drawMode != GLES20.GL_LINES && objData.drawMode != GLES20.GL_LINE_STRIP && objData.drawMode != GLES20.GL_LINE_LOOP) {
                    // Log.d("ModelRenderer","Drawing wireframe model...");
                    try {
                        // Only draw wireframes for objects having faces (triangles)
                        var wireframe = wireframes[objData]
                        if (wireframe == null || changed) {
                            Log.i("ModelRenderer", "Building wireframe model...")
                            wireframe = Wireframe.build(objData)
                            wireframe.color = objData.color
                            wireframes[objData] = wireframe
                            Log.i("ModelRenderer", "Wireframe build: $wireframe")
                        }
                        animator.update(wireframe, scene!!.isShowBindPose)
                        drawerObject.draw(wireframe, projectionMatrix, viewMatrix, wireframe!!.drawMode, wireframe.drawSize, textureId, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace)
                        //objData.render(drawer, lightPosInWorldSpace, colorMask);
                    } catch (e: Error) {
                        Log.e("ModelRenderer", e.message, e)
                    }
                } else if (scene!!.isDrawPoints) {
                    drawerObject.draw(objData, projectionMatrix, viewMatrix, GLES20.GL_POINTS, objData.drawSize,
                            textureId, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace)
                    objData.render(drawer, lightPosInWorldSpace, colorMask)
                } else if (scene.isDrawSkeleton && objData is AnimatedModel && objData
                                .animation != null) {

                    // draw the original object a bit transparent
                    drawerObject.draw(objData, projectionMatrix, viewMatrix, textureId, lightPosInWorldSpace, COLOR_HALF_TRANSPARENT, cameraPosInWorldSpace)

                    // draw skeleton on top of it
                    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                    var skeleton = skeleton[objData]
                    if (skeleton == null || changed) {
                        skeleton = Skeleton.build(objData as AnimatedModel?)
                        this.skeleton[objData] = skeleton
                    }
                    val skeletonDrawer = drawer.getDrawer(skeleton, false, false, drawLighting, doAnimation, drawColors)
                    skeletonDrawer.draw(skeleton, projectionMatrix, viewMatrix, -1, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace)
                    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                } else {
                    if (!infoLogged.containsKey(objData.id + "render")) {
                        Log.i("ModelRenderer", "Rendering object... " + objData.id)
                        Log.d("ModelRenderer", objData.toString())
                        Log.d("ModelRenderer", drawerObject.toString())
                        infoLogged[objData.id + "render"] = true
                    }
                    drawerObject.draw(objData, projectionMatrix, viewMatrix,
                            textureId, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace)
                    objData.render(drawer, lightPosInWorldSpace, colorMask)
                }
            }

            // Draw bounding box
            if (scene!!.isDrawBoundingBox && objData.isSolid || scene.selectedObject === objData) {
                drawBoundingBox(viewMatrix, projectionMatrix, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace, objData, changed)
            }

            // Draw normals
            if (scene.isDrawNormals) {
                var normalData = normals[objData]
                if (normalData == null || changed) {
                    normalData = Normals.build(objData)
                    if (normalData != null) {
                        normalData.id = objData.id + "_normals"
                        // it can be null if object isnt made of triangles
                        normals[objData] = normalData
                    }
                }
                if (normalData != null) {
                    val normalsDrawer = drawer.getDrawer(normalData, false, false, false, doAnimation,
                            false)
                    animator.update(normalData, scene.isShowBindPose)
                    normalsDrawer.draw(normalData, projectionMatrix, viewMatrix, -1, lightPosInWorldSpace, colorMask, cameraPosInWorldSpace)
                }
            }
        } catch (ex: Exception) {
            if (!infoLogged.containsKey(ex.message)) {
                Log.e("ModelRenderer", "There was a problem rendering the object '" + objData!!.id + "':" + ex.message, ex)
                infoLogged[ex.message] = true
            }
        } catch (ex: Error) {
            Log.e("ModelRenderer", "There was a problem rendering the object '" + objData!!.id + "':" + ex.message, ex)
        }
    }

    private fun drawBoundingBox(viewMatrix: FloatArray, projectionMatrix: FloatArray, lightPosInWorldSpace: FloatArray, colorMask: FloatArray?, cameraPosInWorldSpace: FloatArray, objData: Object3DData?, changed: Boolean) {
        var boundingBoxData = boundingBoxes[objData]
        if (boundingBoxData == null || changed) {
            Log.i("ModelRenderer", "Building bounding box... id: " + objData!!.id)
            boundingBoxData = BoundingBox.build(objData)
            boundingBoxData.color = COLOR_WHITE
            boundingBoxes[objData] = boundingBoxData
            Log.i("ModelRenderer", "Bounding box: $boundingBoxData")
        }
        val boundingBoxDrawer = drawer.boundingBoxDrawer
        boundingBoxDrawer.draw(boundingBoxData, projectionMatrix, viewMatrix, -1,
                lightPosInWorldSpace, colorMask, cameraPosInWorldSpace)
    }

    companion object {
        private val TAG = ModelRenderer::class.java.simpleName

        // grid
        private const val GRID_WIDTH = 100f
        private const val GRID_SIZE = 10f
        private val GRID_COLOR = floatArrayOf(0.25f, 0.25f, 0.25f, 0.5f)

        // blending
        private val BLENDING_MASK_DEFAULT = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)

        // Add 0.5f to the alpha component to the global shader so we can see through the skin
        private val BLENDING_MASK_FORCED = floatArrayOf(1.0f, 1.0f, 1.0f, 0.5f)

        // frustrum - nearest pixel
        private const val near = 1.0f

        // frustrum - fartest pixel
        private const val far = 5000f

        // stereoscopic variables
        private const val EYE_DISTANCE = 0.64f
        private val COLOR_RED = floatArrayOf(1.0f, 0.0f, 0.0f, 1f)
        private val COLOR_BLUE = floatArrayOf(0.0f, 1.0f, 0.0f, 1f)
        private val COLOR_WHITE = floatArrayOf(1f, 1f, 1f, 1f)
        private val COLOR_HALF_TRANSPARENT = floatArrayOf(1f, 1f, 1f, 0.5f)
        private val COLOR_ALMOST_TRANSPARENT = floatArrayOf(1f, 1f, 1f, 0.1f)
    }

    init {
        extras.add(axis)
        extras.add(gridx)
        extras.add(gridy)
        extras.add(gridz)
    }

    /**
     * Construct a new renderer for the specified surface view
     *
     * @param modelSurfaceView the 3D window
     */
    init {
        main = modelSurfaceView
        this.backgroundColor = backgroundColor ?: floatArrayOf(0f, 0f, 0f, 0f)
        this.scene = scene
        drawer = RendererFactory(parent)
    }
}