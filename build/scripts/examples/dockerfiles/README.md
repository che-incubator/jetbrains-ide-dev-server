# RPM builder image

An example image for building test RPMs with [`build-ide-rpm.sh`](../build-ide-rpm.sh):
given a JetBrains IDE server tarball URL (or a local tarball path), it produces an RPM
whose payload matches what `entrypoint-init-container.sh` expects for its
`IDE_RPM_PACKAGE_PATH` option — the IDE tree (`bin/`, `lib/`, `plugins/`,
`product-info.json`, etc.) nested under a single top-level `idea-server` directory.

Use it to produce a test RPM for exercising the `IDE_RPM_PACKAGE_PATH` install path,
for example in air-gapped or pre-staged environments.

## Building the image

From the **repository root** (the build context must be the repo root, since the
Dockerfile copies the script out of it):

```sh
podman build . -f build/scripts/examples/dockerfiles/Dockerfile.rpm-builder -t che-ide-rpm-builder:next
```

## Creating an RPM

From an IDE server URL (e.g. the official JetBrains tarball):

```sh
mkdir -p out
podman run --rm --userns=keep-id:uid=10001,gid=0 \
  -v "$PWD/out:/output:Z" che-ide-rpm-builder:next \
  https://download.jetbrains.com/idea/idea-2026.1.tar.gz /output/ide-server.rpm
```

The RPM ends up at `./out/ide-server.rpm` on your host.

From a local tarball (e.g. one already downloaded) — mount it read-only and pass its
in-container path:

```sh
podman run --rm --userns=keep-id:uid=10001,gid=0 \
  -v "$PWD/downloads:/input:ro,Z" -v "$PWD/out:/output:Z" che-ide-rpm-builder:next \
  /input/idea-2026.1.tar.gz /output/ide-server.rpm
```

## About the flags

- `-v ...:Z` — needed on SELinux-enabled hosts (Fedora/RHEL); without it the build
  succeeds but the final copy to `/output` fails with "Permission denied". Harmless
  if SELinux isn't enforcing.
- `--userns=keep-id:uid=10001,gid=0` — only relevant for **rootless podman**; it maps
  the image's unprivileged user (10001) to your own host uid so the output file is
  owned by you instead of a subuid. If you run rootful podman/docker, you can drop it.
- With **docker** instead of podman, the same commands work minus
  `--userns=keep-id:...` (and `:Z` is a podman/SELinux convention docker also accepts).

## Using the RPM

Point the dev server's init container at the RPM instead of downloading the IDE, e.g.
in the Dev Workspace:

```yaml
env:
  - name: IDE_RPM_PACKAGE_PATH
    value: /idea-server/ide-server.rpm
```

with the RPM made available at that path (e.g. baked into a derived image or mounted
into the init container). See `build/scripts/entrypoint-init-container.sh` for the
details of the install path and the expected RPM payload structure.
