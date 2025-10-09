/*===============================================================================
Copyright (c) 2023 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

#ifndef __MEMORYSTREAM_H__
#define __MEMORYSTREAM_H__

#include <istream>
#include <streambuf>

/// streambuf implementation where the buffer is in memory
class MemoryStreamBuf : public std::streambuf
{
public:
    MemoryStreamBuf(char const* base, size_t size)
    {
        char* p(const_cast<char*>(base));
        this->setg(p, p, p + size);
    }
};

/// istream implementation to read from a buffer in memory
class MemoryInputStream : virtual MemoryStreamBuf, public std::istream
{
public:
    MemoryInputStream(char const* base, size_t size) : MemoryStreamBuf(base, size), std::istream(static_cast<std::streambuf*>(this)) { }
};

#endif // __MEMORYSTREAM_H__
