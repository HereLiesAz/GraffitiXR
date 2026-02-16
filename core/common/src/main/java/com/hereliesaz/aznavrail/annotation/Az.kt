package com.hereliesaz.aznavrail.annotation

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Az(
    val app: App = App(),
    val rail: RailItem = RailItem(),
    val railHost: RailHost = RailHost()
)
