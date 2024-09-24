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

# Being called as a post-start command, the script runs the IDE
# from the shared volume which should be mounted to a folder in a dev container.

# Register the current (arbitrary) user.
if ! whoami &> /dev/null; then
  if [ -w /etc/passwd ]; then
    echo "Registering the current (arbitrary) user."
    echo "${USER_NAME:-user}:x:$(id -u):0:${USER_NAME:-user} user:${HOME}:/bin/bash" >> /etc/passwd
    echo "${USER_NAME:-user}:x:$(id -u):" >> /etc/group
  fi
fi

# mounted volume path
ide_server_path="/idea-server"

echo "Volume content:"
ls -la "$ide_server_path"

# Start the app that checks the IDE server status.
# This will be workspace's 'main' endpoint.
if command -v npm &> /dev/null
then
  cd "$ide_server_path"/status-app
  nohup npm start &
else
  echo "node/npm could not be found. Running IDE dev server with no status page."
  echo "Open IDE through the Gateway application."
fi

# Skip all interactive shell prompts.
export REMOTE_DEV_NON_INTERACTIVE=1

cd "$ide_server_path"/bin
./remote-dev-server.sh run ${PROJECT_SOURCE}
