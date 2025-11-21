import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  Animated,
  StyleSheet,
  PanResponder,
  Dimensions,
  ScrollView,
  Image,
  LayoutChangeEvent,
} from 'react-native';
import { AzNavRailContext } from './AzNavRailScope';
import { AzNavItem, AzNavRailSettings, AzButtonShape } from './types';
import { AzNavRailDefaults } from './AzNavRailDefaults';
import { AzButton } from './components/AzButton';
import { AzToggle } from './components/AzToggle';
import { AzCycler } from './components/AzCycler';
import { RailMenuItem } from './components/RailMenuItem';
import { AzLoad } from './components/AzLoad';

interface AzNavRailProps extends AzNavRailSettings {
  children: React.ReactNode;
  navController?: any; // For future integration
  currentDestination?: string;
  isLandscape?: boolean;
  initiallyExpanded?: boolean;
  disableSwipeToOpen?: boolean;
  onExpandedChange?: (expanded: boolean) => void;
}

export const AzNavRail: React.FC<AzNavRailProps> = ({
  children,
  currentDestination,
  isLandscape,
  initiallyExpanded = false,
  disableSwipeToOpen = false,
  displayAppNameInHeader = true,
  packRailButtons = false,
  expandedRailWidth = AzNavRailDefaults.ExpandedRailWidth,
  collapsedRailWidth = AzNavRailDefaults.CollapsedRailWidth,
  showFooter = true,
  isLoading = false,
  defaultShape = AzButtonShape.CIRCLE,
  enableRailDragging = false,
  onExpandedChange,
}) => {
  const [items, setItems] = useState<AzNavItem[]>([]);
  const [isExpanded, setIsExpanded] = useState(initiallyExpanded);
  const [isFloating, setIsFloating] = useState(false);
  const [showFloatingButtons, setShowFloatingButtons] = useState(false);
  const [headerHeight, setHeaderHeight] = useState(0);
  const [hostStates, setHostStates] = useState<Record<string, boolean>>({});

  const railWidthAnim = useRef(new Animated.Value(initiallyExpanded ? expandedRailWidth : collapsedRailWidth)).current;
  const pan = useRef(new Animated.ValueXY()).current;
  const panValue = useRef({ x: 0, y: 0 });
  const opacityAnim = useRef(new Animated.Value(1)).current;

  // Refs for PanResponder stale closures
  const isFloatingRef = useRef(isFloating);
  const enableRailDraggingRef = useRef(enableRailDragging);
  const showFloatingButtonsRef = useRef(showFloatingButtons);
  const wasVisibleOnDragStartRef = useRef(false);

  useEffect(() => { isFloatingRef.current = isFloating; }, [isFloating]);
  useEffect(() => { enableRailDraggingRef.current = enableRailDragging; }, [enableRailDragging]);
  useEffect(() => { showFloatingButtonsRef.current = showFloatingButtons; }, [showFloatingButtons]);

  useEffect(() => {
      const id = pan.addListener((value) => { panValue.current = value; });
      return () => pan.removeListener(id);
  }, [pan]);

  // --- Item Management ---
  const register = useCallback((item: AzNavItem) => {
    setItems((prev) => {
      const index = prev.findIndex((i) => i.id === item.id);
      if (index >= 0) {
        // Update if changed
        // Shallow compare props + options array
        const old = prev[index];
        const isSame =
          old.text === item.text &&
          old.disabled === item.disabled &&
          old.isChecked === item.isChecked &&
          old.selectedOption === item.selectedOption &&
          old.isExpanded === item.isExpanded && // host expansion
          JSON.stringify(old.options) === JSON.stringify(item.options) &&
          old.shape === item.shape &&
          old.color === item.color &&
          old.route === item.route;

        if (isSame) return prev;
        const newItems = [...prev];
        newItems[index] = item;
        return newItems;
      }
      return [...prev, item];
    });
  }, []);

  const unregister = useCallback((id: string) => {
    setItems((prev) => prev.filter((i) => i.id !== id));
  }, []);

  // --- Animation & Effects ---
  useEffect(() => {
    Animated.timing(railWidthAnim, {
      toValue: isExpanded ? expandedRailWidth : collapsedRailWidth,
      duration: 300,
      useNativeDriver: false,
    }).start();

    if (onExpandedChange) onExpandedChange(isExpanded);
  }, [isExpanded, expandedRailWidth, collapsedRailWidth]);

  // --- Gestures ---
  const panResponder = useRef(
    PanResponder.create({
      onMoveShouldSetPanResponder: (_, gestureState) => {
        if (!enableRailDraggingRef.current && !isFloatingRef.current) return false;
        const { dx, dy } = gestureState;
        // "Vertical swipe immediately initiates FAB mode"
        if (!isFloatingRef.current && Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 10 && enableRailDraggingRef.current) {
            return true;
        }
        // Dragging in FAB mode
        return isFloatingRef.current;
      },
      onPanResponderGrant: () => {
        pan.setOffset({
          x: panValue.current.x,
          y: panValue.current.y,
        });
        pan.setValue({ x: 0, y: 0 });
        if (!isFloatingRef.current) {
             // Swipe down/up triggered
             setIsFloating(true);
             setIsExpanded(false);
        }
        if (isFloatingRef.current) {
            wasVisibleOnDragStartRef.current = showFloatingButtonsRef.current;
            setShowFloatingButtons(false);
        }
      },
      onPanResponderMove: Animated.event(
        [null, { dx: pan.x, dy: pan.y }],
        { useNativeDriver: false }
      ),
      onPanResponderRelease: (_, gestureState) => {
        pan.flattenOffset();
        if (isFloatingRef.current) {
            // Snap back logic
            const currentX = panValue.current.x;
            const currentY = panValue.current.y;
            const distance = Math.sqrt(Math.pow(currentX, 2) + Math.pow(currentY, 2));

             if (distance < AzNavRailDefaults.SNAP_BACK_RADIUS_PX) {
                 setIsFloating(false);
                 pan.setValue({ x: 0, y: 0 });
             } else {
                 if (wasVisibleOnDragStartRef.current) {
                     setShowFloatingButtons(true);
                 }
             }
        }
      },
    })
  ).current;

  // Long Press Handler for Header
  const handleHeaderLongPress = () => {
      if (enableRailDragging) {
          if (isFloating) {
              setIsFloating(false);
              pan.setValue({ x: 0, y: 0 });
          } else {
              setIsFloating(true);
              setIsExpanded(false);
          }
      }
  };

  const handleHeaderTap = () => {
      if (isFloating) {
          setShowFloatingButtons(!showFloatingButtons);
      } else {
          setIsExpanded(!isExpanded);
      }
  };

  // --- Render Helpers ---

  const renderRailItem = (item: AzNavItem) => {
      const isHost = item.isHost;
      const isExpandedHost = hostStates[item.id] || false;
      const subItems = items.filter(i => i.hostId === item.id);

      const commonProps = {
          key: item.id,
          color: item.color,
          shape: item.shape || defaultShape,
          disabled: item.disabled,
          style: { marginBottom: AzNavRailDefaults.RailContentVerticalArrangement }
      };

      if (isHost) {
           return (
             <View key={item.id} style={{ alignItems: 'center', width: '100%' }}>
                 <AzButton
                     {...commonProps}
                     text={item.text}
                     onClick={() => setHostStates(prev => ({...prev, [item.id]: !prev[item.id]}))}
                 />
                 {isExpandedHost && subItems.map(renderRailItem)}
             </View>
           );
      }

      if (item.isCycler) {
          return (
              <AzCycler
                  {...commonProps}
                  options={item.options || []}
                  selectedOption={item.selectedOption || ''}
                  onCycle={() => item.onClick && item.onClick()}
              />
          );
      }
      if (item.isToggle) {
          return (
              <AzToggle
                  {...commonProps}
                  isChecked={item.isChecked || false}
                  toggleOnText={item.toggleOnText}
                  toggleOffText={item.toggleOffText}
                  onToggle={() => item.onClick && item.onClick()}
              />
          );
      }

      // Standard Button
      return (
          <AzButton
              {...commonProps}
              text={item.text}
              onClick={() => {
                  if (item.onClick) item.onClick();
                  if (item.collapseOnClick) setIsExpanded(false);
              }}
          />
      );
  };

  const renderMenuItem = (item: AzNavItem, depth = 0): React.ReactNode => {
      const isHost = item.isHost;
      const isExpandedHost = hostStates[item.id] || false;
      const subItems = items.filter(i => i.hostId === item.id);

      return (
          <RailMenuItem
              key={item.id}
              item={item}
              depth={depth}
              isExpandedHost={isExpandedHost}
              onToggleHost={() => setHostStates(prev => ({ ...prev, [item.id]: !prev[item.id] }))}
              onItemClick={() => {
                  if (item.onClick) item.onClick();
                  if (item.collapseOnClick) setIsExpanded(false);
              }}
              renderSubItems={() => (
                  <>
                  {subItems.map(subItem => renderMenuItem(subItem, depth + 1))}
                  </>
              )}
          />
      );
  };

  // Filter items
  const railItems = items.filter(i => i.isRailItem && !i.isSubItem);
  const menuItems = items.filter(i => !i.isSubItem); // Menu shows all top level items, sub items handled by recursion

  const contextValue = useMemo(() => ({ register, unregister }), [register, unregister]);

  return (
    <AzNavRailContext.Provider value={contextValue}>
        <View style={{ flexDirection: 'row', height: '100%' }}>
            <Animated.View
                style={[
                    styles.railContainer,
                {
                    width: railWidthAnim,
                    position: isFloating ? 'absolute' : 'relative',
                    transform: isFloating ? [{ translateX: pan.x }, { translateY: pan.y }] : [],
                    zIndex: isFloating ? 1000 : 1,
                    height: isFloating ? 'auto' : '100%',
                }
            ]}
            {...panResponder.panHandlers}
        >
            {/* Header */}
            <TouchableOpacity
                onPress={handleHeaderTap}
                onLongPress={handleHeaderLongPress}
                delayLongPress={500}
                style={styles.header}
                onLayout={(e) => setHeaderHeight(e.nativeEvent.layout.height)}
                accessibilityRole="button"
                accessibilityLabel={isFloating ? "Undocked Rail" : (isExpanded ? "Collapse Menu" : "Expand Menu")}
                accessibilityHint="Double tap to toggle, long press to drag"
            >
                 {/* Placeholder for App Icon/Name */}
                 <View style={{ width: AzNavRailDefaults.HeaderIconSize, height: AzNavRailDefaults.HeaderIconSize, backgroundColor: 'gray', borderRadius: 24, alignItems: 'center', justifyContent: 'center' }}>
                     <Text style={{ color: 'white' }}>Icon</Text>
                 </View>
                 {(!isFloating && isExpanded && displayAppNameInHeader) && (
                     <Text style={styles.appName} numberOfLines={1}>App Name</Text>
                 )}
            </TouchableOpacity>

            {/* Content */}
            {isExpanded ? (
                <ScrollView style={styles.menuContent}>
                    {menuItems.map(item => {
                         if (item.isDivider) {
                             return <View key={item.id} style={styles.divider} />;
                         }
                         return renderMenuItem(item);
                    })}
                    {showFooter && (
                        <View style={styles.footer}>
                            <Text>Footer</Text>
                        </View>
                    )}
                </ScrollView>
            ) : (
                <ScrollView contentContainerStyle={styles.railContent}>
                     {(isFloating && !showFloatingButtons) ? null : railItems.map(renderRailItem)}
                </ScrollView>
            )}

            {isLoading && (
                 <View style={styles.loaderOverlay}>
                     <AzLoad />
                 </View>
            )}
            </Animated.View>
            {children}
        </View>
    </AzNavRailContext.Provider>
  );
};

const styles = StyleSheet.create({
  railContainer: {
    backgroundColor: '#f0f0f0',
    overflow: 'hidden',
    borderRightWidth: 1,
    borderRightColor: '#ddd',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: AzNavRailDefaults.HeaderPadding,
  },
  appName: {
    marginLeft: 16,
    fontWeight: 'bold',
    fontSize: 18,
  },
  railContent: {
    paddingHorizontal: AzNavRailDefaults.RailContentHorizontalPadding,
    paddingVertical: 8,
    alignItems: 'center',
  },
  menuContent: {
    flex: 1,
  },
  menuItem: {
      padding: 16,
  },
  menuItemText: {
      fontSize: 16,
  },
  divider: {
      height: 1,
      backgroundColor: '#ccc',
      marginVertical: 8,
  },
  footer: {
      padding: 16,
      borderTopWidth: 1,
      borderTopColor: '#ccc',
  },
  loaderOverlay: {
      ...StyleSheet.absoluteFillObject,
      backgroundColor: 'rgba(255,255,255,0.5)',
      alignItems: 'center',
      justifyContent: 'center',
  }
});
