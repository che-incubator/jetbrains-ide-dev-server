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

# Being called as a pre-start command, the script downloads the requested IDE and
# copies the binaries to the shared volume which should be mounted to a folder in a dev container.

ide_flavour="$1"
# mounted volume path
ide_server_path="/idea-server"

# IDEA, if none is specified
if [ -z "$ide_flavour" ]; then
    ide_flavour="idea"
fi

# Download the IDE binaries and install them to the shared volume.
cd "$ide_server_path"
echo "Downloading IDE binaries..."
# After updating the versions here, update the editor definitions in https://github.com/eclipse-che/che-operator/tree/main/editors-definitions
ide_download_url=""
case $ide_flavour in
  idea)
    ide_download_url="https://download.jetbrains.com/idea/ideaIU-2024.3.2.1.tar.gz"
    ;;
  webstorm)
    ide_download_url="https://download.jetbrains.com/webstorm/WebStorm-2024.3.2.1.tar.gz"
    ;;
  pycharm)
    ide_download_url="https://download.jetbrains.com/python/pycharm-professional-2024.3.2.tar.gz"
    ;;
  goland)
    ide_download_url="https://download.jetbrains.com/go/goland-2024.3.2.1.tar.gz"
    ;;
  clion)
    ide_download_url="https://download.jetbrains.com/cpp/CLion-2024.3.2.tar.gz"
    ;;
  phpstorm)
    ide_download_url="https://download.jetbrains.com/webide/PhpStorm-2024.3.2.1.tar.gz"
    ;;
  rubymine)
    ide_download_url="https://download.jetbrains.com/ruby/RubyMine-2024.3.2.1.tar.gz"
    ;;
  rider)
    ide_download_url="https://download.jetbrains.com/rider/JetBrains.Rider-2024.3.4.tar.gz"
    ;;
  *)
    echo -n "Unknown IDE is specified: $ide_flavour"
    echo -n "Check the editor definition."
    ;;
esac
curl -sL "$ide_download_url" | tar xzf - --strip-components=1

cp -r /status-app/ "$ide_server_path"
cp /entrypoint-volume.sh "$ide_server_path"

# Copy the Che-specific JetBrains IDE's config to the volume.
cp /idea.properties "$ide_server_path"

# Copy Node.js binaries to the editor volume.
# It will be copied to the user container if it's absent.
cp /usr/bin/node "$ide_server_path"/node-ubi9
cp /node-ubi8 "$ide_server_path"/node-ubi8

echo "Volume content:"
ls -la "$ide_server_path"
