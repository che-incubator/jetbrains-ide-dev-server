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

# if set (typically, in an editor definition), the init script prepares the configuration files
# for Toolbox to configure it for an air-gap mode
toolbox_mode="$3"

# mounted volume path
ide_server_path="/idea-server"

# IDEA, if none is specified
if [ -z "$ide_flavour" ]; then
    ide_flavour="idea"
fi

# Download the IDE binaries and install them to the shared volume.
cd "$ide_server_path" || exit
# After updating the versions here, update the editor definitions in https://github.com/eclipse-che/che-operator/tree/main/editors-definitions
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

# in an air-gapped environment, switch the IDE download URL from JetBrains site
# to a custom one, provided via an environment variable
editor_download_url_env_name="EDITOR_DOWNLOAD_URL_$(echo "$ide_id" | tr '/' '_' | tr '-' '_' | tr '[:lower:]' '[:upper:]')"
editor_download_url_configured=$(eval "echo \$${editor_download_url_env_name}")
if [ -n "$editor_download_url_configured" ]; then
    ide_download_url=$editor_download_url_configured
    echo "Using editor download URL from environment variable: $ide_download_url"
else
    echo "Using editor download: $ide_download_url"
fi

# Bypass download + extraction if already done (for same binary)
prev_ide_download=$([ -e .ide-download-complete ] && cat .ide-download-complete || echo "")
if [ "${prev_ide_download}" != "${ide_download_url}" ]; then
  echo "Downloading IDE binaries..."
  curl -fsSL "${ide_download_url}" | tar xzf - --strip-components=1
  echo -n "${ide_download_url}" > .ide-download-complete
else
  echo "IDE binaries already downloaded and URL unchanged. Skipping."
fi


# in an air-gapped environment with Toolbox mode, switch the Toolbox Agent download URL from JetBrains site
# to a custom one, provided via an environment variable
toolbox_download_url=$EDITOR_DOWNLOAD_URL_CHE_INCUBATOR_JETBRAINS_SSHD_LATEST
echo "Toolbox download URL: $toolbox_download_url"
echo "Toolbox mode: $toolbox_mode"
if [ -n "$toolbox_mode" ] && [ -n "$toolbox_download_url" ]; then
  echo "Preparing container for Toolbox mode"


# Agent distribution contains the Java runtime.
# Both will be unpacked into ~/.cache/JetBrains/Toolbox-CLI-dist/ after establishing the SSH connection.

# prepare env.sh with Toolbox Agent download URL and SHA256 checksum URL
  cat > "$ide_server_path/toolbox/env.sh" <<EOF
cli_url=$toolbox_download_url
# https://download.jetbrains.com/toolbox/agent/tbcli-linux-x64-\${cli_version}.tar.gz

# SHA file must be available on the same URL as Agent, with .sha256 suffix
cli_sha256=\$(curl -sL \${cli_url}.sha256)

# TODO: java_path=? check if this needs to be be overridden as well
EOF


mkdir -p "$ide_server_path/toolbox/preinstalled"
# point Toolbox to look for the preinstalled IDEs in the shared volume
echo "{\"installationDirectory\": \"$ide_server_path\"}" > "$ide_server_path/toolbox/preinstalled/idea.json"
fi
# TODO: ~/.local/share/JetBrains/Toolbox/environment.json should allow to specify path to a folder containing any IDE


cp /copy-tbx-config.sh "$ide_server_path"

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
