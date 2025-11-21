export const Camera = () => null;
export const useCameraDevice = () => ({ devices: ['wide-angle-camera'] });
export const useCameraPermission = () => ({ hasPermission: true, requestPermission: jest.fn() });
