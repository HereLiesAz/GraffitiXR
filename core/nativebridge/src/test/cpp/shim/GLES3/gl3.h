#pragma once
// Host shim: MobileGS.h includes GLES3 for types only; no GL call is compiled into the tested sources.
#include <cstdint>
typedef unsigned int GLuint;
typedef int GLint;
typedef float GLfloat;
typedef unsigned int GLenum;
