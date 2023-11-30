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

# Register the current (arbitrary) user.
if ! whoami &> /dev/null; then
  if [ -w /etc/passwd ]; then
    echo "Registering the current (arbitrary) user."
    echo "${USER_NAME:-user}:x:$(id -u):0:${USER_NAME:-user} user:${HOME}:/bin/bash" >> /etc/passwd
    echo "${USER_NAME:-user}:x:$(id -u):" >> /etc/group
  fi
fi

echo "Volume content:"
ls -la /idea-server/

# Start the app that checks the IDEA server status.
# This should be the editor's 'main' endpoint.
cd /idea-server/status-app
nohup yarn start &

# Skip all interactive shell prompts.
export REMOTE_DEV_NON_INTERACTIVE=1

cd /idea-server/bin
./remote-dev-server.sh run ${PROJECT_SOURCE}
