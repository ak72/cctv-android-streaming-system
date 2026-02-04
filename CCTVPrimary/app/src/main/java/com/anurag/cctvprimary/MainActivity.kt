package com.anurag.cctvprimary

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anurag.cctvprimary.ui.theme.CCTVPrimaryTheme
import com.anurag.cctvprimary.ui.responsive.ResponsiveSingleScreen
import com.anurag.cctvprimary.ui.screens.CctvScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Back gesture/button should send app to background instead of closing.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            }
        )
        //
        // Disable navigation bar permanently
      /*  WindowCompat.setDecorFitsSystemWindows(window, false)*/

       /* WindowInsetsControllerCompat(window, window.decorView).apply {
          // show(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
          systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      isAppearanceLightStatusBars=true

        }*/



        // Keep screen on while viewing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CCTVPrimaryTheme {
                   Surface(
                    modifier = Modifier
                        .safeDrawingPadding()) {
                    ResponsiveSingleScreen(
                        // Reference design size for the existing single-screen layout.
                        // Keep consistent across devices; the wrapper will scale to fit.
                        refWidth = 360.dp,
                        refHeight = 800.dp,

                        minScale = 0.85f,
                        maxScale = 1.30f,
                        // Protect fixed layout from extreme font scaling overflow.
                        // Set to null if you want to fully honor accessibility font scaling.
                        safeFontScaleRange = 0.90f..1.10f,
                        logTag = "CCTV_PRIMARY"
                    ) {
                        CctvScreen()
                    }
                }
            }
        }
    }
}
