package com.kevinnzou.sample.integrationtest

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun realWebViewSmokeViewController(onSnapshot: (RealWebViewSmokeSnapshot) -> Unit): UIViewController =
    ComposeUIViewController {
        RealWebViewSmokeHarness(onSnapshot = onSnapshot)
    }
