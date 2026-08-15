package com.kevinnzou.sample.integrationtest

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun realWebViewSmokeViewController(onSnapshot: (RealWebViewSmokeSnapshot) -> Unit): UIViewController =
    ComposeUIViewController {
        RealWebViewSmokeHarness(onSnapshot = onSnapshot)
    }

fun schemeWebViewSmokeViewController(onSnapshot: (SchemeWebViewSmokeSnapshot) -> Unit): UIViewController =
    ComposeUIViewController {
        SchemeWebViewSmokeHarness(onSnapshot = onSnapshot)
    }

fun schemeCancellationViewController(onSnapshot: (SchemeCancellationSnapshot) -> Unit): UIViewController =
    ComposeUIViewController {
        SchemeCancellationHarness(onSnapshot = onSnapshot)
    }
