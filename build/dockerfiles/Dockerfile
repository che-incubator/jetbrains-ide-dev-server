# Copyright (c) 2023 Red Hat, Inc.
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#   Red Hat, Inc. - initial API and implementation

FROM registry.access.redhat.com/ubi8/nodejs-18:1-60 AS ubi-builder

WORKDIR /idea-dist/
RUN curl -sL https://download-cdn.jetbrains.com/idea/ideaIU-2023.2.5.tar.gz | tar xzf - --strip-components=1

USER 0

COPY --chmod=755 /build/dockerfiles/*.sh /
COPY /status-app/ /idea-dist/status-app/

# Create a directory for mounting a volume.
RUN mkdir /idea-server

# Adjust permissions on some items so they're writable by group root.
RUN for f in "${HOME}" "/etc/passwd" "/etc/group" "/idea-dist/status-app" "/idea-server"; do\
        chgrp -R 0 ${f} && \
        chmod -R g+rwX ${f}; \
    done

# Build the status app.
WORKDIR /idea-dist/status-app/
RUN npm install

# Switch to unprivileged user.
USER 10001

ENTRYPOINT /entrypoint.sh
