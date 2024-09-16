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

# Dockerfile for building a container that brings the Jet Brains IDEs with the remote developemnt support.
# On a workspace start, the requested IDE is copied to the shared volume which is mounted to a folder in a dev container.
WORKDIR /idea-dist/
RUN curl -sL https://download-cdn.jetbrains.com/idea/ideaIU-2024.2.tar.gz | tar xzf - --strip-components=1

WORKDIR /pycharm-dist/
RUN curl -sL https://download-cdn.jetbrains.com/python/pycharm-professional-2024.2.tar.gz | tar xzf - --strip-components=1

WORKDIR /goland-dist/
RUN curl -sL https://download-cdn.jetbrains.com/go/goland-2024.2.tar.gz | tar xzf - --strip-components=1

WORKDIR /webstorm-dist/
RUN curl -sL https://download-cdn.jetbrains.com/webstorm/WebStorm-2024.2.tar.gz | tar xzf - --strip-components=1

WORKDIR /clion-dist/
RUN curl -sL https://download-cdn.jetbrains.com/cpp/CLion-2024.2.tar.gz | tar xzf - --strip-components=1

WORKDIR /phpstorm-dist/
RUN curl -sL https://download-cdn.jetbrains.com/webide/PhpStorm-2024.2.tar.gz | tar xzf - --strip-components=1

WORKDIR /rubymine-dist/
RUN curl -sL https://download-cdn.jetbrains.com/ruby/RubyMine-2024.2.tar.gz | tar xzf - --strip-components=1

WORKDIR /rider-dist/
RUN curl -sL https://download-cdn.jetbrains.com/rider/JetBrains.Rider-2024.2.tar.gz | tar xzf - --strip-components=1

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
