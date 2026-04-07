package cn.shengwang.convoai.quickstart.ui.common

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewbinding.ViewBinding

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    private var _binding: VB? = null
    protected val mBinding: VB? get() = _binding

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            onHandleOnBackPressed()
        }
    }

    open fun onHandleOnBackPressed() {
        if (supportOnBackPressed()) {
            finish()
        }
    }

    abstract fun getViewBinding(): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = getViewBinding()
        if (_binding?.root == null) {
            finish()
            return
        }
        setContentView(_binding!!.root)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        setupSystemBarsAndCutout(immersiveMode(), usesDarkStatusBarIcons())
        initData()
        initView()
    }

    open fun immersiveMode(): ImmersiveMode = ImmersiveMode.SEMI_IMMERSIVE

    open fun supportOnBackPressed(): Boolean = true

    /**
     * Determines the status bar icons/text color
     * @return true for dark icons (suitable for light backgrounds), false for light icons (suitable for dark backgrounds)
     */
    open fun usesDarkStatusBarIcons(): Boolean = false

    override fun finish() {
        onBackPressedCallback.remove()
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    open fun initData(){}

    /**
     * Initialize the view.
     */
    protected abstract fun initView()

    /**
     * Edge-to-edge 下为根布局叠加状态栏/导航栏 inset；必须保留 XML 原有 padding，否则仅设 systemBars.top
     * 会吞掉原来的内边距，标题行「人脸注册」等易被挡在状态栏/刘海后。
     */
    fun setOnApplyWindowInsetsListener(view: View) {
        val baseStart = view.paddingStart
        val baseTop = view.paddingTop
        val baseEnd = view.paddingEnd
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPaddingRelative(
                bars.left + baseStart,
                bars.top + baseTop,
                bars.right + baseEnd,
                bars.bottom + baseBottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Sets up immersive display and notch screen adaptation
     * @param immersiveMode Type of immersive mode
     * @param lightStatusBar Whether to use dark status bar icons
     */
    protected fun setupSystemBarsAndCutout(
        immersiveMode: ImmersiveMode = ImmersiveMode.EDGE_TO_EDGE,
        lightStatusBar: Boolean = false
    ) {
        // Step 1: Set up basic Edge-to-Edge display
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
            window.setDecorFitsSystemWindows(false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightStatusBar
            }
        } else {
            @Suppress("DEPRECATION")
            var flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

            if (lightStatusBar) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }

            window.decorView.systemUiVisibility = flags
        }

        // Step 2: Set system bar transparency
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // Step 3: Handle notch screens
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Step 4: Set system UI visibility based on immersive mode
        when (immersiveMode) {
            ImmersiveMode.EDGE_TO_EDGE -> {
                // Do not hide any system bars, only extend content to full screen
                // Already set in step 1
            }

            ImmersiveMode.SEMI_IMMERSIVE -> {
                // Hide navigation bar, show status bar
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.apply {
                        hide(WindowInsets.Type.navigationBars())
                        show(WindowInsets.Type.statusBars())
                        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (window.decorView.systemUiVisibility
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                }
            }

            ImmersiveMode.FULLY_IMMERSIVE -> {
                // Hide all system bars
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.apply {
                        hide(WindowInsets.Type.systemBars())
                        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (window.decorView.systemUiVisibility
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                }
            }
        }
    }

    /**
     * Immersive mode types
     */
    enum class ImmersiveMode {
        /**
         * Content extends under system bars, but system bars remain visible
         */
        EDGE_TO_EDGE,

        /**
         * Hide navigation bar, show status bar
         */
        SEMI_IMMERSIVE,

        /**
         * Hide all system bars, fully immersive
         */
        FULLY_IMMERSIVE
    }
}