{
  description = "Nix Building Environment for Android APP";

  inputs = {
    flake-utils = {
      url = "github:numtide/flake-utils";
    };
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem ( system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk = {
              accept_license = true;
            };
          };
        };
        buildToolsVersion = "37.0.0";
        ndkVersion = "30.0.16248370";
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ buildToolsVersion ];
          platformVersions = [ "37" ];
          abiVersions = [ "x86_64" "arm64-v8a" ];
          includeNDK = true;
          ndkVersions = [ ndkVersion ];
          useGoogleAPIs = false;
          useGoogleTVAddOns = false;
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
        };
        pinnedJDK = pkgs.jdk25;
        androidSdk = androidComposition.androidsdk;
        libraries = [pkgs.libclang];
      in {
        devShells = {
          default = pkgs.mkShell {
            name = "Android-Build-Shell";
            buildInputs = with pkgs; [
              gradle
              clang

            ] ++ libraries ++ [
              androidSdk
              pinnedJDK
            ];
            JAVA_HOME = pinnedJDK;
            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            NDK_HOME = "${androidSdk}/libexec/android-sdk/ndk/${ndkVersion}";
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";

          shellHook =
            ''
              export LD_LIBRARY_PATH=${pkgs.lib.makeLibraryPath libraries}:$LD_LIBRARY_PATH
            '';

          };
        };
      }
    );
}

