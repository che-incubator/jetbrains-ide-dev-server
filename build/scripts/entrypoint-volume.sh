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
  get_openssl_version
  echo "[INFO] OpenSSL major version is: $openssl_version."

  local custom_ld_path=""
  local custom_ossl_mods=""

  case "${openssl_version}" in
  *"1"*)
    mv "$ide_server_path"/node-ubi8 "$ide_server_path"/node
    ;;
  *"3"*)
    mv "$ide_server_path"/node-ubi9 "$ide_server_path"/node
    custom_ld_path="$ide_server_path/node-ubi9-ld_libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    custom_ossl_mods="$ide_server_path/node-ubi9-ld_libs/ossl-modules"
    ;;
  *)
    echo "[WARNING] Unsupported OpenSSL major version. Node.js from UBI9 will be used."
    mv "$ide_server_path"/node-ubi9 "$ide_server_path"/node
    custom_ld_path="$ide_server_path/node-ubi9-ld_libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    custom_ossl_mods="$ide_server_path/node-ubi9-ld_libs/ossl-modules"
    ;;
  esac

  nohup env \
    HOME="$tmp_home" \
    LD_LIBRARY_PATH="${custom_ld_path:-$LD_LIBRARY_PATH}" \
    OPENSSL_MODULES="$custom_ossl_mods" \
    "$ide_server_path/node" index.mjs &
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

find_projects() {
  local projects_root="$1"

  for entry in "$projects_root"/*; do
    [ -e "$entry" ] || continue
    # skip hidden
    case "${entry##*/}" in
      .*) continue ;;
    esac
    # only directories
    if [ -d "$entry" ]; then
      echo "$entry"
    fi
  done
}

configure_multi_project_modules() {
  projects_root="$1"

  if [ ! -d "$projects_root" ]; then
    echo "[WARNING] Projects root directory does not exist: $projects_root"
    return
  fi

  echo "[INFO] Scanning for projects in $projects_root"

  # Find all subdirectories (projects) in the project root
  projects=$(find_projects "$projects_root")
  if [ -z "$projects" ]; then
    project_count=0
  else
    project_count=$(echo "$projects" | wc -l)
  fi

  echo "[INFO] Found $project_count project(s)"

  if [ "$project_count" -eq 0 ]; then
    echo "[WARNING] No projects found in $projects_root"
    PROJECT_PATH="$projects_root"
    return
  fi

  # If only one project, use it directly instead of multi-project configuration
  if [ "$project_count" -eq 1 ]; then
    single_project=$(echo "$projects" | head -n 1)
    echo "[INFO] Only one project found. Using single project mode: $single_project"
    PROJECT_PATH="$single_project"
    return
  fi

  # Multiple projects found - create multi-project configuration
  echo "[INFO] Multiple projects found. Creating multi-project configuration"
  PROJECT_PATH="$projects_root"

  # Create .idea directory if it doesn't exist
  mkdir -p "$projects_root/.idea"

  # Generate modules.xml
  # We create a minimal modules.xml that references module directories.
  # Empty .iml placeholder files are created for each module, which IntelliJ IDEA
  # will detect and regenerate with proper configuration based on the project type
  # (Maven, Gradle, Go, Python, etc.) using its auto-import mechanisms.
  modules_xml="$projects_root/.idea/modules.xml"
  echo "[INFO] Creating modules configuration: $modules_xml"

  cat > "$modules_xml" <<'EOF_HEADER'
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectModuleManager">
    <modules>
EOF_HEADER

  # Add each project as a module
  for project_dir in $projects; do
    project_name=$(basename "$project_dir")
    echo "[INFO] Adding module: $project_name"

    # IML file at the root of the module
    iml_file="$project_dir/$project_name.iml"
    # The path in modules.xml is relative to $PROJECT_DIR$ which is $projects_root
    module_path_in_project="$project_name/$project_name.iml"

    # Reference the module in modules.xml
    echo "      <module fileurl=\"file://\$PROJECT_DIR\$/$module_path_in_project\" filepath=\"\$PROJECT_DIR\$/$module_path_in_project\" />" >> "$modules_xml"

    # Create a minimal valid .iml file
    # IntelliJ IDEA will auto-detect the project type.
    # With this conventional .iml location, $MODULE_DIR$ will be the project's root directory.
    cat > "$iml_file" <<EOF_IML
<?xml version="1.0" encoding="UTF-8"?>
<module version="4">
  <component name="NewModuleRootManager">
    <content url="file://\$MODULE_DIR$" />
    <orderEntry type="sourceFolder" forTests="false" />
  </component>
</module>
EOF_IML

  done
  # Close modules.xml
  cat >> "$modules_xml" <<'EOF_FOOTER'
    </modules>
  </component>
</project>
EOF_FOOTER

  # Enable auto-import for Maven, Gradle, and other build systems
  # This ensures IntelliJ automatically detects and imports projects
  workspace_xml="$projects_root/.idea/workspace.xml"
  if [ ! -f "$workspace_xml" ]; then
    echo "[INFO] Creating workspace configuration with auto-import enabled"
    cat > "$workspace_xml" <<'EOF_WORKSPACE'
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="AutoImportSettings">
    <option name="autoReloadType" value="SELECTIVE" />
  </component>
  <component name="ExternalProjectsManager">
    <system id="GRADLE">
      <state>
        <option name="enableAutoImport" value="true" />
      </state>
    </system>
  </component>
  <component name="MavenImportPreferences">
    <option name="importingSettings">
      <MavenImportingSettings>
        <option name="importAutomatically" value="true" />
      </MavenImportingSettings>
    </option>
  </component>
  <component name="GoLibraries">
    <option name="indexEntireGoPath" value="false" />
  </component>
</project>
EOF_WORKSPACE
  fi

  echo "[INFO] Multi-project configuration complete. IntelliJ IDEA will auto-detect project types on startup."
}

