package com.nevusquetta.browser.ui

import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nevusquetta.browser.R
import com.nevusquetta.browser.engine.NevusQuettaWebChromeClient
import com.nevusquetta.browser.engine.NevusQuettaWebViewClient
import com.nevusquetta.browser.viewmodel.BrowserCommand
import com.nevusquetta.browser.viewmodel.BrowserViewModel
import kotlinx.coroutines.flow.collect

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val webView = remember(context) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            isVerticalScrollBarEnabled = true
        }
    }
    val webViewClient = remember(viewModel) { NevusQuettaWebViewClient(viewModel) }
    val webChromeClient = remember(viewModel) { NevusQuettaWebChromeClient(viewModel) }
    var addressBarValue by rememberSaveable { mutableStateOf(uiState.currentUrl) }

    LaunchedEffect(uiState.currentUrl) {
        if (uiState.currentUrl != addressBarValue) {
            addressBarValue = uiState.currentUrl
        }
    }

    LaunchedEffect(viewModel, webView) {
        viewModel.commands.collect { command ->
            when (command) {
                BrowserCommand.Back -> if (webView.canGoBack()) webView.goBack()
                BrowserCommand.Forward -> if (webView.canGoForward()) webView.goForward()
                is BrowserCommand.LoadUrl -> webView.loadUrl(command.url)
                BrowserCommand.Reload -> webView.reload()
                BrowserCommand.StopLoading -> webView.stopLoading()
            }
        }
    }

    DisposableEffect(webView, webViewClient, webChromeClient) {
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient
        if (webView.url == null) {
            webView.loadUrl(uiState.currentUrl)
        }

        onDispose {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = null
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        NavigationToolbar(
            addressBarValue = addressBarValue,
            canGoBack = uiState.canGoBack,
            canGoForward = uiState.canGoForward,
            isLoading = uiState.isLoading,
            onAddressChanged = { addressBarValue = it },
            onAddressSubmitted = { viewModel.submitAddress(addressBarValue) },
            onBackClicked = { viewModel.requestBackNavigation() },
            onForwardClicked = viewModel::onForwardClicked,
            onReloadStopClicked = viewModel::onReloadStopClicked,
            onHomeClicked = viewModel::onHomeClicked,
        )

        if (uiState.isLoading) {
            LinearProgressIndicator(
                progress = uiState.progress / 100f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun NavigationToolbar(
    addressBarValue: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isLoading: Boolean,
    onAddressChanged: (String) -> Unit,
    onAddressSubmitted: () -> Unit,
    onBackClicked: () -> Unit,
    onForwardClicked: () -> Unit,
    onReloadStopClicked: () -> Unit,
    onHomeClicked: () -> Unit,
) {
    val backDescription = stringResource(R.string.browser_back)
    val forwardDescription = stringResource(R.string.browser_forward)
    val stopDescription = stringResource(R.string.browser_stop_loading)
    val reloadDescription = stringResource(R.string.browser_reload)
    val homeDescription = stringResource(R.string.browser_home)
    val addressLabel = stringResource(R.string.browser_address_label)
    val addressPlaceholder = stringResource(R.string.browser_address_placeholder)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onBackClicked,
                enabled = canGoBack,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = backDescription,
                )
            }

            IconButton(
                onClick = onForwardClicked,
                enabled = canGoForward,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = forwardDescription,
                )
            }

            IconButton(onClick = onReloadStopClicked) {
                Icon(
                    imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = if (isLoading) stopDescription else reloadDescription,
                )
            }

            IconButton(onClick = onHomeClicked) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = homeDescription,
                )
            }
        }

        OutlinedTextField(
            value = addressBarValue,
            onValueChange = onAddressChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { Text(addressLabel) },
            placeholder = { Text(addressPlaceholder) },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = { onAddressSubmitted() },
            ),
        )
    }
}
