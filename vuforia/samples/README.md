# Vuforia Engine Native Sample

Copyright © 2025 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

This sample shows how to build apps using the Vuforia Engine API.

## Getting Started

To build and run the sample you will need to download the Vuforia Engine SDK package and unpack it onto your computer.
Once you have the Vuforia Engine you will find a 'samples' directory where you should unpack the sample package.

Your directory structure will look like this:

```
vuforia-sdk
  |-- samples
        |-- vuforia-sample
              |-- Assets
              |-- CrossPlatform
              |-- Media
              |-- <Platform>
              |-- README.md
              |-- license.txt
```

### Prerequisites

Depending on your preferred platform you will need:

|Platform|Tools|
|-|-|
|Android|Android Studio 2023.1+, Optional: Gradle (for command line builds). You will need to install the Android 'SDK Tools' 'CMake' package version 3.22.1|
|iOS|Xcode 15.0.1 or later|
|UWP|Visual Studio 2022 (17.8+) with the Universal Windows Platform workload and C++/WinRT Extension installed|

Please see the Vuforia Engine developer portal at http://developer.vuforia.com for details of supported platforms.

### Building the sample

1. Launch your chosen IDE
2. Open the sample project file
3. Edit the source file CrossPlatform/AppController.cpp and add a license key
4. Build & Run

#### Android Studio

Open an existing project and navigate to the 'Android' directory within the sample

### Apple Xcode

Open the project file found in the 'iOS' directory within the sample

### Visual Studio

Open the solution file found in the 'UWP directory within the sample
