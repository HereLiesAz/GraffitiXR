import React, { useState } from 'react';
import { View, Text, StyleSheet, Button } from 'react-native';
import { launchImageLibrary } from 'react-native-image-picker';
import TransformableImage from '../components/TransformableImage';

const MockupScreen = () => {
  const [imageUri, setImageUri] = useState<string | null>(null);

  const pickImage = async () => {
    const result = await launchImageLibrary({
      mediaType: 'photo',
      selectionLimit: 1,
    });

    if (result.assets && result.assets.length > 0 && result.assets[0].uri) {
      setImageUri(result.assets[0].uri);
    }
  };

  return (
    <View style={styles.container}>
      {imageUri ? (
        <TransformableImage source={{ uri: imageUri }} />
      ) : (
        <View style={styles.placeholder}>
          <Text style={styles.text}>No Image Selected</Text>
          <Button title="Pick Image" onPress={pickImage} />
        </View>
      )}
      {imageUri && (
          <View style={styles.overlay}>
              <Button title="Change Image" onPress={pickImage} />
          </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#222',
    justifyContent: 'center',
    alignItems: 'center',
  },
  placeholder: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  text: {
    color: 'white',
    fontSize: 20,
    marginBottom: 20,
  },
  overlay: {
      position: 'absolute',
      bottom: 50,
  }
});

export default MockupScreen;
