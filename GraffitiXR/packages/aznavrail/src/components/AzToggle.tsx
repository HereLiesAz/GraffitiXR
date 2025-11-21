import React from 'react';
import { AzButton } from './AzButton';
import { AzButtonShape } from '../types';
import { ViewStyle } from 'react-native';

interface AzToggleProps {
  isChecked: boolean;
  onToggle: () => void;
  toggleOnText: string;
  toggleOffText: string;
  color?: string;
  shape?: AzButtonShape;
  style?: ViewStyle;
  disabled?: boolean;
  testID?: string;
}

export const AzToggle: React.FC<AzToggleProps> = ({
  isChecked,
  onToggle,
  toggleOnText,
  toggleOffText,
  color,
  shape,
  style,
  disabled,
  testID,
}) => {
  return (
    <AzButton
      text={isChecked ? toggleOnText : toggleOffText}
      onClick={onToggle}
      color={color}
      shape={shape}
      style={style}
      disabled={disabled}
      testID={testID}
    />
  );
};
