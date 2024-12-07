# Copyright (c) 2023-2024 Red Hat, Inc.
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#   Red Hat, Inc. - initial API and implementation

# The image to get the Node.js binary to support running an IDE in a UBI8-based user container.
# https://registry.access.redhat.com/ubi8/nodejs-20
FROM registry.access.redhat.com/ubi8/nodejs-20:1-69.1732637112 as ubi8

# Base image tag updater line.
# See https://github.com/eclipse-che/che-release/pull/90
# https://registry.access.redhat.com/ubi9/nodejs-20
FROM registry.access.redhat.com/ubi9/nodejs-20:1-59.1726663413

# Dockerfile for building a container that brings everything needed to:
# - install Jet Brains IDE to a Dev Workspace
# - run IDE as a remote development server

USER 0

COPY --chmod=755 /build/scripts/*.sh /
COPY /status-app/ /status-app/

# Create a folders structure for mounting a shared volume and copy the editor binaries to.
RUN mkdir -p /idea-server/status-app

# Adjust the permissions on the entries which should be writable by group root.
RUN for f in "${HOME}" "/etc/passwd" "/etc/group" "/status-app" "/idea-server"; do\
        chgrp -R 0 ${f} && \
        chmod -R g+rwX ${f}; \
    done

# Build the status app.
WORKDIR /status-app/
RUN npm install

# to provide to a UBI8-based user's container
COPY --from=ubi8 /usr/bin/node /node-ubi8

# Switch to unprivileged user.
USER 10001

ENTRYPOINT /entrypoint.sh
