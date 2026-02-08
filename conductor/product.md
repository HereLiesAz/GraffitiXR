# Initial Concept
GraffitiXR is a Local-First, Offline-Capable Android application for street artists that leverages ARCore and a custom Gaussian Splatting engine (MobileGS) to provide robust overlay and tracing tools for large-scale murals.

## Problem Statement
GraffitiXR provides street artists with a suite of offline-capable tools for mural creation in remote or reclaimed locations where data connectivity is unreliable. By utilizing a custom confidence-based voxel mapping system, it enables high-precision AR overlays on large-scale physical surfaces, overcoming the stability issues typical of standard AR solutions in outdoor environments.

## Target Audience
The platform serves a diverse range of creators, from professional muralists working on large-scale public art to amateur artists experimenting with AR-assisted techniques. It is particularly valuable for urban artists who prioritize privacy and require robust, offline tools for working in non-traditional or reclaimed spaces.

## Strategic Goals
- **Extreme Stability:** Maintain rock-solid AR anchor persistence across multiple painting sessions and shifting lighting conditions, essential for multi-day projects.
- **One-Handed Utility:** Employ a thumb-driven UI (AzNavRail) that allows artists to navigate and adjust the application with a single hand while actively painting.
- **Seamless Local Storage:** Efficiently manage complex project containers (.gxr) locally, ensuring that all maps, fingerprints, and art assets remain under the artist's control without cloud dependencies.

## Core Features
- **AR Mode:** Advanced AR projection using confidence-based voxel mapping and Gaussian Splat rendering for precise digital-to-physical alignment.
- **Mockup & Overlay:** In-app tools for pre-visualizing art on photos and performing real-time adjustments to transparency and alignment.
- **Project Library:** A centralized local management system for .gxr project containers, organizing all environmental data and artistic assets.

## Design Principles
- **Immediacy:** Prioritize fast startup times and rapid camera access to minimize friction during the creative process.
- **Unobtrusiveness:** Use a low-glare, dark-themed interface that minimizes digital distraction from the physical canvas.
