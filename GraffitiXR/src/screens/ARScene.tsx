import React, { useState, useEffect, useRef } from 'react';
import { StyleSheet } from 'react-native';
import {
  ViroARScene,
  ViroText,
  ViroARTrackingTargets,
  ViroARImageMarker,
  ViroImage,
  ViroNode,
  ViroConstants,
} from '@reactvision/react-viro';

interface ARSceneProps {
  sceneNavigator: {
    viroAppProps: {
      targetImageUri?: string;
      overlayImageUri?: string;
    };
  };
}

const ARScene = (props: ARSceneProps) => {
  const { targetImageUri, overlayImageUri } = props.sceneNavigator.viroAppProps || {};
  const [targetCreated, setTargetCreated] = useState(false);

  // Transform state
  const [position, setPosition] = useState([0, 0, 0]);
  const [scale, setScale] = useState([1, 1, 1]);
  const [rotation, setRotation] = useState([0, 0, 0]);

  // Accumulators for gestures
  const baseScale = useRef([1, 1, 1]);
  const baseRotation = useRef(0); // Z-rotation (roll)

  useEffect(() => {
    if (targetImageUri) {
        // Register the target.
        // Note: deleting targets is not always instant, overwriting with same name usually works.
        ViroARTrackingTargets.createTargets({
            "runtimeTarget": {
                source: { uri: targetImageUri },
                orientation: "Up",
                physicalWidth: 0.5, // Assumed width in meters
            },
        });
        setTargetCreated(true);
    } else {
        setTargetCreated(false);
    }
  }, [targetImageUri]);

  const onPinch = (pinchState: number, scaleFactor: number, source: any) => {
      if (pinchState === 1) { // Begin
          baseScale.current = scale;
      } else if (pinchState === 2) { // Active
          const newScale = baseScale.current.map(s => s * scaleFactor);
          setScale(newScale);
      }
  };

  const onRotate = (rotateState: number, rotationFactor: number, source: any) => {
       if (rotateState === 1) { // Begin
           baseRotation.current = rotation[2];
       } else if (rotateState === 2) { // Active
           const newRotationZ = baseRotation.current - rotationFactor;
           setRotation([0, 0, newRotationZ]);
       }
  };

  // Reset transforms if overlay changes
  useEffect(() => {
      setScale([1, 1, 1]);
      setRotation([0, 0, 0]);
      setPosition([0, 0, 0]);
      baseScale.current = [1, 1, 1];
      baseRotation.current = 0;
  }, [overlayImageUri]);

  return (
    <ViroARScene>
      {!targetCreated ? (
        <ViroText
            text={targetImageUri ? "Loading Target..." : "Point at a surface and Create Target"}
            scale={[0.2, 0.2, 0.2]}
            position={[0, 0, -1]}
            style={styles.textStyle}
        />
      ) : (
        <ViroARImageMarker target={"runtimeTarget"}>
            {overlayImageUri && (
                <ViroNode
                    position={position}
                    scale={scale}
                    rotation={rotation}
                    onDrag={(pos: any) => setPosition(pos)}
                    onPinch={onPinch}
                    onRotate={onRotate}
                    dragType="FixedToPlane"
                >
                    <ViroImage
                        source={{ uri: overlayImageUri }}
                        width={0.3}
                        height={0.3}
                    />
                </ViroNode>
            )}
        </ViroARImageMarker>
      )}
    </ViroARScene>
  );
};

const styles = StyleSheet.create({
  textStyle: {
    fontFamily: 'Arial',
    fontSize: 20,
    color: '#ffffff',
    textAlignVertical: 'center',
    textAlign: 'center',
  },
});

export default ARScene;
