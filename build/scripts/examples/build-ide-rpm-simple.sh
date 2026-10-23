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
set -e

# Example/reference script (not part of the build, not copied into the runtime image):
# builds an RPM with the file layout entrypoint-init-container.sh expects for its
# IDE_RPM_PACKAGE_PATH option. Use it to produce a test RPM for exercising that install
# path, or as a template for building your own compatible RPM.

ide_source="$1"
output_rpm_path="$2"
ide_rpm_install_prefix="idea-server"

if [ -z "$ide_source" ] || [ -z "$output_rpm_path" ]; then
  cat >&2 <<EOF
Usage: $0 <ide-tarball-url-or-local-path> <output-rpm-path>

Builds an RPM packaging the given IDE tarball under a single top-level directory
("$ide_rpm_install_prefix"), matching what entrypoint-init-container.sh's
IDE_RPM_PACKAGE_PATH expects (bin/, lib/, plugins/, product-info.json, etc. nested
one level under it). Point IDE_RPM_PACKAGE_PATH at the resulting .rpm to install
from it instead of downloading.
EOF
  exit 1
fi

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

payload_dir="$workdir/payload/${ide_rpm_install_prefix}"
mkdir -p "$payload_dir"

echo "Staging IDE tree from: $ide_source"
case "$ide_source" in
  http://*|https://*)
    curl -fsSL "$ide_source" | tar xzf - -C "$payload_dir" --strip-components=1
    ;;
  *)
    tar xzf "$ide_source" -C "$payload_dir" --strip-components=1
    ;;
esac

spec_file="$workdir/ide-server.spec"
cat > "$spec_file" <<EOF
Name: ide-server-test
Version: 1.0
Release: 1
Summary: Test RPM packaging a staged IDE tree for IDE_RPM_PACKAGE_PATH validation
License: EPL-2.0
AutoReqProv: no

# Packaging a prebuilt, mixed-architecture third-party binary tree, not something
# we compiled: skip strip/rpath/dependency post-processing entirely, it is not
# meaningful here and chokes on JetBrains' bundled aarch64 binaries and vendor rpaths.
%global __os_install_post %{nil}
%global __arch_install_post %{nil}
%global debug_package %{nil}

%description
Test-only RPM used to validate the IDE_RPM_PACKAGE_PATH install path in
entrypoint-init-container.sh. Not intended for distribution.

%install
mkdir -p %{buildroot}/${ide_rpm_install_prefix}
cp -a ${payload_dir}/. %{buildroot}/${ide_rpm_install_prefix}/

%files
%defattr(-,-,-,-)
/${ide_rpm_install_prefix}
EOF

echo "Building RPM..."
rpmbuild --define "_topdir ${workdir}/rpmbuild" -bb "$spec_file"

built_rpm=$(find "${workdir}/rpmbuild/RPMS" -name '*.rpm' -print -quit)
if [ -z "$built_rpm" ]; then
  echo "rpmbuild did not produce an RPM" >&2
  exit 1
fi

mkdir -p "$(dirname "$output_rpm_path")"
cp "$built_rpm" "$output_rpm_path"
echo "RPM written to: $output_rpm_path"
