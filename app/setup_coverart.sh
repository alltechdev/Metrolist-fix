#!/bin/bash
# Downloads the prebuilt coverart native library for local development

DOWNLOAD_URL="https://nightly.link/MetrolistGroup/metrolist-coverart-lib/workflows/build-release/main/libcoverart-jniLibs.zip"

echo "Downloading coverart library from latest build..."

# Download the zip file
curl -L -o /tmp/libcoverart-jniLibs.zip "$DOWNLOAD_URL"

if [ $? -ne 0 ]; then
    echo "Failed to download from ${DOWNLOAD_URL}"
    echo "The workflow may not have run yet. Please check:"
    echo "https://github.com/MetrolistGroup/metrolist-coverart-lib/actions"
    exit 1
fi

# Create jniLibs directory
mkdir -p src/main/jniLibs

# Extract
unzip -o /tmp/libcoverart-jniLibs.zip -d src/main/jniLibs

# Cleanup
rm /tmp/libcoverart-jniLibs.zip

echo "Coverart library installed successfully:"
find src/main/jniLibs -name "*.so" -exec ls -la {} \;
