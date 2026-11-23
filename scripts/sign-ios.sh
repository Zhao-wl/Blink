#!/usr/bin/env bash
# CI imports temporary signing material only when the repository owner supplies it.
set -euo pipefail
scratch="$(mktemp -d)"
keychain="$scratch/signing.keychain-db"
password="$(openssl rand -hex 24)"
trap 'security delete-keychain "$keychain" >/dev/null 2>&1 || true; rm -rf "$scratch"' EXIT
printf '%s' "$IOS_P12_BASE64" | base64 -D > "$scratch/cert.p12"
printf '%s' "$IOS_PROFILE_BASE64" | base64 -D > "$scratch/profile"
security cms -D -i "$scratch/profile" > "$scratch/profile.plist"
uuid="$(/usr/libexec/PlistBuddy -c 'Print UUID' "$scratch/profile.plist")"
team="$(/usr/libexec/PlistBuddy -c 'Print TeamIdentifier:0' "$scratch/profile.plist")"
security create-keychain -p "$password" "$keychain"
security unlock-keychain -p "$password" "$keychain"
security import "$scratch/cert.p12" -k "$keychain" -P "$IOS_P12_PASSWORD" -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple: -s -k "$password" "$keychain" >/dev/null
security list-keychains -d user -s "$keychain" "$HOME/Library/Keychains/login.keychain-db"
identity="$(security find-identity -v -p codesigning "$keychain" | awk -F '"' '/"/ {print $2;exit}')"
profiles="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
mkdir -p "$profiles"
cp "$scratch/profile" "$profiles/$uuid.mobileprovision"
xcodebuild -project ios/App/App.xcodeproj -scheme App -configuration Release -destination 'generic/platform=iOS' -derivedDataPath "$scratch/device" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$team" PROVISIONING_PROFILE_SPECIFIER="$uuid" CODE_SIGN_IDENTITY="$identity" build
mkdir "$scratch/Payload"
ditto "$scratch/device/Build/Products/Release-iphoneos/App.app" "$scratch/Payload/App.app"
codesign --verify --deep --strict "$scratch/Payload/App.app"
ditto -c -k --keepParent "$scratch/Payload" out/Blink-iOS-signed.ipa
