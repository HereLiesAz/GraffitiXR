# GraffitiXR (React Native)

GraffitiXR is an Augmented Reality application for visualizing artworks in the real world. This version is a complete rewrite using React Native.

## Architecture

- **Framework**: React Native 0.81.4
- **Language**: TypeScript
- **AR Engine**: ViroReact (`@reactvision/react-viro`)
- **Camera**: `react-native-vision-camera`
- **Navigation**: React Navigation + `aznavrail-react-native`
- **Animation**: `react-native-reanimated`

## Structure

- `src/components`: Reusable UI components.
- `src/screens`: Application screens (Home, AR, Trace, Settings).
- `src/navigation`: Navigation configuration.
- `packages/aznavrail`: Local copy of the AzNavRail library.

## Getting Started

1.  Install dependencies: `npm install`
2.  Run on Android: `npm run android`

## Features

- **Home**: Dashboard.
- **AR**: Place and visualize art using Viro AR.
- **Trace**: Overlay art on camera feed (Non-AR).
- **Settings**: Configure app options.
