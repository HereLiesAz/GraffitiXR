import React, { useEffect, useContext, useRef } from 'react';
import { AzNavItem, AzButtonShape, AzNavItemProps, AzToggleProps, AzCyclerProps, AzHostItemProps, AzSubItemProps, AzSubToggleProps, AzSubCyclerProps } from './types';

export const AzNavRailContext = React.createContext<{
  register: (item: AzNavItem) => void;
  unregister: (id: string) => void;
} | null>(null);

const useAzItem = (item: AzNavItem) => {
  const context = useContext(AzNavRailContext);
  const previousItem = useRef<AzNavItem | null>(null);

  useEffect(() => {
    if (!context) return;

    // Simple comparison to avoid spamming updates
    const prev = previousItem.current;
    const isSame = prev &&
                   prev.id === item.id &&
                   prev.text === item.text &&
                   prev.disabled === item.disabled &&
                   prev.isChecked === item.isChecked &&
                   prev.selectedOption === item.selectedOption &&
                   // Compare arrays
                   JSON.stringify(prev.options) === JSON.stringify(item.options) &&
                   prev.shape === item.shape &&
                   prev.color === item.color;

    if (!isSame) {
        context.register(item);
        previousItem.current = item;
    }

    // Cleanup only on unmount
    return () => {
      // We don't unregister on every update, only on unmount
      // But if ID changes (rare), we should unregister old ID.
    };
  }, [context, item]); // dependencies should capture all props

  useEffect(() => {
      if (context) {
          return () => context.unregister(item.id);
      }
  }, [context, item.id]);

  return null;
};

// --- Component Wrappers ---

export const AzRailItem: React.FC<AzNavItemProps> = (props) => {
  useAzItem({
    ...props,
    isRailItem: true,
    isToggle: false,
    isCycler: false,
    isDivider: false,
    collapseOnClick: true,
    shape: props.shape || AzButtonShape.CIRCLE,
    disabled: props.disabled || false,
    isHost: false,
    isSubItem: false,
    isExpanded: false,
    toggleOnText: '',
    toggleOffText: '',
  });
  return null;
};

export const AzMenuItem: React.FC<AzNavItemProps> = (props) => {
  useAzItem({
    ...props,
    isRailItem: false,
    isToggle: false,
    isCycler: false,
    isDivider: false,
    collapseOnClick: true,
    shape: props.shape || AzButtonShape.CIRCLE,
    disabled: props.disabled || false,
    isHost: false,
    isSubItem: false,
    isExpanded: false,
    toggleOnText: '',
    toggleOffText: '',
  });
  return null;
};

export const AzRailToggle: React.FC<AzToggleProps> = (props) => {
  useAzItem({
    ...props,
    isRailItem: true,
    isToggle: true,
    isCycler: false,
    isDivider: false,
    collapseOnClick: true,
    shape: props.shape || AzButtonShape.CIRCLE,
    disabled: props.disabled || false,
    isHost: false,
    isSubItem: false,
    isExpanded: false,
  });
  return null;
};

export const AzMenuToggle: React.FC<AzToggleProps> = (props) => {
  useAzItem({
    ...props,
    isRailItem: false,
    isToggle: true,
    isCycler: false,
    isDivider: false,
    collapseOnClick: true,
    shape: props.shape || AzButtonShape.CIRCLE,
    disabled: props.disabled || false,
    isHost: false,
    isSubItem: false,
    isExpanded: false,
  });
  return null;
};

export const AzRailCycler: React.FC<AzCyclerProps> = (props) => {
  useAzItem({
    ...props,
    isRailItem: true,
    isToggle: false,
    isCycler: true,
    isDivider: false,
    collapseOnClick: true,
    shape: props.shape || AzButtonShape.CIRCLE,
    disabled: props.disabled || false,
    isHost: false,
    isSubItem: false,
    isExpanded: false,
    toggleOnText: '',
    toggleOffText: '',
  });
  return null;
};

export const AzMenuCycler: React.FC<AzCyclerProps> = (props) => {
  useAzItem({
    ...props,
    isRailItem: false,
    isToggle: false,
    isCycler: true,
    isDivider: false,
    collapseOnClick: true,
    shape: props.shape || AzButtonShape.CIRCLE,
    disabled: props.disabled || false,
    isHost: false,
    isSubItem: false,
    isExpanded: false,
    toggleOnText: '',
    toggleOffText: '',
  });
  return null;
};

