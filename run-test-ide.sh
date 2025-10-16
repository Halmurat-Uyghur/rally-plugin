#!/bin/bash

# Rally IntelliJ Plugin - Test IDE Launcher
# This script runs IntelliJ IDEA with the Rally plugin for testing

set -e

echo "🚀 Rally Plugin Test Environment"
echo "=================================="
echo ""

# Set Java home to use Java 21
export JAVA_HOME=/Users/halmurat/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home

# Check if Java is available
if [ ! -d "$JAVA_HOME" ]; then
    echo "❌ Error: Java 21 not found at $JAVA_HOME"
    echo "Please install Amazon Corretto 21 or update JAVA_HOME"
    exit 1
fi

echo "✅ Using Java: $($JAVA_HOME/bin/java -version 2>&1 | head -n 1)"
echo ""

echo "📦 Building plugin..."
./gradlew buildPlugin --quiet

if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo ""
    echo "🔧 Launching test IntelliJ IDE..."
    echo "   (This may take a minute on first run)"
    echo ""
    echo "📝 Once IntelliJ opens:"
    echo "   1. Go to Settings → Tools → Tasks → Servers"
    echo "   2. Click '+' and select 'Rally'"
    echo "   3. Configure your Rally server and API key"
    echo "   4. Click 'Test' to verify connection"
    echo "   5. Press Alt+Shift+N to browse Rally tasks"
    echo ""
    echo "🛑 Press Ctrl+C in this terminal to stop the test IDE"
    echo ""

    # Run the IDE
    ./gradlew runIde
else
    echo "❌ Build failed. Please check the errors above."
    exit 1
fi
