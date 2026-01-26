#!/bin/sh
#
# Copyright (c) 2023-2025 Red Hat, Inc.
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

# mounted volume path
ide_server_path="/idea-server"
# JetBrains plugins path
jetbrains_plugins_path="$ide_server_path/user-plugins"
# temporary home directory
tmp_home="/tmp/user"

# ============================================================================
# User Registration
# ============================================================================

register_user() {
  if ! whoami &> /dev/null; then
    if [ -w /etc/passwd ]; then
      echo "Registering the current (arbitrary) user."
      echo "${USER_NAME:-user}:x:$(id -u):0:${USER_NAME:-user} user:${HOME}:/bin/bash" >> /etc/passwd
      echo "${USER_NAME:-user}:x:$(id -u):" >> /etc/group
    fi
  fi
}

# ============================================================================
# OpenSSL/LibSSL Version Detection
# ============================================================================

libssl_version=""
get_libssl_version() {
  libssl=$(find / -type f \( -name "libssl.so*" \) 2>/dev/null)
  if [ -z "$libssl" ]; then
    for dir in /lib64 /usr/lib64 /lib /usr/lib /usr/local/lib64 /usr/local/lib; do
      for file in "$dir"/libssl.so*; do
        if [ -e "$file" ]; then
          libssl="$file"
          break 2
        fi
      done
    done
  fi

  echo "[INFO] libssl: $libssl"

  case "${libssl}" in
  *libssl.so.1*)
    echo "[INFO] libssl version is: 1"
    libssl_version="1"
    ;;
  *libssl.so.3*)
    echo "[INFO] libssl version is: 3"
    libssl_version="3"
    ;;
  *)
    libssl_version=""
    echo "[WARNING] unknown libssl version: $libssl"
    ;;
  esac
}

openssl_version=""
get_openssl_version() {
  if command -v openssl >/dev/null 2>&1; then
    echo "[INFO] openssl command is available, OpenSSL version is: $(openssl version -v)"
    openssl_version=$(openssl version -v | cut -d' ' -f2 | cut -d'.' -f1)
  elif command -v rpm >/dev/null 2>&1; then
    echo "[INFO] rpm command is available"
    openssl_version=$(rpm -qa | grep openssl-libs | cut -d'-' -f3 | cut -d'.' -f1)
  else
    echo "[INFO] openssl and rpm commands are not available, trying to detect OpenSSL version..."
    get_libssl_version
    openssl_version=$libssl_version
  fi
}

# ============================================================================
# Status App
# ============================================================================

start_status_app() {
  cd "$ide_server_path"/status-app || exit
  if command -v npm &> /dev/null; then
    # Node.js installed in a user's container
    nohup env HOME=$tmp_home npm start &
  else
    # no Node.js installed, use the one that editor-injector provides
    start_status_app_with_bundled_node
  fi
}

start_status_app_with_bundled_node() {
  get_openssl_version
  echo "[INFO] OpenSSL major version is: $openssl_version."

  case "${openssl_version}" in
  *"1"*)
    mv "$ide_server_path"/node-ubi8 "$ide_server_path"/node
    ;;
  *"3"*)
    mv "$ide_server_path"/node-ubi9 "$ide_server_path"/node
    # When registry.access.redhat.com/ubi9 is used as a user container,
    # there no libbrotli in the image. We provide it additionally.
    export LD_LIBRARY_PATH="$LD_LIBRARY_PATH:$ide_server_path/node-ubi9-ld_libs"
    ;;
  *)
    echo "[WARNING] Unsupported OpenSSL major version. Node.js from UBI9 will be used."
    mv "$ide_server_path"/node-ubi9 "$ide_server_path"/node
    ;;
  esac

  nohup env HOME=$tmp_home "$ide_server_path"/node index.mjs &
}

# ============================================================================
# Machine Exec
# ============================================================================

setup_machine_exec_binary() {
  machine_exec_dir="$ide_server_path/machine-exec-bin"
  machine_exec_binaries_count=$(ls -p "$machine_exec_dir" | grep -v / | wc -l)

  # If only one machine-exec binary is provided (statically-linked one),
  # or it's usage requested explicitly, through the environment variable.
  if [ "$machine_exec_binaries_count" -eq 1 ] || [ "$MACHINE_EXEC_MODE" = "static" ]; then
    echo "[INFO] The machine-exec statically-linked binary will be used"
    ln -s "$machine_exec_dir/machine-exec-static" "$ide_server_path"/machine-exec
  else
    # If multiple machine-exec binaries are provided,
    # select the appropriate one depending on the platform.
    setup_machine_exec_dynamic_binary
  fi
}

