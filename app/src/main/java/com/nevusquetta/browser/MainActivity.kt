package com.nevusquetta.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.nevusquetta.browser.ui.BrowserScreen
import com.nevusquetta.browser.viewmodel.BrowserViewModel

class MainActivity : ComponentActivity() {
    private val browserViewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!browserViewModel.requestBackNavigation()) {
                        finish()
                    }
                }
            },
        )

        setContent {
            MaterialTheme {
                Surface {
                    BrowserScreen(viewModel = browserViewModel)
                }
            }
        }
    }
}
