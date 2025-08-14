# Mural Overlay App - TODO

This file tracks the development of the Mural Overlay application.

## V2: Advanced 'X' Marker Tracking

The next major feature is to replace the current plane-based AR placement with a custom tracking system based on user-painted 'X' markers.

### Research Phase
- [ ] **Investigate ARCore's Augmented Images API:**
  - Determine if it's suitable for tracking multiple, user-defined markers like 'X's.
  - Assess the performance and limitations (e.g., number of images, detection speed).
- [ ] **Investigate OpenCV Integration:**
  - Research how to integrate OpenCV with an Android ARCore project.
  - Explore OpenCV features for shape detection (e.g., finding contours, template matching) to identify 'X's.
  - Evaluate the performance impact of running OpenCV on the camera frames alongside ARCore.
- [ ] **Decide on the best approach:**
  - Based on the research, choose the most promising technology (Augmented Images, OpenCV, or a hybrid approach).

### Implementation Phase
- [ ] **Develop a Marker Detection Prototype:**
  - Create a standalone module or a new activity to test the chosen detection method on the camera feed.
  - Fine-tune the detection algorithm to be robust against different lighting conditions and wall textures.
- [ ] **Integrate Marker Detection with AR View:**
  - Feed the camera frames from the AR session to the detection module.
  - When markers are detected, get their 2D screen coordinates.
- [ ] **Implement 3D Placement Logic:**
  - Use the 2D coordinates of the detected 'X's to determine the 3D position and orientation of the mural. This might involve:
    - Using ARCore's `hitTest` with the 2D coordinates.
    - Calculating the 3D plane and pose from the set of detected 3D points.
  - Create an anchor for the entire mural area based on the detected markers.
- [ ] **Refine the User Experience:**
  - Provide clear instructions to the user on how to draw the markers.
  - Give visual feedback when markers are detected successfully.

### Progress Tracking (Stretch Goal)
- [ ] **Detect Marker Occlusion:**
  - Once the mural is placed, continuously run the marker detection.
  - If a marker is consistently not detected for a period of time, consider it "painted over".
- [ ] **Update App State:**
  - Keep track of which markers are still visible.
  - This information can be used for future features, like showing mural progress.