setup_machine_exec_dynamic_binary() {
  get_openssl_version
  case "${openssl_version}" in
    *"1"*)
      echo "[INFO] The machine-exec dynamically-linked binary for UBI8 will be used"
      ln -s "$ide_server_path"/machine-exec-bin/machine-exec-ubi8 "$ide_server_path"/machine-exec
      ;;
    *"3"*)
      echo "[INFO] The machine-exec dynamically-linked binary for UBI9 will be used"
      ln -s "$ide_server_path"/machine-exec-bin/machine-exec-ubi9 "$ide_server_path"/machine-exec
      ;;
    *)
      echo "[WARNING] Unsupported OpenSSL major version. The machine-exec dynamically-linked binary for UBI9 will be used."
      ln -s "$ide_server_path"/machine-exec-bin/machine-exec-ubi9 "$ide_server_path"/machine-exec
      ;;
  esac
}

start_machine_exec() {
  export MACHINE_EXEC_PORT=3333 # expose the port for IDE plugin
  nohup "$ide_server_path"/machine-exec --url "127.0.0.1:${MACHINE_EXEC_PORT}" &
}

# ============================================================================
# IDE Server
# ============================================================================

start_ide_with_writable_home() {
  plugins_path="$1"
  echo "Launching IDE dev server with HOME=$HOME"
  ./remote-dev-server.sh run "$PROJECT_SOURCE" -Didea.plugins.path="$plugins_path"
}

create_wrapper_script() {
  product_name="$1"
  plugins_path="$2"
  cp "$ide_server_path"/bin/remote-dev-server.sh "$ide_server_path"/bin/remote-dev-server.orig.sh
  chmod +x "$ide_server_path"/bin/remote-dev-server.orig.sh
  cat <<SCRIPT > "$ide_server_path"/bin/remote-dev-server.sh
readonly tmp_home="/tmp/user"
readonly ide_server_path=/idea-server/
readonly plugins_path="$plugins_path"
readonly product_name="$product_name"

mkdir -p \$tmp_home/.config \
  \$tmp_home/.cache \
  \$tmp_home/config/JetBrains/"\$product_name"/options \
  "\$plugins_path"

config_trusted_paths="\$tmp_home/config/JetBrains/\$product_name/options/trusted-paths.xml"
if [ ! -f "\$config_trusted_paths" ]; then
  cat > "\$config_trusted_paths" <<EOF
<application>
  <component name="Trusted.Paths.Settings">
    <option name="TRUSTED_PATHS">
      <list>
        <option value="\$PROJECT_SOURCE" />
      </list>
    </option>
  </component>
</application>
EOF
fi

export HOME="\$tmp_home"
export XDG_CONFIG_HOME="\$tmp_home/.config"
export XDG_CACHE_HOME="\$tmp_home/.cache"
export XDG_DATA_HOME="\$tmp_home/.data"

"\$ide_server_path"/bin/remote-dev-server.orig.sh \$@\
  -Djna.library.path="\$ide_server_path"/plugins/remote-dev-server/selfcontained/lib \
  -Didea.plugins.path="\$plugins_path"
SCRIPT
}

start_ide_with_readonly_home() {
  product_name="$1"
  plugins_path="$2"
  echo "No write permission to HOME=$HOME. Launching IDE dev server with HOME=$tmp_home"
  create_wrapper_script "$product_name" "$plugins_path"
  "$ide_server_path"/bin/remote-dev-server.sh run "$PROJECT_SOURCE"
}

start_ide_server() {
  # To run Rider. See the details in che#23228
  export DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1

  cd "$ide_server_path"/bin || exit

  product_name=$(grep -m1 dataDirectoryName "$ide_server_path"/product-info.json | cut -d'"' -f4)
  plugins_path="$jetbrains_plugins_path/$product_name"

  # Pre-install the Che integration plugin
  mkdir -p "$plugins_path"
  # see https://www.jetbrains.com/help/idea/work-inside-remote-project.html#plugins
  cp -r "$ide_server_path"/ide-plugin/. "$plugins_path"

  # remote-dev-server.sh writes to several sub-folders of HOME (.config, .cache, etc.)
  # When registry.access.redhat.com/ubi9 is used for running a user container, HOME=/ which is read-only.
  # In this case, we point remote-dev-server.sh to a writable HOME.
  if [ -w "$HOME" ]; then
    start_ide_with_writable_home "$plugins_path"
  else
    start_ide_with_readonly_home "$product_name" "$plugins_path"
  fi
}

# ============================================================================
# Main
# ============================================================================

main() {
  register_user

  echo "Volume content:"
  ls -la "$ide_server_path"

  start_status_app
  setup_machine_exec_binary
  start_machine_exec
  start_ide_server
}

main
