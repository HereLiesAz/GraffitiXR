import React from 'react';
import { TouchableOpacity, Text, StyleSheet, ViewStyle, TextStyle } from 'react-native';
import { AzButtonShape } from '../types';

interface AzButtonProps {
  text: string;
  onClick: () => void;
  color?: string;
  shape?: AzButtonShape;
  style?: ViewStyle;
  disabled?: boolean;
  testID?: string;
}

export const AzButton: React.FC<AzButtonProps> = ({
  text,
  onClick,
  color = '#6200ee', // Default primary color
  shape = AzButtonShape.CIRCLE,
  style,
  disabled = false,
  testID,
}) => {
  const isCircle = shape === AzButtonShape.CIRCLE;
  const isSquare = shape === AzButtonShape.SQUARE;
  const isRectangle = shape === AzButtonShape.RECTANGLE;
  const isNone = shape === AzButtonShape.NONE;

  // Default size for circle/square
  const size = 48; // dp equivalent

  const containerStyle: ViewStyle = {
    borderColor: isNone ? 'transparent' : color,
    borderWidth: isNone ? 0 : 2,
    backgroundColor: 'transparent',
    alignItems: 'center',
    justifyContent: 'center',
    opacity: disabled ? 0.5 : 1,
    ...style,
  };

  if (isCircle) {
    containerStyle.width = size;
    containerStyle.height = size;
    containerStyle.borderRadius = size / 2;
  } else if (isSquare) {
    containerStyle.width = size;
    containerStyle.height = size;
    containerStyle.borderRadius = 0;
  } else if (isRectangle) {
    containerStyle.height = size;
    // Width is determined by content or parent
    containerStyle.paddingHorizontal = 8;
    containerStyle.borderRadius = 0;
  } else if (isNone) {
     // Invisible rectangle
     containerStyle.height = size;
  }

  const textStyle: TextStyle = {
    color: color,
    textAlign: 'center',
    fontWeight: 'bold',
  };

  const hasNewline = text.includes('\n');

  return (
    <TouchableOpacity
      onPress={onClick}
      disabled={disabled}
      style={containerStyle}
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={text}
      accessibilityState={{ disabled }}
    >
      <Text
        style={textStyle}
        adjustsFontSizeToFit={!hasNewline}
        numberOfLines={hasNewline ? undefined : 1}
        minimumFontScale={0.1}
      >
        {text}
      </Text>
    </TouchableOpacity>
  );
};
