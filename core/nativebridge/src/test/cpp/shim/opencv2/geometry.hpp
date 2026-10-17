#pragma once
// Host shim: OpenCV 5 split geometry routines (solvePnP, Rodrigues, findHomography, ...) into
// opencv2/geometry.hpp. With an OpenCV 5 install the real header is used; OpenCV 4 (the apt default)
// keeps them in calib3d/imgproc, which opencv.hpp already includes.
#if __has_include_next(<opencv2/geometry.hpp>)
#include_next <opencv2/geometry.hpp>
#else
#include <opencv2/imgproc.hpp>
#include <opencv2/calib3d.hpp>
#endif
