#!/bin/sh
#
# Copyright (c) 2023 Red Hat, Inc.
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#   Red Hat, Inc. - initial API and implementation
#

# Copy IDEA server stuff to the shared volume.
cp -r /idea-dist/* /idea-server/
cp /entrypoint-volume.sh /idea-server/

echo "Volume content:"
ls -la /idea-server
