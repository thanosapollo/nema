{
  description = "Nema Android 36 development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      allowedAndroidPackages = [
        "android-sdk-cmdline-tools"
        "cmdline-tools"
        "android-sdk-tools"
        "android-sdk-platform-tools"
        "android-sdk-build-tools"
        "android-sdk-platforms"
        "android-sdk-emulator"
        "android-sdk-system-image-36-default-x86_64"
        "platform-tools"
        "build-tools"
        "platforms"
        "emulator"
        "system-image-36-default-x86_64"
        "tools"
      ];
      pkgs = import nixpkgs {
        inherit system;
        config = {
          android_sdk.accept_license = true;
          allowUnfreePredicate = pkg: builtins.elem (nixpkgs.lib.getName pkg) allowedAndroidPackages;
        };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" ];
        buildToolsVersions = [ "36.0.0" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "default" ];
        abiVersions = [ "x86_64" ];
        includeCmake = false;
        includeNDK = false;
      };
      sdk = android.androidsdk;
      androidHome = "${sdk}/libexec/android-sdk";
      aapt2 = "${androidHome}/build-tools/36.0.0/aapt2";
      closureCheck = pkgs.runCommand "nema-android-sdk-closure" { } ''
        set -eu
        test -d ${androidHome}/platforms/android-36
        test -x ${androidHome}/build-tools/36.0.0/aapt2
        test -x ${androidHome}/build-tools/36.0.0/apksigner
        test -x ${androidHome}/platform-tools/adb
        test -x ${androidHome}/emulator/emulator
        test -f ${androidHome}/system-images/android-36/default/x86_64/system.img
        test "$(find ${androidHome}/cmdline-tools -type f -name avdmanager | wc -l)" -eq 1
        test "$(find ${androidHome}/cmdline-tools -type f -name apkanalyzer | wc -l)" -eq 1
        set -- ${androidHome}/system-images/*/*/*
        test "$#" -eq 1
        test "$1" = ${androidHome}/system-images/android-36/default/x86_64
        set -- ${androidHome}/platforms/*
        test "$#" -eq 1
        test "$1" = ${androidHome}/platforms/android-36
        set -- ${androidHome}/build-tools/*
        test "$#" -eq 1
        test "$1" = ${androidHome}/build-tools/36.0.0
        test ! -e ${androidHome}/ndk
        test ! -e ${androidHome}/ndk-bundle
        test ! -e ${androidHome}/cmake
        touch "$out"
      '';
    in
    {
      packages.${system}.android-sdk = sdk;
      checks.${system}.android-sdk-closure = closureCheck;
      formatter.${system} = pkgs.nixfmt;

      devShells.${system}.default = pkgs.mkShellNoCC {
        packages = [
          pkgs.jdk17
          sdk
          pkgs.iproute2
          pkgs.sqlite
        ];
        ANDROID_HOME = androidHome;
        ANDROID_SDK_ROOT = androidHome;
        JAVA_HOME = "${pkgs.jdk17}";
        NEMA_AAPT2 = aapt2;
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2}";
        shellHook = ''
          export PATH="$ANDROID_HOME/build-tools/36.0.0:$PATH"
        '';
      };
    };
}
