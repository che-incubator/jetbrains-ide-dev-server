#!/bin/sh
#
# Copyright (c) 2023-2026 Red Hat, Inc.
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#   Red Hat, Inc. - initial API and implementation
#
set -e

# Being called as a pre-start command, the script downloads the requested IDE and
# copies the binaries to the shared volume which should be mounted to a folder in a dev container.
#
# By default the IDE is downloaded from download.jetbrains.com (or from the URL given in
# EDITOR_DOWNLOAD_URL_<ID>, see below). To install from a local RPM instead (e.g. air-gapped /
# pre-staged environments), set IDE_RPM_PACKAGE_PATH to the path of an existing RPM file; when
# set and the file exists, it takes priority over downloading and no network access is required.
# See the "RPM payloads must..." comment below for the RPM's required internal structure.

ide_flavour="$1"
ide_id="$2"
# mounted volume path
ide_server_path="/idea-server"

# IDEA, if none is specified
if [ -z "$ide_flavour" ]; then
    ide_flavour="idea"
fi

# Download the IDE binaries and install them to the shared volume.
cd "$ide_server_path" || exit
# After updating the versions here, update the editor definitions in https://github.com/eclipse-che/che-operator/tree/main/editors-definitions

# RPM payloads must place the IDE tree under a single top-level directory
# (bin/, lib/, plugins/, product-info.json, etc. all nested one level under it,
# the directory's name itself does not matter), mirroring what tar's
# --strip-components=1 flattens out of the official JetBrains tarballs. That
# directory is stripped off during extraction below, so
# entrypoint-init-container.sh ends up with the exact same flattened layout
# regardless of which installation source was used.
#
# To inspect an RPM's structure without extracting it, run:
#   rpm -qlp <path-to.rpm> | tree --fromfile -L 2 .
# Expected shape:
#   .
#   └── idea-server
#       ├── bin
#       ├── jbr
#       ├── lib
#       ├── plugins
#       ├── product-info.json
#       └── ...
ide_rpm_package_path="${IDE_RPM_PACKAGE_PATH:-}"

if [ -n "$ide_rpm_package_path" ] && [ -f "$ide_rpm_package_path" ]; then
  # Install from the local RPM.
  ide_source="$ide_rpm_package_path"
else
  if [ -n "$ide_rpm_package_path" ]; then
    echo "IDE_RPM_PACKAGE_PATH is set to '$ide_rpm_package_path' but the file does not exist; falling back to download."
  fi

  ide_download_url=""
  case $ide_flavour in
    idea)
      ide_download_url="https://download.jetbrains.com/idea/idea-2026.1.tar.gz"
      ;;
    webstorm)
      ide_download_url="https://download.jetbrains.com/webstorm/WebStorm-2026.1.tar.gz"
      ;;
    pycharm)
      ide_download_url="https://download.jetbrains.com/python/pycharm-2026.1.tar.gz"
      ;;
    goland)
      ide_download_url="https://download.jetbrains.com/go/goland-2026.1.tar.gz"
      ;;
    clion)
      ide_download_url="https://download.jetbrains.com/cpp/CLion-2026.1.tar.gz"
      ;;
    phpstorm)
      ide_download_url="https://download.jetbrains.com/webide/PhpStorm-2026.1.tar.gz"
      ;;
    rubymine)
      ide_download_url="https://download.jetbrains.com/ruby/RubyMine-2026.1.tar.gz"
      ;;
    rider)
      ide_download_url="https://download.jetbrains.com/rider/JetBrains.Rider-2026.1.tar.gz"
      ;;
    *)
      echo -n "Unknown IDE is specified: $ide_flavour"
      echo -n "Check the editor definition."
      ;;
  esac

  editor_download_url_env_name="EDITOR_DOWNLOAD_URL_$(echo "$ide_id" | tr '/' '_' | tr '-' '_' | tr '[:lower:]' '[:upper:]')"
  editor_download_url_configured=$(eval "echo \$${editor_download_url_env_name}")
  if [ -n "$editor_download_url_configured" ]; then
      ide_download_url=$editor_download_url_configured
      echo "Using editor download URL from environment variable: $ide_download_url"
  else
      echo "Using editor download: $ide_download_url"
  fi

  ide_source="$ide_download_url"
fi

# Bypass download + extraction if already done (for same source)
prev_ide_download=$([ -e .ide-download-complete ] && cat .ide-download-complete || echo "")
if [ "${prev_ide_download}" != "${ide_source}" ]; then
  if [ "$ide_source" = "$ide_rpm_package_path" ] && [ -n "$ide_rpm_package_path" ]; then
    echo "Installing IDE binaries from RPM package: $ide_source"
    rpm2archive --nocompression - < "$ide_source" | tar xf - --strip-components=1
  else
    echo "Downloading IDE binaries..."
    curl -fsSL "$ide_source" | tar xzf - --strip-components=1
  fi
  echo -n "${ide_source}" > .ide-download-complete
else
  echo "IDE binaries already installed and source unchanged. Skipping."
fi

cp -r /status-app/ "$ide_server_path"
cp /entrypoint-volume.sh "$ide_server_path"

# Copy Node.js binaries to the editor volume.
# It will be copied to the user container if it's absent.
cp /usr/bin/node "$ide_server_path"/node-ubi9
cp /node-ubi8 "$ide_server_path"/node-ubi8
cp -r /node-ubi8-ld_libs/. "$ide_server_path"/node-ubi8-ld_libs
cp -r /node-ubi9-ld_libs/. "$ide_server_path"/node-ubi9-ld_libs

# Copy the folder with machine-exec binaries compatible with UBI8/9
cp -r /machine-exec-bin/. "$ide_server_path"/machine-exec-bin

# Copy the plugin that communicates with the machine-exec server
mkdir -p "$ide_server_path/ide-plugin" && \
cp -a /ide-plugin/. "$ide_server_path/ide-plugin/"

echo "Volume content:"
ls -la "$ide_server_path"
