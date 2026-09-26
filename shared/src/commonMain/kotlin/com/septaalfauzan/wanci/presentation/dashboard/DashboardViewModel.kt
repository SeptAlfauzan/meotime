package com.septaalfauzan.wanci.presentation.dashboard

import androidx.lifecycle.ViewModel
import com.septaalfauzan.wanci.domain.InstalledAppCheck
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases

/**
 * Placeholder so the navigation graph compiles before Task 10. The state machine that observes
 * monitored apps and resolves their installed-state lives there; this constructor signature is
 * the contract the navigation host already calls, and it is replaced in Task 10.
 */
class DashboardViewModel(
    private val useCases: WanciUseCases,
    private val installedAppCheck: InstalledAppCheck,
) : ViewModel()
