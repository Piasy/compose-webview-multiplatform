package com.kevinnzou.sample.integrationtest

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun navigationWebViewSmokeViewController(onSnapshot: (NavigationWebViewSmokeSnapshot) -> Unit): UIViewController =
    ComposeUIViewController { NavigationWebViewSmokeHarness(onSnapshot) }

fun navigationWebViewNoHandlerViewController(): UIViewController = ComposeUIViewController { NavigationWebViewNoHandlerHarness() }
