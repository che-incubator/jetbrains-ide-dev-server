# Copyright (c) 2023-2025 Red Hat, Inc.
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
FROM registry.access.redhat.com/ubi8/nodejs-20:1-72 as ubi8


# FROM registry.access.redhat.com/ubi9/openjdk-21:latest as plugin-builder
FROM gradle:9.1.0-jdk21 as plugin-builder

# NOTE: the below lines are commented out until we figure out why the plugin build is failing in a container

# # Copy the plugin sources
# COPY /che-integration-plugin/ /che-integration-plugin/
# WORKDIR /che-integration-plugin/

# # Adapt the build to a containerized environment
# RUN mkdir -p ~/.gradle && echo "org.gradle.daemon=false" >> ~/.gradle/gradle.properties
# RUN mkdir -p ~/.gradle && echo "kotlin.daemon.enabled=false" >> ~/.gradle/gradle.properties

# RUN ./gradlew --no-daemon --debug -Dorg.gradle.vfs.watch=false -Dkotlin.daemon.enabled=false clean buildPlugin

# RUN cp build/distributions/che-integration-plugin-1.0-SNAPSHOT.zip /plugin.zip

# For now, until the plugin build isn't adapted to a containerized environment and fails,
# copy the pre-built plugin.
COPY che-integration-plugin/build/distributions/che-integration-plugin-1.0-SNAPSHOT.zip /plugin.zip


# Use the latest image that provides the statically-built machine-exec binary (CGO_ENABLED=1)
FROM quay.io/eclipse/che-machine-exec:7.70.0 as machine-exec


# Base image tag updater line.
# See https://github.com/eclipse-che/che-release/pull/90
# https://registry.access.redhat.com/ubi9/nodejs-20
FROM registry.access.redhat.com/ubi9/nodejs-20:9.5-1739482759

# Dockerfile for building a container that brings everything needed to:
# - install Jet Brains IDE to a Dev Workspace
# - run IDE as a remote development server

USER 0

COPY --chmod=755 /build/scripts/*.sh /
COPY /status-app/ /status-app/

# Create a folder structure for mounting a shared volume and copy the editor binaries.
RUN mkdir -p /idea-server/status-app

# Adjust the permissions on the entries which should be writable by group root.
RUN for f in "${HOME}" "/etc/passwd" "/etc/group" "/status-app" "/idea-server"; do \
        if [ "$f" != "/etc/passwd" ]; then \
            chgrp -R 0 "$f" && \
            chmod -R g+rwX "$f"; \
        else \
            chmod -R g-w "$f"; \
        fi; \
    done

# Build the status app.
WORKDIR /status-app/
RUN npm install

# When registry.access.redhat.com/ubi9 is used as a user container,
# there no libbrotli in the image. We provide it additionally to the user's container.
RUN mkdir /node-ubi9-ld_libs && cp -r /usr/lib64/libbrotli* /node-ubi9-ld_libs/

# To make the solution backward compatible with the UBI8-based user containers.
COPY --from=ubi8 /usr/bin/node /node-ubi8

COPY --from=machine-exec --chown=0:0 /go/bin/che-machine-exec /machine-exec
COPY --from=plugin-builder --chown=0:0 /plugin.zip /plugin.zip

# Switch to an unprivileged user.
USER 10001

ENTRYPOINT /entrypoint.sh
