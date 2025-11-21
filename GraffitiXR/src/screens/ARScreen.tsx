import React, { useState, useRef } from 'react';
import { StyleSheet, View, Button, Text } from 'react-native';
import { ViroARSceneNavigator } from '@reactvision/react-viro';
import { launchImageLibrary } from 'react-native-image-picker';
import ARScene from './ARScene';

const ARScreen = () => {
  const [targetImageUri, setTargetImageUri] = useState<string | null>(null);
  const [overlayImageUri, setOverlayImageUri] = useState<string | null>(null);
  const [status, setStatus] = useState<string>('Point at surface');
  const navigatorRef = useRef<any>(null);

  const createTarget = async () => {
    if (navigatorRef.current) {
        try {
            // Viro takeScreenshot returns an object { success, url, errorCode }
            // Usage: navigator.takeScreenshot(fileName, saveToCameraRoll)
            const result = await navigatorRef.current.takeScreenshot('target_capture', false);
            // result is usually { success: true, url: "file:///..." }
            if (result.success && result.url) {
                // The URL might already have file:// prefix
                setTargetImageUri(result.url);
                setStatus("Target Created!");
            } else {
                setStatus("Failed to capture target");
            }
        } catch (e) {
            console.error(e);
            setStatus("Error capturing target");
        }
    }
  };

  const pickOverlay = async () => {
    const result = await launchImageLibrary({
      mediaType: 'photo',
      selectionLimit: 1,
    });

    if (result.assets && result.assets[0]?.uri) {
      setOverlayImageUri(result.assets[0].uri);
      setStatus("Overlay Selected");
    }
  };

  const reset = () => {
      setTargetImageUri(null);
      setOverlayImageUri(null);
      setStatus("Reset");
  }

  return (
    <View style={styles.f1}>
        <ViroARSceneNavigator
          ref={navigatorRef}
          initialScene={{
            scene: ARScene,
          }}
          viroAppProps={{
              targetImageUri: targetImageUri,
              overlayImageUri: overlayImageUri,
          }}
          style={styles.f1}
        />
        <View style={styles.overlay}>
            <Text style={styles.status}>{status}</Text>
            <View style={styles.buttons}>
                {!targetImageUri && <Button title="Create Target" onPress={createTarget} />}
                {targetImageUri && !overlayImageUri && <Button title="Select Overlay" onPress={pickOverlay} />}
                {targetImageUri && <Button title="Reset" onPress={reset} color="red" />}
            </View>
        </View>
    </View>
  );
};

const styles = StyleSheet.create({
  f1: { flex: 1 },
  overlay: {
      position: 'absolute',
      bottom: 20,
      left: 0,
      right: 0,
      alignItems: 'center',
      backgroundColor: 'rgba(0,0,0,0.5)',
      padding: 10,
  },
  status: {
      color: 'white',
      marginBottom: 10,
      fontSize: 16,
  },
  buttons: {
      flexDirection: 'row',
      gap: 10,
  }
});

export default ARScreen;
