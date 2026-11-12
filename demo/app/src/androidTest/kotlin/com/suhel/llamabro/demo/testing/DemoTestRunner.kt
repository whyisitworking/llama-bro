package com.suhel.llamabro.demo.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Runs every instrumented test against [TestDemoApp], whose container is built from a [TestConfig]. */
class DemoTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestDemoApp::class.java.name, context)
}
