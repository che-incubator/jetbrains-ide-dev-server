# Copyright (c) 2023-2024 Red Hat, Inc.
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#   Red Hat, Inc. - initial API and implementation

# https://registry.access.redhat.com/ubi8/nodejs-20
FROM registry.access.redhat.com/ubi8/nodejs-20:1-50.1720405266

# Dockerfile for building a container that brings everything needed to:
# - install Jet Brains IDE to a Dev Workspace
# - run IDE as a remote development server

USER 0

COPY --chmod=755 /build/dockerfiles/*.sh /
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

# Switch to unprivileged user.
USER 10001

ENTRYPOINT /entrypoint.sh
