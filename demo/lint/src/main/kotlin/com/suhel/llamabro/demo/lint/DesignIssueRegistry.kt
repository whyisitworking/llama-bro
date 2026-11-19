package com.suhel.llamabro.demo.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class DesignIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = DesignTokensDetector.ISSUES

    override val api: Int = CURRENT_API

    override val vendor: Vendor = Vendor(vendorName = "Llama Bro demo", identifier = "llamabro-demo-lint")
}