create_trusted_paths_config() {
  config_trusted_paths="$1"

  if [ -f "$config_trusted_paths" ]; then
    return
  fi

  echo "[INFO] Creating trusted-paths configuration: $config_trusted_paths"
  mkdir -p "$(dirname "$config_trusted_paths")"

  cat > "$config_trusted_paths" <<EOF_TRUSTED
<application>
  <component name="Trusted.Paths.Settings">
    <option name="TRUSTED_PATHS">
      <list>
        <option value="$PROJECTS_ROOT" />
      </list>
    </option>
  </component>
</application>
EOF_TRUSTED
}

start_ide_with_writable_home() {
  plugins_path="$1"
  product_name="$2"

  echo "Launching IDE dev server with HOME=$HOME"
  # Create trusted-paths.xml to avoid security prompts
  config_trusted_paths="$HOME/.config/JetBrains/$product_name/options/trusted-paths.xml"
  create_trusted_paths_config "$config_trusted_paths"
  echo "[DEBUG] Full command: ./remote-dev-server.sh run \"$PROJECT_PATH\" -Didea.plugins.path=\"$plugins_path\""
  ./remote-dev-server.sh run "$PROJECT_PATH" -Didea.plugins.path="$plugins_path"
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

mkdir -p \$tmp_home/.config \
  \$tmp_home/.cache \
  "\$plugins_path"

export HOME="\$tmp_home"
export XDG_CONFIG_HOME="\$tmp_home/.config"
export XDG_CACHE_HOME="\$tmp_home/.cache"
export XDG_DATA_HOME="\$tmp_home/.data"

"\$ide_server_path"/bin/remote-dev-server.orig.sh "\$@" \
  -Djna.library.path="\$ide_server_path"/plugins/remote-dev-server/selfcontained/lib \
  -Didea.plugins.path="\$plugins_path"
SCRIPT
}

start_ide_with_readonly_home() {
  plugins_path="$1"
  product_name="$2"
  echo "No write permission to HOME=$HOME. Launching IDE dev server with HOME=$tmp_home"
  echo "[INFO] Opening project: $PROJECT_PATH"
  echo "[DEBUG] Full command: \"$ide_server_path\"/bin/remote-dev-server.sh run \"$PROJECT_PATH\""
  # Create trusted-paths.xml for the temporary home directory
  config_trusted_paths="$tmp_home/.config/JetBrains/$product_name/options/trusted-paths.xml"
  create_trusted_paths_config "$config_trusted_paths"
  create_wrapper_script "$product_name" "$plugins_path"
  "$ide_server_path"/bin/remote-dev-server.sh run "$PROJECT_PATH"
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

  # Configure multi-project modules and determine which directory to launch
  # PROJECT_PATH will be set by configure_multi_project_modules based on the number of projects
  PROJECTS_ROOT="${PROJECTS_ROOT:-/projects}"
  PROJECT_PATH="$PROJECTS_ROOT"
  configure_multi_project_modules "$PROJECTS_ROOT"
  echo "[DEBUG] After configure_multi_project_modules: PROJECT_PATH=$PROJECT_PATH"

  # remote-dev-server.sh writes to several sub-folders of HOME (.config, .cache, etc.)
  # When registry.access.redhat.com/ubi9 is used for running a user container, HOME=/ which is read-only.
  # In this case, we point remote-dev-server.sh to a writable HOME.
  if [ -w "$HOME" ]; then
    start_ide_with_writable_home "$plugins_path" "$product_name"
  else
    start_ide_with_readonly_home "$plugins_path" "$product_name"
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
