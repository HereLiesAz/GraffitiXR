# User Interface & User Experience (UI/UX)

This document details the visual design, interaction patterns, and user experience philosophy of the GraffitiXR application.

## **1. Visual Design System**

### **Theme & Styling**
-   **Dark Mode:** The application uses a dark theme by default to reduce glare and focus attention on the camera feed and projected art.
-   **Transparency:** Panels (like `AdjustmentsPanel`) use semi-transparent backgrounds to ensure the camera view is never fully obstructed.
-   **Immersive:** The UI elements are minimal and overlaid on the full-screen camera view (`ARScreen`, `OverlayScreen`) or static canvas (`MockupScreen`).

### **Core Components**
-   **`AzNavRail`:** A left-aligned vertical navigation rail (using `com.github.HereLiesAz:AzNavRail`) handles top-level navigation.
    -   **Host Items:** Group related functions (e.g., "Project", "Mode", "Griding", "Design").
    -   **Sub Items:** Specific actions (e.g., "Save", "Load", "Create Target").
-   **`AdjustmentsPanel`:** A bottom sheet containing controls for image manipulation.
    -   **Knobs:** Custom rotary controls (`Knob.kt`) replace standard sliders for finer precision and a "pro" feel.
    -   **Toggle Buttons:** Used for binary states like "Invert", "Line Mode".
-   **`Knob` Control:** A custom composable that allows value adjustment via rotation or vertical drag. It supports a label, value display, and reset-on-double-tap.

## **2. Interaction Patterns**

### **Gestures**
The application relies heavily on touch gestures for direct manipulation of the artwork.

-   **One-Finger Drag:**
    -   **AR Mode:** Moves the AR anchor across detected planes (raycasting).
    -   **Refinement Screen:** Draws or erases mask paths.
-   **Two-Finger Gestures (Multitouch):**
    -   **Pinch-to-Zoom:** Scales the overlay image or AR object.
    -   **Twist-to-Rotate:** Rotates the overlay image or AR object (Z-axis).
    -   **Two-Finger Pan:** Moves the AR anchor (alternative to single-finger drag to avoid conflicts).
-   **Double Tap:**
    -   Resets specific values (like Knob centering).
    -   In `OverlayScreen`, resets the image transformation.

### **Feedback Mechanisms**
-   **`TapFeedback`:** Visual ripples or markers appear at the touch point to confirm interaction.
-   **Haptics:** The app triggers vibration feedback (`FeedbackEvent.VibrateSingle`, `VibrateDouble`) for key actions like target capture or snapping.
-   **Toasts:** Used for transient system messages ("Project saved", "Aligned Flat").

## **3. Screen-Specific UX**

### **AR Screen (`ARScreen.kt`)**
-   **State:** `SEARCHING` -> `LOCKED` -> `PLACED`.
-   **Visuals:** Displays the live camera feed. Uses a dotted grid to visualize detected planes.
-   **Interaction:** Users scan the room. Once planes are found, they tap to place the image. The image stays anchored to the real world.

### **Trace/Overlay Screen (`OverlayScreen.kt`)**
-   **Purpose:** For non-AR devices or simple tracing.
-   **Interaction:** The image is "stuck" to the screen (camera moves behind it). Users align the camera to the physical wall.
-   **Tools:** Opacity is key here to see the hand behind the phone.

### **Mockup Screen (`MockupScreen.kt`)**
-   **Purpose:** Static visualization on a photo.
-   **Interaction:** Users pick a background image. The overlay is placed on top. No camera feed.

### **Target Refinement (`TargetRefinementScreen.kt`)**
-   **Purpose:** Fine-tuning the AR target mask.
-   **Visuals:** Shows the captured target image.
-   **Interaction:**
    -   **Yellow Keypoints:** Visualizes OpenCV ORB features.
    -   **Masking:** Users paint to include/exclude areas from tracking.
    -   **Zoom:** Users can zoom in for pixel-perfect masking.

## **4. Onboarding & Help**
-   **`HelpScreen`:** A full-screen tutorial.
-   **Progressive Onboarding:** The `OnboardingManager` tracks which modes the user has seen and shows specific tutorials for `AR`, `OVERLAY`, etc., upon first entry.
