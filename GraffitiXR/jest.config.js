module.exports = {
  preset: 'react-native',
  transformIgnorePatterns: [
    'node_modules/(?!(@react-native|react-native|@react-navigation|aznavrail-react-native|@reactvision|react-native-reanimated|react-native-vision-camera|react-native-vector-icons)/)',
  ],
  moduleNameMapper: {
    '^aznavrail-react-native$': '<rootDir>/packages/aznavrail/src/index.tsx',
  },
};
