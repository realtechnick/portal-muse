#!/bin/sh
# Builds musecore into the app's native libraries: arm64 for the Portal, x86_64 for the emulator.
# Android only extracts files named lib*.so from an APK, hence the name.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
go=${GO:-go}
jni="$root/android/app/src/main/jniLibs"
version=$(git -C "$root" describe --always --dirty 2>/dev/null || echo dev)

build() {
	arch=$1 abi=$2
	mkdir -p "$jni/$abi"
	(cd "$root/core" && CGO_ENABLED=0 GOOS=linux GOARCH=$arch "$go" build -trimpath -ldflags "-s -w -X main.version=$version" \
		-o "$jni/$abi/libmusecore.so" ./cmd/musecore)
	echo "$jni/$abi/libmusecore.so"
}

build arm64 arm64-v8a
build amd64 x86_64
