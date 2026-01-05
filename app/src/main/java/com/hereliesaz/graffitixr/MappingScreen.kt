package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable

@Composable
fun MappingScreen(
    onFinish: () -> Unit
) {
    // val context = LocalContext.current
    // val configuration = LocalConfiguration.current
    // val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    // var showHelp by remember { mutableStateOf(true) }

    // Box(modifier = Modifier.fillMaxSize()) {
    //     AndroidView({ ctx ->
    //         SceneLayout(ctx)
    //     }, modifier = Modifier.fillMaxSize())

    //     Box(
    //         modifier = Modifier
    //             .zIndex(6f)
    //             .fillMaxHeight()
    //     ) {
    //         GraffitiNavRail(
    //             currentDestination = null,
    //             isLandscape = isLandscape
    //         ) {
    //             railItem(id = "help", text = "Help", onClick = { showHelp = !showHelp })
    //             railItem(id = "save", text = "Save", onClick = {
    //                 onFinish()
    //             })
    //         }
    //     }
    // }
}
