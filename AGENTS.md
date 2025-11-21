# AGENT INSTRUCTIONS for GraffitiXR (React Native)

This document provides technical guidance for AI agents working on the GraffitiXR React Native application.

## 1. Conceptual Overview
GraffitiXR helps artists visualize murals in AR. This is a React Native rewrite.

## 2. Technical Details
- **Root**: `GraffitiXR/`
- **Stack**: React Native, ViroReact, VisionCamera.
- **State**: React Context / Local State (for now).

## 3. Development
- Use `npm install` in `GraffitiXR`.
- Build with `npm run android` or `./gradlew assembleDebug` in `GraffitiXR/android`.
- Use `AzNavRail` for navigation layout.

## 4. Key Dependencies
- `@reactvision/react-viro` for AR.
- `react-native-vision-camera` for Camera.
- `aznavrail-react-native` (local package) for Navigation Rail.
