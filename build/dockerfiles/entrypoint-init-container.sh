#!/bin/sh
#
# Copyright (c) 2023-2024 Red Hat, Inc.
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#   Red Hat, Inc. - initial API and implementation
#

ide_flavour="$1"
ide_server_path="/idea-server"

# Copy the editor binaries to the shared volume.

if [ -z "$ide_flavour" ]; then
    # use IDEA, if none is specified
    ide_flavour="idea"
fi

echo "Copying $ide_flavour"
cp -r /"$ide_flavour"-dist/* "$ide_server_path"

cp -r /status-app/ "$ide_server_path"
cp /entrypoint-volume.sh "$ide_server_path"

echo "Volume content:"
ls -la "$ide_server_path"
