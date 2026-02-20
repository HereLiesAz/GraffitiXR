package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.MainViewModel
import java.lang.ref.WeakReference

object ActionDispatcher {
    private var arViewModelRef: WeakReference<ArViewModel>? = null
    private var mainViewModelRef: WeakReference<MainViewModel>? = null

    fun setViewModels(arVm: ArViewModel, mainVm: MainViewModel) {
        arViewModelRef = WeakReference(arVm)
        mainViewModelRef = WeakReference(mainVm)
    }

    fun captureKeyframe() {
        arViewModelRef?.get()?.captureKeyframe()
    }

    fun toggleFlashlight() {
        arViewModelRef?.get()?.toggleFlashlight()
    }

    fun lockTrace() {
        mainViewModelRef?.get()?.setTouchLocked(true)
    }
}
