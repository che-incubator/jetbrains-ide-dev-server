#!/bin/sh
#
# Copyright (c) 2026 Red Hat, Inc.
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#   Red Hat, Inc. - initial API and implementation
#

# Being called as a post-start command, the script copies
# the Toolbox configuration files to the user container.
# It is used to prepare the Toolbox Agent for an air-gapped environment.

# mounted volume path
ide_server_path="/idea-server"

# temporary home directory
tmp_home="/tmp/user"


# target location of the Toolbox environment config to override the source of the Toolbox Agent tarball
tbx_agent_env="$HOME/.config/JetBrains/ToolboxSshDeploy/env.sh"
mkdir -p "$(dirname "$tbx_agent_env")"

# copy env.sh to the target location in the user's container
cp "$ide_server_path/toolbox/env.sh" "$tbx_agent_env"
echo "copied Toolbox environment config to: $tbx_agent_env"


# in an air-gapped mode, the Toolbox needs to be configured to use the preinstalled IDEs
# to prevent Toolbox from downloading the IDEs from JetBrains site
ide_location="$HOME/.local/share/JetBrains/Toolbox/preinstalled/idea.json"
mkdir -p "$(dirname "$ide_location")"

# copy idea.json to the target location in the user's container
cp "$ide_server_path/toolbox/preinstalled/idea.json" "$ide_location"
echo "copied preinstalled IDE config to: $ide_location"


# TODO: add support for the user's HOME directory with RO access
