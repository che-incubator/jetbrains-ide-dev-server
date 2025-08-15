#!/bin/sh
#
# Copyright (c) 2023-2025 Red Hat, Inc.
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
echo "Downloading IDE binaries..."
# After updating the versions here, update the editor definitions in https://github.com/eclipse-che/che-operator/tree/main/editors-definitions
ide_download_url=""
case $ide_flavour in
  idea)
    ide_download_url="https://download.jetbrains.com/idea/ideaIU-2025.2.tar.gz"
    ;;
  webstorm)
    ide_download_url="https://download.jetbrains.com/webstorm/WebStorm-2025.2.tar.gz"
    ;;
  pycharm)
    ide_download_url="https://download.jetbrains.com/python/pycharm-professional-2025.2.tar.gz"
    ;;
  goland)
    ide_download_url="https://download.jetbrains.com/go/goland-2025.2.tar.gz"
    ;;
  clion)
    ide_download_url="https://download.jetbrains.com/cpp/CLion-2025.2.tar.gz"
    ;;
  phpstorm)
    ide_download_url="https://download.jetbrains.com/webide/PhpStorm-2025.2.tar.gz"
    ;;
  rubymine)
    ide_download_url="https://download.jetbrains.com/ruby/RubyMine-2025.2.tar.gz"
    ;;
  rider)
    ide_download_url="https://download.jetbrains.com/rider/JetBrains.Rider-2025.2.tar.gz"
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
fi

curl -sL "$ide_download_url" | tar xzf - --strip-components=1

cp -r /status-app/ "$ide_server_path"
cp /entrypoint-volume.sh "$ide_server_path"

# Copy Node.js binaries to the editor volume.
# It will be copied to the user container if it's absent.
cp /usr/bin/node "$ide_server_path"/node-ubi9
cp /node-ubi8 "$ide_server_path"/node-ubi8
cp -r /node-ubi9-ld_libs "$ide_server_path"/node-ubi9-ld_libs

echo "Volume content:"
ls -la "$ide_server_path"
