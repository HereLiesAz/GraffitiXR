# Graffiti XR

## Overview

This is an Android application that uses AndroidXR to overlay a virtual mural onto a set of real-world markings on a surface. The application is built with Kotlin and uses ComposeXR for rendering the AR content. It's designed to track markings on a surface to better maintain the image's positioning, for when you drop your phone or have to put it in your pocket for a bit. 

## Features

*   **Real-time Image Tracking:** The app uses Augmented Images API to detect and track multiple predefined marker images in the environment.
*   **Virtual Mural Overlay:** Once the markers are detected, the app renders a virtual mural that appears to be painted on the surface where the markers are located.
*   **Dynamic Mural Loading:** The mural texture and the marker images are loaded dynamically.
*   **AndroidXR Rendering:** The camera feed and the virtual content are rendered using OpenGL ES 3.0.
*   **Jetpack Compose XR:** The UI components are built with Jetpack Compose XR.