export const AzDivider: React.FC = () => {
    // ID needed?
    const id = useRef(`divider-${Math.random()}`).current;
    useAzItem({
        id,
        text: '',
        isRailItem: false, // Dividers usually in menu only? Kotlin: azDivider() adds to menu.
        // Wait, "Easily add dividers to your menu".
        // Are dividers on rail? "Rectangular rail items have 2.dp of vertical padding...".
        // I'll assume dividers are menu only unless specified.
        // Kotlin azDivider() implementation creates AzNavItem with isDivider=true.
        // And it seems it's added to the list. The Rail rendering logic decides if it shows.
        // Usually dividers are horizontal lines in menu.
        isToggle: false,
        isCycler: false,
        isDivider: true,
        collapseOnClick: false,
        shape: AzButtonShape.NONE,
        disabled: false,
        isHost: false,
        isSubItem: false,
        isExpanded: false,
        toggleOnText: '',
        toggleOffText: '',
    });
    return null;
};

export const AzRailHostItem: React.FC<AzHostItemProps> = (props) => {
    useAzItem({
        ...props,
        isRailItem: true,
        isHost: true,
        isSubItem: false,
        isToggle: false,
        isCycler: false,
        isDivider: false,
        collapseOnClick: false, // Hosts expand/collapse, don't close rail
        shape: props.shape || AzButtonShape.CIRCLE,
        disabled: props.disabled || false,
        isExpanded: false, // Initial state, managed by parent usually
        toggleOnText: '',
        toggleOffText: '',
    });
    return null;
};

export const AzMenuHostItem: React.FC<AzHostItemProps> = (props) => {
    useAzItem({
        ...props,
        isRailItem: false,
        isHost: true,
        isSubItem: false,
        isToggle: false,
        isCycler: false,
        isDivider: false,
        collapseOnClick: false,
        shape: props.shape || AzButtonShape.CIRCLE,
        disabled: props.disabled || false,
        isExpanded: false,
        toggleOnText: '',
        toggleOffText: '',
    });
    return null;
};

export const AzRailSubItem: React.FC<AzSubItemProps> = (props) => {
    useAzItem({
        ...props,
        isRailItem: true,
        isHost: false,
        isSubItem: true,
        isToggle: false,
        isCycler: false,
        isDivider: false,
        collapseOnClick: true,
        shape: props.shape || AzButtonShape.CIRCLE,
        disabled: props.disabled || false,
        isExpanded: false,
        toggleOnText: '',
        toggleOffText: '',
    });
    return null;
};

export const AzMenuSubItem: React.FC<AzSubItemProps> = (props) => {
    useAzItem({
        ...props,
        isRailItem: false,
        isHost: false,
        isSubItem: true,
        isToggle: false,
        isCycler: false,
        isDivider: false,
        collapseOnClick: true,
        shape: props.shape || AzButtonShape.CIRCLE,
        disabled: props.disabled || false,
        isExpanded: false,
        toggleOnText: '',
        toggleOffText: '',
    });
    return null;
};

export const AzRailSubToggle: React.FC<AzSubToggleProps> = (props) => {
    useAzItem({
        ...props,
        isRailItem: true,
        isHost: false,
        isSubItem: true,
        isToggle: true,
        isCycler: false,
        isDivider: false,
        collapseOnClick: true,
        shape: props.shape || AzButtonShape.CIRCLE,
        disabled: props.disabled || false,
        isExpanded: false,
    });
    return null;
};

export const AzMenuSubToggle: React.FC<AzSubToggleProps> = (props) => {
    useAzItem({
        ...props,
        isRailItem: false,
        isHost: false,
        isSubItem: true,
        isToggle: true,
        isCycler: false,
        isDivider: false,
        collapseOnClick: true,
        shape: props.shape || AzButtonShape.CIRCLE,
        disabled: props.disabled || false,
        isExpanded: false,
    });
    return null;
};

export const AzRailSubCycler: React.FC<AzSubCyclerProps> = (props) => {
    useAzItem({
        ...props,
        isRailItem: true,
        isHost: false,
        isSubItem: true,
        isToggle: false,
        isCycler: true,
        isDivider: false,
        collapseOnClick: true,
        shape: props.shape || AzButtonShape.CIRCLE,
        disabled: props.disabled || false,
        isExpanded: false,
        toggleOnText: '',
        toggleOffText: '',
    });
    return null;
};

export const AzMenuSubCycler: React.FC<AzSubCyclerProps> = (props) => {
    useAzItem({
        ...props,
        isRailItem: false,
        isHost: false,
        isSubItem: true,
        isToggle: false,
        isCycler: true,
        isDivider: false,
        collapseOnClick: true,
        shape: props.shape || AzButtonShape.CIRCLE,
        disabled: props.disabled || false,
        isExpanded: false,
        toggleOnText: '',
        toggleOffText: '',
    });
    return null;
};
